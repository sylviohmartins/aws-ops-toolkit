# Retry, resiliência e backpressure

Fontes consultadas em **2026-09-18**. **DECISÃO:** cada fronteira tem um único dono de retry técnico e um orçamento temporal. A engine não repete automaticamente uma operação inteira que pode ter produzido efeitos parciais.

## Quem repete o quê

| Fronteira | Dono | Política inicial proposta |
|---|---|---|
| Leitura AWS idempotente | SDK `StandardRetryStrategy` | `maxAttempts=3` explícito; timeouts por tentativa e total |
| Escrita com condição/token comprovadamente seguro | SDK, com política por operação | Até 3 tentativas dentro do orçamento; tratar resposta ambígua por reconciliação |
| Publish/send/invoke sem idempotência suficiente | Client/request configurado para 1 tentativa | UNKNOWN após resultado ambíguo; decidir no reconciliador |
| `UnprocessedItems`/`UnprocessedKeys` | Loop semântico do adapter | Reenviar somente pendentes, com orçamento total incluindo retries SDK |
| HTTP idempotente | Camada HTTP de integração | Tentativas limitadas, backoff com jitter, Retry-After limitado |
| HTTP não idempotente | Sem retry técnico automático | Exigir chave/contrato e consulta de status antes de habilitar |
| Resume de job | Engine | Carrega etapas pendentes; não equivale a retry do job completo |

**FATO:** o SDK possui estratégias standard, legacy e adaptive; adaptive compartilha o impacto de throttling entre chamadas do client e pressupõe isolamento por recurso. **DECISÃO:** fixar standard e quantidade de tentativas, pois defaults podem variar por versão/serviço. Não compartilhar instância mutável de retry strategy entre clients. [Retry no SDK Java 2.x](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/retry-strategy.html).

Não somar `@Retryable`/Resilience4j/SDK sobre a mesma chamada. Três camadas com três tentativas cada podem emitir 27 requests. Métrica de tentativa conta chamadas físicas, não apenas invocações lógicas do adapter. Se a configuração do transporte HTTP adicionar retry, desativar ou contabilizar explicitamente.

## Classificação e ação

| Categoria | Exemplo | Ação operacional |
|---|---|---|
| THROTTLING | DynamoDB throttled, HTTP 429 | Backoff limitado, reduzir admissão; pausar se persistente |
| TRANSIENT_NETWORK | Falha de conexão, reset, timeout | Leitura pode repetir; escrita incerta exige idempotência/reconciliação |
| DOWNSTREAM_UNAVAILABLE | HTTP 502/503/504, serviço indisponível | Retry limitado; circuit breaker na integração e pausa do job |
| AUTHENTICATION | `ExpiredToken`, login necessário | Invalidar permit e AUTHENTICATION_REQUIRED; sem loop de retry |
| AUTHORIZATION | `AccessDenied`, HTTP 403 | Pausar e revisar; não elevar privilégio |
| VALIDATION | Request inválido, schema inesperado, erro 400 sem causa transitória | Falhar cedo ou bloquear pipeline; não repetir |
| CONFLICT | `ConditionalCheckFailedException`, versão alterada, HTTP 409 | Contar conflito, reler/replanejar conforme regra; não sobrescrever |
| UNKNOWN_OUTCOME | Timeout após envio, falha de persistir confirmação | Ledger UNKNOWN; reconciliar antes de novo efeito |
| LOCAL_CAPACITY | Disco, fila/permits, orçamento esgotado | Backpressure ou pausa segura; sem descartes silenciosos |

Um HTTP 500 pode refletir falha depois de o servidor realizar o efeito. Classificação por status sozinha não estabelece idempotência. Corpo inesperado em 2xx é erro de contrato; não contar como sucesso sem validar schema mínimo.

## Timeouts e orçamento

**FATO:** timeout total do SDK inclui tentativas/backoff; timeout por tentativa limita uma tentativa. Timeouts de conexão/aquisição/socket devem caber no timeout da tentativa; os detalhes variam por transporte. [Timeouts SDK](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/timeouts.html).

