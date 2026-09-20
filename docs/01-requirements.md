# Requisitos e critérios de aceite

Projeto: **aws-ops-toolkit**. Pesquisa consultada em **2026-09-18**. Este documento define requisitos do produto alvo; a entrega de código atual é um skeleton local demonstrativo e não uma autorização de uso produtivo.

## Convenções de evidência e escopo

- **FATO**: comportamento da plataforma acompanhado de fonte oficial.
- **DECISÃO**: desenho proposto para este projeto; deve ser confirmado por validação.
- **HIPÓTESE**: premissa ainda não comprovada no ambiente real.
- **VALIDAR NO AMBIENTE / ITAÚ**: mecanismo, política, acesso ou homologação corporativa que não pode ser inferida externamente.

O produto deve permitir adicionar uma operação por composição de `selecionar → validar → transformar → executar → verificar`, reutilizando infraestrutura preparada antes do incidente. O alvo é throughput sustentável, com limites demonstráveis, evidência de resultado e recuperação consciente de efeitos parciais.

## Requisitos funcionais

| ID | Requisito | Aceite observável no produto alvo |
|---|---|---|
| RF01 | Operações registradas, tipadas e versionadas | Tipo desconhecido e parâmetros inválidos rejeitados antes de acessar AWS; uma operação nova implementa contrato pequeno |
| RF02 | REST local assíncrono | Início retorna `202` com ID durável, status consultável e requisição encerrada antes do processamento extenso |
| RF03 | Dry-run obrigatório para escrita | Mesmo seletor/transformador de EXECUTE, sem side effects; relatório mostra candidatos, descartes, mudanças e estimativas |
| RF04 | Identidade verificável | Conta, região, papel autorizado, recursos e origem de credenciais validados no preflight e na retomada |
| RF05 | Escrita explicitamente liberada | Flag, modo, aprovação do plano, metadados de incidente e permissão real simultaneamente necessários |
| RF06 | DynamoDB completo | Get/BatchGet/Query/Scan/parallel scan; atualizações condicionais, batch put/delete e transações quando cabíveis |
| RF07 | Outros adaptadores | SQS/DLQ, SNS, Lambda, S3 e REST com políticas de efeitos, erros e idempotência explícitas |
| RF08 | Pausa/cancelamento cooperativos | Para nova admissão, resolve ou registra in-flight, checkpoint e relatório parcial; sem `Thread.stop()` |
| RF09 | Retomada durável | Revalida identidade, plano e versão; usa cursores por segmento e ledger por item/etapa sem repetir efeito confirmado |
| RF10 | Relatórios streaming | CSV detalhado, XLSX de resumo e manifesto; tamanho de heap limitado independentemente do total de registros |
| RF11 | Auditoria | Relação entre incidente, versão, operador, alvo, decisão, etapa e request ID, sem credenciais ou payload integral desnecessário |
| RF12 | Expiração legítima | Interrompe novas escritas, preserva incertezas e checkpoint; só retoma após autenticação legítima e novo preflight |
| RF13 | Reconciliação | Resultado remoto incerto não é contado como sucesso; plano explícito para comprovar, repetir com segurança ou encaminhar revisão |
| RF14 | Canary e limites | `maxItems`, limites de chamadas, taxa, concorrência, duração e orçamento de erro congelados no plano |
| RF15 | Segurança de disco | Reserva antes de começar, monitoração durante execução e falha fechada se evidências não puderem ser gravadas |

## Requisitos não funcionais

