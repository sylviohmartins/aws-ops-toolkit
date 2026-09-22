# Reuso, utilitários, modelagem e evolutividade

Este documento complementa a arquitetura principal sem substituir seus guardrails. Regra: **reutilizar primeiro, abstrair apenas em pontos reais de variação, externalizar o que muda e manter o core estável**.

## A. Reuse Strategy

Ordem antes de criar código comum: Java 25/JDK → AWS SDK → Spring → dependência já presente → biblioteca pequena consolidada → implementação própria.

Não existem classes genéricas Utils/CommonUtils/GeneralUtils/Helper/GlobalConstants. As abstrações atuais têm responsabilidade explícita:

| Capacidade | Implementação | Motivo |
| --- | --- | --- |
| batches incrementais | Batching | chunking bounded de Iterable |
| batches concorrentes | BatchProcessor + BatchOptions | batches in-flight limitados, erro parcial e resultado estruturado |
| CSV | ReportWriter, CsvReportWriter, CsvColumn | streaming, Commons CSV, flush e formula-injection protection |
| Dynamo tipado | DynamoTableDescriptor/Gateway/Factory | schema lógico separado do nome físico |
| masking/hash | Masking, Hashing | semântica de segurança reutilizada |
| operação | Workflow + discovery Spring | regra do incidente na borda |

Não foi criada CollectionOperations genérica: List.copyOf, Map.copyOf, streams, collectors e Objects já resolvem map/filter/group/index/merge. Abstração própria começa somente onde há chunking, bounded concurrency ou semântica operacional.

## B. Utility Inventory

| Capacidade | Frequência | JDK resolve? | Biblioteca | Própria? | Decisão |
| --- | --- | --- | --- | --- | --- |
| null/required/equality | alta | sim | Bean Validation | não | Objects/validation/records |
| filter/map/group/index | alta | sim | — | não | streams/collectors |
| immutable copy | alta | sim | — | não | List/Set/Map.copyOf |
| distinct simples | média | sim | — | não | distinct/Set |
| distinct por chave | baixa | parcial | — | não hoje | criar somente com caso real |
| first/last seguro | baixa | sim | — | não | iterator/list API |
| parsing/bounds | média | sim | Bean Validation | não | tipos + validação |
| elapsed/durations | alta | sim | Spring binding | não | Instant/Duration |
| path/size | alta | sim | Spring DataSize | não | Path/Files/DataSize |
| batching | alta | parcial | — | **sim** | Batching/BatchProcessor |
| CSV | alta | não | Commons CSV | **sim** | writer de domínio sobre biblioteca |
| XLSX | média | não | Apache POI SXSSF | infraestrutura existente | streaming/resumo |
| JSON | alta | não | Jackson do Boot | não | ObjectMapper gerenciado |
| NDJSON | baixa/média | não | Jackson do Boot | não hoje | usar streaming Jackson quando houver consumidor real; não criar writer sem caso de uso |
| hashing/masking | alta | parcial | — | **sim** | regra de segurança padronizada |
| filename genérico | baixa | sim | — | não | adiar |
| mapper universal/reflection | — | — | — | **proibido** | mapping explícito/compile-time |

**Null policy:** argumentos obrigatórios falham cedo com Bean Validation/Objects; coleções retornadas ou armazenadas preferem vazias/imutáveis em vez de `null`; `Optional` só representa ausência singular quando melhora a API e não é usado indiscriminadamente em fields/DTOs; relatório converte valor ausente para célula vazia segundo sua projeção. O mesmo conceito não alterna entre `null`, empty collection e `Optional<List<T>>`.

## C. Collection & Batch Processing

Para small collections, usar JDK diretamente. Para centenas de milhares/milhões, usar paginação/iterator/chunks e processamento incremental; nunca coletar dataset global nem criar futures ilimitados.

Batching.forEachBatch mantém apenas um buffer do tamanho do batch. BatchProcessor admite no máximo maxConcurrency batches simultâneos; usa virtual threads, mas a admissão ocorre antes de continuar consumindo a origem. O teste `processesOneMillionGeneratedItemsWithoutMaterializingTheDataset` percorre 1.000.000 de itens gerados sob demanda com batch máximo observado de 1.000, sem construir uma lista global.

BatchOptions.MAX_SUPPORTED_CONCURRENCY é limite estrutural da abstração, não recomendação AWS. Limites específicos de SQS/Dynamo permanecem nos adapters correspondentes.

## D. Report Infrastructure

Contrato: ReportWriter<T> → CsvReportWriter<T> → List<CsvColumn<T>>.

A operação informa projeção/colunas e destino; não reimplementa escaping, delimitador, header, buffering, flush ou null handling.

