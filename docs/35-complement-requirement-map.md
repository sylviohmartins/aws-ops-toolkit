# Rastreabilidade do prompt complementar

Legenda: **PASS** = implementado/documentado e verificável localmente; **DECISÃO** = requisito de avaliação atendido por decisão explícita de não criar/adicionar algo sem necessidade; **N/A** = exemplo conceitual não obrigatório como implementação separada.

| # | Tema | Status | Evidência principal |
| ---: | --- | --- | --- |
| 1 | princípio central/evolutividade | PASS | 33-reuse-extensibility + discovery de Workflow beans |
| 2 | não criar utils layer genérica | PASS | nenhum Utils/CommonUtils; seção A/O |
| 3 | ordem de preferência para reuso | PASS | seção A/B |
| 4 | inventário de utilitários | PASS | seção B |
| 5 | collections específicas | DECISÃO | JDK direto; sem wrapper trivial |
| 6 | collections grandes | PASS | Batching/BatchProcessor + teste incremental de 1.000.000 itens sem materialização global |
| 7 | batching first-class | PASS | batch package + testes |
| 8 | CSV reutilizável | PASS | ReportWriter/CsvReportWriter/CsvColumn |
| 9 | CSV performance/streaming | PASS | Iterator/Session + flush configurável |
| 10 | report projection | PASS | OperationReportRow + extension example |
| 11 | responsabilidade de modelos | PASS | seção H + ExtensionModelExampleTest |
| 12 | não reutilizar DTO para tudo | PASS | modelos separados por reason-to-change |
| 13 | mappers | PASS | estratégia explícita; MapStruct adiado por falta de benefício |
| 14 | adicionar novas tabelas | PASS | descriptor/gateway/config mapping + recipe |
| 15 | typed Dynamo access | PASS | DynamoTableGateway<T> |
| 16 | table descriptor | PASS | DynamoTableDescriptor<T> |
| 17 | schemaless mode | PASS | runtime Document/AttributeValue preservado |
| 18 | configuração: regra absoluta | PASS | 64 properties; SQLite/S3/SQS tuning externalizado; checker bloqueia regressões literais e estados de efeito stringificados |
| 19 | @ConfigurationProperties | PASS | records por capability + ConfigurationPropertiesScan |
| 20 | properties imutáveis/tipadas | PASS | Duration/DataSize/Path/URI/enums/cópias defensivas |
| 21 | validação de properties | PASS | Bean Validation + validação canônica + OperationalPropertiesValidationTest |
| 22 | nomenclatura de properties | PASS | toolkit.<capability>.* + kebab-case |
| 23 | organização application.yml | PASS | seções por capability |
| 24 | documentação no YAML | PASS | YAML enxuto; detalhes no catálogo |
| 25 | configuration catalog | PASS | 34-configuration-catalog.md |
| 26 | autocomplete properties | PASS | configuration processor + additional metadata |
| 27 | defaults seguros | PASS | AWS/runtime/writes false |
| 28 | magic numbers | PASS | tuning operacional externalizado; CoreLimits/DynamoLimits/ReportLimits para limites estruturais; checker dedicado |
| 29 | magic strings | PASS | JobState/JobMode/EffectState + constantes/JDK; estados de efeito não circulam mais como strings Java |
| 30 | constantes no escopo correto | PASS | ReportLimits/Masking/BatchOptions locais à capability |
| 31 | properties não são constantes | PASS | tuning em properties |
| 32 | decision table constant/property/enum/VO | PASS | seção G |
| 33 | dependency management | PASS | Boot parent + AWS BOM |
| 34 | dependencyManagement vs pluginManagement | PASS | POM + seção K |
| 35 | governança de dependências | PASS | seção K |
| 36 | organização do POM | PASS | famílias + tooling previsível |
| 37 | versões: fonte única | PASS | properties apenas para famílias não gerenciadas |
| 38 | baixo impacto de mudanças | PASS | matriz M |
| 39 | OCP pragmático | PASS | Workflow discovery; sem interfaces artificiais |
| 40 | stable core vs variable edge | PASS | seção A/N/P |
| 41 | registry/extensibilidade | PASS | ObjectProvider<Workflow> + teste de registration |
| 42 | factories somente quando necessárias | PASS | factory apenas Dynamo/report onde DI/config justificam |
| 43 | null policy | PASS | seção H/P + immutable empty collections |
| 44 | immutability | PASS | records/copyOf; sem cópia global em massa |
| 45 | result types | PASS | BatchResult/BatchProcessingResult/ReportResult |
| 46 | exception hierarchy | DECISÃO | não ampliar hierarquia sem handlers distintos; categorias atuais suficientes |
| 47 | common vs domain code | PASS | regras permanecem nos workflows |
| 48 | IO não é util | PASS | report/AWS/HTTP são componentes |
| 49 | static utility | PASS | Batching/Hashing/Masking são pure/stateless |
| 50 | performance de abstrações | PASS | buffers/futures bounded + teste de 1M em Batching + benchmarks de ledger 1M/5M/10M |
| 51 | evitar reflection genérica | PASS | sem universal reflection mapper |
| 52 | functions como extensão | PASS | CsvColumn Function<T,?> e callbacks de batch |
| 53 | generics com propósito | PASS | gateways/report/batch com generics simples |
| 54 | exemplo nova tabela | PASS | recipe adding-dynamodb-table + ExtensionModelExampleTest |
| 55 | exemplo nova operação | PASS | recipe adding-operation + Workflow discovery |
| 56 | exemplo nova coluna CSV | PASS | recipe adding-report |
| 57 | trocar nome físico da tabela | PASS | DynamoProperties mapping only |
| 58 | tuning concurrency | PASS | toolkit.operations.workers/hot tuning |
| 59 | docs de extensibilidade | PASS | docs/development/* |
| 60 | code conventions | PASS | seção L |
| 61 | nomenclatura de classes | PASS | seção L; sem HelperManager/CommonService |
| 62 | boolean parameters | PASS | options/records nos novos contratos |
| 63 | parameter object | PASS | BatchOptions e requests tipados |
| 64 | builder | DECISÃO | records/factories preferidos; builder só SDK/library |
| 65 | documentação de código | PASS | comentários apenas em invariantes/risco/performance |
| 66 | Javadoc | PASS | APIs reutilizáveis críticas documentadas sem burocracia |
| 67 | docs de properties | PASS | catálogo por capability |
| 68 | configuration example | PASS | examples/application-example.yml |
| 69 | environment variable mapping | PASS | seção E + catálogo |
| 70 | configuration precedence | PASS | seção E + catálogo |
| 71 | configuration snapshot | PASS | JobCoordinator CONFIG_SNAPSHOT sanitizado |
| 72 | não duplicar configuração | PASS | single source por properties; diferenças de page-size documentadas por capability |
| 73 | dependency analysis | PASS | maven-dependency-plugin analyze-only no profile static-analysis |
| 74 | qualidade quantitativa do arcabouço | PASS | seção N/M |
| 75 | change impact matrix | PASS | seção M |
| 76 | aceite utilitários | PASS | sem utils genérico/JDK wrappers; batch/report/security focados |
| 77 | aceite configuração | PASS | properties tipadas/validadas/metadata/defaults/sem secrets |
| 78 | aceite constantes | PASS | seção G + revisão de literals |
| 79 | aceite modelagem | PASS | separation-by-reason-to-change, sem dogma |
| 80 | aceite Maven | PASS | BOMs/pluginManagement/versões/dependency analysis |
| 81 | aceite evolutividade | PASS | recipes + workflow registry + table/report extension points |
| 82 | revisão contra overengineering | PASS | seção P; MapStruct/CollectionOperations/FileNameGenerator não criados |
| 83 | entregável A–P | PASS | 33-reuse-extensibility.md |
| 84 | entregáveis concretos | PASS | application-example, properties, batch, CSV, gateway/descriptor, mapper/projection examples, decision/change tables |
| 85 | princípio final | PASS | stable core/variable edge e recipes |

## Critérios de encerramento

O complemento só é considerado verde quando, além desta matriz:

1. Maven compila com Java 25;
2. testes unitários/regressivos passam;
3. profile lab mantém integração verde;
4. SpotBugs não encontra bug High;
5. dependency analysis é executado e findings classificados;
6. metadata de configuração é gerada;
7. check-docs e git diff --check passam;
8. não surgem secrets, Utils genérico ou recursos AWS físicos hardcoded fora de fixtures/lab.

Resultados executados devem ser registrados em 30-final-validation.md.
