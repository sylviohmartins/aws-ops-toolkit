# ADR 014 — Escrita liberada por plano, identidade e evidência

Status: aceito para arquitetura alvo; indisponível no skeleton. Data: 2026-09-18.

## Context

Possuir uma credencial poderosa não significa que uma regra emergencial, seu volume e seu alvo foram aprovados. O caso mais perigoso é uma configuração aparentemente válida apontando para conta ou recurso incorretos.

## Decision

Negar escrita por default. Exigir cumulativamente identidade efetiva permitida, região/recursos allowlisted, operação homologada, `writeEnabled`, EXECUTE, incidente/motivo, plano originado de dry-run, confirmação vinculada ao hash, canary, budgets e ledger saudável. O skeleton executa somente LOCAL/DRY_RUN; adicionar flag não habilita produção.

## Alternatives

Booleano `confirm=true`, nome de profile contendo `prod` ou IAM permitido são controles insuficientes de intenção. Aprovação humana sem verificações técnicas não detecta toda configuração errada. Criar um mecanismo próprio de Break Glass está fora do escopo.

## Consequences

Mudança de regra, alvo ou teto invalida a confirmação. Renovação revalida identidade antes de retomar. Em qualquer dúvida de schema, disco ou autorização, parar novas escritas. Rollback é outra operação condicionada, não uma restauração cega. Guardrails técnicos complementam IAM e políticas corporativas, sem substituir aprovação organizacional. Ver [segurança](../03-security-production-guardrails.md).
