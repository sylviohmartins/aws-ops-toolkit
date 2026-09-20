# Roadmap, dependências e critérios de pronto

**DECISÃO:** evoluir por capacidades demonstráveis, com uma pequena operação real por etapa. O entregável inicial é documentação e skeleton LOCAL. Classes de exemplo e planos não equivalem a MVP liberado para produção. Não estimar cronograma sem conhecer homologação, IAM, dependências corporativas e equipe.

## MVP, CORE e ADVANCED

| Nível | Escopo | Condição de liberação |
| --- | --- | --- |
| Entrega inicial | Arquitetura pesquisada, ADRs, blueprint, Maven/Java 25 e demonstração sintética LOCAL | Build/smoke local registrados; AWS produtiva permanece indisponível |
| MVP leitura segura | Identidade efetiva, allowlists, preflight, Get/Query/Scan limitado, CSV, cancelamento e checkpoint durável de leitura | Integração DEV/HML e incident simulation revisadas; autorização específica para leitura |
| MVP escrita segura | Uma operação estreita com dry-run, canary, aprovação vinculada, conditional writes, ledger, idempotência e reconciliação | Todos os gates de escrita abaixo aprovados; nenhuma dependência essencial marcada como blueprint |
| CORE | Adapters completos, modos typed/dinâmico, SQS/DLQ, SNS, Lambda, S3, HTTP, XLSX opcional, retomada e auditoria reutilizáveis | Cada capability tem contrato, testes de integração proporcionais e runbook |
| ADVANCED | AIMD, hot tuning, async seletivo/Transfer Manager, profiling especializado, tuning e checkpoint otimizado | Evidência de necessidade/ganho; segurança e recovery não regressam |

O MVP não precisa suportar todos os serviços antes de ser útil; uma ferramenta read-only confiável já reduz scripts descartáveis. Mas escrever em produção exige toda a cadeia de segurança, ainda que apenas uma tabela seja suportada.

## Fases

| Fase | Entregável | Depende de | Risco principal | Validação | Critério de pronto |
| --- | --- | --- | --- | --- | --- |
| 0 — Decisões e homologação | Inventário de requisitos, stack, ADRs, limites, fluxo corporativo | Donos técnicos/operacionais | Inferir política interna | Revisão de arquitetura e perguntas `VALIDAR NO AMBIENTE` | Nenhuma suposição crítica tratada como fato |
| 1 — Foundation | Wrapper, Java 25, Boot, properties tipadas, security local, logging, CI | 0 | Incompatibilidade/BOM/segredos | Build, análise estática, startup inválido/valido, bind loopback | Checkout limpo reproduz build; defaults sem escrita |
| 2 — Engine LOCAL | Registro de operações, REST 202, estado/progresso, bounded admission, cancelamento | 1 | Races, jobs órfãos, fila ilimitada | Smoke sintético e teste pequeno de lifecycle | Sem requisição longa aberta; estado terminal coerente |
| 3 — Auth e preflight AWS | Provider central, STS efetivo, allowlists, disco e alvos, health | 1–2 + acesso DEV | Divergência CLI/Toolkit/JVM | DEV/HML com profile inválido, role errada e sessão expirada | Falha cedo e mensagens sanitizadas |
| 4 — DynamoDB leitura | Typed/schemaless, Get/BatchGet/Query/Scan/parallel scan, rate/capacity | 3 | Custo, página vazia, reparticionamento | Dataset de várias páginas, cursor por segmento e cancelamento | Memória limitada, sem omitir páginas, orçamento respeitado |
| 5 — Reporting e ledger | CSV streaming, manifestos, pre-images mínimas, SQLite, cursores/eventos | 2–4 | Disco/atomicidade entre relatório e checkpoint | Falha de I/O, crash entre etapas, regeneração de relatório | Fonte de verdade durável e artefatos reconciliáveis |
| 6 — Escrita estreita | Guardrails, plano aprovado, conditional update, idempotência, canary, reconcile | 3–5 | Efeito duplicado/overwrite | Crash pós-write, conflito concorrente, auth mid-job | MVP escrita aprovado em HML; sem unknown oculto |
| 7 — HTTP integrações | RestClient/HTTP Interfaces, error mapping, pool/deadline/bulkhead | 2–3; antecede 6 se regra usar HTTP | Retry de ação não idempotente, 429/500 | Stub + integração autorizada | Contratos seguros, orçamento único de tentativas |
| 8 — SQS/SNS | Inspector, replay, consumidor temporário explícito, publish/batch/FIFO | 3,5,6 | Delete prematuro, duplicidade, impacto em consumidores | Queue/topic isolados, falhas por entrada, visibilidade | Ack/replay documentados e reconciliáveis |
| 9 — Lambda/S3 | Invocações, qualifier fixo, streaming/multipart e evidências | 3,5,6 | Sucesso de transporte confundido com negócio, destino indevido | FunctionError, timeout, multipart incompleto, permissão negada | Erros interpretados e artefatos protegidos |
| 10 — Recovery integrado | Auth pause/resume, crash recovery, locks, versão de plano, outbox quando necessária | 5–9 conforme operação | Dual write e execução dupla | Matriz A–K, Stop forçado e duas instâncias | Nenhum avanço sem evidência e nenhuma retomada cega |
| 11 — Performance e XLSX | Lab, sizing, G1/ZGC, CSV gzip/SXSSF sob demanda | 4–10 | Otimização prematura, temporários enormes | Sweep de concorrência e profiling | Configuração recomendada sustentada por dados |
| 12 — Cenário pagamentos | Operação composta fictícia e runbook final | 6–11 | Regras conflitantes entre serviços | Injeções do cenário e revisão do owner | Reconciliação e evidências completas, pendências explícitas |
| 13 — Recursos avançados | AIMD/hot tuning/async seletivo | 11–12 | Oscilação e aumento de complexidade | A/B HML, rollout limitado, rollback de configuração | Benefício mensurável e operação compreensível |

