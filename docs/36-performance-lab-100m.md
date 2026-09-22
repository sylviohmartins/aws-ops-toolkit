# Performance Lab — referência de até 100 milhões por tabela

## 1. Baseline

Esta rodada parte do runtime já validado com paginação, limites explícitos, Virtual Threads,
checkpoint/ledger e backpressure. O novo laboratório existe separado do código operacional e
não habilita AWS nem escrita. A referência arquitetural é de até **100.000.000 de registros
por tabela**, em oito tabelas, sem pressupor 800 milhões simultâneos.

A baseline anterior do ledger SQLite havia sido executada com 1M, 5M e 10M sob `-Xmx256m`.
Ela provou memória bounded e durabilidade local, mas não throughput AWS. O antigo teto de
30M do harness foi ampliado para 100M com uma trava explícita `-AllowLargeLedger` acima de
30M para evitar consumo acidental de disco e tempo.

## 2. Metodologia

O novo `PerformanceLabBenchmarkTest` gera dados deterministicamente por página, sem construir
uma lista global. Cada item modelado executa uma cadeia de checksum dependente, com uma rodada
a cada 64 bytes modelados, limitada a 128 rodadas. Com item de 1 KiB são 16 rodadas por item.
O checksum final é observado e validado para impedir que o hot path seja eliminado pelo JIT.

Antes das medições há warm-up de 2M. São registrados contadores, páginas, throughput médio,
CPU do processo, heap observado, GC e amostras p50/p95/p99 de latência por página. Volume,
concorrência, page size e os oito perfis executam três repetições e registram a dispersão;
a mediana representa o ponto. O comparador altera uma variável por vez e classifica mudanças
acima de ±5% como `IMPROVEMENT` ou `REGRESSION`; abaixo disso, `NEUTRAL`.
## 3. Configuração da máquina

A execução validada ocorreu em Windows 11, Java 25.0.4.1 LTS e Maven 3.9.12.
O benchmark usa `-Xmx256m`. CPU lógica, memória física, Java vendor, SO e commit são gravados
automaticamente no JSON de cada run. O script também preserva histórico em
`benchmark-results/performance-lab/`.

Importante: o PATH da máquina possui Java 8 antes do JDK 25. A execução do laboratório fixa
explicitamente `JAVA_HOME`/`PATH` para o JDK 25. Uma primeira tentativa com Java 8 foi
rejeitada pelo build e não é considerada evidência de performance.

## 4. Configuração AWS

Nenhuma chamada AWS é feita pelo benchmark local. Portanto, esta rodada **não mede**:
RCU/WCU, distribuição de partições, latência de rede, TLS, throttling real, retries do SDK,
connection pool ou quotas de conta/tabela. O repositório também não contém oito mapeamentos
reais de tabelas; os oito slots do lab são perfis sintéticos de repetibilidade.

Testes AWS pesados permanecem reservados a DEV/HML autorizado. Produção não deve ser usada
para descobrir limite por stress test.

## 5. Resultados das oito tabelas

Os oito perfis locais usam a mesma configuração para verificar boundedness e repetibilidade.
Eles **não equivalem a oito tabelas DynamoDB reais**. A avaliação real deve ser individual
porque item size, keys, índices, cardinalidade e skew podem ser diferentes.

| Perfil | Volume medido local | Natureza | Projeção real AWS |
| --- | ---: | --- | --- |
| synthetic-table-1..8 | 5M × 3 repetições cada | CPU/mapping bounded | pendente DEV/HML |
## 6. Comparação Query vs Scan vs PartiQL

A escolha operacional permanece orientada por access pattern:

| Situação | Preferência | Motivo |
| --- | --- | --- |
| chave primária completa conhecida | `GetItem` | evita varredura |
| múltiplas chaves conhecidas | `BatchGetItem` | agrupa reads conhecidos |
| partition key adequada | `Query` | limita leitura ao conjunto endereçável |
| índice atende o filtro | `Query` no GSI/LSI | evita full scan |
| expressão SQL melhora legibilidade | PartiQL, após validar o plano efetivo | sintaxe SQL não garante Query |
| atributo sem chave/índice | `Scan` | varredura é inevitável |
| full scan grande inevitável | `Parallel Scan`, com concorrência limitada | explora partições sem producer ilimitado |

O `DynamoWorkflow` existente já diferencia Query por `id` e Scan paginado/segmentado com
`ProjectionExpression` e `ReturnConsumedCapacity.TOTAL`. PartiQL não é implementado apenas
para “parecer SQL”; ele deve ser introduzido somente quando um cenário real justificar e
então comparado com a API nativa.

## 7. Parallel Scan benchmarks

