# Checkpoint, retomada e idempotência

**DECISÃO — 2026-09-18:** cursores de página resolvem progresso de leitura; um ledger por item e etapa resolve efeitos parciais. Nenhum arquivo local torna atômicos DynamoDB, SNS, SQS, Lambda, HTTP e S3.

## Dois níveis de implementação

| Nível | Store | Garantia e limite |
|---|---|---|
| Skeleton demonstrativo | Snapshot JSON e arquivos locais com substituição atômica quando suportada | Recuperação de geração sintética/leituras; não há ledger transacional de side effects AWS |
| CORE de escrita alvo | SQLite local, WAL, transações curtas e single writer | Atomicidade entre intenção, estado de etapas, cursor e eventos de relatório no mesmo banco; sem atomicidade com a AWS |

**FATO:** rename com `ATOMIC_MOVE` depende do filesystem; sua semântica não cria uma transação com outro arquivo nem comprova persistência física do diretório em todos os sistemas. [Java StandardCopyOption](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/StandardCopyOption.html). **DECISÃO:** não usar snapshots JSON como prova de exactly-once, nem fallback silencioso para cópia não atômica em requisito que dependa dessa propriedade.

A demonstração deve falhar claramente se a garantia necessária não estiver disponível. Checksum detecta corrupção acidental, não oferece proteção contra usuário com permissão de reescrever arquivo e checksum. ACL e armazenamento aprovado são requisitos distintos.

## Invariantes

1. Nenhum efeito é emitido antes de a intenção e a chave de idempotência estarem duráveis.
2. Nenhum item é contado como confirmado antes de confirmação confiável ou reconciliação positiva.
3. Cursor de conclusão só avança quando todos os itens daquela página estão contabilizados duravelmente.
4. `UNKNOWN` não é sucesso nem falha comprovada; impede conclusão e repetição cega.
5. Identidade, plano, regra/build e parâmetros da seleção são imutáveis na retomada.
6. Report é projeção recuperável do ledger; erro de report não autoriza repetir efeito de negócio.
7. Mesma chave de etapa com payload diferente é erro de integridade, nunca nova tentativa.
8. Apenas um owner local executa o job; concorrência externa depende de condições/deduplicação remotas.

## Modelo de dados alvo

DDL ilustrativo de arquitetura; **não implementado no skeleton**:

```sql
CREATE TABLE operation (
  id TEXT PRIMARY KEY,
  logical_correction_id TEXT NOT NULL,
  definition_version TEXT NOT NULL,
  plan_hash TEXT NOT NULL,
  identity_policy_hash TEXT NOT NULL,
  status TEXT NOT NULL,
  revision INTEGER NOT NULL,
  schema_version INTEGER NOT NULL,
  updated_at TEXT NOT NULL
);
CREATE TABLE segment_checkpoint (
  operation_id TEXT NOT NULL,
  segment INTEGER NOT NULL,
  total_segments INTEGER NOT NULL,
  committed_cursor_json TEXT,
  completed INTEGER NOT NULL,
  PRIMARY KEY (operation_id, segment)
);
CREATE TABLE page_manifest (
  operation_id TEXT NOT NULL,
  segment INTEGER NOT NULL,
  page_id TEXT NOT NULL,
  start_cursor_json TEXT,
  next_cursor_json TEXT,
  state TEXT NOT NULL,
  PRIMARY KEY (operation_id, page_id)
);
CREATE TABLE step_ledger (
  operation_id TEXT NOT NULL,
  page_id TEXT NOT NULL,
  item_key TEXT NOT NULL,
  step TEXT NOT NULL,
  idempotency_key TEXT NOT NULL UNIQUE,
  payload_hash TEXT NOT NULL,
  state TEXT NOT NULL,
  expected_version TEXT,
  request_id TEXT,
  sanitized_evidence_json TEXT,
  attempt_count INTEGER NOT NULL,
  updated_at TEXT NOT NULL,
  PRIMARY KEY (operation_id, item_key, step)
);
CREATE TABLE report_event (
  sequence INTEGER PRIMARY KEY AUTOINCREMENT,
  operation_id TEXT NOT NULL,
  event_key TEXT NOT NULL UNIQUE,
  row_json TEXT NOT NULL
);
CREATE TABLE report_projection (
  operation_id TEXT NOT NULL,
  artifact_id TEXT NOT NULL,
  last_sequence INTEGER NOT NULL,
  manifest_json TEXT NOT NULL,
  PRIMARY KEY (operation_id, artifact_id)
);
```

