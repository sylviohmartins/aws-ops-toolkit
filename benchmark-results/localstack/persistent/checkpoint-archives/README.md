# Archived LocalStack projection checkpoints

Este diretório contém **snapshots históricos e sintéticos** de checkpoints produzidos durante
os experimentos de 5M em 01/10/2026. Eles foram preservados como evidência do comportamento
de checkpoint/resume observado durante a evolução do Performance Lab.

Eles **não são estado operacional ativo** e não devem ser usados automaticamente para retomar
benchmarks atuais. O harness atual grava checkpoints vivos em
target/localstack-performance/<mode>/projection-checkpoints/..., caminho ignorado pelo Git,
e aplica identidade versionada por checkpoint-config.json antes de aceitar resume.

Conteúdo preservado:

- 20261001-heap2g-partial-5m-32x16-page1000/: snapshot parcial da execução 5M com 32
  segmentos, incluindo cursores sintéticos tenant-* / record-*;
- 20261001-preheap-resumed-5m-32x16-page1000/: snapshot da execução retomada anterior,
  incluindo run-state.json com attempts=3 e elapsed conhecido naquele estágio.

Os dados pertencem exclusivamente ao fixture sintético lab-perf-v3-*; não contêm
credenciais AWS reais nem dados de produção.