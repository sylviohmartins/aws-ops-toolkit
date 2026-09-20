# aws-ops-toolkit

Arquitetura pesquisada, plano de implementação e código-base Java para operações locais de engenharia em AWS. A proposta é preparar autenticação, clients, limites, relatórios e recuperação antes de uma war room, deixando a nova operação concentrada na regra do incidente.

**O código executável atual oferece uma demonstração sintética em LOCAL/DRY_RUN.** Ela tem REST assíncrono, CSV paginado, checkpoint, pausa, cancelamento e retomada. Os adapters AWS/HTTP são exemplos compiláveis e não estão ligados a uma operação produtiva. Escrita AWS permanece indisponível; configurar uma credencial poderosa ou mudar uma flag não implementa os controles pendentes.

## Baseline

| Componente | Versão/decisão |
|---|---|
| Java | 25, sem preview |
| Spring Boot | 4.1.1; Framework/Jackson gerenciados pelo BOM |
| AWS SDK for Java | BOM 2.55.0, clients síncronos e transporte Apache 5 explícito |
| Maven | Wrapper 3.9.12 |
| Concorrência | MVC + virtual threads, admissão e limites explícitos |
| HTTP externo | HTTP Service Interface + RestClient/JDK HTTP |
| Persistência atual | JSON e chunks CSV locais; sem ledger produtivo de escrita |

Versões são o recorte documentado da pesquisa de **2026-09-18**, não atualizações automáticas. Uma baseline corporativa homologada prevalece. A [pesquisa](docs/00-research.md) compara alternativas e traz fontes oficiais; o [blueprint](docs/26-implementation-blueprint.md) separa implementado, exemplo e planejado.

## Executar a demonstração

No PowerShell, dentro do projeto, com JDK 25 configurado em `JAVA_HOME`:

```powershell
java -version
.\mvnw.cmd -version
.\mvnw.cmd clean verify
```

O primeiro uso do Wrapper precisa resolver Maven e dependências pelo repositório/mirror permitido no ambiente. O projeto exige um bearer token local com 32 a 256 caracteres e não fornece um token default. Para gerar um token aleatório por sessão, sem imprimi-lo, e iniciar em foreground:

```powershell
$toolkitTokenBytes = New-Object byte[] 32
$toolkitRandom = [Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $toolkitRandom.GetBytes($toolkitTokenBytes)
} finally {
    $toolkitRandom.Dispose()
}
$env:TOOLKIT_LOCAL_TOKEN = [Convert]::ToBase64String($toolkitTokenBytes)
[Array]::Clear($toolkitTokenBytes, 0, $toolkitTokenBytes.Length)
.\mvnw.cmd spring-boot:run
```

A aplicação usa `127.0.0.1:8080`; endpoints de operações e Actuator exigem o mesmo token. Não gravar o token no Git, YAML, URL ou log. Depois de encerrar a aplicação, remover a variável da sessão com `Remove-Item Env:TOOLKIT_LOCAL_TOKEN`. O guia de [API](docs/27-api.md) oferece uma execução em background que permite chamar a API na mesma sessão sem copiar/imprimir o token.

Para exercitar a demonstração com token e diretório isolados, depois do build:

```powershell
.\scripts\smoke.ps1
```

Validação registrada em **2026-09-20**: build com 14 testes aprovado, análise SpotBugs sem findings High e smoke local incluindo interrupção e retomada aprovado. Evidências e limites ficam no [checklist final](docs/30-final-validation.md). O smoke usa somente dados sintéticos; integração AWS/HTTP DEV/HML e operação produtiva não foram executadas.

## Leitura na ordem das 32 partes solicitadas

