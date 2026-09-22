# Integrações REST

**DECISÃO:** o runtime executável usa o `java.net.http.HttpClient` do JDK para o caminho HTTP pequeno e controlado do toolkit, evitando uma dependência/stack paralela sem benefício concreto. Spring HTTP Service Clients/RestClient continuam alternativas válidas para uma integração futura mais declarativa, mas só devem ser introduzidos quando houver contrato real que justifique a abstração. Não adicionar OpenFeign nem WebFlux por padrão. [Spring REST clients](https://docs.spring.io/spring-framework/reference/integration/rest-clients.html).

## Implementação operacional

O caminho executável usa [PaymentGateway](../src/main/java/io/github/awsopstoolkit/runtime/PaymentGateway.java), construído a partir de `HttpProperties`. Há uma única fonte de verdade para endpoint/hosts, connect/response timeout, tentativas, `Retry-After` máximo e tamanho máximo de body. O adapter reutiliza `HttpClient`, não segue redirects, limita o body antes de materializá-lo e integra retries/throttling ao mesmo backpressure do runtime.

Não existe um cliente HTTP genérico paralelo nem um proxy sem consumidor: uma integração nova deve criar apenas seu adapter/DTO quando o contrato externo justificar e reutilizar a política comum documentada em [adicionar integração](development/adding-integration.md). Isso evita dois stacks HTTP com defaults divergentes.

O pool interno do JDK reutiliza conexões; a aplicação controla admissão por `DispatchLimiter` e pelos budgets do job. Não alegar que o JDK builder expõe um `maxConnections` equivalente ao Apache. Se o ambiente exigir controle de pool total/por rota, proxy ou mTLS especializado, validar em DEV/HML antes de trocar transporte. Base URI vem de configuração aprovada, nunca da API do job.

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
