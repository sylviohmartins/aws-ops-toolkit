# ADR 008 — JSON demonstrativo e ledger SQLite antes da escrita

Status: JSON implementado; SQLite aceito para CORE, ainda não implementado. Data: 2026-09-18.

## Context

Um cursor não explica quais efeitos de uma página foram concluídos. Relatório e checkpoint gravados em arquivos separados não formam transação, e um crash pode ocorrer depois de sucesso remoto e antes do registro local.

## Decision

Manter JSON/partes de relatório para a demonstração sintética. Antes de escrita AWS, implementar SQLite local com WAL, `synchronous=FULL`, single writer e transações curtas contendo intenção, outcome, eventos de relatório e cursor. Efeitos remotos ocorrem fora da transação; incerteza vira UNKNOWN. [SQLite WAL](https://www.sqlite.org/wal.html), [atomicidade](https://www.sqlite.org/atomiccommit.html).

## Alternatives

JSON journal próprio exigiria implementar recuperação, índices, compactação e atomicidade. Banco remoto adiciona infraestrutura e permissões, podendo ser apropriado se houver serviço corporativo homologado. Só checkpoint de página perde informação de falhas parciais. SQLite em share de rede não atende a proposta.

## Consequences

Homologar driver/versionamento, backup, disco e recuperação antes de EXECUTE. Não copiar somente arquivo `.db` ativo; considerar WAL no backup. O banco local não oferece exactly-once entre serviços nem coordenação entre workstations. Testes de crash por janela são obrigatórios. Ver [checkpoint e idempotência](../23-checkpoint-resume-idempotency.md).
