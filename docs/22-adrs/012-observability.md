# ADR 012 — Métricas locais e auditoria separada

Status: baseline aceita; instrumentação completa no roadmap. Data: 2026-09-18.

## Context

Operador precisa saber se o job avança, pressiona downstream, perde autenticação ou consome disco. Logging por item degrada hot path e pode expor dados; métricas não substituem evidência de resultado.

## Decision

Actuator/Micrometer para métricas agregadas, logs estruturados para diagnóstico, ledger/audit sink para evidência. JFR/JMC sob demanda. IDs de job/item ficam em logs/ledger sanitizados, não em tags de cardinalidade ilimitada. Exportação OpenTelemetry é opcional e depende de destino aprovado. [Conceitos Micrometer](https://docs.micrometer.io/micrometer/reference/concepts.html).

## Alternatives

Somente logs tornam throughput/latência difíceis de avaliar. Span por item e tags arbitrárias aumentam custo e memória. Stack remota obrigatória cria dependência desnecessária para uso local e pode exportar dados indevidamente. Monitorar só CPU ignora saturação de AWS, API e disco.

## Consequences

Métricas específicas precisam ser implementadas e verificadas; presença do starter não as cria. Latência de fila e remota são separadas. Audit obrigatório não pode descartar silenciosamente eventos se seu buffer encher. Endpoints sensíveis permanecem protegidos e loopback. Ver [observabilidade](../14-observability.md).
