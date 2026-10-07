# ADR 008 — JSON demonstrativo e ledger SQLite antes da escrita

Status: JSON implementado; ledger SQLite CORE implementado no runtime local, com homologação ambiental ainda pendente. Data da decisão: 2026-09-18; status revisado em 2026-10-07.

## Context

Um cursor não explica quais efeitos de uma página foram concluídos. Relatório e checkpoint gravados em arquivos separados não formam transação, e um crash pode ocorrer depois de sucesso remoto e antes do registro local.

## Decision

Manter JSON/partes de relatório para a demonstração sintética. O runtime CORE implementa SQLite local com WAL, `synchronous=FULL`, foreign keys, lock local de processo e transações curtas para jobs, cursores, tasks, efeitos e auditoria. Efeitos remotos ocorrem fora da transação; incerteza continua virando UNKNOWN. O schema concreto é uma materialização do modelo conceitual descrito em [checkpoint e idempotência](../23-checkpoint-resume-idempotency.md), não uma cópia literal daquele DDL. [SQLite WAL](https://www.sqlite.org/wal.html), [atomicidade](https://www.sqlite.org/atomiccommit.html).

## Alternatives

JSON journal próprio exigiria implementar recuperação, índices, compactação e atomicidade. Banco remoto adiciona infraestrutura e permissões, podendo ser apropriado se houver serviço corporativo homologado. Só checkpoint de página perde informação de falhas parciais. SQLite em share de rede não atende a proposta.

## Consequences

Homologar driver/versionamento, backup, disco e recuperação antes de EXECUTE. Não copiar somente arquivo `.db` ativo; considerar WAL no backup. O banco local não oferece exactly-once entre serviços nem coordenação entre workstations. Testes de crash por janela são obrigatórios. Ver [checkpoint e idempotência](../23-checkpoint-resume-idempotency.md).
