# ADR 010 — Profile explícito e identidade efetiva validada

Status: aceito. Data: 2026-09-18.

## Context

Um engenheiro pode ter múltiplas contas, variáveis de ambiente antigas e uma conexão diferente no AWS Toolkit. O processo Java precisa demonstrar sua identidade efetiva sem armazenar segredos.

## Decision

Selecionar `ProfileCredentialsProvider` com nome obrigatório e região explícita; usar o mesmo provider nos clients e no STS de preflight. Preservar o mecanismo corporativo do profile: SSO, STS ou credential_process legitimamente configurado. Toolkit conectado não é prova de credencial do Java. [Profiles SDK](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/credentials-profiles.html).

## Alternatives

DefaultCredentialsProvider é apropriado em ambientes preparados para sua precedência, mas properties/ambiente podem vencer o profile selecionado. Será exceção explícita, com mesma validação STS. Credenciais estáticas no código/YAML são rejeitadas. Não implementar broker corporativo nem automatizar elevação.

## Consequences

Autenticação/refresh dependem do provider; não supor expiração conhecida pelo contrato genérico. Falha de sessão fecha admissão e exige login legítimo/resume. Nova identidade precisa pertencer à política do plano. Break Glass, roles, duração e revogação são VALIDAR NO AMBIENTE. Ver [autenticação](../04-aws-authentication.md).
