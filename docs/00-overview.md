# aws-ops-toolkit — Executive Summary

**DECISÃO — 2026-09-18:** criar um monólito modular local para engenheiros autorizados, com operações extensíveis, execução assíncrona, memória limitada, evidências e recuperação. Uma nova regra implementa principalmente `selecionar → validar → transformar → executar → verificar`. Segurança e controle prevalecem sobre throughput máximo.

Esta entrega é **arquitetura, plano de implementação e código-base demonstrativo**, conforme o prompt. Não é uma ferramenta homologada para modificar produção. O código executável oferece uma operação sintética LOCAL/DRY_RUN, relatórios CSV, persistência, pausa/cancelamento e retomada. Exemplos compiláveis AWS/HTTP não são rotas produtivas. A [matriz de implementação](26-implementation-blueprint.md) identifica exatamente cada nível.

## Decisões principais

1. **Java 25 sem preview + Spring Boot 4.1.1.** Projeto novo sem baseline corporativa informada. Boot 3.5.16 é alternativa conservadora condicionada a homologação e suporte. Versão corporativa aprovada prevalece; [pesquisa e comparação](00-research.md).
2. **MVC + Virtual Threads + SDK AWS síncrono**, com admissão limitada, limites por downstream e taxa explícita. CPU disponível não determina capacidade autorizada da AWS. Async/Netty/CRT somente quando medição justificar.
3. **Ports and Adapters + Command/Strategy/Registry**, composição de passos pequenos. Evitar DSL universal, hierarquia de classes e um novo framework batch completo.
4. **Get/Query/índice antes de Scan**, mas Scan paralelo é capacidade de primeira classe. Páginas e segmentos têm limites, checkpoint independente e backpressure; filtros não barateiam automaticamente leitura.
5. **Escrita passa por plano aprovado, identidade, allowlist, conditional write, ledger e reconciliação.** Sem credencial legítima, ambiente/conta/região/alvo exatos e confirmação, não escreve.
6. **Retomada é recuperação, não repetição cega.** Expiração de sessão interrompe admissão; efeitos incertos são reconciliados. Ledger local não cria atomicidade entre DynamoDB, SNS e outras APIs.
7. **CSV streaming primeiro; XLSX resumido opcional.** Evidência mínima, dados mascarados, reserva de disco, retenção e destino aprovados. Relatório nunca precisa acumular milhões de objetos na heap.
8. **HTTP Service Clients + RestClient**, sem Spring Cloud/OpenFeign inicial. Retry nativo AWS é dono de tentativas AWS; qualquer outro retry tem orçamento e idempotência explícitos.

## Caminho de adoção

Começar pelo skeleton local e uma operação read-only DEV/HML. Liberar o MVP de escrita apenas após ledger, write gate, dry-run vinculado a plano, canary e testes adversos de recuperação. Implementar os demais serviços conforme uso real. AIMD, hot tuning, transferência async e tuning especializado pertencem a ADVANCED. Ver [roadmap](20-implementation-roadmap.md).

## Fronteira corporativa

**VALIDAR NO AMBIENTE:** como o Break Glass fornece e revoga credenciais, JDK/Spring homologados, roles/contas/recursos permitidos, proxy/TLS, retenção, armazenamento de evidências, limites por ambiente e processo de aprovação. Nenhum detalhe interno foi inferido. Estar autenticado no AWS Toolkit do IntelliJ não comprova a identidade do processo Java; a verificação é feita pelo provider real e STS.

Fontes e datas ficam próximas das afirmações nos documentos técnicos; os exemplos usam nomes sintéticos e placeholders. Não há segredo, dado de cliente ou conta produtiva no código.