O laboratório local não simula partições DynamoDB, portanto não escolhe `TotalSegments` AWS.
A matriz a executar em DEV/HML é progressiva: 1, 2, 4, 8, 16, 32, 64 e 128, parando quando
throughput deixa de crescer proporcionalmente ou quando p95, throttling, retries, CPU, rede
ou pool deterioram.

`TotalSegments` e `ProcessingConcurrency` continuam variáveis independentes; não existe regra
`segments == workers`.
## 8. Concorrência

No workload sintético corrigido, com 20M por ponto e 1 KiB/item modelado:

| Workers | Throughput local aproximado | Leitura |
| ---: | ---: | --- |
| 1 | 24,7 M/s | baseline; dispersão 3,9% |
| 2 | 46,3 M/s | ganho material |
| 4 | 89,4 M/s | ganho material |
| 8 | 160,5 M/s | ganho material |
| 16 | 243,9 M/s | knee; dispersão 4,6% |
| 32 | 240,1 M/s | neutral; dispersão 23,1% |
| 64 | 241,3 M/s | plateau; dispersão 17,3% |
| 128 | 234,8 M/s | plateau/regressão prática; dispersão 26,3% |

Conclusão local: o ganho escala de forma clara até 16 workers. Acima disso, a mediana deixa
de melhorar e a variabilidade cresce fortemente. Portanto **16 é o knee conservador do
simulador**; a faixa 16–64 representa saturação, não uma recomendação para DynamoDB.

## 9. Connection Pool

O runtime já externaliza `toolkit.aws.max-connections`. O laboratório local não possui
transporte AWS e não pode definir o pool ótimo. Em DEV/HML devem ser correlacionados workers,
leased/pending connections, acquire latency e p95 remoto. A regra é dimensionar por medição,
não copiar o sweet spot de workers do simulador.

## 10. CPU/JVM

O ponto de 16 workers apresentou a maior mediana estável do sweep final (~243,9 M/s) com
baixa dispersão relativa (4,6%). De 32 a 128 workers não houve ganho material e a dispersão
subiu para 17–26%, evidenciando saturação/scheduling local. Java 25 e Virtual Threads não
eliminam esse limite.
## 11. Memória/GC

O run mediano de 100M usou pipeline bounded e observou aproximadamente **25,6 MiB de heap**
sob `-Xmx256m`, sem GC naquele run curto. O heap não cresce em função do total de registros:
o estado vivo é proporcional a workers, cursores, amostras e buffers do harness.

Isso valida a propriedade arquitetural local, mas não substitui JFR/JMC em um ensaio remoto
longo com AWS SDK, payload real, Jackson/mapping e relatórios.

## 12. Network

Não medida nesta rodada. Bytes no JSON são **modelados**, não bytes de rede. DEV/HML deve
coletar receive/send, bytes retornados, tamanho p50/p95 de item, compressão quando houver,
latência de conexão e saturação de interface/VPN.

## 13. Reports

O benchmark novo não inclui CSV/XLSX no hot path para não misturar CPU de mapping com I/O de
relatório. O benchmark anterior do ledger mede report separadamente. Para milhões de linhas,
a política continua CSV streaming/CSV.GZ e arquivos segmentados; XLSX não deve virar buffer
global nem gargalo invisível.

## 14. Writes

A rodada de 100M é read/processing synthetic. Escrita massiva real precisa ser ensaiada
separadamente em DEV/HML com 10k, 100k, 500k e 1M alterações, medindo throughput de escrita,
`ConditionalExpression`, conflitos, idempotência, retries e replay após resume. Nenhuma escrita
foi enviada à AWS nesta execução.
## 15. Resilience scenarios

O projeto já possui testes de runtime para limites, recovery e falhas; o Performance Lab não
finge throttling ou expiração de credencial como se fossem AWS reais. A matriz remota deve
cobrir 429/5xx/timeouts, throttling, credencial expirada, conditional failure e item inválido,
com fail-fast quando error budget ou risco operacional ultrapassar o limite.

## 16. Bottlenecks encontrados

1. **Harness anterior simplista**: 100M em loop quase vazio produziu ~2 bilhões/s e não era
   evidência útil. Foi descartado.
2. **Timer de espera sintética no Windows**: sub-milisegundo via `parkNanos` sofreu granularidade
   do SO e contaminou page-size/concurrency. Foi removido do modelo principal.
3. **Após correção, CPU/scheduling local**: a curva cresce até 16 workers e depois satura.
4. **Limite externo dominante ainda desconhecido**: em AWS real pode migrar para DynamoDB,
   rede, pool, relatório ou downstream.

## 17. Otimizações realizadas

