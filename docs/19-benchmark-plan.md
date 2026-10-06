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

Exemplo: `offline-fixture.ps1 -Action clone -SourceRecords 1100000`, depois `offline-fixture.ps1 -Action append -AppendFrom 1100000 -TargetRecords 1120000`, `offline-fixture.ps1 -Action full-validate -TargetRecords 1120000` e somente após esse gate `offline-fixture.ps1 -Action promote -TargetRecords 1120000 -ValidatedOffline`. A promoção para o volume consumido pelo LocalStack ocorre com o serviço parado, cria backup gzip do banco anterior, verifica que a cópia promovida tem o mesmo tamanho físico do fixture validado e, após reiniciar o LocalStack, executa probes de primeiro/último item e `Query` exata pela API pública. A cardinalidade completa continua sendo validada antes da promoção; o pós-copy usa `--probe-only` para não depender de `DescribeTable` em escalas nas quais essa operação apresenta long-tail severa. Qualquer falha de cópia ou probes restaura automaticamente o backup.

Em 27/09/2026, a extensão experimental de 1,11M para 1,12M itens complexos `enterprise-incident-v1` mediu aproximadamente 6,0 mil itens/s no builder offline e passou na validação pública de cardinalidade e probes. Esse número mede somente construção local de fixture; não representa throughput do DynamoDB, LocalStack ou AWS.

### Ajuste do fixture de 5 milhões

No fixture persistente de 5M itens, o DynamoDB Local com heap de 1 GiB apresentou caudas extremas em Parallel Scan: configurações 64/128, 32/64 e 16/32 chegaram a exceder timeouts de 30 s, 180 s e 600 s em chamadas individuais. O serviço persistente foi então ajustado para `DYNAMODB_HEAP_SIZE=2048m` e limite de container de 5 GiB.

Perfis de um segmento completo, com `pageSize=1000` e ProjectionExpression, mostraram que aumentar `TotalSegments` reduz fortemente a profundidade e a latência por página sem exigir mais workers: 32 segmentos processaram 205 mil itens em 150,5 s (p95 1,58 s; máximo 2,11 s), 64 segmentos processaram 105 mil itens em 42,5 s (p95 0,46 s; máximo 0,54 s) e 128 segmentos processaram 40 mil itens em 11,0 s (p95 0,265 s; máximo 0,268 s). Por isso, em escala alta, workers e segments devem ser tratados como controles independentes: muitos segmentos rasos com concorrência moderada são preferíveis a simplesmente elevar ambos.

O tuner aceita configurações explícitas no formato `workers x segments`, incluindo cenários como `8x128`. Resultados completos devem registrar se a medição foi `FRESH` ou `RESUMED`; `elapsedSeconds` do benchmark checkpointável representa tempo acumulado entre tentativas, não necessariamente o tempo de parede da última invocação. O script de benchmark também mantém um lock exclusivo sobre o diretório de saída para impedir execuções concorrentes de `-FreshProjection` de apagarem checkpoints de uma medição em andamento. A escrita atômica de checkpoints recria o diretório pai imediatamente antes de persistir o arquivo temporário, evitando falha de `run-state.json.tmp` quando o diretório precisar ser reconstruído.

Em 01/10/2026, a medição **fresh** de 5M, sem reaproveitar checkpoints, concluiu com `8 workers / 128 segments / pageSize=1000`, ProjectionExpression e heap de 2 GiB no DynamoDB Local. Foram examinados e retornados 5.000.000 itens em 6.128 requests, em 2.940,22 s, equivalentes a **1.700,55 itens/s** e ~0,255 MiB/s de payload projetado. O resultado registrou `checkpointMode=FRESH`, `resumedSegments=0` e `attempts=1`. Esse é o baseline comparável para 5M; resultados anteriores de ~4,8 mil itens/s usavam resume/checkpoints e não devem ser interpretados como throughput fresh de ponta a ponta.

