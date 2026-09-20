# ADR 002 — Spring Boot 4.1.1 com BOM

Status: aceito para projeto novo; baseline corporativa prevalece. Data: 2026-09-18.

## Context

Não foi fornecida versão Spring homologada pela organização. É necessário comparar evolução atual com uma opção conservadora compatível com Java 25, sem adotar a versão mais nova apenas por sua data.

## Decision

Usar Spring Boot 4.1.1, Framework 7.0.9 e Jackson 3 gerenciados pelo BOM. A linha 3.5.16/Framework 6.2.19 é alternativa quando já homologada e com suporte comprovado. A página oficial consultada confirma a compatibilidade Java da baseline escolhida; versões fixadas são um recorte, não autorização de upgrade automático. [Requisitos Boot](https://docs.spring.io/spring-boot/system-requirements.html), [Boot 3.5](https://docs.spring.io/spring-boot/3.5/system-requirements.html).

## Alternatives

Boot 3.5 reduz migração em organizações com bibliotecas existentes, mas exige verificar janela de suporte e não elimina futura migração. Boot 4.0 adicionaria uma baseline intermediária sem vantagem demonstrada para este projeto novo. Spring sem Boot reduz automação, mas aumenta configuração manual de segurança, HTTP e observabilidade.

## Consequences

Homologar starters, serialização Jackson 3, plugins e bytecode Java 25 antes de AWS real. Não misturar exemplos de Boot 3/Jackson 2 com imports/auto-configuração de Boot 4. Dependências gerenciadas não recebem versão individual sem justificativa. A escolha torna o skeleton reproduzível; não declara prontidão corporativa. Se 3.5 for exigida, migrar deliberadamente, registrar ADR substituta e repetir validação.
