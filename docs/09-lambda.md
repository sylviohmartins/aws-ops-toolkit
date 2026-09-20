# Lambda: distinguir aceitação, execução e resultado de negócio

Projeto: **aws-ops-toolkit**. Pesquisa oficial consultada em **18/09/2026**.

## 1. Modos e resultado

`LambdaService` entrega `Invoke` com tipo explícito, gate de concorrência/taxa e autorização sobre a função/qualifier. A resposta é classificada em `ACCEPTED`, `EXECUTED`, `FUNCTION_ERROR` ou `PERMISSION_CHECKED`. A última etapa de validação do payload depende do contrato da função e é **arquitetura alvo** por operação.

| InvocationType | Status esperado | Significado operacional |
|---|---|---|
| `RequestResponse` | 200 | A resposta inclui execução; verificar `FunctionError` e payload |
| `Event` | 202 | Evento aceito para execução assíncrona; resultado ainda desconhecido |
| `DryRun` | 204 | Parâmetros/permissão de invocação validados; regra de negócio não executada |

O status SDK/HTTP não representa erro lógico da função. `FunctionError` sinaliza erro de execução e o payload contém detalhes; a aplicação ainda pode retornar payload de negócio com falha sem preencher esse indicador. A documentação atual estabelece entrada de até **6 MB síncrona e 1 MB assíncrona**. Conferir bytes e quotas atuais antes de construir o request. [Invoke](https://docs.aws.amazon.com/lambda/latest/api/API_Invoke.html)

Por isso `EXECUTED` no código não se chama `SUCCEEDED`. Uma resposta `{ "status": "rejected" }` pode representar falha de negócio apesar de HTTP 200. Também não serializar `InvokeResponse` integralmente em logs: payload e log tail podem conter dados restritos.

## 2. Planejamento e destino

Preferir ARN completo e versão publicada quando a intervenção exigir comportamento reproduzível. Se usar alias, registrar resolução/versão executada e avaliar roteamento ponderado; um alias pode mudar entre planejamento e execução. A allowlist deve abranger o destino efetivo e qualifier, não apenas um nome curto. Não concatenar qualifier duplicado a ARN já qualificado: resolver e validar a representação antes do adapter.

O guardrail verifica a identidade efetiva, região, recurso, operação, incidente e modo. Invocar uma função pode produzir escrita mesmo quando seu nome sugere consulta. Não classificar automaticamente Lambda como leitura; `DryRun` da API também passa pela política de autorização do exemplo.

O dry-run do toolkit valida seleção, schema, amostra e plano. O `InvocationType.DRY_RUN` da AWS é somente um teste de parâmetros/permissão, quando explicitamente autorizado; não simula o resultado da função nem suas dependências.

Exemplo com a classe entregue:

```java
var request = InvokeRequest.builder()
        .functionName(approvedFunctionArn)
        .qualifier(approvedVersion)
        .invocationType(InvocationType.REQUEST_RESPONSE)
        .payload(SdkBytes.fromUtf8String(validatedPayload))
        .build();
var result = lambdaService.invoke(request);
switch (result.outcome()) {
    case FUNCTION_ERROR -> recordFunctionFailure(result.response());
    case EXECUTED -> validateBusinessPayload(result.response().payload());
    default -> throw new IllegalStateException("Unexpected mode for synchronous operation");
}
```

`recordFunctionFailure` e `validateBusinessPayload` são hooks da operação, ilustrativos; não fazem parte do adapter. Validar JSON/schema e limites antes do envio. O wrapper não incorpora uma biblioteca universal de contratos de todas as funções.

## 3. Timeouts, concorrência e resultado desconhecido

O timeout do client deve caber no orçamento da operação e, para chamadas síncronas autorizadas longas, permitir o tempo esperado da função. O perfil padrão de timeout curto do toolkit não é adequado automaticamente a uma função de vários minutos: fornecer client/per-request override explícito e manter cancelamento/deadline.

Um timeout local ou cancelamento não interrompe necessariamente a função que já foi invocada. Registrar `INVOCATION_UNKNOWN`; parar novos dispatches e reconciliar por chave de negócio/operation ID, logs ou resultado durável autorizado. Não afirmar que a função foi cancelada só porque a future local foi interrompida.

O semáforo limita invocações em voo. O orçamento considera concorrência reservada da função, limite da conta, tráfego normal e capacidade dos downstreams; CPU local não define esse limite. `TooManyRequestsException` é classificado como throttling e reduz a taxa; distinguir limite da conta/função por código retornado, sem registrar payload sensível.

## 4. Retry síncrono versus assíncrono

Para invocação direta síncrona, a decisão de repetir fica com o chamador e precisa considerar o tipo de falha e a idempotência da função. Não envolver o SDK numa segunda camada genérica de retry. Função que executa parte do trabalho e lança erro pode duplicar efeitos na próxima invocação. [Comportamento de retries Lambda](https://docs.aws.amazon.com/lambda/latest/dg/invocation-retries.html)

Na invocação assíncrona, o serviço pode executar novamente eventos que falharam e também entregar duplicatas. A configuração de event age, tentativas e destinos/DLQ altera o comportamento; não assumir que reenviar pelo toolkit é necessário só porque ainda não existe confirmação. Erros de função e throttling/sistema têm políticas de retentativa diferentes. [Erros e retries assíncronos](https://docs.aws.amazon.com/lambda/latest/dg/invocation-async-error-handling.html)

O client de referência usa uma tentativa. Uma operação que precise retry deve transmitir identificador estável e contratar idempotência com a função, incluindo resultado já produzido e retomada após sessão expirada. O journal separa `PREPARED`, `INVOKE_UNKNOWN`, `ACCEPTED`, `FUNCTION_FAILED`, `BUSINESS_FAILED` e `BUSINESS_CONFIRMED`.

## 5. Evidências e evolução

Registrar operation ID, request ID AWS, destino/qualifier, versão executada quando retornada, tipo, status, latência, código de erro e estado de negócio. Payload bruto e log tail ficam fora dos logs padrão. Para `Event`, reconciliar posteriormente por destino de resultado ou repositório corporativo autorizado; 202 não permite fechar a operação como sucesso de negócio.

Antes de homologar: testar 200 com `FunctionError`, 200 com erro somente no payload, 202 sem confirmação posterior, timeout após efeito remoto, alias alterado, throttling, autenticação expirada e cancelamento local. O esqueleto classifica a resposta; não implementa o consumidor dos destinos de resultado nem promete processamento exactly-once.