Em 02/10/2026, uma execução de retomada sobre os checkpoints já completos de 5M confirmou o caminho de resume: `128/128` segmentos foram reutilizados, `checkpointMode=RESUMED`, `attempts=2` e a invocação adicional não precisou refazer o scan. O artefato `20261002-013450-91c15e47-5000000.json` é evidência funcional de retomada/idempotência e **não** substitui o resultado fresh de 01/10 como métrica de throughput.

Com 5M itens materializados, o arquivo principal do DynamoDB Local ocupa 10.804.543.488 bytes (~10,06 GiB), ou ~2.160,91 bytes físicos por item. Projeção linear: 10M ~20,13 GiB, 25M ~50,31 GiB e 100M ~201,25 GiB somente para o estado DynamoDB, sem contar backup, WAL/temporários, cópia offline e margem operacional.

### Higiene do armazenamento Docker/WSL

Em 01/10/2026, a falta de espaço do host não era causada apenas pelo fixture. O `docker_data.vhdx` havia crescido para ~89,01 GiB e o filesystem interno do Docker usava ~55,1 GiB. A investigação encontrou dois image stores coexistindo: o store ativo baseado em containerd e um store clássico `overlay2` antigo, invisível no inventário normal do store ativo. O `overlay2` legado sozinho ocupava ~32,3 GiB com dezenas de imagens de 2–4 anos e nenhum container associado.

A limpeza foi feita por APIs suportadas do Docker, sem apagar diretórios internos nem volumes: o store clássico foi ativado temporariamente, confirmado com `containerd-snapshotter=false / storage-driver=overlay2`, e recebeu `docker image prune -a -f` e `docker builder prune -a -f`. Foram removidos ~31,02 GiB de imagens antigas e ~630,6 MiB de build cache; `overlay2` caiu para ~104 KiB. O store containerd foi então restaurado e validado. O volume `aws-ops-toolkit_localstack-performance-persistent`, o backup de rollback e os volumes do FlowOps foram preservados.

Após `fstrim` e shutdown limpo do WSL/Docker Desktop, o VHDX dinâmico encolheu de ~89,01 GiB para ~30,62 GiB. O uso interno do filesystem Docker caiu para ~16,5 GiB e o espaço livre do único disco `C:` subiu para ~66,08 GiB. O backup anterior de rollback permanece comprimido em ~227 MiB.

Com esse novo headroom, **10M deixa de estar bloqueado por capacidade**: o fluxo offline agora aplica preflight separado em clone, append e promoção, sempre preservando 10 GiB de reserva. A promoção também cria rollback gzip antes de substituir o banco live e remove a cópia `offline-test` somente depois da validação pós-promoção. Escalas maiores continuam sujeitas ao pico temporário de live + offline + rollback e devem passar pelo preflight real; não devem ser aprovadas apenas pela projeção do tamanho final.

### Promoção para 10 milhões e validação pós-copy

Em 02/10/2026, o fixture offline foi estendido de 5M para **10.000.000 itens** e recebeu o gate `STRUCTURAL_PLUS_DYNAMODB_LOCAL_PUBLIC_API_CARDINALITY_PROBES_QUERY` em modo `structural-api`. Durante a promoção, o banco live passou a 21.492.959.232 bytes (~20,02 GiB) e foi preservado um rollback comprimido em `/data/offline-backups/20261002-015118-123456789012useast1_us-east-1.db.gz` (~1,17 GiB).

A atualização do manifesto não ocorreu no mesmo fluxo porque a validação pós-copy ainda dependia de `DescribeTable`, que apresentou long-tail severa no banco de 10M. O estado live foi então reconciliado com evidência independente: contagem SQLite exata em modo somente leitura retornou **10.000.000**; `GetItem` pela API pública encontrou os registros 0, 4.999.999, 5.000.000 e 9.999.999; e uma `Query` exata para o último registro retornou 1 item. O manifesto foi atualizado para 10M preservando uma cópia do manifesto stale anterior.

