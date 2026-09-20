# Maven, versões e dependências

Pesquisa arquitetural original em 18/09/2026; POM/Wrapper e referências de tooling conferidos em **20/09/2026**. O [pom.xml](../pom.xml) é a configuração executável e prevalece sobre trechos resumidos. Este capítulo descreve escolha e verificações; resultados de build/test/smoke pertencem ao [registro factual](30-final-validation.md), sem aprovação presumida.

## Stack e gerenciamento de versões

| Componente | Seleção no repositório | Origem e consequência |
| --- | --- | --- |
| Java | `java.version=25`, `maven.compiler.release=25` | Linguagem/API/bytecode Java 25, sem preview |
| Spring Boot parent | `4.1.1` | Gerencia dependências Spring/Jackson e plugins herdados |
| AWS SDK BOM | `2.55.0` | Mantém módulos AWS alinhados |
| Maven | `3.9.12` no Wrapper | Versão previsível por checkout; Enforcer aceita `[3.9.12,4)` |
| Maven Wrapper | `3.3.4`, `only-script` | Scripts versionados, ZIP Maven com SHA-256 fixado |
| Artefato | `io.github.awsopstoolkit:aws-ops-toolkit:0.1.0-SNAPSHOT` | Versão de desenvolvimento do projeto, não snapshot de dependências externas |

Spring Framework e Jackson seguem o parent/BOM, sem repetir versões nas dependências. O código usa a linha Jackson 3 (`tools.jackson.*`), não exemplos 2.x importados sem adaptação. Não importar Spring Cloud BOM porque não há dependência Cloud/Feign. Se a organização exigir parent corporativo, importar `spring-boot-dependencies` e restaurar explicitamente o gerenciamento de plugins necessário; BOM gerencia versões de dependências, não herda toda a configuração do parent. [Maven: dependency management](https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html).

O POM importa `software.amazon.awssdk:bom` em `dependencyManagement` e declara somente módulos usados pelos exemplos, sem versão em cada módulo. Evitar `aws-java-sdk` v1 e pacote agregado de todos os serviços. [AWS: projeto Maven SDK 2.x](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/setup-project-maven.html).

## Tabela de dependências

“Obrigatória” descreve a base/feature indicada, não obrigação de instalar toda a tabela. Colunas de maturidade e overhead são avaliação arquitetural; compatibilidade final requer build e homologação no ambiente.

