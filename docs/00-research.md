# Pesquisa técnica e escolha da stack

Consulta em **2026-09-18**. Versões são o recorte resolvido para esta entrega, não promessa de suporte futuro ou ausência de CVEs. **FATO** indica documentação da plataforma; **DECISÃO**, escolha do projeto; **HIPÓTESE**, premissa a medir; **VALIDAR NO AMBIENTE**, informação corporativa indisponível externamente.

## Spring e Java

| Linha pesquisada | Compatibilidade documentada | Benefício | Custo e decisão |
| --- | --- | --- | --- |
| Boot 4.1.1 / Framework 7.0.9 | Java 17–26; Java 25 incluído | Base atual para projeto novo, Spring 7 e manutenção da linha recente | Jakarta EE 11/Servlet 6.1, Jackson 3, starters reorganizados: homologar integrações |
| Boot 3.5.16 / Framework 6.2.19 | Java 17–25 | Menor mudança para organização em Boot 3/Jackson 2 | Verificar janela real de suporte e contrato corporativo; não escolher só por parecer conservador |

**DECISÃO:** 4.1.1 porque não há aplicação antiga a migrar nem versão corporativa informada, as dependências efetivamente usadas resolvem e o código é validado em Java 25. Não inferir homologação a partir do build. Se a organização exigir 3.5, adaptar starters e Jackson, compilar e repetir os mesmos checks; não basta trocar o parent. [Boot atual](https://docs.spring.io/spring-boot/system-requirements.html), [Boot 3.5](https://docs.spring.io/spring-boot/3.5/system-requirements.html), [migração 4.0](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide).

Java 25 oferece records, sealed classes, pattern matching e Virtual Threads sem preview; ScopedValue também é estável. Structured Concurrency continua preview no 25 e fica excluída. O build exige release 25, sem `--enable-preview`. Usar distribuição/patch corporativos mantidos; o JDK usado para validar é registrado no checklist. [Oracle linguagem](https://docs.oracle.com/en/java/javase/25/language/java-language-changes-release.html), [ScopedValue](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/ScopedValue.html), [Structured Concurrency](https://docs.oracle.com/en/java/javase/25/core/structured-concurrency.html).

## SDK e transporte

AWS SDK for Java 2.x é adotado com **BOM 2.55.0**, versão observada no [metadata oficial publicado no Maven Central](https://repo.maven.apache.org/maven2/software/amazon/awssdk/bom/maven-metadata.xml). Não usar SDK v1 nem misturar versões individuais dos módulos. Releases frequentes não equivalem a obrigação de atualizar durante um incidente; verificar changelog, grafo e integração antes. [Repositório oficial](https://github.com/aws/aws-sdk-java-v2).

| Opção | Avaliação | Uso proposto |
| --- | --- | --- |
| AWS sync + Apache5 | API direta; pool, timeouts, TLS corporativo e troubleshooting conhecido | Base, transporte explicitamente selecionado |
| AWS async + Netty NIO | Futures/publisher e conexões não bloqueantes; demanda precisa ser limitada; event loop não recebe trabalho bloqueante | Experimento quando ganho justificar |
| AWS CRT sync/async | Bibliotecas nativas, throughput/transferências; validar plataformas, proxy/TLS e memória nativa | S3/ADVANCED mediante benchmark |
| SDK URLConnection | Menos dependências, menos opções de pool/HTTP; não é `java.net.http.HttpClient` | Não escolhido |
| JDK HttpClient | Pool reutilizado e HTTP moderno, integrado ao RestClient | HTTP corporativo; não supor ser transporte oficial drop-in do SDK |

Documentação geral e anúncios do SDK podem divergir durante troca do transporte default; por isso a configuração fixa Apache5 e não depende de autodetecção. O Apache5 tem parâmetros de socket, pool e conexão; read/write separados são opções específicas de transportes como Netty, não knobs universais. [Transportes](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/http-configuration.html), [Apache5](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/http-configuration-apache5.html), [timeouts](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/timeouts.html).

Enhanced Client é apropriado a tabelas conhecidas; Enhanced **Document** API existe no SDK2 e é alternativa dinâmica, diferente da antiga Document API v1. Map de `AttributeValue` é o caminho inicial de menor dependência para tabelas incidentais. Async Enhanced e paginators existem, mas não impõem coleta integral: iterar página a página. Cursor explícito simplifica checkpoint. [Enhanced Document](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/ddb-en-client-doc-api.html), [paginators](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/pagination.html), [DynamoDB completo](06-dynamodb.md).

Credenciais: ProfileCredentialsProvider explícito é padrão; DefaultCredentialsProvider é alternativa documentada cuja precedência pode selecionar env/system properties antes do profile. SSO/STS/credential_process precisam dos módulos e fluxo corretos. Toolkit/IntelliJ não garantem transmissão automática à JVM. A [análise de autenticação](04-aws-authentication.md) detalha precedência, refresh e limitações.

## HTTP, resiliência e serialização

| Tecnologia | Maturidade/manutenção e necessidade | Overhead, compatibilidade e alternativa |
| --- | --- | --- |
| HTTP Service Clients | Contratos declarativos oficiais Spring | Proxy pequeno; combina com RestClient; sem framework Cloud adicional |
| RestClient | Síncrono, suporte Spring | Modelo mais simples para VT e SDK sync; adotado |
| WebClient | Maduro, streaming reativo | Reactor/event loop e contexto elevam custo cognitivo; só para fluxo reativo necessário |
| Spring Cloud OpenFeign | Feature-complete; Spring sugere HTTP Service Clients | Exigiria Cloud BOM, Decoder/interceptors/políticas; não escolhido |
| Resilience4j | Módulos maduros de circuit breaker/bulkhead/rate/retry | Somente core necessário numa fase HTTP; não instalar starter Boot3 em Boot4 sem validação; SDK já cobre retry AWS |
| Jackson 3 | Linha usada pelo Boot4, mudança de packages/API | Usar BOM, não adicionar Jackson2 só por hábito; validar converters corporativos e desabilitar polimorfismo arbitrário |

[Spring REST clients](https://docs.spring.io/spring-framework/reference/integration/rest-clients.html), [OpenFeign](https://docs.spring.io/spring-cloud-openfeign/reference/), [Resilience4j](https://resilience4j.readme.io/docs/getting-started), [Jackson3](https://github.com/FasterXML/jackson/wiki/Jackson-Release-3.0). O skeleton não usa Feign; portanto não há ErrorDecoder desse framework. Existe classificação explícita de status HTTP no exemplo compilável.

## Persistência, relatórios, observabilidade e ferramentas

| Tema | Escolha/avaliação | Fonte e decisão detalhada |
| --- | --- | --- |
| Maven | 3.9.12 Wrapper only-script, SHA256 fixado; Java25 | [Release notes](https://maven.apache.org/docs/3.9.12/release-notes.html), [Wrapper](https://maven.apache.org/tools/wrapper/), [build](25-maven-dependencies.md) |
| SQLite/H2/JSON | JSON atômico basta para demo determinística; SQLite WAL/ledger para efeitos; H2 alternativa Java | [Comparação e limites de durabilidade](23-checkpoint-resume-idempotency.md) |
| POI SXSSF | Maduro; janela limitada mas temporários, estilos e certas estruturas usam memória/disco | [POI SXSSF](https://poi.apache.org/components/spreadsheet/how-to.html#sxssf), [relatórios](15-reporting-audit.md) |
| fastexcel | Alternativa streaming de menor superfície para planilhas simples; validar recursos/compatibilidade/manutenção | [Projeto oficial](https://github.com/dhatim/fastexcel); sem dependência inicial |
| Commons CSV | Parser/printer maduro e streaming; adequado quando schemas/formatos crescerem | [Projeto oficial](https://commons.apache.org/proper/commons-csv/); writer mínimo para duas colunas sintéticas nesta entrega |
| Micrometer/Actuator | Integrados ao Boot, métricas locais sem servidor externo obrigatório | [Micrometer](https://docs.micrometer.io/micrometer/reference/), [observabilidade](14-observability.md) |
| OpenTelemetry | Interoperabilidade de tracing/exportação; útil se destino corporativo aprovado | [Java](https://opentelemetry.io/docs/languages/java/); opcional, não exportar dados por padrão |
| JFR/JMC | JFR no JDK; JMC para análise, adequado a Windows | [Oracle JFR](https://docs.oracle.com/en/java/javase/25/jfapi/), [JMC](https://www.oracle.com/java/technologies/jdk-mission-control.html) |
| async-profiler | CPU/alloc/locks, validar plataforma e permissões | [Projeto oficial](https://github.com/async-profiler/async-profiler); ferramenta de lab, não dependência runtime |

Dependência adicional precisa de capacidade concreta, versão compatível, manutenção, licença, CVEs e custo de troubleshooting conhecidos. BOM é alinhamento de versões, não certificado de segurança. [Tabela final de dependências](25-maven-dependencies.md) registra obrigatoriedade e alternativas.