A partir dessa ocorrência, `validate_offline_fixture.py` oferece `--probe-only`. O gate pré-promoção continua responsável pela cardinalidade completa; o gate pós-copy verifica abertura e legibilidade do banco promovido via primeiro/último item e `Query`, com timeout/retry limitados. A promoção também compara o tamanho do banco copiado com o fixture offline antes de reiniciar o serviço. Isso evita que um `DescribeTable` patológico deixe o live já substituído com metadados locais ainda apontando para a escala anterior.

O baseline comparável de 10M deve ser uma execução **fresh**, read-only, com `8 workers / 128 segments / pageSize=1000`, sem checkpoints prévios. Resultados por resume devem ser registrados separadamente e não comparados como throughput fresh.

A primeira execução fresh de 10M, iniciada em 02/10/2026 com essa configuração, revelou um limite artificial no harness: `@Timeout(3600)` interrompeu o teste exatamente após 1 hora, com 6.024.699 itens examinados (60,25%) e 69/128 segmentos concluídos. Não houve falha do LocalStack nem timeout de chamada AWS. O limite do teste foi alterado para `@Timeout(value = 48, unit = TimeUnit.HOURS)` e validado por `spotless:check test-compile`.

A execução foi então retomada preservando os checkpoints e concluiu os **10.000.000 itens**, `128/128` segmentos e 12.128 requests. O artefato `20261002-155310-c644e0ef-10000000.json` registra `checkpointMode=RESUMED`, `attempts=2`, `resumedSegments=77`, `resumedCompletedSegments=69`, `elapsedHistoryComplete=true` e `elapsedSeconds=6.190,54 s`, equivalentes a **1.615,37 itens/s** e ~0,242 MiB/s quando se soma somente o tempo efetivamente gasto nas duas tentativas. A segunda tentativa processou 3.975.301 itens em 2.592,55 s (~1.533,36 itens/s). Esse resultado comprova travessia real dos 10M e resume completo, mas não substitui o baseline fresh de invocação única.

Após a correção do timeout, o baseline **fresh** de 10M foi executado novamente em uma única invocação, sem reaproveitar checkpoints. O artefato `20261002-173718-77038aa4-10000000.json` registra `checkpointMode=FRESH`, `attempts=1`, `resumedSegments=0`, `resumedCompletedSegments=0`, `128/128` segmentos concluídos e 12.128 requests. Foram examinados e retornados 10.000.000 itens em **6.042,81 s**, equivalentes a **1.654,86 itens/s** e ~0,248 MiB/s. Em comparação com o baseline fresh de 5M (`1.700,55 itens/s`, 2.940,22 s), o throughput caiu apenas **~2,69%** enquanto a cardinalidade dobrou; o tempo total ficou em ~2,055x e o número de requests em ~1,979x. Isso fecha o baseline comparável de 10M para este hardware/emulador, sem representar throughput do DynamoDB gerenciado.

### Promoção para 25 milhões com staging sem duplicação

Em 03/10/2026, o preflight para 25M mostrou que o fluxo original `clone -> append -> promote` não caberia com segurança no disco disponível. Com ~46,93 GiB livres no início da análise e banco live 10M de 21.492.959.232 bytes, a projeção do fixture 25M era ~53,58 GB (~49,89 GiB). O clone consumiria ~20,02 GiB adicionais e o append exigiria mais ~30,03 GiB; somados à reserva operacional de 10 GiB, o fluxo original exigiria cerca de 60,04 GiB livres antes do clone.

O filesystem do volume Docker foi testado com `cp --reflink=always`, mas retornou `Operation not supported`. Para remover a duplicação temporária sem abrir mão de rollback, `offline-fixture.ps1` passou a oferecer o estágio `stage`: cria primeiro um rollback gzip do live, para o LocalStack, move o DB live para `/data/offline-test` no mesmo volume via `mv`, registra `offline-stage.json` e valida estruturalmente o fixture destacado. A promoção correspondente detecta o stage ativo e usa `STAGED_MOVE`, movendo o DB validado de volta para o caminho live em vez de copiá-lo. A ação `restore-stage` restaura o rollback se for necessário abandonar o staging.

