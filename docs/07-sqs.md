# SQS, inspeção de DLQ e replay controlado

Projeto: **aws-ops-toolkit**. Referências oficiais consultadas em **18/09/2026**.

## 1. O que existe e o que será implementado

`SqsService` fornece requests SDK 2.x para consultar atributos, receber com consentimento de impacto, enviar, enviar em lote, excluir, excluir em lote e alterar visibilidade. Todas as chamadas passam por `AwsCallGate`; operações com efeito remoto passam também por `WriteAuthorization`. Receive não exclui mensagens automaticamente.

O adapter é um exemplo compilável, sem bean automático. Um consumidor contínuo, journal durável de replay, scheduler de renovação de visibilidade, validação completa de payload e operações REST específicas são **arquitetura alvo**, descrita abaixo. Permissão IAM não substitui o consentimento operacional para interferir numa queue compartilhada.

## 2. Receive não é peek

`ReceiveMessage` disponibiliza body, atributos solicitados, message ID e receipt handle. A chamada torna mensagens temporariamente invisíveis e afeta a contagem de recebimentos. Cada recebimento gera um receipt handle; ele é o identificador para excluir aquela entrega, não o `MessageId`. Não persistir handles como se fossem chaves permanentes de retomada. [ReceiveMessage](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/APIReference/API_ReceiveMessage.html)

Sem delete, uma mensagem volta a ficar disponível ao vencer a visibilidade. Isso também pode bloquear temporariamente mensagens do mesmo grupo em FIFO. Visibilidade não elimina duplicatas nem oferece garantia de processamento único. [Visibility timeout](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-visibility-timeout.html)

Inspeções repetidas podem consumir o orçamento de `maxReceiveCount` de uma fila com DLQ. Avaliar retenção e redrive policy antes da sessão; não modificar políticas da fila silenciosamente. Contagens do `GetQueueAttributes` são estimativas operacionais, não um inventário transacional de todas as mensagens. [DLQ e redrive policy](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-dead-letter-queues.html)

### Protocolo do inspector

1. Confirmar identidade/conta/região, URL e ARN real da queue, tipo Standard/FIFO, consumidores existentes, retenção e DLQ associada.
2. Registrar motivo, limite de mensagens/receives, tempo da sessão, visibilidade explícita e consentimento para o impacto.
3. Adquirir capacidade local **antes** de receber; a amostra não pode crescer indefinidamente.
4. Receber uma pequena amostra; mascarar campos sensíveis e guardar apenas artefatos aprovados.
5. Não enviar, excluir ou alterar política automaticamente. Deixar a visibilidade vencer ou liberá-la explicitamente conforme plano autorizado.
6. Registrar quantas chamadas e entregas ocorreram, inclusive duplicatas; encerrar no orçamento.

Visibilidade zero não equivale a inspeção sem impacto: o receive e a contagem já aconteceram, e outro consumidor pode receber imediatamente. Também não existe busca arbitrária de uma mensagem por `MessageId` nessa API; seleção ocorre sobre as mensagens entregues ao inspector.

Exemplo com client/gate/autorização fornecidos pela composição da aplicação:

```java
var service = new SqsService(sqsClient, gate, authorization);
var page = service.receiveForInspection(ReceiveMessageRequest.builder()
        .queueUrl(approvedQueueUrl)
        .maxNumberOfMessages(1)
        .waitTimeSeconds(20)
        .visibilityTimeout(10)
        .messageSystemAttributeNamesWithStrings("All")
        .messageAttributeNames("All")
        .build(), true); // Consentimento explícito; a autorização de recurso também será validada.
// Redação + relatório limitado. Não há chamada automática de delete.
```

Long polling aceita até 20 segundos. Timeout de socket/tentativa deve exceder a espera, com margem para rede e processamento; o client de referência usa esse cuidado. Resposta vazia não prova que a fila inteira está vazia. [Short e long polling](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-short-and-long-polling.html)

**Dry-run:** planejar a partir de metadados e amostra já autorizada/exportada. Uma nova amostra live exige uma fase separada de inspeção com consentimento, mesmo quando o replay posterior estiver em dry-run. Não rotular `ReceiveMessage` como uma leitura sem efeito.

## 3. Standard, FIFO, atributos e integridade

| Aspecto | Decisão da operação |
|---|---|
| Standard | Admitir entrega repetida e reordenação; chave de negócio/idempotência no consumidor |
| FIFO | Preservar `MessageGroupId`; serializar efeitos por grupo e limitar grupos simultâneos |
| Deduplicação FIFO | Usar ID estável para a mesma tentativa lógica; não confundir janela de dedup com ledger permanente |
| Content-based dedup | Conferir configuração da fila; mudanças apenas em atributos podem não mudar o hash do body |
| Message attributes | Preservar somente os permitidos; validar nomes, tipos e tamanho serializado |
| System attributes | Não copiar indiscriminadamente para atributos de aplicação; manter semântica e origem |
| MD5 | Comparar digest de transporte quando aplicável; não tratar como assinatura/autenticidade ou chave de idempotência |
| Poison messages | Quarentena/relatório e limite de tentativas; não criar loop automático de DLQ para origem |

FIFO usa identificadores de grupo e deduplicação específicos; um receive entrega mensagens de grupos conforme disponibilidade. A semântica da aplicação continua exigindo idempotência para efeitos externos. [Identificadores FIFO](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-fifo-queue-message-identifiers.html)

O digest de atributos depende da representação definida pelo serviço, incluindo nome/tipo/valor; não basta calcular MD5 de um JSON arbitrário. Nunca registrar body integral para investigar divergência; capturar tamanho, digest permitido e request ID. [Metadados e MD5](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-message-metadata.html)