Decisões:
- Apache Commons CSV executa escaping/quoting;
- streaming por Iterator ou Session.write;
- flush-every-records configurável;
- ReportResult retorna contagem;
- formula-like cells são neutralizadas por default;
- ReportLimits concentra limites estruturais;
- OperationReportRow é projection, não entity/DTO externo;
- JSON reutiliza o ObjectMapper gerenciado;
- NDJSON foi avaliado e não ganhou abstração própria sem uso concreto; quando necessário, deve ser emitido incrementalmente com Jackson, sem materialização global.

Adicionar coluna altera somente projection/schema.

## E. Configuration Standards

Prefixo raiz: toolkit. Padrão: toolkit.<capability>.<attribute>, lowercase/kebab-case.

Capabilities atuais: toolkit.core, toolkit.aws, toolkit.dynamodb, toolkit.http, toolkit.journal, toolkit.operations, toolkit.report, toolkit.s3 e toolkit.sqs.

Todos usam @ConfigurationProperties imutáveis/records e ignoreUnknownFields=false. Collections são defensivamente copiadas quando expostas como contrato.

Tipos: Duration, DataSize, Path, URI, enum, Set, Map, int, long e boolean. Valores temporais/tamanho usam unidade explícita.

Precedência: command-line/system/environment sources do Spring quando aplicáveis > profile YAML > application.yml > @DefaultValue. Preferir YAML/profile para baseline e environment variables para sessão/segredos.

| Property | Environment variable |
| --- | --- |
| toolkit.aws.region | TOOLKIT_AWS_REGION |
| toolkit.aws.profile | TOOLKIT_AWS_PROFILE |
| toolkit.operations.workers | TOOLKIT_OPERATIONS_WORKERS |
| toolkit.operations.requests-per-second | TOOLKIT_OPERATIONS_REQUESTS_PER_SECOND |
| toolkit.report.flush-every-records | TOOLKIT_REPORT_FLUSH_EVERY_RECORDS |

O runtime registra snapshot sanitizado de workers, pageSize, requestsPerSecond, lifetimes, parâmetros adaptativos relevantes, environment e region em CONFIG_SNAPSHOT.

## F. Configuration Catalog

O catálogo completo está em [34-configuration-catalog.md](34-configuration-catalog.md).

Autocomplete usa spring-boot-configuration-processor e META-INF/additional-spring-configuration-metadata.json. Exemplo completo: [examples/application-example.yml](../examples/application-example.yml).

Defaults de escrita continuam fail-closed: toolkit.core.write-enabled=false, toolkit.operations.writes=false, toolkit.aws.enabled=false e toolkit.operations.enabled=false.

## G. Constants & Magic Values Policy

| Valor | Constant | Property | Enum | Value Object | Motivo |
| --- | --- | --- | --- | --- | --- |
| write-enabled | não | **sim** | não | não | ambiente/operação |
| workers/RPS/page size | não | **sim** | não | não | tuning |
| AIMD healthy streak / circuit threshold / open duration / read attempts / retry backoff | não | **sim** | não | não | tuning/resiliência |
| HTTP response body limit | não | **sim** | não | não | memória/contrato downstream |
| SQLite busy/API/report windows | não | **sim** | não | não | contenção/memória local |
| S3 stream buffer/multipart part | não | **sim** | não | não | memória/requests/throughput |
| SQS receive/lease/reacquire/poison tuning | não | **sim** | não | não | latência/consistência/retries |
| dry-run sample size | não | **sim** | não | não | diagnóstico/volume |
| shutdown timeout | não | **sim** | não | não | tuning |
| XLSX max data rows | **sim** | validado | não | não | limite do formato |
| formula prefixes | **sim** | não | não | não | regra estrutural |
| environment | não | config | **sim** | não | conjunto fechado |
| job/effect state/mode | não | não | **sim** | não | conjuntos fechados; `EffectState` elimina strings estruturais do ledger |
| physical table name | não | **sim** | não | não | varia por ambiente |
| stable mask prefix | **sim local** | não | não | não | política técnica |

Não criar constantes para 0/1, índices ou matemática óbvia.

## H. Model Separation Strategy

Persistence Model → Domain → API Response / Report Projection; Integration DTO entra pela borda.