A primeira tentativa do novo stage detectou um erro de path antes de mover o banco: o diretório era criado em `/var/lib/localstack/offline-test` enquanto o volume auxiliar estava montado em `/data`. O live permaneceu intacto, o LocalStack voltou healthy e o backup temporário foi removido. O path foi corrigido para `/data/offline-test`; o stage passou então com 10.000.000 linhas e 128 amostras verificadas.

O append offline de 10M para **25.000.000 itens** gravou 15.000.000 novos registros em **1.939,96 s**, média de **7.732,12 registros/s**, e terminou com cardinalidade exata de 25.000.000 e 128 amostras válidas. O DB offline resultante ficou em **53.575.275.520 bytes** (~49,89 GiB). O gate `structural-api` foi ajustado para combinar a cardinalidade estrutural exata do SQLite com probes pela API pública (`GetItem` primeiro/último + `Query` exata), evitando `DescribeTable` patológico; `full-validate` continua disponível quando uma varredura completa for explicitamente desejada.

A promoção 25M concluiu com `strategy=STAGED_MOVE`: o LocalStack voltou `healthy`, o probe pós-promoção confirmou os registros 0 e 24.999.999 e `Query` retornou exatamente 1 item para o último registro. O manifesto foi atualizado para `primaryRecords=25000000` e `targetRecords=25000000`, `offline-test` foi removido e o stage marker foi limpo. O rollback do estado 10M ficou preservado em `/data/offline-backups/20261003-024002-123456789012useast1_us-east-1.db.gz`. Ao final, o host tinha ~11,58 GiB livres; portanto qualquer benchmark ou expansão além de 25M deve considerar que a margem sobre a reserva de 10 GiB é pequena.

### Limite observado no Parallel Scan de 25 milhões

O baseline comparável de 25M foi iniciado com a mesma configuração usada em 5M/10M: `8 workers / 128 segments / pageSize=1000`, read-only e `FreshProjection`. A primeira tentativa foi abortada preventivamente quando o espaço livre caiu abaixo da reserva de 10 GiB. Após limpeza de caches reconstruíveis, a execução fresh foi repetida e avançou até **822.784 itens (3,291%)**, 991 páginas e 1 segmento concluído antes de falhar por `Read timed out` após ~46 minutos, com `socket=120s`, `attempt=180s`, `API=600s`, 3 tentativas.

A retomada com checkpoints e envelope ampliado (`socket=240s`, `attempt=300s`, `API=600s`, 5 tentativas) falhou novamente: uma única chamada `Scan` permaneceu sem resposta até atingir o **API timeout de 600 s**. Nesse ponto os checkpoints somavam 827.771 itens, 997 páginas e 1 segmento concluído. Portanto o problema não é apenas um timeout curto; o LocalStack 4.14.0 apresenta long-tail patológico para esse Parallel Scan na fixture de 25M sob 8 workers.

A execução também revelou pressão de host: o `pagefile.sys` do Windows cresceu para ~24,75 GiB, enquanto o DB/volume Docker permaneceu estável. Isso reduziu o espaço livre do `C:` para ~8 GiB sem crescimento do fixture. Processos temporários antigos e caches reconstruíveis foram limpos, recuperando margem sem apagar fixture, checkpoints relevantes, rollback ou volumes FlowOps. A configuração de 8 workers fica registrada como **não concluída/inviável neste host/emulador em 25M**; experimentos seguintes devem reduzir concorrência para distinguir contenção do emulador de custo intrínseco de cardinalidade.

O experimento fresh subsequente reduziu a concorrência para **4 workers**, mantendo `128 segments`, `pageSize=1000` e os maiores timeouts válidos do harness (`socket=240s`, `attempt=300s`, `API=600s`, 5 tentativas). Essa configuração avançou para **1.237.474 itens (4,95%)**, 1.490 páginas e 2 segmentos completos em 5.142,06 s antes de repetir o mesmo padrão: uma única chamada `Scan` excedeu o API timeout de 600 s. A redução de 8 para 4 workers melhora o avanço inicial e reduz pressão concorrente, mas não elimina o long-tail patológico. O próximo teste reduz para 2 workers mantendo segmentos e página constantes para isolar ainda mais o efeito de concorrência.

