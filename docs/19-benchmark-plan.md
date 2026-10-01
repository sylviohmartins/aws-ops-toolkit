# Performance Lab: plano reproduzível

**Status: plano + baselines locais executadas em 22/09/2026.** O ledger foi medido com 1M, 5M e 10M de candidatos sob `-Xmx256m`. O Performance Lab dedicado também percorreu até 100M unidades **sintéticas geradas pelo harness** por cenário, com repetição, mediana, dispersão, sweeps de concorrência/page size/seletividade e oito perfis de referência. Separadamente, o fixture persistente DynamoDB Local/LocalStack com itens complexos `enterprise-incident-v1` foi materializado e percorrido até **5 milhões de itens reais no emulador**; 10M+ ainda não foi materializado nessa trilha e não deve ser apresentado como medido. Esses números validam memória, boundedness e comportamento local; não comprovam throughput, quotas ou saturação AWS. Testes remotos continuam restritos a DEV/HML isolados e autorizados. Resultado detalhado: [Performance Lab 100M](36-performance-lab-100m.md). Não realizar testes destrutivos ou de saturação em produção.

## Perguntas e ambientes

O experimento deve responder: qual o menor nível de concorrência que entrega throughput sustentável com memória limitada, p95 aceitável e recuperação correta? O segundo objetivo é medir o custo de ledger, relatórios, GC e retries, separadamente do tempo de rede.

| Camada | Experimento | Limite do que comprova |
| --- | --- | --- |
| LOCAL sintético | 1, 5 e 10 milhões de registros gerados por página; 1 KiB/4 KiB/16 KiB por item e outliers | Heap/fila/CPU/disco; não simula fielmente quotas AWS |
| Stub HTTP | Latência parametrizada, 429, 500, perda de resposta e timeout | Backpressure, classificação, retry/deadline e cancelamento |
| DynamoDB Local/emulador, se adotado | Paginação, schemas, condições e dados sintéticos | Não reproduz completamente IAM, partições, limites, throttling ou latência AWS |
| DEV/HML isolado | Recursos e quotas aprovados; leitura/escrita sintética limitada | Comportamento remoto, credenciais, rate limiting e custo real naquele ambiente |
| Ensaio de falhas | Crash controlado, auth expirada, disco simulado cheio | Recuperação e auditabilidade; independente de records/s |

Para dezenas de milhões, usar o harness dedicado do Performance Lab em vez do endpoint sintético inicial. O laboratório separado suporta referência progressiva até **100 milhões**, com trava explícita para o ledger pesado acima de 30M e sem apresentar o ensaio como throughput AWS. Volume não deve virar materialização prévia em heap: gerar registro/página deterministicamente com seed e tamanhos declarados.

## Protocolo

1. Registrar commit, JDK exato/distribuição, Boot/SDK, SO, CPU, memória, disco, alimentação/plano de energia, rede/VPN, quotas, configuração, dataset seed e tamanho. Usar build idêntico, sem debugger, com diretório de artefatos por execução.
2. Estabelecer baseline com concorrência 1, rate limit conservador e logging INFO agregado. Separar aquecimento de medição; sugestão inicial: 2 min de aquecimento e 5 min de medição, ajustados até estabilizar JIT/GC e cobrir o fluxo.
3. Repetir pelo menos 3 vezes por cenário, reordenando execuções para reduzir viés térmico/rede. Publicar variação e mediana; não selecionar apenas a melhor tentativa. Jobs de durabilidade também precisam de soak de horas, além da janela curta.
4. Variar uma dimensão por vez: concorrência, limite de página, tamanho do item, taxa, ledger, CSV/gzip/XLSX, GC. Nas comparações sync/async/WebFlux manter regra, limits, retry, dataset, pool e carga oferecida equivalentes.
5. Interromper o sweep se memória/fila deixarem de estabilizar, reserva de disco for atingida, error budget for excedido ou dono de HML pedir parada. Não continuar até 256 só para preencher tabela.
6. Repetir o ponto vencedor com falhas, relatório habilitado e cancelamento/resume. Descartar um resultado rápido se perder evidência ou repetir efeitos.

