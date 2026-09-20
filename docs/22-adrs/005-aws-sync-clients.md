# ADR 005 — AWS SDK 2.x síncrono e Apache 5 explícito

Status: aceito para baseline. Data: 2026-09-18.

## Context

São necessárias integrações reutilizáveis, com política de timeout/retry e diagnóstico previsível. Defaults de transporte podem mudar entre versões do SDK.

## Decision

Usar AWS SDK for Java 2.55.0 por BOM, clients síncronos reutilizados e `Apache5HttpClient` configurado explicitamente. Definir região, provider, conexões, timeouts, tentativas e identificação da ferramenta. Um client nunca nasce por item. [Transportes SDK](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/http-configuration.html), [Apache 5](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/http-configuration-apache5.html).

## Alternatives

Netty async se encaixa em fluxo verdadeiramente assíncrono; não usar apenas para fazer `join()` em toda chamada. CRT pode beneficiar transferências, mas requer validação de runtime nativo, TLS e proxy corporativo. URLConnection reduz dependências, com menos recursos. HTTP do JDK atende a integração RestClient, sem presumir que seja intercambiável com o SPI AWS.

## Consequences

Clients e transporte compartilhado têm lifecycle de aplicação e fechamento coordenado. Isolar políticas por recurso/efeito para que publish não idempotente não herde retry de leitura. AWS permanece desligada por default no skeleton; adapters compiláveis não autorizam execução. Reavaliar async/CRT após medição de gargalo, preservando contratos e limites.