O experimento fresh com **2 workers** finalmente concluiu os 25.000.000 itens em uma única tentativa, sem resume: artefato `20261005-073004-2ac756c3-25000000.json`, `checkpointMode=FRESH`, `attempts=1`, `resumedSegments=0`, `128/128` segmentos concluídos e 30.137 requests. O tempo total foi **25.810,86 s** (~7h10), throughput de **968,58 itens/s** e ~0,145 MiB/s. Esse resultado demonstra que 25M é viável neste hardware/emulador quando a concorrência cai para 2 workers; portanto o long-tail observado em 8w/4w é predominantemente associado à contenção do LocalStack/SQLite sob maior paralelismo, e não à cardinalidade isoladamente.

A comparação direta de throughput com 5M/10M exige ressalva porque 25M usou menos workers. Os baselines fresh de 5M e 10M com 8 workers ficaram em 1.700,55 e 1.654,86 itens/s, respectivamente, enquanto 25M/2w ficou em 968,58 itens/s (cerca de 41,47% abaixo de 10M e 43,04% abaixo de 5M). Essa diferença representa a **configuração prática necessária para completar 25M neste host**, não uma regressão de throughput em condições idênticas. O tempo total do 25M foi ~4,27x o do 10M, apesar de a cardinalidade ser 2,5x maior, refletindo principalmente a redução de concorrência.

### Profiler leve de profundidade de segmentos em 25M

Para testar se o custo do 25M poderia ser reduzido sem elevar novamente a concorrência, o harness recebeu um modo `segment-profile-only`. Ele usa o mesmo `DynamoDbService`, SDK, ProjectionExpression e timeouts do benchmark, mas executa somente o segmento 0 e marca os demais segmentos como concluídos em memória. O artefato `20261005-124712-2c2dbcfe-25000000-segment-profile.json` comparou 128, 256 e 512 segmentos com `pageSize=1000` e um único worker efetivo.

Resultados do segmento 0:
- **128 segmentos:** 200.000 itens, 241 páginas, 264,98 s, **754,76 itens/s**, ~1,100 s/página;
- **256 segmentos:** 100.000 itens, 121 páginas, 65,60 s, **1.524,48 itens/s**, ~0,542 s/página;
- **512 segmentos:** 25.000 itens, 31 páginas, 8,90 s, **2.808,01 itens/s**, ~0,287 s/página.

A instrumentação posterior de latência por chamada `Scan` foi validada no LocalStack real com o artefato `20261006-020105-5634f8db-25000000-segment-profile.json`, novamente no segmento 0/512. Foram 31 chamadas: média **535,52 ms**, p50 **228,09 ms**, p95 **1.075,04 ms**, p99/máximo **8.760,18 ms**. O perfil inteiro levou 16,63 s; portanto uma única chamada de 8,76 s respondeu por aproximadamente metade do tempo total. Isso confirma quantitativamente que o emulador apresenta cauda longa mesmo em configuração rasa e de baixa concorrência, e justifica registrar percentis/máximo por chamada em vez de observar apenas throughput médio.

O resultado reforça a hipótese já observada em 5M: `workers` e `TotalSegments` devem ser tratados de forma independente. Aumentar a quantidade de segmentos reduz a profundidade de cada scan e, neste emulador, derruba fortemente a latência média por página mesmo com concorrência fixa. Assim, o próximo candidato de baseline 25M é **2 workers / 512 segments / pageSize=1000**, não mais workers. Esse full scan só deve ser iniciado quando o host voltar a pelo menos 10 GiB livres.

