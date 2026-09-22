# Catálogo de configuração

Fonte de verdade: records anotados com @ConfigurationProperties. O YAML de exemplo está em [examples/application-example.yml](../examples/application-example.yml). Valores reais de segredo, account/resource PROD ou token não pertencem ao repositório.

## toolkit.core

| Property | Type | Default | Required/range | Description | Operational impact |
| --- | --- | --- | --- | --- | --- |
| toolkit.core.environment | Environment | LOCAL no YAML | LOCAL/DEV/HML/PROD | ambiente operacional | PROD ativa guardrails mais rígidos |
| toolkit.core.data-directory | Path | .aws-ops-toolkit/data | non-null | journal/checkpoints/reports | disco local e recuperação |
| toolkit.core.minimum-free-space | DataSize | 1GB | non-null | reserva mínima de disco | evita iniciar/continuar sem evidência suficiente |
| toolkit.core.max-concurrent-operations | int | 2 | 1..4 | foundation operations | maior valor aumenta uso local |
| toolkit.core.page-size | int | 100 | 1..1000 | page size da foundation sintética | memória/throughput LOCAL |
| toolkit.core.write-enabled | boolean | false | — | primeiro gate de escrita | deve permanecer false por default |
| toolkit.core.local-token | String | sem default real | 32..256, non-blank | bearer loopback | segredo por sessão; não logar |

## toolkit.aws

| Property | Type | Default | Required/range | Description | Operational impact |
| --- | --- | --- | --- | --- | --- |
| toolkit.aws.enabled | boolean | false | — | cria clients AWS reais | quando true exige profile/account explícitos |
| toolkit.aws.region | String | us-east-1 | non-blank | região central dos clients | região errada deve falhar no preflight |
| toolkit.aws.profile | String | vazio | requerido se AWS enabled | profile shared config | seleciona provider legítimo |
| toolkit.aws.expected-account | String | vazio | 12 dígitos quando AWS enabled | account esperada | bloqueia conta errada |
| toolkit.aws.max-connections | int | 8 | 1..64 | pool HTTP SDK | aumentar eleva pressão downstream |
| toolkit.aws.connection-timeout | Duration | 3s | >0 | conexão TCP/TLS | controla espera por conexão |
| toolkit.aws.acquisition-timeout | Duration | 2s | >0 | espera por conexão do pool | impede fila local longa |
| toolkit.aws.socket-timeout | Duration | 25s | >0 | IO socket | limita chamada remota |
| toolkit.aws.max-idle | Duration | 30s | >0 | idle pool | reciclagem de conexões |
| toolkit.aws.api-call-attempt-timeout | Duration | 30s | >0 e <= total | tentativa SDK | deadline por attempt |
| toolkit.aws.api-call-timeout | Duration | 35s | >0 | chamada SDK total | deadline global |

## toolkit.dynamodb

| Property | Type | Default | Required/range | Description | Operational impact |
| --- | --- | --- | --- | --- | --- |
| toolkit.dynamodb.page-size | int | 100 | 1..10000 | typed access page size | memória/request size |
| toolkit.dynamodb.tables | Map<String,String> | {} | logical/physical non-blank | logical→physical table | troca tabela por ambiente sem recompilar |

Exemplo:

~~~yaml
toolkit:
  dynamodb:
    tables:
      payments: dev-payments
~~~

## toolkit.http

| Property | Type | Default | Required/range | Description | Operational impact |
| --- | --- | --- | --- | --- | --- |
| toolkit.http.payment-endpoint | URI | null | opcional | endpoint do cenário payment | habilita workflow específico |
| toolkit.http.payment-hosts | Set<String> | [] | — | host allowlist | SSRF/target safety |
| toolkit.http.connect-timeout | Duration | 3s | >0 | conexão | latência/fail-fast |
| toolkit.http.response-timeout | Duration | 10s | (0,1m] | resposta | deadline por request |
| toolkit.http.max-attempts | int | 3 | 1..5 | tentativas safe-read | evita retry explosivo |
| toolkit.http.max-response-body | DataSize | 64KB | (0,10MB] | body máximo aceito | limita materialização de resposta em memória |
| toolkit.http.max-retry-after | Duration | 10s | >=0 | Retry-After máximo | acima disso pausa em vez de dormir indefinidamente |

## toolkit.operations

