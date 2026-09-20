# Riscos, limites e anti-padrões

Esta análise distingue mitigação proposta de implementação disponível. A versão inicial é demonstração LOCAL e não elimina os riscos abaixo em operações AWS reais. Evidências de aceitação estão planejadas no [checklist](30-final-validation.md).

## Registro de riscos

| Risco | Impacto | Mitigação de desenho | Risco residual / responsável |
| --- | --- | --- | --- |
| Conta/role/recurso errados | Alteração no ambiente errado | Identidade efetiva, allowlists, alvo imutável e confirmação vinculada ao plano | STS não prova permissão de toda ação; owner IAM valida escopo |
| Credencial expira ou muda | Lote parcialmente aplicado | Barreira de auth, checkpoint, validação de nova sessão e reconciliação | Provider pode não expor expiração; erro remoto ainda é autoridade |
| Timeout após aceite remoto | Duplicação/perda aparente | Intenção durável, status desconhecido e marcador verificável | Sem idempotência downstream, bloquear retry ou exigir decisão manual |
| DynamoDB + SNS/SQS sem transação comum | Registro corrigido sem evento ou duplicado | Outbox/CDC aprovada, eventId e dedupe no consumidor | Ledger local sozinho não resolve dual write |
| Scan sobre tabela mutável | Candidatos omitidos/alterados no tempo | Janela de negócio, condições e reconciliação | Não há snapshot transacional; owner define completude |
| Carga excessiva | Incidente piora | Admissão, taxa/capacidade, bulkheads, error budget e canary | Quota compartilhada e hot partitions exigem métricas do serviço |
| Report/checkpoint falha | Evidência incompleta ou replay incorreto | Ledger como autoridade, cursores conservadores, projeção regenerável | Não há garantia sem armazenamento persistente confiável |
| Disco cheio/perdido | Pausa, perda de histórico local | Estimativa, reserva, monitoramento, destino aprovado | Falha física requer evidência remota/idempotência no sistema alvo |
| Duas instâncias retomam o job | Corridas e efeito duplicado | Lock/lease local e guardas idempotentes remotas | Lock local não coordena duas máquinas sem mecanismo compartilhado |
| Dados sensíveis em report/log/JFR | Exposição e retenção indevida | Colunas mínimas, ACL/criptografia, masking, limpeza conforme política | Política e classificação corporativas não inferíveis |
| CSV com fórmula maliciosa | Execução/exfiltração ao abrir | Sanitização específica do leitor, XLSX textual quando apropriado | Salvar/reabrir pode invalidar defesa; não existe sanitização universal |
| Dependência desatualizada ou incompatível | Falha de build/runtime/vulnerabilidade | BOM, pinning, checks e atualização revisada | Repositório interno, licenças e SLA precisam homologação |
| Término forçado | Hooks não executam | Persistência durante fluxo; detectar interrupção na inicialização | Chamada em voo pode ter terminado após morte local |
| Rollback cego | Sobrescreve alteração legítima | Nova operação condicional/compensação | Eventos e efeitos financeiros podem ser irreversíveis |
| Automação adaptativa instável | Oscilação, burst e troubleshooting difícil | AIMD com teto/cooldown e piloto HML | Implementar somente após baseline manual confiável |

Fundamentos externos: [Scan sem snapshot](https://docs.aws.amazon.com/amazondynamodb/latest/APIReference/API_Scan.html), [outbox e dual write](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html), [SQLite WAL](https://www.sqlite.org/wal.html), [CSV Injection](https://owasp.org/www-community/attacks/CSV_Injection). Os controles descritos são decisões do toolkit, não garantias automáticas dessas plataformas.

## Proibido ou desaconselhado

| Anti-padrão | Substituição |
| --- | --- |
| `scanAll().collect(toList())`, mapas globais e listas de milhões de futures | Páginas, admissão anterior à submissão e estado em disco |
| VT/pool/`parallelStream()` sem limite downstream | Semáforo, taxa, conexões e fila limitados |
| Client AWS por item | Client reutilizável por configuração/provider, fechado no lifecycle |
| Retry infinito ou retry SDK × adapter × engine | Um responsável por tentativa; deadline e orçamento total |
| `catch (Exception) {}` ou continuar após falha sistêmica | Classificação, estado explícito e pausa fail-safe |
| Token/access key em YAML, Git, URL, log ou relatório | Provider corporativo e redação por allowlist |
| Scan sem paginação/rate limit | Cursor por segmento, capacidade medida, budgets |
| Tratar filtro/projeção como desconto automático de leitura | Planejar Query/índice e consumo real |
| `BatchWriteItem` como batch update ou ignorar `UnprocessedItems` | Update condicional e retry apenas das entradas não processadas |
| Overwrite do item inteiro para mudar um atributo | Update mínimo com condição de versão/estado |
| Avançar checkpoint após ler página, antes de confirmar itens | Commit somente após desfechos duráveis |
| Mudar `TotalSegments` ou regra no resume | Hash e particionamento imutáveis; novo plano quando necessário |
| Retry de POST/escrita de resultado desconhecido | Idempotency key verificável ou reconciliação |
| Delete SQS durante inspeção ou antes do efeito confirmado | Visibilidade explícita e ack conforme política |
| Sucesso SDK/HTTP tratado como sucesso lógico Lambda ou batch inteiro | Inspecionar erro de função/payload e resultado por entrada |
| Produção com write flag isolada | Identidade + alvo + modo + autorização + plano + canary |
| DRY_RUN que publica, invoca com efeito ou apaga mensagem | Sinks sem efeitos e relatório de proposta |
| Audit trail somente em fila de logs descartável | Ledger durável antes/depois de efeitos |
| `operationId`/ID de pagamento em tags Micrometer | Tags finitas; detalhes em ledger/log restrito |
| Ignorar disco de temporários SXSSF/gzip/ledger WAL | Estimativa de pico e reserva por volume |
| Framework de plugins/reflection/DSL genérica durante incidente | Contratos tipados pequenos e operação registrada estaticamente |
| Endpoint aceitando URL/ARN/expressão arbitrários | Alvos e parâmetros tipados validados/allowlisted |
| Desativar TLS/proxy corporativo para “resolver conectividade” | Diagnóstico e configuração homologada |
| Prometer exactly-once ou rollback universal | Documentar at-least-once, idempotência e compensação limitada |

## Trade-offs aceitos

Síncrono com VT troca eventual ganho async por fluxo mais legível. Checkpoint por página pode repetir leituras, mas simplifica confirmação. SQLite adiciona dependência e disciplina de migração em troca de transação local pesquisável. CSV prioriza escala sobre apresentação. Outbox pode exigir mudança coordenada no sistema alvo; não esconder esse custo atrás de abstração. A base sem preview evita dependência de API instável durante correção produtiva.

**VALIDAR NO AMBIENTE:** distribuição Java homologada, política de suporte Boot, mirrors Maven, processo Break Glass, endpoint/proxy/TLS, roles, budget AWS/HTTP, requisitos de evidência, uso de SQLite/nativo, criptografia, retention, S3/KMS e mecanismos downstream de idempotência. Ausência de informação restringe a capacidade liberada, não autoriza supor defaults corporativos.