O script de benchmark também passou a aplicar o gate de **10 GiB livres** antes de qualquer benchmark persistente, e não apenas antes de crescimento do fixture. `-AllowLowDisk` continua disponível como override explícito, mas não deve ser usado para medições longas neste host sem justificativa. O mesmo limite agora é monitorado **durante** o Parallel Scan: a cada 100 páginas o harness consulta o espaço útil do filesystem do `OUTPUT_DIR`; se cair abaixo da reserva, sinaliza cancelamento, deixa a página já processada persistir seu checkpoint e interrompe antes da próxima chamada AWS. O override `-AllowLowDisk` propaga `min-free-bytes=0` para desabilitar explicitamente também esse guardrail contínuo. A frequência e a reserva ficam registradas no JSON do benchmark.

Durante a primeira execução full-scan `2 workers / 512 segments`, o Windows reiniciou às 13:14:11, cerca de dois minutos depois do último checkpoint. O reboot preservou 19 checkpoints concluídos, mas deixou `segment-0019.json`, `segment-0020.json` e `run-state.json` com bytes NUL. O loader anterior abortava toda a retomada ao encontrar qualquer JSON inválido. O harness foi endurecido para mover estado fisicamente corrompido para `.corrupt-<timestamp>`, refazer apenas o segmento afetado e continuar reutilizando checkpoints válidos. Mismatch de configuração continua sendo erro duro. O estado temporal também passou a ser persistido redundantemente em `run-state.backup.json` e `run-state.json`, ambos por escrita atômica. No load, as duas cópias válidas são comparadas e a mais avançada é escolhida. Se o primário estiver corrompido, o backup preserva `attempts` e o elapsed conhecido; o resultado registra `runStateRecoverySource=BACKUP` e `knownAccumulatedElapsedSeconds`, mas mantém `elapsedHistoryComplete=false`, pois um reboot abrupto ainda pode perder a fração desde o último checkpoint temporal. Assim o laboratório conserva evidência conhecida sem fabricar throughput total exato.

A retomada concluiu os **25.000.000 itens / 512 segmentos** com sucesso no artefato `20261005-192914-6e82e02e-25000000.json`: `checkpointMode=RESUMED`, 19 segmentos reaproveitados, 30.504 requests e 3.924.500.000 bytes lidos. Como o `run-state` da primeira tentativa foi corrompido pelo reboot, `elapsedSeconds`, `scannedPerSecond` e `megabytesPerSecond` totais permanecem propositalmente nulos (`elapsedHistoryComplete=false`). A tentativa pós-reboot, porém, é íntegra: **23.750.000 itens em 14.651,12 s**, ou **1.621,04 itens/s**, com 28.985 páginas. Comparado ao baseline fresh `2 workers / 128 segments` de 968,58 itens/s, o throughput válido da tentativa `2x512` foi **~67,36% maior**. Como os universos medidos diferem (23,75M na tentativa pós-reboot versus 25M no baseline fresh), essa comparação deve ser interpretada como evidência operacional forte da redução de profundidade por mais segmentos, e não como um novo baseline fresh canônico de elapsed total.

Os checkpoints novos também passaram a usar uma identidade versionada. O diretório inclui o sufixo `cfg-v1` e contém `checkpoint-config.json` com versão, tabela, cardinalidade alvo, `pageSize`, `TotalSegments`, `itemShape` e ProjectionExpression. O resume falha fechado quando o manifesto não corresponde à configuração esperada ou quando encontra estado legado sem identidade dentro do namespace novo. `workers` fica deliberadamente fora da identidade: ele altera concorrência de execução, mas não a semântica do cursor de cada segmento, permitindo reduzir/aumentar workers entre tentativas sem invalidar checkpoints compatíveis. Os namespaces legados permanecem preservados como evidência e não são adotados silenciosamente. O conjunto de testes agora cobre criação/idempotência, rejeição de legado, identidade incompatível, limite de espaço livre, agregação de percentis, redundância do run-state e compatibilidade/observer do `DynamoDbService`.