| Property | Type | Default | Required/range | Description | Operational impact |
| --- | --- | --- | --- | --- | --- |
| toolkit.operations.enabled | boolean | false | — | liga runtime durável | safe default off |
| toolkit.operations.healthy-responses-before-increase | int | 20 | 1..10000 | streak saudável antes de aumentar AIMD | controla velocidade de recuperação |
| toolkit.operations.circuit-failures-before-open | int | 5 | 1..100 | falhas antes de abrir gate local | evita pressão sustentada em downstream degradado |
| toolkit.operations.circuit-open-duration | Duration | 10s | >0 | janela de pausa do gate local | reduz hammering durante falha |
| toolkit.operations.retry-base-backoff | Duration | 100ms | (0,10s] | base de full-jitter backoff | controla pressão de retries |
| toolkit.operations.read-max-attempts | int | 3 | 1..5 | tentativas de leitura AWS seguras controladas pelo runtime | SDK fica em uma tentativa para evitar retry empilhado |
| toolkit.operations.writes | boolean | false | — | segundo gate de escrita | sozinho não autoriza efeitos |
| toolkit.operations.lab-endpoint | URI | null | lab only | endpoint emulador | nunca apontar credencial dummy a AWS real |
| toolkit.operations.resources | Set<String> | [] | allowlist | recursos permitidos | fail-closed |
| toolkit.operations.principals | Set<String> | [] | allowlist | roles/principals permitidos | fail-closed |
| toolkit.operations.requests-per-second | int | 10 | 1..1000 | teto RPS | AIMD pode reduzir |
| toolkit.operations.workers | int | 2 | 1..32 | teto concorrência | AIMD pode reduzir |
| toolkit.operations.page-size | int | 100 | 1..1000 | task/planning page | memória/SQLite |
| toolkit.operations.plan-lifetime | Duration | 24h | >0 | validade plano selado | força replan quando stale |
| toolkit.operations.approval-lifetime | Duration | 15m | >0 | validade approval | reduz janela de risco |
| toolkit.operations.retention | Duration | 30d | >0 | evidência terminal local | disco/privacy |
| toolkit.operations.dry-run-sample-size | int | 10 | 1..100 | before/after samples | aumenta diagnóstico e payload |
| toolkit.operations.shutdown-timeout | Duration | 25s | >0 | graceful executor wait | depois força shutdown local |
| toolkit.operations.estimated-bytes-per-record | DataSize | 8KB | >0 | estimativa conservadora de ledger/disco por candidato | aumenta a reserva de disco do preflight |

## toolkit.report

| Property | Type | Default | Required/range | Description | Operational impact |
| --- | --- | --- | --- | --- | --- |
| toolkit.report.csv-delimiter | String | , | 1 char | delimitador CSV | interoperabilidade |
| toolkit.report.flush-every-records | int | 500 | 1..100000 | flush cadence | durability vs IO |
| toolkit.report.max-concurrent-reports | int | 1 | 1..8 | heavy reports | disco/CPU/memória |
| toolkit.report.xlsx-row-window | int | 100 | 1..10000 | SXSSF row window | heap vs temp disk |
| toolkit.report.xlsx-max-data-rows | int | 1000000 | 1..1048575 | data rows por sheet | limite abaixo do formato Excel |
| toolkit.report.xlsx-estimated-bytes-per-row | long | 1024 | >=128 | estimativa de disco | preflight conservador |
| toolkit.report.protect-spreadsheet-formulas | boolean | true | — | neutraliza células formula-like | segurança ao abrir CSV/XLSX |

## toolkit.sqs

| Property | Type | Default | Required/range | Description | Operational impact |
| --- | --- | --- | --- | --- | --- |
| toolkit.sqs.visibility-timeout | Duration | 120s | (0,12h] | lease ReceiveMessage | afeta concorrentes/reprocessamento |

## Nomenclatura e fonte de verdade

- prefixo único: toolkit;
- kebab-case;
- nomes completos;
- uma configuração não deve existir como outra constante/YAML independente;
- constantes fixas de formato/protocolo não viram property;
- valores operacionais não viram static final.

## Metadata e IntelliJ

spring-boot-configuration-processor gera META-INF/spring-configuration-metadata.json no build. additional-spring-configuration-metadata.json acrescenta descrições operacionais. O artefato gerado deve conter os grupos/properties acima e habilitar completion/typing no IntelliJ.

## Precedência

O projeto não implementa uma camada paralela de configuração. Usa a precedência padrão do Spring Boot. Para operação humana:

1. baseline em application.yml;
2. diferenças ambientais em application-<profile>.yml;
3. environment variables/system properties para sessão/infra;
4. command-line somente quando a execução exigir override explícito e auditável.

O relatório/audit nunca deve imprimir local-token, credentials ou conteúdo sensível.
