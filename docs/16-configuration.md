# Configuração externa

Este capítulo descreve a configuração executável atual do aws-ops-toolkit. O catálogo detalhado está em [34-configuration-catalog.md](34-configuration-catalog.md) e o exemplo completo, seguro e sem segredos está em [examples/application-example.yml](../examples/application-example.yml).

## Princípios

- prefixo único: toolkit;
- @ConfigurationProperties por capability;
- records/tipos imutáveis sempre que o contrato permitir;
- Duration, DataSize, Path, URI, enums e collections tipadas em vez de strings/unidades implícitas;
- ignoreUnknownFields=false nos grupos do toolkit;
- Bean Validation + invariantes do construtor canônico;
- valores operacionais externalizados;
- defaults fail-closed;
- nenhuma credencial/token no código ou YAML versionado.

| Prefixo | Responsabilidade |
| --- | --- |
| toolkit.* | bootstrap, diretório local, ambiente e primeiro gate de escrita |
| toolkit.aws.* | credentials profile, account, region, pool/timeouts dos clients |
| toolkit.dynamodb.* | acesso Dynamo tipado e logical→physical table mappings |
| toolkit.http.* | políticas da integração HTTP |
| toolkit.journal.* | contenção SQLite e janelas bounded de leitura |
| toolkit.operations.* | runtime durável, workers, rate, lifetimes e gates |
| toolkit.report.* | CSV/XLSX e limites de reporting |
| toolkit.s3.* | buffers e multipart tuning |
| toolkit.sqs.* | receive, visibility e lease/reacquisition tuning |

A documentação completa de tipo/default/range/impacto está no [catálogo](34-configuration-catalog.md).

## Properties classes atuais

- ToolkitProperties
- AwsProperties
- DynamoProperties
- HttpProperties
- JournalProperties
- RuntimeProperties
- ReportProperties
- S3Properties
- SqsProperties

@ConfigurationPropertiesScan registra os grupos. Collections expostas por properties que funcionam como contrato (tables, resources, principals, paymentHosts) são copiadas defensivamente.

## Defaults de segurança

~~~yaml
toolkit:
  aws:
    enabled: false
  core:
    write-enabled: false
  operations:
    enabled: false
    writes: false
~~~

Nenhum desses switches, isoladamente, autoriza uma escrita. Fora de LOCAL, o runtime também exige identidade/conta/recurso permitidos, request EXECUTE, confirmação explícita, referência operacional/motivo, plano selado e aprovação válida.

## Unidades explícitas

~~~yaml
toolkit:
  core:
    minimum-free-space: 1GB
  aws:
    connection-timeout: 3s
    api-call-timeout: 35s
  journal:
    busy-timeout: 5s
  operations:
    approval-lifetime: 15m
    plan-lifetime: 24h
    retention: 30d
    shutdown-timeout: 25s
  s3:
    multipart-part-size: 8MB
    stream-buffer-size: 64KB
  sqs:
    acknowledgement-lease-reserve: 5s
    receive-wait-time: 1s
    visibility-timeout: 120s
~~~

Não há mais properties novas do runtime expressas como *-millis, *-seconds ou bytes sem unidade quando Spring suporta tipo semântico.

## Tuning sem recompilação

Valores operacionais como workers, RPS, page size, dry-run sample size, SQLite windows/timeout, report flush cadence, S3 buffer/part size, SQS receive/lease tuning e physical table name vêm de configuração.

Trocar uma tabela física mapeada ou workers de 16 para 32 não exige recompilar.

O endpoint de hot tuning do runtime pode ajustar taxa/concorrência dentro dos ceilings configurados e auditáveis; não altera identidade, recursos, plano ou outros invariantes do job.

## Metadata e autocomplete

O POM inclui spring-boot-configuration-processor como dependência optional. O build gera META-INF/spring-configuration-metadata.json. META-INF/additional-spring-configuration-metadata.json adiciona descrições operacionais às properties críticas.

Objetivo no IntelliJ: autocomplete, tipo, descrição e descoberta de property inválida/renomeada.

## Precedência

O projeto utiliza a precedência padrão do Spring Boot; não existe uma camada paralela de configuração.

Para uso operacional:

1. application.yml: baseline seguro;
2. application-<profile>.yml: diferenças DEV/HML/PROD/lab;
3. environment variables/system properties: máquina/sessão/integração;
4. command-line: override explícito e excepcional.

| Property | Environment variable |
| --- | --- |
| toolkit.aws.region | TOOLKIT_AWS_REGION |
| toolkit.aws.profile | TOOLKIT_AWS_PROFILE |
| toolkit.operations.workers | TOOLKIT_OPERATIONS_WORKERS |
| toolkit.operations.requests-per-second | TOOLKIT_OPERATIONS_REQUESTS_PER_SECOND |
| toolkit.report.flush-every-records | TOOLKIT_REPORT_FLUSH_EVERY_RECORDS |

Segredos devem vir por mecanismo externo autorizado. TOOLKIT_CORE_LOCAL_TOKEN nunca deve ser colocado em Git, URL ou log.

## Snapshot efetivo

No início de um job, JobCoordinator grava no audit CONFIG_SNAPSHOT com workers, page size, requests/second, plan/approval lifetimes, environment e region. Token e credentials não entram no snapshot.

## Profiles

| Profile | Ambiente | AWS real default | Escrita default | Objetivo |
| --- | --- | --- | --- | --- |
| base | LOCAL | off | off | foundation segura |
| dev | DEV | off | off | configurar explicitamente recursos autorizados |
| hml | HML | off | off | homologação controlada |
| prod | PROD | off | off | fail-closed; habilitação deliberada e autorizada |
| lab | LOCAL | emulador loopback | runtime lab | Moto/API sintética |

O profile lab só é seguro porque os clients são substituídos por endpoints loopback e fixtures sintéticos.

## Fonte única

Uma mesma configuração não deve existir como fontes independentes em YAML + constante Java + variável auxiliar. Regras fixas de protocolo/formato podem ser constantes; valores ajustáveis operacionalmente pertencem a properties.

Exemplos:
- Excel max rows → constante estrutural em ReportLimits;
- workers → property;
- JobMode/JobState → enum;
- physical table name → property;
- formula-protection on/off → property;
- formula prefix set → constante estrutural do report.

## VALIDAR NO AMBIENTE

Continuam específicos do ambiente corporativo: provider/SSO/Break Glass real, proxy/TLS/mirror, account/roles/resources autorizados, diretórios/política de criptografia, retenção/PII e limites sustentáveis de AWS/downstreams.

Nenhum default local substitui essas validações.
