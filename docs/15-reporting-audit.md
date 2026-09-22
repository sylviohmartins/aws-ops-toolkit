# Relatórios, auditoria e segurança de disco

Pesquisa: 18/09/2026. **DECISÃO:** CSV streaming é formato primário de detalhes; JSON resume a operação; XLSX é visão resumida opcional, gerada depois do processamento. No skeleton há saída CSV LOCAL. O ledger transacional, XLSX, upload e audit trail de produção descritos abaixo são blueprint a implementar.

**Implementação inicial concreta:** `FileCheckpointStore` guarda `checkpoint.json` e `chunk-<cursor>.csv` em `<toolkit.core.data-directory>/<operationId>/`. O download escreve header e concatena apenas chunks confirmados pelo cursor; cada página é mantida temporariamente em memória, sem acumular a massa completa. Um chunk órfão pode ser substituído por replay determinístico. A árvore `reports/` e os manifestos abaixo são alvo CORE, não nomes de arquivos já produzidos pela demonstração. Reserva inicial de disco no YAML é 1 GiB; a proposta produtiva de 2 GiB adiante exige calibração e não descreve esse default.

## Artefatos e contrato

```text
reports/<operationId>/
  manifest.json
  summary.json
  success.part-00001.csv
  skipped.part-00001.csv
  failures.part-00001.csv
  before.part-00001.csv
  after.part-00001.csv
  errors.ndjson
  summary.xlsx
  execution.log
```

Criar somente arquivos pertinentes. `before/after` exigem política da operação; `execution.log` pode ser um extrato do log estruturado. Arquivo em escrita recebe sufixo `.partial`; consumidor só considera finalizado quando o manifesto o declara completo. Cancelamento produz relatório parcial com status explícito, não sucesso. Nada é publicado automaticamente fora da máquina.

O manifesto alvo contém versão de schema, operation/rule version, hash de configuração não sensível, conta/região e aliases de recursos aprovados, modo, partes, tamanho, checksum, número de linhas, faixa de eventos e estado de finalização. `summary.json` contém início/fim/duração total e ativa; identidade minimizada; parâmetros permitidos; examinados, retornados, candidatos, atualizados, ignorados, conflitos, falhas, resultados desconhecidos, pendências de eventos, retries, throttles, requests/s, items/s e capacidade quando coletada. Contadores confirmados são distintos de estimativas.

## CSV streaming

| Aspecto | Convenção proposta |
| --- | --- |
| Charset | UTF-8; BOM apenas em exportação humana explicitamente configurada |
| Separador e linha | Vírgula e CRLF; dialeto `;` opcional declarado no manifesto |
| Escaping | Aspas duplas em campos com delimitador/aspas/quebras; aspas internas duplicadas |
| Headers | Fixos, ordenados e versionados; nunca derivados sem validação de atributos arbitrários |
| Null | Campo vazio acompanhado de coluna de presença quando a distinção de string vazia importar |
| Números | Decimal sem agrupador, ponto decimal, `BigDecimal` para valor monetário; IDs como texto |
| Tempo | ISO-8601 em UTC com offset explícito |
| Texto não confiável | Política anti-formula para exportação destinada a planilhas |
| Compressão | `.csv.gz` opcional; partes independentes para retomada e checksum |

