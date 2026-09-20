# ADR 003 — MVC como fronteira HTTP

Status: aceito. Data: 2026-09-18.

## Context

O servidor recebe comandos locais e consultas de progresso. Jobs longos não pertencem ao lifecycle da conexão HTTP. A equipe precisa depurar uma regra em minutos durante um incidente.

## Decision

Usar Spring MVC para REST e retornar 202 após admissão durável. Processar jobs em executor controlado separado. MVC + virtual threads + AWS sync é o padrão; backpressure pertence às fronteiras do pipeline, não depende da escolha do servidor web. [Spring REST clients](https://docs.spring.io/spring-framework/reference/integration/rest-clients.html).

## Alternatives

WebFlux + SDK async permite fluxo reativo contínuo, mas exige modelo coerente ponta a ponta e domínio de diagnóstico/cancelamento. MVC + SDK async é útil para uma integração específica, não como dualidade generalizada. Modelo híbrido será aceito somente onde benchmark comprovar benefício relevante.

## Consequences

Fluxo da operação permanece legível e bloqueante por etapa. Não bloquear request até processar milhões de itens. Admissão de jobs, limites de memória e downstream precisam ser explícitos. Não instalar Reactor só para limitar uma fila. A comparação completa está em [arquitetura](../02-architecture.md); mudar a fronteira web não dispensa ledger, segurança nem controle de taxa.