| ID | Requisito | Critério de validação |
|---|---|---|
| RNF01 | Java 25 sem preview | `release=25`; build e execução sem `--enable-preview` |
| RNF02 | Memória limitada | Nenhuma lista/fila/future proporcional a milhões de itens; benchmark registra heap estável após aquecimento |
| RNF03 | Pressão controlada | Permits e taxa por downstream/recurso; conexão e espera na fila com timeout |
| RNF04 | Recuperabilidade | Ensaio de interrupção de processo entre cada transição durável; relatório distingue confirmado, falho e incerto |
| RNF05 | Segurança local | Bind `127.0.0.1`, autenticação efêmera, CORS fechado, diretórios privados e exposição mínima do Actuator |
| RNF06 | Auditabilidade | Artefatos com schema/versionamento/hash e trilha preservada; armazenamento corporativo quando autorizado |
| RNF07 | Extensibilidade pragmática | Regra não cria client, executor, retry, arquivo ou credencial; utiliza portas existentes |
| RNF08 | Observabilidade proporcional | Métricas agregadas, logs estruturados de transições/erros e amostragem; sem milhões de logs de sucesso |
| RNF09 | Build reproduzível | Maven Wrapper, BOMs, dependências fixadas, análise estática e commits revisáveis |
| RNF10 | Fail-safe | Qualquer dúvida de identidade, alvo, schema ou durabilidade bloqueia novas escritas |

Não há SLA de `items/s` universal. Latência, tamanho de item, capacidade AWS, APIs externas e política produtiva definem o teto. Os números do laboratório são resultados do cenário medido, nunca garantia para produção.

## Separação entre entregas

| Nível | Conteúdo | Condição para uso |
|---|---|---|
| Skeleton entregue | API local, operação sintética, engine demonstrativa, exemplos de clientes/adaptadores e persistência JSON limitada | Somente validação local e aprendizado da arquitetura |
| MVP seguro | Leitura AWS em DEV/HML, preflight real, limites, relatórios, checkpoints, pausa/cancelamento, integração validada | Homologação e ensaios de falha; escrita continua desligada |
| CORE de escrita | Ledger transacional, idempotência por etapa, canary, confirmação do plano, reconciliação e trilha de auditoria | Evidência de integração e revisão operacional, inclusive dos resultados ambíguos |
| ADVANCED | Controle adaptativo, tuning durante execução, maior paralelismo e otimização com profiling | Necessidade observada e limites comprovados |

**DECISÃO:** não confundir exemplos compiláveis de adapters com capacidade autorizada de produção. Alterar `writeEnabled` sozinho nunca torna o skeleton uma ferramenta produtiva.

## Fora do escopo

Criação de um sistema corporativo de Break Glass; implementação de bypass de IAM/SSO; acesso depois do término da autorização; gestão permanente de access keys; agendamento distribuído; multiusuário remoto; exactly-once universal entre serviços; rollback automático irrestrito; implantação cloud da aplicação local; benchmark destrutivo em PROD.

## Premissas a resolver antes do primeiro uso AWS

1. **VALIDAR NO AMBIENTE / ITAÚ:** distribuição/patch Java e linha Spring homologadas, suporte e aprovação de dependências.
2. **VALIDAR NO AMBIENTE / ITAÚ:** portal SSO/processo de credencial, roles, contas, regiões, permissão Break Glass, duração e revogação.
3. **VALIDAR NO AMBIENTE / ITAÚ:** acesso de rede/proxy/TLS, audit trail, retenção, criptografia e diretório local permitido.
4. **HIPÓTESE:** downstream permite chaves de idempotência ou consulta confiável de resultados; caso contrário operações não repetíveis exigirão reconciliação manual.
5. **HIPÓTESE:** tabelas suportam versionamento/condições úteis à correção; ausência disso exige redesenho da regra antes de escrever.

As premissas não são permissões. IAM continua sendo a barreira de autorização efetiva; menor privilégio e credenciais temporárias seguem as [boas práticas oficiais IAM](https://docs.aws.amazon.com/IAM/latest/UserGuide/best-practices.html).

## Critério de liberação

Build, análise estática, smoke local e integração DEV/HML precedem dry-run, canary e operação. Para escrita são indispensáveis ensaios de crash, expiração, disco cheio, conflito e repetição de requisição. Uma pequena suíte focada nas transições duráveis e no write gate tem benefício maior que centenas de testes que reproduzam getters.