| Necessidade | Dependência | Motivo | Alternativa | Obrigatória? | Maturidade / overhead / compatibilidade |
| --- | --- | --- | --- | --- | --- |
| REST MVC | `spring-boot-starter-webmvc` | Controller, JSON, RestClient/HTTP interfaces | WebFlux, sem benefício comprovado nesta base | Sim, atual | Ecossistema Spring mantido; servidor/serialização são custo deliberado; linha Boot 4 |
| Input/config validation | `spring-boot-starter-validation` | Jakarta Bean Validation | Validadores manuais onde regra é contextual | Sim, atual | Evita validação repetida; constraints não substituem regra de negócio |
| Autenticação local | `spring-boot-starter-security` | Filter chain e contexto de autenticação | Filtro isolado, com mais responsabilidade própria | Sim, atual | Componente consolidado; não cria política AWS automaticamente |
| Métricas/health | `spring-boot-starter-actuator` | Micrometer e diagnóstico local | Contadores JDK manuais | Sim, atual | Custo moderado controlado por meters/tags/endpoints |
| DynamoDB | `software.amazon.awssdk:dynamodb` | Low-level S/N/B, Query/Scan/update | Enhanced Client em modo tipado | Sim, exemplos AWS atuais | SDK oficial; retry/timeouts explícitos e BOM |
| Identidade e profiles SSO | `sts`, `sso`, `ssooidc` do AWS SDK | STS e suporte da cadeia de profile | Provider corporativo aprovado | Sim, exemplos atuais | SDK oficial; sem criar mecanismo próprio de credenciais |
| Mensageria | `sqs`, `sns` do AWS SDK | Adapters e operações dos serviços | Remover de distribuição estritamente Dynamo-only | Sim, exemplos atuais | Reutilizáveis; sem runtime remoto se AWS está desabilitada |
| Função/artefatos | `lambda`, `s3` do AWS SDK | Adapters de invocação e streaming | Feature opcional numa distribuição mínima | Sim, exemplos atuais | Sem promessa de semântica de negócio pelo SDK |
| Transporte AWS sync | `software.amazon.awssdk:apache5-client` | Pool e timeouts explícitos | Transporte compatível menor; async Netty/CRT quando justificado | Sim, atual | Dependência explícita para configurar builder; pool deve ser limitado |
| JSON/config | Jackson via starter/Boot BOM | Inputs, snapshots e serialização | API low-level quando necessário | Sim, transitiva atual | Não fixar manualmente patch gerenciado; medir alocações de massa |
| Logging/métricas | SLF4J/Logback/Micrometer via starters | Diagnóstico e contadores | Infraestrutura manual não justificada | Sim, transitivas atuais | Cuidado com cardinalidade e payload; nenhuma exportação externa obrigatória |
| Testes focados | `spring-boot-starter-test`, scope `test` | Safety, checkpoint e CSV | JDK/manual para casos simples | Sim, só build | Não entra como runtime produtivo; evitar suíte artificial extensa |
| DynamoDB tipado/document API | `dynamodb-enhanced` | Schemas conhecidos/Enhanced Document | Low-level atual | Não; CORE | SDK oficial e BOM; adiciona mapping/reflection/converters conforme estratégia |
| Ledger de escrita | Driver JDBC SQLite homologado | Transações locais, índice de etapas/pendências | JSON apenas demo/leitura; H2 como alternativa avaliada | Não atual; obrigatório para desenho de escrita escolhido | SQLite consolidado; driver/binário nativo, WAL, lock e durabilidade exigem validação |
| CSV de vários dialectos | Commons CSV | Escaping/schema de exportação generalizada | Writer JDK fixo atual; Jackson CSV | Não atual | API específica; revalidar release/transitivas; pequeno custo contra menos código próprio |
| XLSX resumido | `poi-ooxml`, SXSSF | Streaming por janela | fastexcel; preferir CSV para massa | Não; CORE opcional | Ecossistema amplo; temporários/transitivas e segurança precisam avaliação |
| Resiliência HTTP avançada | Módulos seletivos Resilience4j | Circuit breaker/rate/bulkhead se necessários | JDK + retry SDK + error budget | Não atual | Evitar starter/todos módulos por hábito; compatibilidade Boot 4/JDK 25 validada antes |
| Telemetria distribuída | Micrometer Tracing/OTel ou agent escolhido | Correlação com serviços quando autorizada | Meters/logs locais atuais | Não atual | Escolher uma instrumentação; custo de spans/exportação e BOMs |
| Métricas adicionais VT | `micrometer-java21` compatível | Instrumentação específica de VT | JFR e métricas JVM base | Não atual | Nome do módulo não limita o JDK a 21; validar BOM/JDK 25 |
| Transferência grande S3 | `s3-transfer-manager` e transporte escolhido | Coordenação de upload/download | Streaming S3 sync atual | Não; ADVANCED | SDK oficial, custo extra de threads/buffers/nativos conforme transporte |
| API interativa | Springdoc/OpenAPI compatível | Exploração de contratos após autenticação | Exemplos HTTP e docs atuais | Não atual | Validar linha Boot 4; não expor Swagger ou schema sensível sem proteção |
| Microbenchmark | JMH em módulo/perfil separado | Medir hot path identificado | Lab end-to-end | Não atual | Ferramenta especializada; sem dependência runtime |

Fontes/avaliação por capability: [AWS adapters](06-dynamodb.md), [HTTP](11-http-integrations.md), [relatórios e bibliotecas](15-reporting-audit.md), [observabilidade/OTel](14-observability.md), [ledger](23-checkpoint-resume-idempotency.md), [JVM/JMH](24-java-jvm.md). A tabela deliberadamente não adiciona bibliotecas sem necessidade ao POM.

## Plugins efetivamente configurados

| Plugin | Versão/configuração | Execução e alcance |
| --- | --- | --- |
| `spring-boot-maven-plugin` | Gerenciado pelo parent | Repackage para JAR executável e execução local |
| `maven-compiler-plugin` | Gerenciado pelo parent; parameters/warnings habilitados | Compilação com `release=25`; não exige flag preview |
| `maven-enforcer-plugin` | `3.6.2` | RequireJavaVersion `[25,26)` e RequireMavenVersion `[3.9.12,4)` |
| `spotless-maven-plugin` | `3.3.0` | `check` em validate; formatação Java |
| `google-java-format` dentro de Spotless | `1.33.0`, estilo AOSP, remove unused imports | Formatação determinística; não é análise semântica |
| `spotbugs-maven-plugin` | `4.10.4.1`, perfil `static-analysis` | `check` em verify, effort Max, threshold High, failOnError true |

