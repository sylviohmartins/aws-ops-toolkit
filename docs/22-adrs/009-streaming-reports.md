# ADR 009 — CSV detalhado e XLSX resumido

Status: CSV streaming e XLSX resumido via SXSSF implementados no runtime local; homologação de retenção/PII/armazenamento permanece ambiental. Data da decisão: 2026-09-18; status revisado em 2026-10-07.

## Context

Milhões de registros não cabem em coleções de relatório em heap. A evidência precisa ser recuperável, conter resultado conhecido e minimizar dados sensíveis.

## Decision

CSV streaming em partes com schema fixo, manifesto, checksum e identificação de parcial é o formato detalhado. JSON traz resumo/metadados. XLSX é resumo/amostra após processamento, preferencialmente POI SXSSF quando homologado; não é dump integral da tabela. SXSSF usa janela de linhas e temporários, que entram no orçamento de disco. [POI SXSSF](https://poi.apache.org/components/spreadsheet/how-to.html#sxssf).

## Alternatives

XSSF integral serve somente volumes pequenos conhecidos. Fastexcel pode reduzir overhead, a validar em benchmark e homologação. Commons CSV será preferido a expandir parser/writer próprio para múltiplos dialectos. CSV.GZ economiza armazenamento com custo de compressão e partes independentes.

## Consequences

Report é projeção do ledger: falha ao gerar CSV/XLSX não provoca repetição da escrita AWS. Cabeçalhos, encoding, escaping, neutralização de fórmula em exportação humana e colunas permitidas são contratos versionados. O runtime expõe CSV e XLSX via `SXSSFWorkbook`, com janela de linhas e limite de registros configuráveis, e usa o `SqliteJournal` para auditoria local. Isso ainda não equivale a audit trail produtivo homologado: retenção, PII, ACLs, criptografia e destino de evidência permanecem `VALIDAR NO AMBIENTE`. Ver [relatórios](../15-reporting-audit.md).
