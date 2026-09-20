# Performance Lab: plano reproduzível

**Status: plano, sem resultados medidos.** Benchmark massivo, limites de throughput e comportamento AWS remoto não foram comprovados pela simples existência do skeleton. Usar dados sintéticos, stubs locais e recursos DEV/HML isolados e autorizados. Não realizar testes destrutivos ou de saturação em produção.

## Perguntas e ambientes

O experimento deve responder: qual o menor nível de concorrência que entrega throughput sustentável com memória limitada, p95 aceitável e recuperação correta? O segundo objetivo é medir o custo de ledger, relatórios, GC e retries, separadamente do tempo de rede.

| Camada | Experimento | Limite do que comprova |
| --- | --- | --- |
| LOCAL sintético | 1, 5 e 10 milhões de registros gerados por página; 1 KiB/4 KiB/16 KiB por item e outliers | Heap/fila/CPU/disco; não simula fielmente quotas AWS |
| Stub HTTP | Latência parametrizada, 429, 500, perda de resposta e timeout | Backpressure, classificação, retry/deadline e cancelamento |
| DynamoDB Local/emulador, se adotado | Paginação, schemas, condições e dados sintéticos | Não reproduz completamente IAM, partições, limites, throttling ou latência AWS |
| DEV/HML isolado | Recursos e quotas aprovados; leitura/escrita sintética limitada | Comportamento remoto, credenciais, rate limiting e custo real naquele ambiente |
| Ensaio de falhas | Crash controlado, auth expirada, disco simulado cheio | Recuperação e auditabilidade; independente de records/s |

Para dezenas de milhões, ampliar o ensaio para 30 milhões apenas após 10 milhões estabilizarem heap/disco. O endpoint sintético inicial aceita no máximo 10 milhões; 30 milhões exige harness de laboratório separado ou evolução revisada desse limite, sem apresentar esse ensaio como executável pela API atual. Volume não deve virar materialização prévia em heap: gerar registro/página deterministicamente com seed e tamanhos declarados.

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

Tabela de resultados a preencher após execução:

| Concorrência | Items/s | p95 total / remoto | CPU | Heap estabilizado | Retry / throttle | Disco e checkpoint | Resultado |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | Não medido | Não medido | Não medido | Não medido | Não medido | Não medido | Pendente |
| 4–256 | Não medido | Não medido | Não medido | Não medido | Não medido | Não medido | Sweep conforme limites |

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
