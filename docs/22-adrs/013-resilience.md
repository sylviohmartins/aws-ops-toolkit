# ADR 013 — Retry por fronteira, sem multiplicação de tentativas

Status: aceito. Data: 2026-09-18.

## Context

Throttling, timeout e indisponibilidade são esperados. Uma tentativa repetida pode duplicar pagamento/evento; retry SDK envolto em retry de framework e retry de job pode amplificar incidentes.

## Decision

SDK é dono do retry técnico AWS, com standard e `maxAttempts` explícito. Leitura idempotente inicia com teto de 3; efeito sem idempotência suficiente usa 1 e reconciliação de UNKNOWN. Loop de pendentes de Batch APIs tem budget total próprio que conta chamadas físicas. HTTP tem política independente, considerando método, chave, contrato e Retry-After. [Retry SDK](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/retry-strategy.html).

## Alternatives

Retries infinitos ou empilhados escondem erro sistêmico. Adaptive SDK pode funcionar em client isolado por recurso, mas competiria com controle adaptativo do pipeline. Resilience4j só entra por capacidade demonstrada, sem decorar AWS indiscriminadamente. Fail-fast absoluto para todo erro transitório perderia recuperação útil.

## Consequences

Classificar autenticação, autorização, validação, conflito, rede e throttling. Deadline e orçamento de erro podem pausar mesmo sem esgotar todas as tentativas. Cancelamento interrompe espera; timeout após envio não prova falha remota. Não configurar retry global de POST não idempotente. Ver [resiliência](../13-resilience.md) e [ledger](../23-checkpoint-resume-idempotency.md).
