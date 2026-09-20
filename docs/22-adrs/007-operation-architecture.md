# ADR 007 — Engine pequena com ports e adapters

Status: aceito com limite de escopo. Data: 2026-09-18.

## Context

Scripts isolados refazem autenticação, paginação, concorrência e relatórios. Uma hierarquia extensa ou um framework de workflow genérico tornaria a regra difícil de depurar durante uma war room.

## Decision

Monólito modular com operações tipadas, registry validado, engine responsável por lifecycle e políticas, portas pequenas e adapters por capacidade. Compor Command, Strategy e Pipeline. Regras não criam clients, threads, arquivos ou credenciais; executam seleção/validação/transformação e efeitos pelas portas. Evitar Template Method como superclasse central que permita contornar barreiras.

## Alternatives

Controller-Service-Repository não representa satisfatoriamente estado durável e efeitos múltiplos. Spring Batch é candidato se a organização já o suporta ou surgirem scheduler, metadados corporativos e jobs generalizados. Workflow distribuído/cloud e DSL dinâmico excedem o escopo local atual.

## Consequences

Uma operação nova precisa de pouco boilerplate, mas declarar novo efeito continua exigindo idempotência/reconciliação. A engine não deve evoluir sem limite até replicar um batch framework. Reavaliar antes de distribuir execução ou suportar múltiplos hosts. O contrato atual `OperationDefinition<I>` e a operação sintética são um subconjunto funcional; o blueprint completo está em [engine](../05-operation-engine.md) e [arquitetura](../02-architecture.md).