A proveniência do benchmark também passou a ser capturada no **início** da execução. O script resolve `git rev-parse HEAD` e `git status --porcelain` antes do Maven, grava `gitCommit` e `gitDirty` no JSON e usa o mesmo commit pré-execução no filename histórico; resultados com alterações locais recebem o sufixo `-dirty`. O perfil curto `20261006-020837-3cac0516-25000000-segment-profile.json` validou a trilha limpa de ponta a ponta: `gitCommit=3cac05161644cd7bf7d3163fb94ba8b6fbad6e3d`, `gitDirty=false`, e o filename usa o mesmo hash curto sem marcador dirty.

### Perfil targeted de GetItem, BatchGet, Query e PartiQL

O laboratório agora oferece `-TargetedReadProfileOnly` para comparar apenas access patterns endereçáveis na tabela principal, sem executar `Scan` ou `Parallel Scan`. O modo é estritamente read-only e rejeita `-WriteCompatibility`. Ele mede `GetItem`, `BatchGetItem`, `Query` por partition key e PartiQL `SELECT ... WHERE pk=?`.

A comparação direta Query × PartiQL usa a mesma partition key (`tenant-00000`), a mesma projeção (`pk,sk,status,version`), consistência eventual explícita e `pageSize` comum. O harness valida paridade de cardinalidade e de bytes projetados; qualquer diferença encerra a execução. Como o backing store local demonstrou alta sensibilidade a cache, a medição descarta um warmup de cada caminho e usa ordem balanceada `Query -> PartiQL -> PartiQL -> Query`; o ratio final usa a soma das duas execuções medidas de cada API.

`ReturnConsumedCapacity=TOTAL` é solicitado sempre que suportado. A comparação de capacidade só é marcada como válida quando **todas** as execuções dos dois caminhos retornam essa informação. Em validação preliminar no LocalStack 4.14.0, `Query` reportou capacidade, enquanto `ExecuteStatement`/PartiQL não reportou; por isso capacidade não deve ser inferida ou preenchida artificialmente para PartiQL.

O único histórico anterior que continha Query e PartiQL, `20260923-111351-c5449f63-10000.json`, **não deve ser usado para comparar desempenho relativo entre essas duas APIs**: naquela versão, `Query` retornava o item completo enquanto PartiQL projetava somente quatro atributos. O artefato permanece válido como evidência histórica de execução, mas não como benchmark equivalente Query × PartiQL.

O perfil equivalente sobre o fixture de **25M** foi concluído no artefato `20261006-102354-04cf1a71-25000000-targeted-read-profile.json`, produzido read-only e com `gitDirty=false`. `GetItem` realizou 100 point lookups em 1,273 s e reportou 50 RCUs; `BatchGetItem` recuperou os mesmos 100 itens em uma chamada, 0,102 s e também 50 RCUs. Para a partition key `tenant-00000`, cada execução de Query e PartiQL retornou exatamente **25.000 itens** e **1.350.000 bytes projetados** em 30 requests. Nas duas execuções medidas, Query acumulou **6,674 s** (~7.492 itens/s efetivos), enquanto PartiQL acumulou **27,874 s** (~1.794 itens/s), ratio balanceado **PartiQL/Query = 4,177x**. Esse resultado é específico do LocalStack 4.14.0 e não autoriza inferir que PartiQL no DynamoDB AWS tenha a mesma penalidade.

A equivalência de `ConsumedCapacity` não pôde ser validada: Query reportou 3.833,5 unidades por execução, mas `ExecuteStatement`/PartiQL retornou capacidade ausente. O harness registra `queryPartiqlConsumedCapacityComparable=false` em vez de assumir zero. Um Scan filtrando `pk=tenant-00000` devolveria semanticamente o mesmo conjunto, mas exigiria varrer os **25M** itens; ele foi deliberadamente excluído deste perfil targeted porque repetiria o custo de full scan já caracterizado. Para esse access pattern, o resultado operacional permanece: **quando a partition key é conhecida, Query é o caminho nativo preferencial; PartiQL só deve ser adotado por ergonomia/necessidade concreta e medido no ambiente alvo; Scan não é equivalente em custo**.

