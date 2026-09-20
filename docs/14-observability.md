# Observabilidade local e diagnóstico

Pesquisa: 18/09/2026. **DECISÃO:** Actuator + Micrometer para métricas locais, logs estruturados para diagnóstico e audit trail separado para evidência. JFR/JMC sob demanda. Exportação remota, tracing e async-profiler são opcionais. Este capítulo é o contrato alvo; consultar o código e o checklist final para o subconjunto implementado.

No código inicial, o YAML configura logs estruturados em arquivo e rolling; Actuator expõe health/metrics sob autenticação local. A engine incrementa `toolkit.records.processed` com tag finita de tipo. Os demais meters de negócio, bridge MDC, audit trail durável e exportação OTel/SDK deste capítulo ainda são plano. O `ScopedValue` de operação existente não injeta automaticamente MDC nos logs.

## Instrumentação e cardinalidade

Boot integra Micrometer e fornece métricas JVM/processo; métricas específicas de Virtual Threads requerem o módulo apropriado. Isso não significa que contadores de negócio aparecem automaticamente. Registrar explicitamente os meters da engine e adapters. [Spring Boot: metrics](https://docs.spring.io/spring-boot/reference/actuator/metrics.html).

| Métrica proposta | Tipo/unidade | Origem e uso |
| --- | --- | --- |
| `toolkit.records.scanned` / `.matched` | Counter, registros | Separar examinados de candidatos |
| `toolkit.records.processed` / `.updated` / `.skipped` / `.failed` | Counter, registros | Definição de desfecho único por item no ledger |
| `toolkit.aws.calls` / `.attempts` / `.retries` / `.throttled` | Counter, chamadas/tentativas | Diferenciar chamada lógica e tentativa do SDK |
| `toolkit.aws.latency` / `toolkit.http.latency` | Timer, segundos | Latência remota; p50/p95/p99 com histogramas limitados |
| `toolkit.queue.depth` / `.bytes` / `.inflight` | Gauge, itens/bytes | Detectar produtor adiantado e saturação |
| `toolkit.checkpoint.age` | Gauge, segundos | Tempo desde último checkpoint confirmado |
| `toolkit.report.bytes` / `toolkit.disk.usable` | Counter/Gauge, bytes | Projeção de crescimento e reserva |
| `toolkit.dynamo.consumed` | Counter, unidades de capacidade | `ReturnConsumedCapacity`; separado por leitura/escrita |
| `toolkit.auth.health` | Gauge ou status na API | Estado conhecido, data da última validação; sem token |
| `jvm.*`, `process.*`, `system.*`, `disk.*` | Meters fornecidos pelo runtime | Heap, GC, CPU, threads, disco |

Throughput é derivado de delta/tempo, não um contador de taxa: `items/s = Δprocessed / Δsegundos`. Publicar média desde início e janela recente, distinguindo pausa do tempo ativo. ETA só existe com denominador confiável; Scan em tabela mutável geralmente não permite percentual exato. Um número aproximado deve carregar `estimated=true`.

Tags permitidas: tipo de operação, ambiente, serviço, ação, classe de resultado e alias de recurso de uma allowlist finita. **Não usar `operationId`, payment ID, URL completa, ARN variável, mensagem de exceção ou token como tags.** Esses valores pertencem ao relatório/ledger/log sanitizado. Limitar tags e quantidade de meters por `MeterFilter`; rejeições devem ser observáveis. [Micrometer: Meter Filters](https://docs.micrometer.io/micrometer/reference/concepts/meter-filters.html).

O SDK AWS disponibiliza publicação de métricas; um `MetricPublisher` próprio pode alimentar meters locais. Selecionar somente métricas úteis e evitar bloquear callbacks com disco/rede. CloudWatch não é requisito nem destino padrão da ferramenta. [AWS SDK: metrics](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/metrics.html).

## Logs de operação e trilha de auditoria

Exemplo de esquema alvo, sem identificadores reais:

```json
{"timestamp":"2026-09-18T12:00:00Z","level":"INFO","event":"step.progress","operationId":"<UUID>","step":"conditional-update","resource":"payments-hml","mode":"DRY_RUN","elapsedMs":1200,"processed":100,"failed":0}
```

Mensagens usam códigos estáveis e campos, sem concatenar request/response. Request IDs dos serviços podem ser guardados no ledger restrito. SQL, payloads, tokens, headers de autorização e caminhos contendo identificadores devem passar por allowlist/redação. Logs de progresso por intervalo ou página, nunca INFO por item em milhões de itens. Erros repetidos são agregados, com amostras limitadas; evidência essencial continua no ledger.

**DECISÃO:** rolling files por tamanho e tempo, retenção e `totalSizeCap` configuráveis, diretório por instalação com acesso local restrito. Fila assíncrona de diagnóstico limitada pode descartar DEBUG com contador de perdas; eventos de auditoria de intenção/resultado não podem ser silenciosamente descartados. Se armazenamento de evidência obrigatório falhar, fechar admissão de escrita.

MDC é escopo por thread, não transporte automático entre executors. O wrapper da tarefa instala somente campos permitidos e restaura/limpa no `finally`. `ScopedValue` mantém contexto imutável; o bridge de logging preenche MDC quando necessário. Fazer rebind explícito em toda tarefa submetida. Não pressupor que tracing, MDC e ScopedValue sejam a mesma infraestrutura. [Oracle: bindings ScopedValue](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/ScopedValue.html).

## Exposição e tracing

`127.0.0.1`, autenticação local e endpoints Actuator allowlisted. Health público, se adotado, não detalha credenciais, recursos ou exceptions. Métricas/progresso exigem autenticação; `env`, heap dump, config props e thread dump não ficam irrestritos. Traces não exportam bodies, PII ou baggage arbitrário. Exportação OTLP exige endpoint corporativo aprovado e retenção definida.

**DECISÃO:** OTel é opcional quando correlacionar HTTP/eventos agrega à investigação. Micrometer Observation pode representar `operation.step` e `adapter.call`; amostrar em vez de criar milhões de spans indiscriminadamente. Escolher um único caminho de instrumentação para evitar duplicação. O projeto OTel recomenda Java agent como opção padrão geral; o starter é alternativa em cenários específicos. Validar BOMs e instrumentações com a linha Boot usada antes da adoção; não importar dependências preventivamente. [OpenTelemetry Spring Boot](https://opentelemetry.io/docs/zero-code/java/spring-boot-starter/).

## Diagnóstico sem stack externa obrigatória

| Sintoma | Primeiro instrumento | Investigação |
| --- | --- | --- |
| Heap sobe continuamente | Micrometer + JFR | Retenção, filas, mapas, buffers, alocações |
| CPU alta e pouco progresso | JFR/JMC | Serialização, transformação, compressão, GC |
| CPU baixa e fila cresce | Timers + in-flight + JFR | Latência remota, pool, permissões, lock, disco |
| VT não escala | JFR + thread dump | Bloqueio nativo/foreign, contenção, limite downstream |
| Checkpoint envelhece | Estado de página/ledger | Item lento, writer parado, disco/lock |
| Picos de latência | SDK attempts + timers | Retry, throttling, DNS, conexão e pausa GC |

JFR vem com o JDK; JMC analisa gravações. Capturar somente durante janela necessária e tratar JFR/heap dumps como dados potencialmente sensíveis. [Oracle: JMC 9](https://docs.oracle.com/en/java/java-components/jdk-mission-control/9/user-guide/jdk-mission-control-users-guide.pdf).

Exemplos manuais, substituindo o PID e usando diretório aprovado já criado:

```text
jcmd <PID> JFR.start name=aws-ops settings=profile duration=60s filename=diagnostics/incident.jfr maxsize=256m
jcmd <PID> Thread.dump_to_file -format=json diagnostics/threads.json
jfr summary diagnostics/incident.jfr
```

Ver opções do JDK realmente instalado antes de executar. Eventos podem ter thresholds/amostragem, portanto ausência de evento não prova ausência de problema. [Oracle: jcmd Java 25](https://docs.oracle.com/en/java/javase/25/docs/specs/man/jcmd.html).

Async-profiler fica reservado para laboratório em plataformas suportadas. A distribuição oficial consultada oferece Linux e macOS; não pressupor execução nativa no Windows deste projeto. Executar o workload em Linux/WSL altera o ambiente medido e isso deve constar do resultado. Não confundir medir uma JVM Linux com anexar a uma JVM Windows. [async-profiler: plataformas e modos](https://github.com/async-profiler/async-profiler).
