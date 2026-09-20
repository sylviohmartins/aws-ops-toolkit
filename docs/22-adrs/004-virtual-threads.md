# ADR 004 — Virtual threads com admissão limitada

Status: aceito. Data: 2026-09-18.

## Context

Operações aguardam AWS, APIs HTTP e disco. O baixo custo de uma thread não altera a capacidade de produção nem o espaço da fila de trabalho.

## Decision

Usar virtual threads em tarefas dominadas por I/O, adquirindo permits antes de submeter trabalho. Manter fila/página limitada por itens e bytes, taxa por recurso e número máximo de jobs. CPU intensiva usa executor separado e limitado após profiling. A adequação de VT a espera de I/O é documentada pelo [Java 25](https://docs.oracle.com/en/java/javase/25/core/virtual-threads.html).

## Alternatives

Pool fixo de platform threads é viável com concorrência baixa, mas menos flexível em espera bloqueante. CompletableFuture e async clients podem atender uma fronteira medida; futures ilimitadas são igualmente perigosas. `parallelStream()` usa um modelo inadequado para governar recursos operacionais distintos.

## Consequences

Executor VT não é controle de capacidade. Semáforos, limites de conexão e deadlines devem ser observáveis e liberados em falhas de submissão. Não presumir herança de ScopedValue/MDC em tasks arbitrárias. A demonstração usa modelo deliberadamente simples; paralelismo de produção exige benchmark e ensaios de cancelamento, fila cheia e timeout. Ver [concorrência](../12-concurrency-performance.md).