### Diagnóstico read-only do backing store SQLite

O laboratório passou a oferecer `scripts/profile-localstack-sqlite.ps1` + `lab/profile_dynamodb_sqlite.py` exclusivamente para diagnosticar o backing store do DynamoDB Local. O wrapper monta o volume Docker como `:ro`, executa o container com filesystem read-only e rede desativada, ativa `PRAGMA query_only=ON`, registra proveniência Git e salva o resultado em `benchmark-results/localstack/sqlite-diagnostics`. O artefato declara explicitamente `managedDynamoDbEquivalent=false`: esse caminho **não é uma alternativa operacional ao DynamoDB gerenciado** e não deve ser incorporado ao runtime do toolkit.

No schema físico da tabela principal, a PK SQLite é `(hashKey, rangeKey)` e existe um índice `hashValue`. O `EXPLAIN QUERY PLAN` mostrou:
- `COUNT(*)`: scan do índice covering `hashValue`;
- point lookup por `hashKey + rangeKey`: search pela PK SQLite;
- leitura sem predicado: table scan.

O cache do host domina fortemente medições locais. Em observações sucessivas do mesmo DB de 25M, sem qualquer flush de cache, o `COUNT(*)` caiu de ~106,52 s para ~15,38 s e depois para **1,14 s**. Por isso o profiler grava `cacheControl=NONE` e `coldCacheGuaranteed=false`; seus tempos servem para diagnóstico de plano e comportamento do emulador, não como baseline canônico.

O artefato reproduzível `20261006-021652-a21090ec-25000000-sqlite-diagnostic.json`, produzido com `gitDirty=false`, confirmou 25.000.000 linhas. Já com cache aquecido, 1.000 lookups por chave tiveram média ~0,017 ms, p95 ~0,037 ms e máximo ~0,090 ms; a leitura sequencial dos primeiros 100.000 `ObjectJSON` atingiu ~719 mil linhas/s e ~1.226 MiB/s. Em conjunto com a telemetria de `client.scan(...)`, isso mostra que a cauda longa observada no Parallel Scan não pode ser explicada apenas por leitura sequencial bruta do SQLite: há efeitos importantes de plano, cache e da própria camada DynamoDB Local.

### Gate de capacidade para 50M, 75M e 100M

Após o fechamento do baseline 25M, o host tinha apenas **~9,07 GiB livres** no único volume disponível (`C:`). O DB live de 25M mede **53.575.275.520 bytes (~49,90 GiB)**, aproximadamente **2.143 bytes por registro**. Mantendo essa densidade, o fixture de 50M projeta ~**99,79 GiB**, o de 75M ~**149,69 GiB** e o de 100M ~**199,58 GiB**.

Mesmo com `STAGED_MOVE`, que elimina a duplicação integral do DB durante a promoção, o append 25M -> 50M ainda precisa acrescentar ~**49,90 GiB**. Com a reserva operacional mínima de 10 GiB, seriam necessários ~**59,90 GiB livres antes do append**. Com ~9,07 GiB disponíveis, o déficit mínimo é **~50,82 GiB**, sem sequer contabilizar a criação de um novo rollback comprimido. Usando a razão de compressão observada no backup 10M, um rollback 25M ficaria na ordem de ~5,77 GiB, elevando o déficit prático para aproximadamente **56,6 GiB**.

Para 75M e 100M, considerando apenas crescimento do DB + reserva de 10 GiB, os déficits mínimos são ~**100,72 GiB** e ~**150,62 GiB**, respectivamente. Não existe outro volume local disponível neste host. Portanto **50M, 75M e 100M estão bloqueados por capacidade física no ambiente atual** e não devem ser iniciados. O próximo passo válido para essas escalas é disponibilizar armazenamento adicional/mover o storage do Docker ou executar o laboratório em host com folga compatível; apagar fixture, rollback ou evidências para tentar forçar a expansão não é aceitável.