## Matriz mínima

| Dimensão | Valores planejados | Controle |
| --- | --- | --- |
| Itens em voo | 1, 4, 8, 16, 32, 64, 128, 256 | Valores altos só se orçamento e máquina permitirem |
| Scan ativo | 1, 2, 4, 8 | `TotalSegments` fixo por job; abrir novo job para comparar particionamento |
| Página avaliada | 25, 100, 500 | Observar bytes reais e candidatos por página |
| Latência HTTP | 10, 50, 200, 1.000 ms sintéticos | Distribuição com cauda longa, não só constante |
| Candidatos | 1%, 5%, 50% | Custo de enriquecimento/escrita varia significativamente |
| Falhas | 0%, 1%, 5% e rajada sustentada | Separar retryable, conflito, desconhecido e auth |
| Relatório | Nenhum detalhe, CSV, gzip, XLSX resumido | Nunca suprimir ledger de escrita para melhorar número |
| GC | Ergonomia/G1; ZGC na comparação | Mesmo heap e depois sizing adequado a cada coletor |

O benchmark da engine não é benchmark do SDK nem do serviço AWS. HML compartilhado limita conclusões e exige registrar outras cargas quando observáveis. Workload fechado que espera cada conclusão pode esconder latência sob pressão; medir separadamente tempo na fila e tempo remoto. Ensaiar carga oferecida controlada quando necessário para observar saturação.

## Métricas e artefatos

Guardar `benchmark-manifest.json`, configuração sanitizada, `samples.csv`, relatório final, JFR e log GC quando habilitados. Coletar items/s, pages/s, chamadas/tentativas AWS/s, capacidade, CPU, RSS, heap usado após GC, allocation rate, pausas/tempo de GC, platform/virtual threads, in-flight, bytes de fila, p50/p95/p99, throttling, retry rate, tempo de checkpoint, fsync e tamanho de artefatos. Registrar amostragem e dados ausentes.

## Resultados locais executados em 22/09/2026

O benchmark de ledger usou páginas de 1.000 candidatos, Java 25.0.4.1, Windows 11, 16 processadores lógicos e `-Xmx256m`. Uma primeira execução revelou custo quadrático porque cada página fazia `COUNT(*)` global sobre `tasks`. O hot path foi substituído por contador transacional `planned_records`, preservando rollback, deduplicação e budget. Os números abaixo são da reexecução **após** a correção:

| Candidatos | Inseridos/reportados | Tempo total medido | Pico heap | Crescimento heap | Ledger final em disco |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 1.000.000 | 1.000.000 / 1.000.000 | 27,64 s | 45,96 MiB | 33,76 MiB | 138,9 MiB |
| 5.000.000 | 5.000.000 / 5.000.000 | 142,40 s | 43,92 MiB | 31,78 MiB | 705,9 MiB |
| 10.000.000 | 10.000.000 / 10.000.000 | 320,48 s | 45,90 MiB | 33,74 MiB | 1.424,3 MiB |

O heap não cresceu proporcionalmente ao volume e nenhum cenário produziu OOME. O disco cresce aproximadamente com o ledger, como esperado; espaço livre continua sendo gate de preflight.

O sweep local usa 4.096 tarefas sintéticas de 2 ms e mede apenas virtual threads + limitador local. Não inclui rede, SDK, AWS, quotas ou throttling real:

| Concorrência | tasks/s | p95 | p99 | CPU máquina estimada |
| ---: | ---: | ---: | ---: | ---: |
| 1 | 95 | 40.208 ms | 42.476 ms | 0,23% |
| 4 | 298 | 13.059 ms | 13.592 ms | 0,38% |
| 8 | 606 | 6.404 ms | 6.680 ms | 0,45% |
| 16 | 1.184 | 3.300 ms | 3.427 ms | 0,37% |
| 32 | 2.257 | 1.722 ms | 1.791 ms | 0,81% |
| 64 | 4.333 | 905 ms | 941 ms | 0,10% |
| 128 | 10.076 | 381 ms | 402 ms | 6,97% |
| 256 | 21.028 | 176 ms | 191 ms | 7,02% |