## 4. Envio e exclusão em lote

`SendMessageBatch` aceita até 10 entradas. A documentação atual limita mensagem individual e soma do lote a **1 MiB**, incluindo os componentes contabilizados pelo serviço; não reutilizar automaticamente o antigo limite de 256 KiB. Validar bytes UTF-8, caracteres, atributos, IDs únicos e limites antes de enviar. Uma resposta HTTP 200 pode conter sucessos e falhas. [SendMessageBatch](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/APIReference/API_SendMessageBatch.html)

Correlacionar pelo `Id` local de cada entrada; persistir `MessageId`, sequência FIFO, resultado e destino. Retentar somente falhas classificadas como transitórias e com semântica de idempotência aceitável. Um envio com timeout pode ter sido aceito: classificar como resultado desconhecido até reconciliação, especialmente em Standard.

`DeleteMessageBatch` também tem resultados por entrada e lote de até 10. Excluir somente recibos de mensagens cujo processamento/reenfileiramento foi confirmado duravelmente e autorizado pela política. Não transformar falha parcial de delete em sucesso global. [DeleteMessageBatch](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/APIReference/API_DeleteMessageBatch.html)

O código verifica quantidade de entradas e devolve integralmente as listas de sucesso/falha. A validação semântica e de tamanho detalhada ficará na operação. Não há retry de lote na camada adapter; o client de referência usa uma tentativa para não duplicar efeitos sem uma política específica.

## 5. Replay de DLQ: ordem que evita perda silenciosa

Arquitetura alvo por mensagem:

```text
receber → validar/selecionar → transformar → registrar PREPARED
        → enviar ao destino aprovado → registrar SENT + destinationMessageId
        → reconciliar conforme contrato → registrar DELETE_ELIGIBLE
        → excluir original, se permitido → registrar SOURCE_DELETED
```

Se a mensagem não satisfizer o filtro, não excluí-la. Se transformação ou validação falhar, registrar falha e interromper/reter conforme política. Se o destino aceitar mas o registro local falhar, a próxima execução pode reenviar: **não existe transação distribuída entre send e delete**. Preferir duplicata recuperável a perda; o destino deve reconhecer chave estável de negócio/repair.

O journal deve guardar origem/destino, ID da mensagem, chave de negócio protegida, versão da transformação, hash aprovado, resultado do envio, status da reconciliação e status da exclusão. A máquina deve diferenciar `SEND_UNKNOWN`, `SENT`, `DELETE_FAILED` e `SOURCE_DELETED`. Após queda, não reutilizar receipt handle antigo: receber novamente e consultar o journal. Uma mensagem transformada precisa de política explícita de dedup; reutilizar indevidamente um ID pode suprimir o replay necessário.

Registrar contadores independentes: recebidas, candidatas, descartadas da seleção, transformadas, enviadas, envio desconhecido, reconciliadas, excluídas e falhas. A igualdade “enviadas = excluídas” não é premissa; discrepâncias devem ser explicadas.

O redrive nativo (`StartMessageMoveTask`) pode ser preferível quando o objetivo é mover mensagens sem seleção/transformação personalizada. Possui permissões e controle de velocidade próprios. Não implementa o filtro/transformador da operação customizada. `SqsService` não expõe esse comando no esqueleto; sua inclusão exige uma operação específica revisada. [Redrive nativo](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-configure-dead-letter-queue-redrive.html)

## 6. Consumidor local temporário

O consumidor alvo é habilitado por configuração explícita por queue e possui número fixo de pollers/workers, fila local limitada, orçamento de mensagens e deadline. Um poller só chama receive quando já reservou capacidade para a quantidade solicitada. O consumidor concorre com os consumidores de negócio, inclusive durante incidentes.

Uma mensagem entregue recebe lease local com instante de recebimento, visibility deadline, receipt handle atual e estado. Renovar visibilidade antes da margem de segurança apenas enquanto o worker estiver ativo e autorizado. Respeitar o limite de 12 horas desde o recebimento, sem renovar indefinidamente. Se a renovação falhar ou a sessão expirar, parar novos efeitos; a mensagem pode voltar a outro consumidor. [Gerenciamento de visibilidade](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-visibility-timeout.html)

No cancelamento: parar novos receives, drenar trabalhos até deadline, confirmar somente os resultados duráveis e deixar mensagens pendentes reaparecerem ou liberar visibilidade por ação autorizada. Não executar delete em bloco durante shutdown. Interromper um long poll depende de interrupção/timeout do transporte; o shutdown possui prazo finito.

Métricas alvo: chamadas e entregas/s, mensagens em voo locais, duração de processamento, margem de lease, renovação falha, idade aproximada, tamanho do backlog local, duplicatas reconhecidas, falhas por causa e ack pendente. Não usar message ID como label de métrica de alta cardinalidade.

## 7. Critérios de aceite antes de operar

- Receive sem consentimento/autorização não chega ao client; inspeção nunca autoexclui.
- Backpressure impede receber mais mensagens que os slots disponíveis.
- Send/delete batch parcial é persistido por entrada.
- Falha após send e antes de delete não perde original; duplicação potencial é reconciliada.
- Expiração de sessão durante receive, renovação, send ou delete gera estados distintos.
- FIFO mantém a ordem de efeitos por grupo; poison message não paralisa grupos não relacionados.
- Cancelamento não confirma trabalho incompleto nem mantém leases indefinidamente.

Esses critérios incluem funcionalidades ainda não implementadas. Uma validação unitária do adapter não demonstra segurança do replay completo em produção.
