# aws-ops-toolkit

Toolkit Java para planejar, aprovar e executar operações locais de engenharia em AWS com limites, evidência durável e recuperação. A arquitetura continua orientada a uma regra pequena por incidente: `selecionar → validar → transformar → executar → verificar`.

O código atual contém dois níveis executáveis. A foundation mantém a demonstração sintética LOCAL/DRY_RUN em `/api/v1/operations`. O [runtime operacional](docs/32-operational-runtime.md) acrescenta `/api/v1/jobs`, ledger SQLite, plano selado por hash, canary/promoção, pausa/cancelamento, reconciliação de efeitos incertos, relatórios CSV/XLSX e workflows concretos de DynamoDB, SQS, SNS, Lambda, S3 e HTTP. O laboratório Docker usa Moto e dados sintéticos em loopback; essa integração local não constitui homologação para AWS real ou produção.

## Baseline

| Componente | Versão/decisão |
|---|---|
| Java | 25, sem preview |
| Spring Boot | 4.1.1; Framework/Jackson gerenciados pelo BOM |
| AWS SDK for Java | BOM 2.55.0, clients síncronos e transporte Apache 5 explícito |
| Maven | Wrapper 3.9.12 |
| Concorrência | MVC + virtual threads, admissão e limites explícitos |
| HTTP externo | HTTP Service Interface + RestClient/JDK HTTP |
| Persistência | Foundation em JSON/chunks CSV; runtime operacional em SQLite WAL/FULL |

Versões são o recorte documentado da pesquisa de **2026-09-18**, não atualizações automáticas. Uma baseline corporativa homologada prevalece. A [pesquisa](docs/00-research.md) compara alternativas e traz fontes oficiais; o [blueprint](docs/26-implementation-blueprint.md) separa implementado, exemplo e planejado.

## Executar a foundation sintética

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

Evidências executadas, contagens e limites ficam no [checklist final](docs/30-final-validation.md). O smoke usa somente dados sintéticos; ele não autoriza operação em AWS real.

## Executar o laboratório Docker

Com Docker Compose e JDK 25 em `JAVA_HOME`, o script inicia Moto e a API HTTP sintética, aguarda saúde e provisiona os fixtures:

```powershell
.\scripts\lab.ps1 -Action up
```

Gere um bearer token como no exemplo anterior e inicie a aplicação com o profile `lab`:

```powershell
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=lab'
```

Em outra sessão que tenha o mesmo `TOOLKIT_LOCAL_TOKEN`, use `/api/v1/jobs`. O guia do [runtime operacional](docs/32-operational-runtime.md) documenta request, aprovação, promoção do canary, reconciliação, operações e relatórios. Para executar os checks automatizados do laboratório ou encerrar os containers:

```powershell
.\scripts\lab.ps1 -Action test
.\scripts\lab.ps1 -Action down
```

Sem o script, os equivalentes principais são `docker compose up -d --wait`, `docker compose exec -T aws python /lab/bootstrap.py` e `docker compose down`. Credenciais `testing` pertencem exclusivamente ao emulador; nunca substitua o endpoint de loopback por uma URL AWS mantendo essas credenciais ou o profile `lab`.

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

Complementos: [runtime operacional e laboratório Docker](docs/32-operational-runtime.md), [requisitos e critérios de aceite](docs/01-requirements.md), [rastreabilidade do prompt](docs/31-requirement-map.md) e [guia de contribuição](CONTRIBUTING.md). O complemento do runtime não altera a ordem das 32 partes exigidas pelo prompt.

## Evolução e colaboração

Criar branches curtas como `feat/architecture-foundation`, `fix/checkpoint-order` ou `docs/credential-refresh`, commits pequenos em formato Conventional Commits e revisão antes da integração. Exemplos: `docs: document checkpoint recovery` e `feat: add bounded inventory operation`. Não commitar outputs operacionais, `.aws`, tokens, reports, dumps ou configurações locais. Política da organização prevalece sobre nomes ilustrativos; este projeto não inventa workflow interno de aprovação.

Uma operação fora do laboratório precisa passar por integração DEV/HML, preflight de identidade/recurso, limites e recuperação apropriados. Ledger, plano aprovado e canary já existem no runtime, mas precisam ser validados com o serviço e o filesystem autorizados; reconciliadores e política de cada efeito também precisam de aceite. O mecanismo corporativo de Break Glass, permissões, homologação e retenção permanece marcado como **VALIDAR NO AMBIENTE**.