Não houve platô nesse workload local até 256. Portanto o sweep **não define** concorrência recomendada para AWS; o teto operacional deve ser encontrado em DEV/HML com quotas, consumed capacity, throttling e latência reais.

Selecionar o ponto anterior ao platô: por exemplo, ganho de throughput menor que 5% com duplicação de concorrência e crescimento de latência indica que elevar mais não se justifica. Esse limiar é **DECISÃO de laboratório ajustável**, não lei universal. Aplicar margem operacional abaixo do teto medido e confirmar SLA/orçamento do downstream.

## Critérios de aprovação

- Heap vivo e fila estabilizam com o volume, em vez de crescer linearmente com total de registros; zero OOME, fila/futures/conexões sem limite.
- Números de entrada, decisões, saídas, ledger e artefatos reconciliam, inclusive replay; zero perda silenciosa e zero duplicação de efeito no consumidor idempotente.
- Cancelamento fecha admissão rapidamente e termina após chamadas em voo dentro do deadline; desconhecidos permanecem visíveis.
- Expiração em 48%, crash pós-write/pré-ledger e disco cheio mantêm cursor conservador e permitem reconciliação.
- Em HML, taxa e capacidade respeitam orçamento aprovado e não degradam o serviço além dos limites acordados.
- Toda afirmação de melhoria inclui baseline, condições, repetibilidade e limitações.

## Profiling e microbenchmarks

JFR/JMC primeiro; async-profiler somente em SO suportado. Comparar com profiling desligado para estimar overhead. Otimizar alocações de mapas/strings, serialização, boxing e cópias somente quando forem custo relevante. Evitar benchmark de `System.nanoTime` envolvendo rede como prova de micro-otimização.