Na exportação para planilha, aspas CSV sozinhas não neutralizam fórmula. Campos de texto iniciados por `=`, `+`, `-`, `@`, variantes Unicode ou controles perigosos recebem tratamento documentado e validado no leitor adotado. Apóstrofo é defesa parcial: salvar/reabrir no Excel pode remover proteções. Não existe sanitização universal para todos os leitores; considerar XLSX com células explicitamente textuais na distribuição humana. Não adulterar valores numéricos tipados; separar arquivo humano do ledger canônico. [OWASP: CSV Injection](https://owasp.org/www-community/attacks/CSV_Injection).

**DECISÃO:** writer de consumidor único com buffer limitado e falhas de I/O propagadas. Evitar `PrintWriter` sem verificar erro; flush não equivale a durabilidade em disco. No MVP, um writer JDK pequeno atende schema fixo; antes de dialectos variados adotar Commons CSV, que oferece formatos e escaping consolidados. Jackson CSV é alternativa para reutilizar mapping e API streaming, exigindo módulo compatível com a linha Jackson escolhida; não copiar coordenadas 2.x para o código Jackson 3 sem revisão. Nenhuma biblioteca de CSV elimina por si a necessidade de política anti-formula. [Apache Commons CSV](https://commons.apache.org/proper/commons-csv/apidocs/org/apache/commons/csv/package-summary.html), [FasterXML: formatos textuais](https://github.com/FasterXML/jackson-dataformats-text).

| Candidato CSV | Manutenção e compatibilidade a validar | Overhead e decisão |
| --- | --- | --- |
| JDK `Writer` + schema fixo | Sem dependência adicional; escaping e contrato ficam sob responsabilidade do toolkit | Adequado à demonstração de duas colunas; não ampliar para parser genérico |
| Commons CSV | Release publicada e documentação Apache; testar JDK 25 e versão homologada | Pequena API específica; preferido se surgirem dialectos/schema variáveis |
| Jackson CSV | Projeto oficial mantém módulo CSV; alinhar major/BOM ao Jackson do Boot | Reuso de mapping, mas configuração e alocações precisam de benchmark |

## XLSX: escolha e limites

| Opção | Maturidade/capacidade | Custos e riscos | Decisão |
| --- | --- | --- | --- |
| POI XSSF | Ecossistema amplo e recursos ricos | Modelo de workbook em heap inadequado para massa completa | Somente pequenos workbooks conhecidos |
| POI SXSSF | API madura, janela de linhas com descarte para disco | Temporários podem crescer; estilos, comentários e strings compartilhadas exigem atenção | Primeira opção CORE para XLSX streaming |
| fastexcel | Projeto ativo com foco em escrita/leitura eficiente e API menor | Recursos mais restritos; avaliar suporte corporativo e necessidade de estilos | Alternativa em benchmark específico, sem declarar vitória por benchmark do fornecedor |
| CSV / CSV.GZ | Ferramentas universais e escrita incremental simples | Sem tipos ricos, múltiplas abas ou formatação | Formato primário para milhões de registros |

**FATO:** SXSSF mantém uma janela limitada de linhas e usa arquivos temporários. Configurar compressão de temporários pode economizar disco com custo de CPU. Não fazer autosize irrestrito nem criar um estilo novo por célula. Encerrar workbook/streams e remover temporários conforme API da versão escolhida, inclusive em falha; testar esse caminho. [Apache POI: SXSSF](https://poi.apache.org/components/spreadsheet/how-to.html#sxssf). Fastexcel é opção com menos funcionalidades, cuja adequação exige medição com dados do toolkit. [Repositório oficial fastexcel](https://github.com/dhatim/fastexcel).

**FATO:** uma worksheet Excel suporta 1.048.576 linhas e 16.384 colunas. Uma linha de header deixa 1.048.575 linhas de dados. Exceder significa dividir sheets/arquivos ou usar CSV; trocar biblioteca não remove o limite. **DECISÃO:** `summary.xlsx` contém agregados e amostra limitada; não empilhar dezenas de milhões de linhas em abas por conveniência. [Microsoft: limites Excel](https://support.microsoft.com/en-us/excel/excel-specifications-and-limits).

**Versões observadas em 18/09/2026, não adicionadas ao POM:** a página oficial oferece POI 5.5.1; Commons CSV oferece 1.14.1, apesar do cabeçalho documental `1.14.2-SNAPSHOT`; fastexcel apresenta release 0.20.2. Revalidar releases, advisories, transitivas, licença e artefatos do mirror aprovado no momento de adotar. A presença de release não comprova compatibilidade corporativa/JDK25 nem ausência de vulnerabilidade. Não escolher snapshot por aparecer no título do Javadoc. [Downloads POI](https://poi.apache.org/download.html), [Downloads Commons CSV](https://commons.apache.org/proper/commons-csv/download_csv.cgi), [Releases fastexcel](https://github.com/dhatim/fastexcel/releases).

## Ledger, consistência e retomada

Relatório não é banco de controle. O alvo produtivo usa ledger SQLite local com chave `(operationId, itemKey, step, ruleVersion)`, estado, intenção, resultado, idempotency key e referência à evidência. Ledger e arquivos não compartilham transação com AWS. Persistir intenção antes da chamada; resultado depois. Falha entre essas etapas gera estado `UNKNOWN`, a reconciliar, nunca repetição cega.

**DECISÃO:** relatório é projeção regenerável do ledger. Resultados de etapas e eventos de relatório são gravados em transações locais; o cursor só avança após todos os desfechos exigidos da página estarem duráveis, conforme [checkpoint](23-checkpoint-resume-idempotency.md). Renderização é posterior ao commit. Se falhar, retomar a projeção do último evento exportado; não repetir escrita AWS para recriar CSV. Confirmar metadados de parte no ledger após fechar/sincronizar a parte; arquivo órfão é detectado e refeito. Limitar bytes/idade do backlog em disco e fechar admissão se o atraso ameaçar a reserva operacional.

SQLite WAL em disco local com `synchronous=FULL`, transações curtas e único writer é o alvo. Reservar espaço para WAL/checkpoint e evitar leitores de longa duração; não colocar WAL em share de rede. `FULL` melhora garantias frente a perda de energia, mas não protege contra falha física, filesystem defeituoso ou disco perdido. JSON com rename atômico do skeleton demonstra checkpoint, não implementa esse ledger. [SQLite: WAL e sincronização](https://www.sqlite.org/wal.html).

## Pre-image, post-image e rollback

Pre-image guarda apenas chave protegida, versão/estado observado, atributos alterados e justificativa da transformação. Não copiar um item inteiro por padrão. Salvar valores necessários à reconciliação com controle de acesso e retenção; hashing irreversível sozinho não permite restaurar valor. Post-image deve refletir o que o serviço confirmou ou o que foi lido depois, com timestamp e fonte; proposta de dry-run é marcada `proposed`, nunca `applied`.

Rollback é uma nova operação planejada: condicionar a versão e ao marcador da correção, verificar efeitos downstream e recusar restauração cega se outro sistema já modificou o registro. Pagamentos e eventos publicados podem exigir compensação de negócio, não reversão técnica. O relatório distingue compensável, manual e irreversível.

## Disco, retenção e privacidade

Estimativa anterior ao job: `linhas estimadas × bytes p95 por linha × quantidade efetiva de artefatos + ledger + WAL + temporários + logs + reserva`. Medir a amostra do dry-run; não presumir que gzip ou XLSX comprimido representam seu pico temporário. Como hipótese inicial, aplicar fator de margem de 2 ao estimado e preservar mínimo de 2 GiB; homologar conforme ambiente. Verificar volumes distintos de relatórios, temporários e ledger, não só a unidade corrente.

Durante o job monitorar espaço por intervalo e antes de chunk de escrita; se abaixo da reserva, fechar admissão, drenar apenas o que cabe com evidência, persistir estado de falha. Se nem checkpoint couber, o cursor anterior permanece autoridade e itens em voo são desconhecidos. Não prometer relatório final completo quando filesystem está cheio. A próxima inicialização reconcilia o ledger; o operador libera/migra espaço conforme política, sem apagar evidência automaticamente.

**VALIDAR NO AMBIENTE:** diretório aprovado, ACLs, criptografia do volume, retenção, destino S3, chave KMS, classificação e quem pode ler dados. Aplicar allowlist de colunas, masking e, quando necessário, HMAC com chave gerida corporativamente para identificadores correlacionáveis. Hash simples de domínio previsível pode ser revertido por enumeração. Exclusão automática é opcional e só alcança artefatos finalizados após retenção; bloquear limpeza se job estiver aberto ou evidência sob retenção especial.

Audit trail registra operador, tempo UTC, build, operação, conta/role/região, alvo, ação, quantidades, decisão de guardrail, confirmação, canary, pause/resume/cancel e resultado. Credenciais e bearer tokens nunca entram. Arquivo local/checksum detecta algumas alterações, mas não é trilha inviolável: produção depende também da auditoria corporativa e dos registros AWS aplicáveis. O objetivo é evidência correlacionável, não promessa de não repúdio apenas com NDJSON.
