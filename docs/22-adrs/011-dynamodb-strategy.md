# ADR 011 — Acesso DynamoDB seletivo e scan governado

Status: aceito; profundidade produtiva no roadmap. Data: 2026-09-18.

## Context

Incidentes podem exigir localizar candidatos entre milhões de itens sem índice adequado. Scan precisa existir, mas seu custo e ausência de snapshot devem ser visíveis.

## Decision

Preferir Get/BatchGet/Query/índice adequado; oferecer Scan paginado e parallel scan com limites e cursores por segmento. Fixar `TotalSegments` no plano e ajustar somente workers ativos. Usar client de baixo nível para operações dinâmicas controladas; Enhanced Client é opção para schemas estáveis. Document API v1 não entra na baseline SDK 2.x.

## Alternatives

Scan irrestrito desperdiça capacidade e dificulta controle. Criar GSI emergencial pode ter custo/impacto superior à correção e é mudança separada. Export/snapshot aprovado pode atender conjunto estável offline. Async clients não eliminam custos ou a necessidade de paginação.

## Consequences

Registrar examinados/retornados/capacidade, filtros e consistência. FilterExpression não reduz os dados lidos pelo Scan. Alteração exige condição/versão; BatchWrite é para put/delete, não batch update. Reconciliação trata mudança concorrente e resultados ambíguos. Detalhes/fonte em [Scan AWS](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Scan.html) e [módulo DynamoDB](../06-dynamodb.md).
