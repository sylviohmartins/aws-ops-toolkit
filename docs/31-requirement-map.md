# Rastreabilidade do prompt

Nome definitivo: **aws-ops-toolkit**. A numeração abaixo corresponde às seções do prompt recebido. A ordem de leitura das 32 partes do entregável está no [README](../README.md). “Blueprint” significa desenho concreto para implementar; não significa capacidade já liberada.

| Seções do prompt | Evidência principal | Nível desta entrega |
| --- | --- | --- |
| 0–3 missão, contexto e princípio | [Resumo](00-overview.md), [requisitos](01-requirements.md) | Arquitetura e limites operacionais |
| 4–5 pesquisa e stack | [Pesquisa](00-research.md), [Maven](25-maven-dependencies.md) | Fontes oficiais, versões fixadas e comparação Boot/HTTP |
| 6 concorrência | [Arquitetura](02-architecture.md), [concorrência](12-concurrency-performance.md) | A/B/C/D comparados; VT e gates compiláveis |
| 7–9 arquitetura, jobs e estados | [Engine](05-operation-engine.md), [API](27-api.md) | Engine sintética executável + lifecycle produtivo blueprint |
| 10–12 dry-run, PROD, credenciais | [Guardrails](03-security-production-guardrails.md), [autenticação](04-aws-authentication.md) | LOCAL/DRY_RUN obrigatório; STS exemplo; aprovação e auth resume produtivos blueprint |
| 13–14 clients e retries | [Pesquisa](00-research.md), [resiliência](13-resilience.md), [config](16-configuration.md) | Config compilável, clients reutilizados e tentativas explícitas |
| 15–20 DynamoDB | [DynamoDB](06-dynamodb.md) | Exemplos leitura/escrita condicional, Scan paralelo limitado e checkpoint callback; algoritmo adaptativo/ledger blueprint |
| 21–22 SQS/DLQ/consumer | [SQS](07-sqs.md) | Adapter compilável; replay/consumer completo blueprint |
| 23 SNS | [SNS](08-sns.md) | Adapter publish/batch e desenho dedupe/outcomes |
| 24 Lambda | [Lambda](09-lambda.md) | Invoke compilável e distinção FunctionError/aceitação |
| 25 S3 | [S3](10-s3.md) | Streaming compilável e multipart/Transfer Manager blueprint |
| 26–27 HTTP/Resilience4j | [HTTP](11-http-integrations.md), [resiliência](13-resilience.md) | HTTP Interface/RestClient compilável; Feign rejeitado; breaker/retries HTTP blueprint |
| 28–30 engine/checkpoint/idempotência | [Engine](05-operation-engine.md), [checkpoint](23-checkpoint-resume-idempotency.md) | Demo durável com replay determinístico; SQLite/ledger/outbox blueprint |
| 31–36 relatórios/auditoria/logs/métricas | [Relatórios](15-reporting-audit.md), [observabilidade](14-observability.md) | CSV, logs, Actuator e contador reais; XLSX/audit ledger/catalog completo blueprint |
| 37 Java/JVM | [Java/JVM](24-java-jvm.md) | Recursos/GC/profiling avaliados, sem preview |
| 38–41 config/hot tuning/API/request | [Config](16-configuration.md), [API](27-api.md) | YAML reais e exemplo alvo separado; request tipado e bearer local; tuning blueprint |
| 42–46 cancel/shutdown/budget/canary/rollback | [Engine](05-operation-engine.md), [runbook](17-war-room-runbook.md), [cenário](18-complex-scenario.md) | Cancel/pause/crash-resume locais; budgets/canary/compensação produtivos blueprint |
| 47–50 Maven/deps/pacotes/reutilização | [Maven](25-maven-dependencies.md), [blueprint](26-implementation-blueprint.md) | POM/Wrapper/classes concretos e matriz alvo |
| 51 cenário complexo | [Pagamentos](18-complex-scenario.md) | Cenário fictício com falhas e reconciliação; não executado AWS |
| 52–54 lab/performance/validação | [Benchmark](19-benchmark-plan.md), [evidências](30-final-validation.md) | Plano de medição + build/testes/smoke locais registrados |
| 55–59 preflight/disco/privacy/runbook/fail-safe | [Guardrails](03-security-production-guardrails.md), [relatórios](15-reporting-audit.md), [runbook](17-war-room-runbook.md) | Preflight local/disco real; política produtiva blueprint |
| 60–63 antipadrões/ADRs/docs/diagramas | [Riscos](21-risks.md), [ADRs](22-adrs/), [README](../README.md) | Documentação modular, 14 ADRs e diagramas Mermaid |
| 64–67 blueprint/skeleton/qualidade/Java | [Blueprint](26-implementation-blueprint.md), [JVM](24-java-jvm.md), [código](../src/main/java/io/github/awsopstoolkit/) | Java compilável, responsabilidades e limites explícitos |
| 68–69 fases/MVP/CORE/ADVANCED | [Roadmap](20-implementation-roadmap.md) | Entregável/dependências/risco/validação/pronto por fase |
| 70–72 questionamento/fatos/fontes | [Pesquisa](00-research.md), ADRs e fontes inline | Tecnologia escolhida por necessidade; premissas corporativas identificadas |
| 73 revisão A–K | [Validação final](30-final-validation.md) | Revisão teórica separada dos ensaios executados |
| 74 ordem final | [Índice de 32 partes](../README.md) | Ordem preservada com links para documentos |
| 75–76 sucesso/design | [Resumo](00-overview.md), [blueprint](26-implementation-blueprint.md), [roadmap](20-implementation-roadmap.md) | Fundação para operação reutilizável; liberação produtiva condicionada aos gates |

Não existe rollback universal, credencial permanente, mecanismo de elevação de privilégio ou promessa de exactly-once entre serviços. Os exemplos e o roadmap devem ser usados juntos: os controles faltantes para produção estão declarados, não substituídos por comentários no código.