Convenções:
- *DynamoItem: persistência recorrente;
- domain record/class: invariantes;
- *Request/*Response: API do toolkit;
- *ApiResponse: integração externa;
- *ReportRow: relatório;
- Value Object apenas quando adiciona invariantes/type safety.

ExtensionModelExampleTest prova PaymentDynamoItem, Payment, PaymentId, request/response, integration DTO e report row separados por razão de mudança.

## I. Mapping Strategy

Prioridade: constructor/factory explícito → MapStruct para mappings estruturados repetitivos → mapping manual especializado.

MapStruct **não foi adicionado** porque mappings atuais são pequenos. Se adotado: @MapperConfig central, unmappedTargetPolicy=ERROR e sem reflection.

Mapper universal/reflection-based é proibido em hot path.

## J. DynamoDB Table Extension Model

Typed access: DynamoTableDescriptor<T> + DynamoTableGatewayFactory + DynamoTableGateway<T>. Nome físico vem de DynamoProperties.tables.

Gateway tipado centraliza get/query/scan lazy. Mutations permanecem nos workflows/adapters guardados para não bypassar write authorization, ledger e conditions.

Schemaless mode continua disponível nos workflows Document/AttributeValue para investigação ad hoc.

Nova tabela típica: novo item + descriptor (+ mapper se necessário) + operação. Alteração existente: uma entrada toolkit.dynamodb.tables.<logical-name>. Zero mudanças em client AWS, controller ou coordinator.

## K. Maven Dependency Management Strategy

- Spring Boot parent gerencia Spring/Jackson;
- AWS SDK usa BOM oficial;
- versões não gerenciadas têm uma única property;
- pluginManagement centraliza Enforcer/Spotless/SpotBugs;
- maven-dependency-plugin:analyze-only roda em static-analysis;
- configuration processor é optional/build-time;
- Spring Cloud e MapStruct não foram adicionados sem necessidade.

Dependência nova exige responder: problema, alternativa JDK/Spring/AWS, BOM, manutenção, transitivas, impacto runtime e facilidade de remoção.

## L. Naming Conventions

Requests/responses/results/options usam sufixos específicos. AWS usa Service/Gateway/Descriptor; properties usam *Properties; writers *ReportWriter; projections *ReportRow; workflows nome do cenário + Workflow; enums representam conjuntos finitos.

Não coexistir Manager/Processor/Handler sem responsabilidades distinguíveis. BatchProcessor permanece porque é especificamente bounded batch execution.

## M. Change Impact Matrix

| Mudança | Componentes afetados | Core? | Config? | Novo código? |
| --- | --- | ---: | ---: | ---: |
| nova tabela tipada | item + descriptor (+ mapper) | não | 1 mapping | sim |
| novo índice | descriptor | não | normalmente não | pequeno |
| novo endpoint | adapter + properties se capability nova | não | sim | sim |
| nova queue | workflow/request/allowlist | não | sim | talvez |
| novo relatório | projection + schema | não | talvez | sim |
| nova operação | Workflow bean + modelos específicos | **não** | própria | sim |
| novo campo CSV | projection/schema | não | não | pequeno |
| nova regra | workflow da operação | não | talvez | pequeno |
| nova property | *Properties + YAML/catalog/metadata | não | sim | pequeno |
| novo ambiente | profile/config/runbook | não | sim | mínimo |
| renomear tabela física | nenhum Java | não | **sim** | não |
| workers 16→32 | nenhum Java | não | **sim** | não |

## N. Extension Recipes

- [Adicionar operação](development/adding-operation.md)
- [Adicionar tabela DynamoDB](development/adding-dynamodb-table.md)
- [Adicionar integração](development/adding-integration.md)
- [Adicionar relatório](development/adding-report.md)

| Extensão | Novos arquivos típicos | Arquivos Java existentes modificados |
| --- | ---: | ---: |
| nova operação | 1–4 | **0** no core |
| nova tabela tipada | 1–3 | **0** no core |
| novo relatório | 1–2 | **0** no core |
| nova integração | 1–3 | **0** no core; property class apenas se necessária |

RuntimeConfiguration aceita Workflow beans descobertos pelo Spring. JobCoordinator rejeita type duplicado.

## O. Anti-patterns

Proibidos/desaconselhados: Utils/CommonUtils/GlobalConstants; wrappers triviais do JDK; DTO único para todas as camadas; mapper universal reflection; collect global de massa; futures ilimitados; parallelStream sem controle; IO em utility estática; recurso AWS físico hardcoded; timeout/batch/concurrency operacional como static final; property duplicada; boolean soup; factory/builder sem variação real; dependência por conveniência futura; CSV escaping artesanal.

## P. Final Simplification Review

| Componente | Removível sem perda? | Decisão |
| --- | --- | --- |
| Batching | não para callbacks incrementais | manter |
| BatchProcessor | não para concorrência bounded/erro parcial | manter |
| BatchOptions | não; evita argumentos soltos | manter |
| ReportWriter | não; ponto real de formato | manter |
| CsvReportWriterFactory | parcialmente; centraliza DI/properties | manter |
| DynamoTableDescriptor | não; separa schema lógico/nome físico | manter |
| DynamoTableGatewayFactory | não; config + Enhanced Client | manter |
| MapStruct | sim hoje | **não adicionar** |
| CollectionOperations | sim | **não criar** |
| FileNameGenerator | sim | **não criar** |
| S3Properties | não; buffer e multipart são tuning real | **manter** |
| properties SNS vazias | sim hoje | **não criar** até existir estado próprio |

Conclusão: a menor arquitetura útil mantém abstrações onde há IO, bounded processing, segurança, configuração ou ponto real de extensão. Novas regras crescem na borda e reutilizam o núcleo.
