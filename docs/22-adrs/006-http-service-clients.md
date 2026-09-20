# ADR 006 — HTTP Service Interface com RestClient

Status: aceito. Data: 2026-09-18.

## Context

Regras precisam enriquecer dados com APIs REST tipadas, observar falhas e impedir repetição insegura. Não há requisito reativo ponta a ponta.

## Decision

Contrato anotado HTTP Service Interface com proxy sobre RestClient e transporte JDK HTTP explícito. Centralizar base URL allowlisted, timeouts, headers permitidos, correlation ID, handlers de status/body, limite de resposta e classificação de erros. [Spring HTTP clients](https://docs.spring.io/spring-framework/reference/integration/rest-clients.html).

## Alternatives

WebClient atende streaming/fluxos async quando necessários. OpenFeign foi avaliado, porém o Spring o apresenta como feature-complete e direciona novas avaliações para HTTP Service Clients. Não há motivo para importar Spring Cloud apenas para Feign neste projeto. [Direção OpenFeign](https://docs.spring.io/spring-cloud-openfeign/reference/).

## Consequences

Não existe `ErrorDecoder` Feign na solução. A responsabilidade equivalente continua obrigatória via handler/adapter: mapear 4xx/5xx, resposta inesperada, rede e timeout, sem expor tokens. Retry de POST depende do contrato de idempotência; 500 ou timeout não provam ausência de efeito. RestClient e interface são camadas complementares, não alternativas excludentes. Ver [integrações HTTP](../11-http-integrations.md).