- ampliado suporte de harness até 100M;
- preservada trava explícita para ledger >30M;
- criado benchmark paginado e bounded de 100M;
- adicionado warm-up;
- adicionado trabalho sintético observável proporcional ao item modelado;
- adicionadas métricas de CPU, heap, GC e p50/p95/p99 por página;
- adicionados sweeps de volume, concorrência, page size e seletividade;
- adicionados oito perfis sintéticos;
- criado histórico versionado e comparador de runs;
- comparador endurecido para recusar metodologia/configuração incompatível sem override explícito;
- removido modelo de latência sub-ms que distorcia o Windows.
## 18. Antes vs depois

A primeira versão do novo harness informou ~2,0 bilhões de registros/s para 100M, sinal de
workload insuficiente. Após introduzir checksum/mapping dependente de 16 rodadas por item,
o mesmo cenário ficou em ~154 milhões/s e passou a exibir saturação de concorrência.

A mudança foi mantida porque melhora validade metodológica; o número menor é deliberadamente
mais útil que um benchmark artificialmente “rápido”.

## 19. Configuração recomendada

Para **laboratório local**, usar como ponto inicial:

- `pageSize=1000`;
- `concurrency=8` como baseline conservadora;
- sweep 1→128 para localizar saturação;
- item modelado coerente com o cenário;
- `-Xmx256m` para detectar materialização indevida;
- logging agregado, nunca por item.

O knee local apareceu em 16 workers, mas produção/AWS não deve copiar esse valor sem medir
quota, pool, latência, partições e downstream. Para page size, 1000 melhorou ~8,9% sobre 500
na mediana; 5000 ficou neutral versus 1000. Assim, 1000 permanece a baseline local mais
coerente em vez de maximizar a página.

## 20. Projeção para 100 milhões

Aqui não foi necessário projetar o hot path local: **100M foram realmente percorridos em
três repetições**. A mediana final foi ~0,626 s no modelo CPU/mapping local, ~159,7 M
registros/s, com dispersão de throughput de ~1,8%.

Esse tempo **não é projeção de Scan DynamoDB**. Para AWS, a projeção deverá ser:
`100.000.000 / throughput_sustentável_remoto`, com o resultado marcado explicitamente como
`PROJETADO` até existir uma execução física equivalente.
## 21. Riscos

- confundir benchmark sintético com capacidade AWS;
- usar um único run curto como sustainable throughput;
- inferir RCU/WCU a partir de bytes modelados;
- executar stress test em produção;
- aumentar segments/workers/pool simultaneamente e perder causalidade;
- ocultar baixa seletividade de Scan atrás de `FilterExpression`;
- deixar relatório/downstream acumular objetos sem backpressure.

## 22. Próximas otimizações possíveis

A próxima evidência necessária é um DEV/HML autorizado com oito tabelas/perfis reais. Medir
Query versus Scan/FilterExpression, Parallel Scan progressivo, page size, ProjectionExpression,
pool, throttling/retries, sync+Virtual Threads versus async quando aplicável, rede, report,
checkpoint/resume, credencial expirada e escrita condicional.

Também vale rodar JFR/JMC somente quando CPU/allocations/GC se mostrarem materiais. Índice novo
não deve ser criado automaticamente: seletividade baixa e scans repetidos devem gerar
`INDEX OPPORTUNITY`/`ACCESS PATTERN REVIEW` para decisão de modelagem.

## 23. Veredito técnico

**Confirmado localmente:** o toolkit pode percorrer 100M unidades sintéticas sem materializar
o dataset inteiro; a memória permanece bounded; o harness identifica saturação de
concorrência; 16 workers foi o knee estável do modelo atual; page size 1000 foi a baseline
mais coerente após repetição; e os resultados são persistidos/comparáveis.

**Não confirmado e não inventado:** throughput sustentável DynamoDB, melhor `TotalSegments`,
pool ótimo, custo RCU/WCU, impacto de rede, PartiQL versus API nativa, throttling real,
credencial expirada durante scan remoto e resultados individuais das oito tabelas reais.
Portanto o Performance Lab local está implementado e reproduzível, enquanto a homologação AWS
continua sendo uma etapa ambiental, não uma lacuna que possa ser preenchida com números
sintéticos.

## Como executar

~~~powershell
$env:JAVA_HOME = 'C:\Development\tools\java\jdk-25.0.4.1'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\scripts\performance-lab.ps1
~~~

Para um ledger acima de 30M, a execução deliberada exige:

~~~powershell
.\scripts\benchmark.ps1 -Records 100000000 -PageSize 1000 -AllowLargeLedger
~~~

Não executar esse ledger pesado sem verificar espaço livre e objetivo do experimento.