JMH é opcional para um hot path comprovado, em projeto/perfil separado, com warmup/forks e resultado consumido corretamente. Não adicionar ao runtime do toolkit nem substituir ensaio end-to-end. O projeto recomenda execução controlada por linha de comando. [OpenJDK JMH](https://github.com/openjdk/jmh). Ferramentas e comandos estão em [observabilidade](14-observability.md) e [Java/JVM](24-java-jvm.md).

## Fixture DynamoDB persistente e aceleração offline

O caminho oficial do laboratório continua sendo `scripts/localstack-performance.ps1`: `prepare` materializa o dataset usando a API do DynamoDB e `run`/`benchmark` apenas inicia o serviço e mede leitura. O benchmark é **read-only por padrão**; o ensaio de escrita condicional só existe quando `-WriteCompatibility` é informado explicitamente.

Para investigar escalas que tornem o seed por API excessivamente caro, `scripts/offline-fixture.ps1` oferece uma trilha **experimental** sobre uma cópia isolada do SQLite interno do DynamoDB Local. Ela nunca substitui o fixture oficial silenciosamente. Como esse formato é detalhe de implementação e pode mudar entre versões, um dataset criado ou estendido offline só é aceito para experimentação depois de: verificação estrutural por `build_dynamodb_sqlite_fixture.py`; abertura pelo DynamoDB Local da mesma versão usada no laboratório; `DescribeTable`, probes de primeiro/último item e `Query` pela API pública; e `full-validate` quando o dataset sustentar um resultado de escala.

Exemplo: `offline-fixture.ps1 -Action clone -SourceRecords 1100000`, depois `offline-fixture.ps1 -Action append -AppendFrom 1100000 -TargetRecords 1120000`, `offline-fixture.ps1 -Action full-validate -TargetRecords 1120000` e somente após esse gate `offline-fixture.ps1 -Action promote -TargetRecords 1120000 -ValidatedOffline`. A promoção para o volume consumido pelo LocalStack ocorre com o serviço parado, cria backup do banco anterior, revalida a cardinalidade/probes pela API do LocalStack e restaura automaticamente o backup se essa validação falhar.

Em 27/09/2026, a extensão experimental de 1,11M para 1,12M itens complexos `enterprise-incident-v1` mediu aproximadamente 6,0 mil itens/s no builder offline e passou na validação pública de cardinalidade e probes. Esse número mede somente construção local de fixture; não representa throughput do DynamoDB, LocalStack ou AWS.

### Ajuste do fixture de 5 milhões

No fixture persistente de 5M itens, o DynamoDB Local com heap de 1 GiB apresentou caudas extremas em Parallel Scan: configurações 64/128, 32/64 e 16/32 chegaram a exceder timeouts de 30 s, 180 s e 600 s em chamadas individuais. O serviço persistente foi então ajustado para `DYNAMODB_HEAP_SIZE=2048m` e limite de container de 5 GiB.

Perfis de um segmento completo, com `pageSize=1000` e ProjectionExpression, mostraram que aumentar `TotalSegments` reduz fortemente a profundidade e a latência por página sem exigir mais workers: 32 segmentos processaram 205 mil itens em 150,5 s (p95 1,58 s; máximo 2,11 s), 64 segmentos processaram 105 mil itens em 42,5 s (p95 0,46 s; máximo 0,54 s) e 128 segmentos processaram 40 mil itens em 11,0 s (p95 0,265 s; máximo 0,268 s). Por isso, em escala alta, workers e segments devem ser tratados como controles independentes: muitos segmentos rasos com concorrência moderada são preferíveis a simplesmente elevar ambos.

O tuner aceita configurações explícitas no formato `workers x segments`, incluindo cenários como `8x128`. Resultados completos devem registrar se a medição foi `FRESH` ou `RESUMED`; `elapsedSeconds` do benchmark checkpointável representa tempo acumulado entre tentativas, não necessariamente o tempo de parede da última invocação. O script de benchmark também mantém um lock exclusivo sobre o diretório de saída para impedir execuções concorrentes de `-FreshProjection` de apagarem checkpoints de uma medição em andamento. A escrita atômica de checkpoints recria o diretório pai imediatamente antes de persistir o arquivo temporário, evitando falha de `run-state.json.tmp` quando o diretório precisar ser reconstruído.

Em 01/10/2026, a medição **fresh** de 5M, sem reaproveitar checkpoints, concluiu com `8 workers / 128 segments / pageSize=1000`, ProjectionExpression e heap de 2 GiB no DynamoDB Local. Foram examinados e retornados 5.000.000 itens em 6.128 requests, em 2.940,22 s, equivalentes a **1.700,55 itens/s** e ~0,255 MiB/s de payload projetado. O resultado registrou `checkpointMode=FRESH`, `resumedSegments=0` e `attempts=1`. Esse é o baseline comparável para 5M; resultados anteriores de ~4,8 mil itens/s usavam resume/checkpoints e não devem ser interpretados como throughput fresh de ponta a ponta.

Com 5M itens materializados, `/var/lib/localstack/state/dynamodb` ocupa aproximadamente 10,063 GiB, ou ~2.160,91 bytes físicos por item. Projeção linear: 10M ~20,13 GiB, 25M ~50,31 GiB e 100M ~201,25 GiB somente para o estado DynamoDB, sem contar backup, WAL/temporários e margem operacional. Em 01/10/2026 o host tinha ~5,85 GiB livres no único disco local. Mantendo a reserva mínima de 10 GiB, a expansão a 10M exige ~20,06 GiB livres no início da operação, ou **~14,21 GiB adicionais** além do disponível; 25M exige ~50,25 GiB livres (~44,40 GiB adicionais) e 100M ~201,19 GiB livres (~195,33 GiB adicionais). Portanto 10M+ fica bloqueado pelo preflight até haver capacidade de armazenamento adicional. Esse bloqueio é de capacidade local e não deve ser reinterpretado como limite do toolkit ou do DynamoDB.

O backup de rollback de ~2,4 GiB foi comprimido para ~227 MiB dentro do volume Docker, preservando sua recuperabilidade. A operação liberou espaço lógico no filesystem Linux do Docker, mas o espaço livre observado no `C:` permaneceu praticamente inalterado, pois o disco virtual do Docker/WSL não encolheu automaticamente. Recuperar espaço no host exigiria compactação/migração do VHD ou remoção de dados fora dele; isso é uma operação de infraestrutura da máquina e não faz parte do benchmark em si.