`--release` restringe linguagem, bytecode e API alvo, diferentemente de apenas `source/target`. Enforcer impede runtime de build errado; não instala JDK. [Compiler release](https://maven.apache.org/plugins/maven-compiler-plugin/examples/set-compiler-release.html), [Enforcer Java](https://maven.apache.org/enforcer/enforcer-rules/requireJavaVersion.html).

Spotless `check` aponta diferenças e `apply` modifica arquivos; revisão deve ver o diff antes de commit. A combinação plugin/formatter é versionada fora dos BOMs quando necessário. [Spotless Maven](https://github.com/diffplug/spotless/blob/main/plugin-maven/README.md).

SpotBugs analisa bytecode para padrões de bugs; o perfil High não significa “todos os achados tratados” e não substitui segurança, integração ou revisão concorrente. `effort=Max` aumenta esforço de análise, não cobertura garantida. Sem `-Pstatic-analysis`, o build padrão não executa esse perfil. Sua compatibilidade com classfiles Java 25 e resultado real precisam constar do log de execução; falha de ferramenta não é aprovação limpa. [SpotBugs check](https://spotbugs.github.io/spotbugs-maven-plugin/check-mojo.html), [parâmetros de análise](https://spotbugs.github.io/spotbugs-maven-plugin/spotbugs-mojo.html).

## Comandos de desenvolvimento e evidência

```text
./mvnw.cmd -version
./mvnw.cmd verify
./mvnw.cmd -Pstatic-analysis verify
./mvnw.cmd spotless:apply
./mvnw.cmd dependency:tree
./mvnw.cmd help:effective-pom -Doutput=target/effective-pom.xml
```

Em shell Unix usar `./mvnw`; em PowerShell usar `./mvnw.cmd`. `spotless:apply` é ação de edição separada, não requisito a executar após todo verify. IntelliJ deve usar Project SDK e Maven Runner Java 25 e Wrapper. Não usar `-DskipTests` ou desabilitar checks para transformar um build quebrado em evidência aprovada.

O [Wrapper](../.mvn/wrapper/maven-wrapper.properties) aponta Maven 3.9.12 e SHA-256 do ZIP; modo `only-script` dispensa JAR do wrapper versionado e baixa a distribuição conforme scripts. Mirror/proxy podem ser configurados via política corporativa; preservar integridade e não embutir senha no repositório. [Maven Wrapper oficial](https://maven.apache.org/tools/wrapper/).

## Avaliações opcionais e segurança de supply chain

**Dependency analysis:** `dependency:analyze` pode ajudar a detectar uso não declarado/declaração sem uso, mas análise por bytecode possui limitações em auto-configuração, reflection, ServiceLoader e providers AWS. Rever achados; não remover SSO/SDK/starter automaticamente porque não aparece chamada Java direta. O goal standalone inclui test-compile; `analyze-only` é opção para ligar ao lifecycle depois de calibrar. Não está configurado como gate no POM atual. [Maven Dependency Plugin](https://maven.apache.org/plugins/maven-dependency-plugin/analyze-mojo.html).

**OWASP Dependency-Check:** avaliado como ferramenta opcional e **não executado nesta entrega documental**. Antes de incluir profile, validar versão/plugin/JDK, fontes de vulnerabilidade, NVD API key quando pertinente, cache/mirror/proxy e tempo de atualização. Credencial de API fica fora do POM/log; triagem precisa distinguir vulnerabilidade aplicável, falso positivo e dependência inacessível. Não suprimir tudo para obter verde; supressões específicas exigem justificativa, responsável e prazo. Pode complementar ferramenta corporativa existente, sem duplicar scans por hábito. [Dependency-Check Maven oficial](https://jeremylong.github.io/DependencyCheck/dependency-check-maven/).

**DECISÃO de release:** fixar versões, revisar transitivas e licença, usar artefatos de origem/mirror aprovados, manter atualização periódica e registrar exceções. SBOM/assinatura/repositório de artefatos entram conforme política corporativa; não afirmar que o POM atual já gera SBOM ou assina release. Builds determinísticos no sentido de versões fixadas não garantem, por si, JAR byte a byte reproduzível; timestamps, ambiente e plugins precisam de configuração/ensaio específicos.

A escolha Boot 4.1.1 não invalida uma linha corporativamente homologada: se prevalecer Boot 3.5, revisar starters, Jackson, APIs, plugins e todo o build; não trocar apenas o número do parent. Mudança de stack exige ADR e validação real. [ADR Spring Boot](22-adrs/002-spring-boot.md).