1. **Executive Summary:** [visão geral](docs/00-overview.md).
2. **Pesquisa técnica:** [tecnologias, versões, alternativas e fontes](docs/00-research.md).
3. **Arquitetura:** [componentes, relações e diagramas](docs/02-architecture.md).
4. **Security & Production Guardrails:** [barreiras e preflight](docs/03-security-production-guardrails.md).
5. **Authentication & Break Glass lifecycle:** [profiles, Toolkit, STS e expiração](docs/04-aws-authentication.md).
6. **Operation Engine:** [contratos, pipeline e lifecycle](docs/05-operation-engine.md).
7. **DynamoDB:** [acesso, paginação, scan paralelo, condições e transações](docs/06-dynamodb.md).
8. **SQS:** [inspeção, DLQ, replay e processamento](docs/07-sqs.md).
9. **SNS:** [publicação, batches e duplicidade](docs/08-sns.md).
10. **Lambda:** [invocação e interpretação de resultados](docs/09-lambda.md).
11. **S3:** [streaming, transferência e evidências](docs/10-s3.md).
12. **REST Integrations:** [HTTP Service Clients e políticas de erro](docs/11-http-integrations.md).
13. **Concurrency & Performance:** [limites, memória e processamento](docs/12-concurrency-performance.md).
14. **Retry, Resilience & Backpressure:** [responsabilidade de retry e budgets](docs/13-resilience.md).
15. **Checkpoint, Resume & Idempotency:** [fronteiras duráveis e reconciliação](docs/23-checkpoint-resume-idempotency.md).
16. **Reporting & Audit:** [CSV, XLSX, privacidade e disco](docs/15-reporting-audit.md).
17. **Observability:** [métricas, logs e diagnóstico](docs/14-observability.md).
18. **Java 25/JVM:** [recursos, GC e profiling](docs/24-java-jvm.md).
19. **Maven & Dependencies:** [build e dependências](docs/25-maven-dependencies.md), [pom.xml](pom.xml).
20. **Configuration:** [properties e exemplos YAML](docs/16-configuration.md).
21. **Package Structure:** [árvore real e evolução dos módulos](docs/26-implementation-blueprint.md#pacotes-e-arquivos-reais).
22. **Class Responsibility Matrix:** [matriz de responsabilidades](docs/26-implementation-blueprint.md#matriz-de-responsabilidades-do-código-atual).
23. **APIs:** [contrato efetivamente implementado](docs/27-api.md) e [API alvo](docs/05-operation-engine.md#api-alvo).
24. **Código-base:** [blueprint e exemplo de extensão](docs/26-implementation-blueprint.md), [fontes Java](src/main/java/io/github/awsopstoolkit).
25. **Complex War-Room Scenario:** [correção fictícia de pagamentos](docs/18-complex-scenario.md).
26. **Benchmark Plan:** [laboratório, métricas e saturação](docs/19-benchmark-plan.md).
27. **Runbook:** [preparação, execução e encerramento](docs/17-war-room-runbook.md).
28. **ADRs:** [decisões arquiteturais](docs/22-adrs).
29. **Risks & Anti-patterns:** [riscos, restrições e práticas proibidas](docs/21-risks.md).
30. **Implementation Roadmap:** [fases e critérios de pronto](docs/20-implementation-roadmap.md).
31. **MVP recomendado:** [MVP, CORE e ADVANCED](docs/20-implementation-roadmap.md#mvp-core-e-advanced).
32. **Checklist final:** [revisão crítica A–K e validações](docs/30-final-validation.md).

Complementos: [requisitos e critérios de aceite](docs/01-requirements.md), [rastreabilidade do prompt](docs/31-requirement-map.md) e [guia de contribuição](CONTRIBUTING.md).

## Evolução e colaboração

Criar branches curtas como `feat/architecture-foundation`, `fix/checkpoint-order` ou `docs/credential-refresh`, commits pequenos em formato Conventional Commits e revisão antes da integração. Exemplos: `docs: document checkpoint recovery` e `feat: add bounded inventory operation`. Não commitar outputs operacionais, `.aws`, tokens, reports, dumps ou configurações locais. Política da organização prevalece sobre nomes ilustrativos; este projeto não inventa workflow interno de aprovação.

Uma operação real precisa passar por integração DEV/HML, preflight de identidade/recurso, limites e recuperação apropriados. A arquitetura de escrita exige ledger transacional, plano aprovado, canary e reconciliação antes de liberação. O mecanismo corporativo de Break Glass, permissões, homologação e retenção permanece marcado como **VALIDAR NO AMBIENTE**.