Recovery não é uma feature a adiar até a fase 10: persistência e idempotência são construídas antes da primeira escrita; fase 10 endurece a composição. O caminho crítico de uma correção DynamoDB+HTTP é `0→1→2→3→4→5→7→6→10`; serviços não usados não bloqueiam esse MVP.

## Gates de produção

1. **Build:** Java/BOMs/plugin versions homologados, análise estática proporcional, dependências verificadas, executável reproduzível e revisionado.
2. **Segurança:** loopback/autenticação, perfil/STS/conta/região/recurso, confirmação de plano, incident/change, least privilege e sanitização.
3. **Correção:** seleção/transformação compartilhadas com dry-run; conditional write; marker/idempotência verificável; nenhuma operação não idempotente repetida automaticamente.
4. **Durabilidade:** ledger validado com crash; checkpoint por segmento; bloqueio de segunda instância/job concorrente; relatório regenerável; unknown reconciliável; storage aprovado.
5. **Operação:** canary, error budget, cancelamento, auth recovery, disco, runbook, owner e limite de volume/taxa.
6. **Evidência:** integração HML com casos adversos, critérios pós-operação e aceite do responsável corporativo. Não marcar gate concluído com base em diagrama.

Testes automatizados pequenos oferecem benefício alto para transições de estado, verificação da conta, recusa de escrita sem autorização, cursor não avançado na falha, idempotency key estável e CSV malicioso/escaping. Evitar centenas de testes de getters/mocks que espelham implementação; priorizar smoke, integração e fault injection.

## Reutilização e mudança

Antes da war room devem estar prontos, no nível liberado: clients/provider, preflight, gateways permitidos, engine, limits, retry classifier, ledger/checkpoint, report/audit e métricas. Durante incidente, a operação nova especifica input, seleção, validação, transformação, efeitos e reconciliação; não recria infraestrutura. Nova operação que exige exceção aos guardrails vai para revisão, não expande permissões automaticamente.

Branches curtas `<tipo>/<assunto>`, commits atômicos `docs:`, `feat:`, `fix:`, `test:`, `build:` e `chore:`; PR com problema/comportamento/validação/limitações; merge após revisão e checks; release/tag com changelog. Respeitar política corporativa se divergir. Incidentes podem acelerar revisão, mas não justificar force-push na principal, commit de dados ou bypass silencioso de checks.