Valores ilustrativos iniciais para pequenas leituras: aquisição 2 s, conexão 3 s, socket 10 s, tentativa 15 s e total 45 s. Não são limites AWS nem recomendação universal. Transferência S3 grande e Lambda síncrona precisam de budget próprio. Um total de 45 s pode truncar as três tentativas se backoff consumir o saldo: quantidade de tentativas é teto, não garantia de que todas ocorrerão.

`deadline` global do job e `deadline` da etapa limitam a soma de waits de permits, fila, Retry-After, tentativas e reconciliação. Desistir é resultado explícito. Medir latência incluindo espera e latência efetiva do downstream separadamente.

Para retries implementados pela integração: `delay = random(0, min(cap, base * 2^attempt))`, com aritmética sem overflow, clock monotônico e interrupção cooperativa. Respeitar `Retry-After` quando válido e autorizado pelo contrato, mas limitar ao saldo do orçamento. SDK usa sua estratégia própria; não envolver cada tentativa AWS com outro sleep/backoff.

## Backpressure de ponta a ponta

```text
admission do job
  → permit global de memória/página
  → taxa e concorrência do recurso de origem
  → página limitada
  → transformação limitada
  → permit/taxa de cada downstream
  → persistência de resultado
  → projeção do relatório
  → liberação da página e próximo cursor
```

Taxa de leitura não pode ignorar capacidade do relatório/ledger/HTTP. Se consumidor degrada, fila limitada enche e produtor espera; se deadline estoura, pausa. Não usar fila ilimitada, lista de futures ou cache de payload sem tamanho/TTL. Tamanho máximo de item/resposta também deve ser limitado para que fila de 100 elementos não vire vários GB.

Semaphores devem ser liberados em `finally`, inclusive quando a submissão ao executor falhar. Não segurar permit de um serviço enquanto aguarda outro desnecessariamente. Se vários recursos forem adquiridos juntos, estabelecer ordem única ou executar etapas sem locks cruzados. Flush/auditoria não pode depender de vaga que o próprio job retém, evitando deadlock de encerramento.

## Circuit breaker, bulkhead e Resilience4j

**DECISÃO:** não incluir Resilience4j no skeleton por hábito. JDK cobre semaphores/filas; SDK cobre retry AWS. Para HTTP repetitivo, introduzir CircuitBreaker/RateLimiter/Bulkhead Resilience4j quando as regras e métricas exigirem isso, com compatibilidade Java25/Spring Boot4 validada e sem retry duplicado. [Documentação oficial Resilience4j](https://resilience4j.readme.io/docs/getting-started).

Circuit breaker protege chamadas repetidamente falhas, mas não corrige autenticação ou resultado desconhecido. Em half-open, probes devem ser leituras seguras ou chamadas já protegidas por idempotência; nunca usar mutação de negócio como teste automático de saúde. Separar circuit/bulkhead por API/recurso e manter capacidade reservada para status/cancelamento/flush.

## Error budget e adaptação

Teto absoluto `maxErrors`, taxa `maxErrorRate`, janela temporal e amostra mínima trabalham juntos. Exemplo para HML: avaliar janela de 60 s após ao menos 100 resultados, pausar se erros transitórios > 5%; qualquer mismatch de identidade, schema crítico, AccessDenied ou falha de ledger fecha escrita imediatamente. Os números são hipóteses a calibrar; conflitos podem ter orçamento separado, sem escondê-los no denominador total de itens examinados.

AIMD opcional: iniciar baixo; após janela saudável aumentar permits em 1 até teto manual; em throttling/error/latência acima do alvo reduzir à metade (mínimo 1), resfriar e observar. Se nem 1 chamada simultânea é segura, pausar. `TotalSegments` do scan continua fixo; apenas segmentos ativos variam. Um controller por recurso evita oscilações de múltiplas camadas adaptativas. Hot tuning deve auditar operador, valor anterior/novo e revisão; aumentos nunca excedem teto aprovado.

## Validação necessária

Injetar 429 com Retry-After, 500 antes/depois do efeito, resets, timeout, fila cheia, throttling, erro de schema e expiração. Comprovar máximo de chamadas físicas, teto de memória/fila, ausência de retry explosivo, cancelamento durante backoff e tratamento UNKNOWN. O teste mais valioso força perda da resposta após commit remoto e verifica que o sistema não assume falha segura nem repete cegamente.
