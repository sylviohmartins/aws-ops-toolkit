# Integrações REST

**DECISÃO:** Spring HTTP Service Clients descrevem interfaces e RestClient executa em fluxo síncrono limitado. Não adicionar OpenFeign nem WebFlux ao caminho padrão. HTTP Interface não compete com RestClient: o primeiro descreve o contrato, o segundo é o cliente subjacente. [Spring REST clients](https://docs.spring.io/spring-framework/reference/integration/rest-clients.html).

## Exemplo compilável

[PaymentLookup](../src/main/java/io/github/awsopstoolkit/integration/PaymentLookup.java) usa `@GetExchange`, path variable validada e `X-Correlation-ID`. [HttpIntegrationClient](../src/main/java/io/github/awsopstoolkit/integration/HttpIntegrationClient.java) constrói proxy sobre RestClient/JDK HTTP, permite somente HTTPS/hosts aprovados, desabilita redirects, limita simultaneidade e inícios de chamada. Nenhum bean é criado automaticamente para essa integração.

Configuração do exemplo: conexão 3s, resposta 10s; número de chamadas simultâneas e taxa são argumentos obrigatórios do construtor. HttpClient é reutilizado e fechado junto ao adapter. `AwsCallGate` é reaproveitado como limitador de chamadas lógicas: apesar do nome, seu código não depende de AWS. Evolução pode renomeá-lo para `CallGate` quando virar porta compartilhada.

O pool interno do JDK reutiliza conexões, mas a aplicação controla requests em voo pelo gate; não alegar que o JDK builder expõe um `maxConnections` equivalente ao Apache. Se for requisito controlar pool total e por rota independentemente, trocar para Apache HttpComponents e verificar timeout de aquisição. Uma allowlist de host deve incluir política de porta, DNS/proxy e mTLS no alvo; HTTPS sozinho não valida destino corporativo. Base URI vem de configuração aprovada, nunca da API do job.

## Classificação e política alvo

| Resultado | Categoria | Ação |
| --- | --- | --- |
| 2xx com body válido | Confirmado | Verificar ID, schema, versão e regra de negócio |
| 204 onde contrato exige body / JSON inválido / ID inesperado | UNEXPECTED_RESPONSE | Parar etapa; sem aceitar sucesso incompleto |
| 3xx | UNEXPECTED_RESPONSE | Não seguir automaticamente para outro host |
| 400/404/422 | BUSINESS/VALIDATION | Sem retry; 404 pode ser ausência legítima só se o contrato disser |
| 401 | AUTHENTICATION | Pausar integração e renovar autenticação legitimamente |
| 403 | AUTHORIZATION | Não repetir nem buscar escalada; revisar autorização |
| 409/412 | CONFLICT | Relê/reconcilia versão; não retry cego |
| 429 | RATE_LIMITED | Pausar admissão desse downstream, respeitar Retry-After e budget |
| 500/502/503/504 | UNAVAILABLE | Retry apenas idempotente e dentro de deadline; circuit breaker para falha sustentada |
| timeout/rede após envio | NETWORK_OR_TIMEOUT / UNKNOWN para efeitos | Não concluir que servidor não processou |

O skeleton diferencia 401,403,429, erros de negócio, 5xx e respostas inesperadas. Timeout e rede compartilham categoria conservadora; classes de erro refinadas e parsing de `Retry-After` pertencem à implementação CORE. Não há retry automático no exemplo. Esta tabela é a política a implementar, não uma alegação de que o adapter já possui breaker ou orçamento de retries.

Body de erro futuro: ler somente limite de bytes acordado (ex.8KiB), aceitar campos `code`, `retryable` e `correlationId` por schema, mapear a categorias internas e descartar texto livre sensível. Content type ausente/HTML/proxy/corpo inválido não pode quebrar o classificador. Não devolver body completo, token, URL com query sensível ou stacktrace pela API local. `Retry-After` admite segundos ou data HTTP: normalizar, limitar por deadline e persistir pausa em vez de segurar milhões de tarefas. [RFC 9110](https://www.rfc-editor.org/rfc/rfc9110.html).

## Autenticação, headers e mutações

Tokens/mTLS/proxy vêm do mecanismo corporativo autorizado e ficam em memória; não há valor real em YAML. Interceptor futuro obtém token por provedor, adiciona correlação e remove headers sensíveis dos logs. Contexto transporta operationId e chave de idempotência, nunca o próprio segredo.

Uma operação POST/PATCH só permite retry se o servidor oferecer contrato durável de idempotência ou houver reconciliação capaz de comprovar efeito. Header `Idempotency-Key` não cria garantia sozinho. Chave estável deriva de job+item+step+versão de transformação e é registrada antes do envio. Timeout vira UNKNOWN; consultar resultado pelo ID/chave antes de repetir. GET pode receber tentativas limitadas se for realmente sem efeitos; PUT/DELETE também precisam considerar semântica de negócio e concorrência.

No alvo, a composição é: validação do destino → orçamento de admissão → breaker → retry permitido pelo contrato → gate por tentativa → chamada com timeout → classificação → ledger. Rate limiting conta tentativas efetivas, não apenas ações de negócio. O orçamento global impede repetição em HTTP+operação. [Resilience4j](https://resilience4j.readme.io/docs/getting-started), [resiliência](13-resilience.md).

## Decisão sobre bibliotecas

RestClient reduz custo de depuração com VT. WebClient agrega em streaming reativo ponta a ponta; bloquear seu event loop seria regressão. OpenFeign está feature-complete segundo Spring; só adotar se a padronização corporativa trouxer benefício concreto. Nesse caso, ADR deve especificar ErrorDecoder, body limitado, timeout, Retryer único, interceptors, correlação, idempotência, rede e erro inesperado antes de habilitar escrita. [OpenFeign oficial](https://docs.spring.io/spring-cloud-openfeign/reference/).

Validação CORE: stub local com 429/Retry-After, 500, delayed response, reset de conexão, JSON inválido, payload excessivo, redirect, 401/403 e POST cujo efeito ocorreu antes do timeout. Integração DEV/HML confirma proxy/TLS/auth e contrato de idempotência. Benchmark mede requests/s, p95, gates, heap e número real de tentativas.
