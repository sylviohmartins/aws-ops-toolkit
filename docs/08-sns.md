# SNS: publicação rastreável e deduplicação

Projeto: **aws-ops-toolkit**. Pesquisa oficial consultada em **18/09/2026**.

## 1. Contrato e escopo

`SnsService` entrega wrappers de `Publish` e `PublishBatch` com client compartilhado, gate de taxa/concorrência e `WriteAuthorization` imediatamente antes do envio. O adapter aceita apenas destino explícito por tópico; recusa caminhos alternativos via telefone ou endpoint móvel para não permitir que o recurso autorizado difira do destino real.

Validação do schema da mensagem, aprovação de destinos, journal de publicação e reconciliação de consumidores são responsabilidade da operação e constituem **arquitetura alvo**. O wrapper não prova entrega nem processamento de negócio.

SNS publica para tópicos da região correspondente e admite body UTF-8 de até **256 KiB** para publicação em tópicos. Contar bytes e componentes sujeitos ao limite; não contar apenas caracteres Java. `MessageStructure=json` tem significado de payload por protocolo: não ativá-lo só porque a mensagem de negócio é JSON. [Publish](https://docs.aws.amazon.com/sns/latest/api/API_Publish.html)

## 2. Fluxo recomendado

1. Validar conta/região/ARN e resolver política do tópico, subscribers relevantes e impacto esperado.
2. Validar versão do schema, campos obrigatórios, conteúdo permitido, tamanho UTF-8 e atributos.
3. Construir o mesmo payload determinístico em dry-run e execute; mostrar amostra redigida, destinos e estimativa de fan-out.
4. Persistir intenção de publicação com chave de negócio/operation ID e versão da transformação.
5. Autorizar e publicar com limite de chamadas em voo.
6. Persistir request ID, message ID, destino, resultado, timestamp e sequência quando aplicável.
7. Reconciliar o efeito downstream por um mecanismo acordado com o consumidor, quando exigido pelo incidente.

`messageId` confirma aceitação da publicação; a operação precisa de evidência adicional para afirmar que todos os consumidores processaram o evento. O estado local deve separar `PREPARED`, `PUBLISHED`, `DELIVERY_UNKNOWN`, `BUSINESS_CONFIRMED` e `FAILED`.

Não registrar conteúdo integral por padrão. Logs levam `operationId`, recurso aprovado, código de erro, tamanho e request ID. Hash do payload não é anonimização garantida: aplicar a mesma política de dados sensíveis e retenção.

## 3. FIFO e atributos

Para FIFO, manter `MessageGroupId` coerente com a ordenação de negócio e usar `MessageDeduplicationId` estável para a mesma publicação lógica. Deduplicação por conteúdo usa body, não os atributos. A janela de deduplicação é limitada; seu escopo depende da configuração do tópico. Ela não equivale a proteção permanente contra um replay efetuado horas depois. [Deduplicação SNS FIFO](https://docs.aws.amazon.com/sns/latest/dg/fifo-message-dedup.html)

A regra deve declarar se o replay representa o mesmo evento ou um evento corretivo novo. Essa decisão determina a chave de dedup e evita suprimir correções legítimas. Atributos destinados a subscription filters precisam ser preservados ou transformados explicitamente; uma publicação aceita pode ser filtrada por todos os subscribers.

Exemplo de request; as variáveis são resultado do plano validado:

```java
var request = PublishRequest.builder()
        .topicArn(approvedTopicArn)
        .message(validatedPayload)
        .messageGroupId(businessGroup)
        .messageDeduplicationId(stableEventId)
        .messageAttributes(Map.of("operationId", MessageAttributeValue.builder()
                .dataType("String").stringValue(operationId).build()))
        .build();
var response = snsService.publish(request);
// Persistir response.messageId() antes de avançar o item no journal.
```

Esse exemplo é FIFO; para Standard, aplicar apenas campos compatíveis com a configuração e o consumidor. Não inferir schema, grupo ou dedup apenas do nome textual do tópico.

## 4. PublishBatch e falhas parciais

`PublishBatch` está disponível e aceita até 10 mensagens. O lote tem limite agregado de **256 KiB**, além do limite individual; validar ambos. IDs de entrada devem ser únicos dentro da requisição. A resposta possui entradas bem-sucedidas e falhas, inclusive em resposta HTTP 200. [PublishBatch](https://docs.aws.amazon.com/sns/latest/api/API_PublishBatch.html)

Mapear cada entrada ao item da operação; persistir sucessos e retentar apenas falhas transitórias cuja política permita. Nunca reenviar automaticamente o lote inteiro. Falha de transporte depois da aceitação é resultado desconhecido, especialmente em Standard: reconcile ou aceite duplicação conforme contrato explícito do consumidor.

Os adapters não acrescentam retry; o client de referência usa uma tentativa. Se uma operação futura habilitar retries, justificar a idempotência ponta a ponta e registrar o orçamento total, incluindo tentativas internas do SDK. Erro de autorização, payload inválido e incompatibilidade de schema não são motivos para retry.

## 5. Operação alvo e validação

Uma operação `publish-corrective-events` recebe fonte paginada, transformador puro, schema de saída, tópico aprovado, limites e política de dedup. O dry-run calcula candidatos e payloads sem publicar. Execute revisita precondições que possam ter mudado e mantém estado por evento.

Ensaiar: mistura de sucesso/falha em lote; timeout com resultado desconhecido; repetição após a janela FIFO; atributos que alteram o routing; ausência de permissão em tópico cross-account; payload multibyte próximo do limite; cancelamento entre publish e checkpoint. O módulo só deve ser liberado para uma operação concreta após esses resultados serem revisados.