Migração inicial deve acrescentar foreign keys, índices de pendências/página e constraints de estado adequados. DDL usa `TEXT` para cursores tipados serializados: preservar tipos DynamoDB S/N/B e números decimais como representação exata, não converter para double. Grandes pre-images ficam em artefatos protegidos com referência/hash, ou colunas mínimas no ledger, conforme tamanho e exigência de recuperação.

`idempotency_key` deve nascer da intenção lógica: `{logicalCorrectionId, ruleVersion, account, region, resource, canonicalItemKey, expectedVersion, step}` com canonicalização e hash estáveis. `operationId` sozinho só deduplica uma execução; não impede dois jobs novos de repetirem a mesma correção. Reexecução da mesma intenção reutiliza o identificador lógico; uma correção nova exige nova análise e versão esperada.

## Configuração de durabilidade local

**DECISÃO:** SQLite em disco local homologado, `journal_mode=WAL`, `synchronous=FULL`, foreign keys habilitadas, `busy_timeout` limitado e leituras paginadas. Verificar retorno dos PRAGMAs no startup. Single writer processa comandos limitados em transações curtas; nunca manter transação aberta enquanto aguarda HTTP/AWS. WAL permite concorrência entre readers/writer, mas permanece um writer por vez e não é indicado para banco compartilhado em filesystem de rede. [SQLite WAL](https://www.sqlite.org/wal.html), [PRAGMAs](https://www.sqlite.org/pragma.html#pragma_synchronous).

Agendar checkpoint de WAL, limitar leitores longos e observar tamanho em disco. Não copiar somente `.db` de um banco ativo e assumir backup consistente; usar API de backup ou fechamento coordenado. Não apagar `-wal`/`-shm` manualmente. [Backup SQLite](https://www.sqlite.org/backup.html). A garantia depende de filesystem/armazenamento que respeite flush e lock; validar crash/power-loss no ambiente disponível. [Atomicidade SQLite](https://www.sqlite.org/atomiccommit.html).

O driver JDBC, versão SQLite embutida, licença e binários nativos precisam ser homologados e fixados na fase CORE. SQLite não é dependência necessária para rodar a demonstração atual. Limitar cache e lote; estimar espaço de ledger + WAL + pre-images + relatórios + temporários, incluindo margem.

## Página e fronteira de commit

1. Ler página a partir do último cursor confirmado, sob limite de taxa/capacidade.
2. Persistir `page_manifest` com cursor anterior/próximo, chaves, versões/propostas mínimas e etapas PENDING **antes** de produzir qualquer efeito dessa página.
3. Para cada etapa, transação curta marca INTENT_DURABLE; em seguida enviar chamada fora da transação.
4. Confirmar SUCCEEDED/SKIPPED/CONFLICT/FAILED_KNOWN/UNKNOWN e gravar evento de relatório na mesma transação local.
5. Quando não houver trabalho não contabilizado na página, confirmar o cursor do segmento. Pendente de reconciliação que impeça continuar mantém a página aberta e pausa o job.
6. Liberar memória da página; report writer consome `report_event` streaming e registra projeção.

O manifesto de página evita depender de reexecutar exatamente o mesmo Scan após crash: a página em progresso é recuperada do conjunto já registrado. Se o crash ocorreu antes desse registro, não houve efeito; reler do cursor anterior é seguro para a regra de escrita, embora dados vivos possam ter mudado.

`LastEvaluatedKey` não é snapshot. Scan pode observar mudanças concorrentes; retomar não significa reconstruir a tabela num instante passado. Ao exigir conjunto estável, planejar uma seleção materializada ou fonte de snapshot/export aprovada e custos próprios. [Semântica de Scan](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Scan.html). Em parallel scan `TotalSegments`, filtros, projeção e recurso são imutáveis; cada segmento persiste cursor próprio. Número de workers ativos pode mudar sem reparticionar segmentos.

## Janelas de falha e recuperação

| Interrupção | Evidência durável | Recuperação |
|---|---|---|
| Antes da intenção | Sem efeito emitido | Planejar etapa novamente |
| Após intenção, antes do envio | INTENT_DURABLE | Tratar como potencialmente enviado no crash; reconciliar ou repetir somente se idempotente |
| Após sucesso remoto, antes de receber resposta | Intenção, sem confirmação | UNKNOWN; consultar estado remoto/dedup |
| Após resposta, antes de commit local | Mesmo caso ambíguo | UNKNOWN; não concluir pelo log volátil |
| Após commit local, antes de atualizar CSV | Resultado e report_event | Regerar/continuar projeção; não reemitir efeito |
| Após CSV, antes do offset/manifesto | Resultado e sequência | Recriar a parte ou truncar apenas até fronteira validada; deduplicar por event_key |
| Após confirmar página | Cursor e outcomes no mesmo banco | Próxima página, etapas confirmadas não repetidas |

Um CSV append e uma transação SQLite não são atômicos. Solução alvo: partes determinísticas por faixa de sequência com escrita temporária, flush, rename e manifesto com hash; publicar manifesto apenas após validar parte. No restart, artefato órfão é reconciliado; regenerar a partir do ledger é sempre permitido. Para grandes volumes, não reter milhões de linhas em memória para reconstruir.

## Efeitos entre serviços

Exemplo: corrigir DynamoDB e publicar SNS. Depois de atualizar DynamoDB, uma falha de rede no publish não pode provocar repetição do update. Ledger registra estados independentes `DDB_UPDATE`, `SNS_PUBLISH`, `SQS_SEND`, `LAMBDA_INVOKE`, `REPORT_UPLOAD`. A etapa seguinte só avança quando sua dependência está comprovada.

Quando autorizado pelo modelo de dados, preferir transação DynamoDB que grava alteração e um registro outbox durável no mesmo serviço. Um publicador posterior lê o outbox e usa `eventId` estável; consumidores deduplicam. **FATO:** outbox trata o problema de dual write, mas entrega duplicada ainda precisa ser considerada. [AWS transactional outbox](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html).

Se não for permitido criar/usar outbox remoto, o ledger local preserva intenção e exige reconciliação da atualização antes de publicar. Ele não remove a janela entre commit remoto e commit local. Se a publicação não oferecer consulta de resultado nem deduplicação efetiva, registrar pendência e requerer reconciliação orientada ao negócio; o sistema não deve afirmar exactly-once.

DynamoDB conditional update verifica versão/estado previsto e pode gravar marcador da correção se o schema permitir. Em retry após sucesso remoto, a condição original pode falhar: reler e conferir marcador/hash distingue repetição bem-sucedida de conflito de terceiros. Igualdade de um campo final isolado não prova que nossa operação ocorreu. [UpdateItem](https://docs.aws.amazon.com/amazondynamodb/latest/APIReference/API_UpdateItem.html).

Transações DynamoDB aceitam `ClientRequestToken`, mas a janela de idempotência documentada é de **10 minutos**; jobs retomados horas depois precisam de condições/ledger remoto próprios. A transação não inclui SNS/HTTP/S3. [Transações DynamoDB](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/transaction-apis.html).

SQS/SNS FIFO têm janelas e condições específicas de deduplicação; Standard permite duplicatas. Não confundir dedup da mensagem com exactly-once do efeito de negócio. Lambda síncrona pode executar e perder a resposta; async confirma aceite, não resultado de negócio. Para cada serviço, exigir chave de evento estável, consumidor idempotente ou reconciliação verificável.

## Resume e concorrência

Na abertura do store: adquirir lock de processo, validar schema/hash/integridade, localizar jobs ativos órfãos e marcá-los INTERRUPTED. Não iniciar escrita automaticamente. Rejeitar checkpoint corrompido ou versão de operação incompatível; não adivinhar cursor. Migração de checkpoint deve ser explícita, testada e manter cópia anterior protegida.

Resume valida identidade/conta/região/recursos, plano, regra e autorização vigente; drena pendências UNKNOWN antes de admitir efeitos novos. Se outra aplicação alterou um item, condition/version impede sobrescrita e produz conflito conhecido. Lock local não impede execução em outra máquina: correção crítica exige condições remotas, chave de intenção comum e, quando necessário, mecanismo corporativo de coordenação.

Retenção do ledger deve cobrir o período de reconciliação e auditoria. Remover o banco enquanto houver UNKNOWN ou outbox pendente é vedado. Backup, evidência e prazo exato são **VALIDAR NO AMBIENTE / ITAÚ**.

## Testes pequenos indispensáveis antes da escrita

Injetar interrupção em todas as janelas da tabela; repetir resume duas vezes; simular corrupção/versão incompatível; expirar credencial durante página; negar flush por falta de disco; repetir token com payload diferente; mudar identidade; alterar item concorrentemente. Critérios: nenhum item é pulado silenciosamente, nenhum efeito confirmado é refeito pela engine, UNKNOWN permanece visível e toda repetição remota depende de uma propriedade comprovada de idempotência.
