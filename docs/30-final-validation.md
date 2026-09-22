# Revisão final, evidências e critérios de aceitação

Esta revisão cobre **o desenho** solicitado e explicita o que o skeleton prova. Leitura de código, revisão teórica, build, teste automatizado, smoke e integração AWS são evidências diferentes. Nenhuma linha desta matriz significa teste em produção.

## Revisão teórica A–K

| Cenário | Controle no desenho completo | Invariante e reação esperada | Validação ainda necessária |
| --- | --- | --- | --- |
| A — 10 milhões no DynamoDB | Paginação, admissão antes de submeter, fila/bytes limitados e cursores por segmento | Memória depende da janela, nunca do total; página vazia com cursor continua | **Baseline local 1M/5M/10M concluída sem OOM**; Scan/Query contra DynamoDB DEV/HML real ainda deve medir consumed capacity, latência e heap |
| B — 500 mil alterações | Plano, dry-run, canary, conditional writes, ledger e rate/error budget | Nenhum efeito sem intenção/guardrail; nenhum avanço sobre item desconhecido | HML com escrita sintética, falhas e reconciliação |
| C — Credencial expira em 48% | Barreira global de admissão, drenagem por deadline, checkpoint e `AUTHENTICATION_REQUIRED` | Novas escritas param; retomada exige identidade autorizada e reconciliação | Provider real, expiração/renovação legítima e mudança de role |
| D — DynamoDB throttla | Retry SDK limitado, orçamento de taxa/capacidade e AIMD de taxa **e concorrência** | Retry não multiplica; pressão cai e persistência permanece consistente | AIMD/retries têm testes locais; throttling/quota/consumed capacity reais continuam DEV/HML isolado |
| E — HTTP 429 | Classificação, `Retry-After` limitado por deadline, rate limiter e bulkhead | Não criar fila ilimitada nem reexecutar ação não idempotente | Stub/HML com rajadas e cancelamento durante espera |
| F — HTTP 500 | Retry só seguro, circuit breaker/budget, bloqueio de enriquecimento inválido | Sem fallback inventando confirmação; falha persistente pausa | Stub de falha e verificação de zero escrita indevida |
| G — Disco esgota | Estimativa/reserva/monitoramento, ledger autoridade e checkpoint conservador | Sem novas escritas quando evidência não pode persistir; relatório pode ficar parcial | Filesystem/quota de teste; crash no ponto de falha e recuperação |
| H — Stop no IntelliJ | Graceful shutdown quando possível; estado durável durante o job | Término forçado não depende de hook; detectar interrupção e unknowns | Smoke local força crash/restart e valida recuperação; encerramento pelo IDE/OS e filesystem corporativo ainda merecem ensaio DEV/HML |
| I — Mesmo job retomado | Lock, plano/schema/versão imutáveis, ledger e idempotency keys | Não repetir efeitos confirmados; cursor é posição segura, não prova de efeito | Retomar após crash e tentativa por segunda instância |
| J — Alteração concorrente | ConditionExpression de versão/estado e update mínimo | Conflito não sobrescreve estado alheio; reavaliar ou separar | `DockerLabTest.concurrentDynamoChangeIsDetectedBeforeDownstreamEffects` passou; writers concorrentes contra DynamoDB real continuam DEV/HML |
| K — Conta errada | STS com provider efetivo, allowlists e comparação de alvo antes da escrita | Falha fechada; profile/role selecionado não é prova suficiente | Credenciais válidas para conta divergente e recursos de nome igual |

Essas respostas só valem quando todos os componentes dependentes estiverem implementados e homologados. Caso B também exige resolver a fronteira update+evento do [cenário complexo](18-complex-scenario.md). Caso G não promete checkpoint final se o disco já falhou. Caso H não promete cleanup após encerramento forçado. Caso I não confunde JSON atômico local com transação distribuída.

## Falhas de desenho identificadas e corrigidas no blueprint

| Fragilidade inicial possível | Correção incorporada |
| --- | --- |
| “VT resolve backpressure” | Admissão anterior à submissão, limits por recurso e fila/bytes limitados |
| “Mudar segmentos adapta Scan” | Particionamento fixo por job; apenas quantidade ativa/taxa variam |
| “Checkpoint após receber página” | Cursor avança depois de desfechos confirmados, com replay idempotente |
| “SQLite torna AWS + CSV + SNS atômico” | Separar ledger, projeção regenerável e outbox/reconciliação remota |
| “Timeout significa que não escreveu” | Resultado desconhecido, leitura de marcador e decisão por evidência |
| “SSO/Toolkit logado implica JVM autorizada” | STS no provider real e revalidação após renovação |
| “Stop sempre faz flush” | Recuperação baseada em persistência já feita; hooks são melhor esforço |
| “XLSX suporta a massa inteira em heap” | CSV primário e SXSSF resumido, limite de worksheet e temporários |
| “Relatório local é inviolável” | Auditoria correlacionada e armazenamento corporativo; checksum não é não repúdio |

## Evidência disponível no código inicial

Inspeção de código não é registro de execução. Este inventário foi conferido nos arquivos da entrega; resultados executados devem constar da próxima seção.

| Capacidade | O que existe | Limite explícito |
| --- | --- | --- |
| Foundation | POM Java 25/Boot 4.1.1/SDK BOM, Wrapper, profiles e properties | Homologação corporativa continua pendente |
| API local | Controle autenticado, bind loopback e operação assíncrona | Sem UI/browser e sem exposição remota |
| Operação de exemplo | `synthetic-inventory`, dados determinísticos e `DRY_RUN` LOCAL | Não lê nem corrige pagamentos AWS |
| Admissão | Foundation limita jobs; runtime admite um job operacional ativo, usa virtual threads, rate limit e AIMD de taxa+concorrência | Limites sustentáveis contra AWS real dependem de DEV/HML |
| Pause/cancel/resume | Estados locais, pausa/cancelamento cooperativos e resume de PAUSED/INTERRUPTED | FAILED/CANCELLED não são retomados pela implementação inicial |
| Persistência | Foundation mantém JSON/chunks; runtime usa SQLite WAL/FULL com jobs, cursores, tasks, effects e audit, intenção durável antes do efeito | Não há transação distribuída com AWS nem garantia universal de power-loss/filesystem corporativo |
| Relatório | Foundation CSV; runtime gera plano sanitizado, dry-run before/after, erros, summary, manifesto, CSV streaming e XLSX/SXSSF | Destino corporativo, cadeia de custódia e política final de colunas/PII dependem do ambiente |
| Disco | Estimativa sintética e reserva verificada no início/commit | Falha de storage pode deixar FAILED e exigir inspeção; não há recovery produtivo completo |
| Observabilidade | Logs estruturados, Actuator, audit SQLite e métricas de dispatch, registros, capacity, AWS/HTTP requests, failures, retries, throttling, latência e transições | OTel/Datadog/dashboard/alertas corporativos continuam dependentes do ambiente |
| AWS/HTTP | Workflows concretos para DynamoDB/SQS/SNS/Lambda/S3/HTTP, guardrails, ledger e laboratório Moto/HTTP exercitável | IAM/SSO/Break Glass, quotas, proxy/TLS e semântica real dos downstreams continuam `VALIDAR NO AMBIENTE` |
| Testes pequenos | Arquivos focados em safety, checkpoint e CSV | Resultado depende de execução do build, registrado separadamente |

## Validação executada: registro factual

Validação local atualizada em **2026-09-22**, Windows 11, Oracle JDK **25.0.4.1** em `C:\Development\tools\java\jdk-25.0.4.1`, Maven Wrapper **3.9.12** e Spring Boot **4.1.1**. A pesquisa principal foi realizada em 18/09, com conferências complementares posteriores. Os resultados abaixo foram observados localmente, sem acesso a serviços AWS reais; Moto e a API HTTP sintética são laboratório, não homologação AWS.

| Verificação | Registro nesta revisão |
| --- | --- |
| Pesquisa oficial e revisão dos requisitos | Realizadas para a documentação, fontes inline e decisões separadas de hipóteses |
| Inspeção de escopo do código | Realizada; limitações listadas acima |
| Build/compilação/formatação/testes | `mvnw.cmd -B -ntp verify -Pstatic-analysis -Dtoolkit.lab=true`: BUILD SUCCESS; **68 testes**, zero falhas/erros e **2 skips**, exclusivamente os benchmarks condicionais já executados separadamente; release 25 sem preview. Os **6 `DockerLabTest`** passaram no mesmo `verify`; Spotless confirmou **105 arquivos Java** limpos |
| Análise estática | SpotBugs 4.10.4.1, effort Max, threshold High: zero findings e zero erros da ferramenta; não equivale a auditoria de vulnerabilidades |
| Startup positivo e smoke LOCAL | Foundation `scripts/smoke.ps1`: PASS no JAR final após validar startup, auth/origin, write denial, admission, CSV, pause, crash/resume e cancelamento; evidência `target/smoke/74623293-f171-43f1-a3cb-a7ff74266ba1`. Runtime `scripts/lab-smoke.ps1`: PASS no mesmo JAR para `DRY_RUN` sem efeitos, plano/hash, pause, crash/restart, aprovação, canary, promoção, CSV/XLSX e manifesto; evidência `target/lab-smoke/8c6fdce9-437d-4c69-b278-81bbb11395c4` |
| Startup negativo / gates de escrita | Ausência de token continua fail-closed. `toolkit.core.write-enabled=true` deixou de ser erro de startup porque é agora um dos dois gates deliberados do runtime; fora de LOCAL, efeitos exigem também `operations.writes=true`, request `EXECUTE`, confirmação, referência operacional, motivo, plano selado e aprovação vigente. Testes regressivos cobrem gates ausentes |
| Extensibilidade de nova operação/tabela/relatório | `WorkflowExtensionRegistrationTest`, `ExtensionModelExampleTest`, `DynamoTableGatewayFactoryTest`, `BatchProcessorTest` e `CsvReportWriterTest` comprovam discovery sem alteração do core, extensão tipada de tabela, batching bounded e reporting reutilizável |
| Documentação | `python scripts/check-docs.py`: **54 documentos Markdown**, links locais e blocos de código balanceados aprovados; `python scripts/check-complement.py`: **64 properties cobertas**, 7 docs requeridos, 7 componentes reutilizáveis, placeholder de token consistente e nenhum Utils genérico/@Value/credencial/recurso AWS/tuning operacional alvo hardcoded detectado; `git diff --check` aprovado |
| CI | Workflow Windows/Java25 entregue com Actions fixadas por SHA; execução no GitHub não realizada |
| Benchmark de milhões de itens | `scripts/benchmark.ps1` executado com `-Xmx256m` após remover um `COUNT(*)` global por página: 1M = 1.000.000/1.000.000, ~27,64 s, pico ~46,0 MiB; 5M = 5.000.000/5.000.000, ~142,40 s, pico ~43,9 MiB; 10M = 10.000.000/10.000.000, ~320,48 s, pico ~45,9 MiB. Crescimento de heap ficou ~31,8–33,8 MiB e não acompanhou linearmente o volume. O sweep local 1/4/8/16/32/64/128/256 também passou; não houve platô até 256 no workload sintético, portanto nenhum desses valores é teto/recomendação AWS |
| Integração AWS/HTTP | Laboratório Docker/Moto + API HTTP sintética executado: **6/6 `DockerLabTest` PASS** e `lab-smoke` PASS. DEV/HML contra AWS e downstreams reais continua não executado |
| Teste destrutivo ou escrita de produção | Não executado; não autorizado por esta documentação |
| Homologação corporativa/Break Glass | `VALIDAR NO AMBIENTE` |

Testes focados cobrem paginação vazia com cursor, falha de callback sem avanço de checkpoint, conta incorreta, token AWS expirado, bypass de retenção recusado, bloqueios LOCAL/DRY_RUN/escrita, disco, parâmetros inválidos, replay de chunk órfão, versão incompatível, corrida pause/resume, regra encerrada incompleta e escaping CSV. Não foi criada uma suíte extensa artificial.

Durante a revisão foram corrigidos: ownership do worker na transição PAUSED/resume, vínculo de checkpoint à versão da regra, retorno prematuro marcado incorretamente como conclusão, autenticação em redispatch assíncrono de CSV e Content-Type do relatório. O build e o smoke foram repetidos após as correções.

O JDK 25 usado nesta rodada é `C:\\Development\\tools\\java\\jdk-25.0.4.1`. O Java padrão da máquina não foi alterado; os comandos de validação apontaram `JAVA_HOME` e `PATH` apenas para o processo executado. A resolução Maven nesta máquina utilizou o trust store Windows via opções locais de JVM para confiar nas raízes já autorizadas pelo sistema; a validação TLS não foi desabilitada e essa configuração específica não foi colocada no POM. Spotless emitiu aviso de API `sun.misc.Unsafe` em dependência de tooling, sem falha de formatação/compilação; acompanhar atualização do plugin. Artefatos brutos de validação estão em `target/` e logs ignorados pelo Git.

## Checklist para primeira utilização segura

Marcar itens de liberação somente com evidência. A lista é gate futuro, não promessa de implementação atual.

- [ ] JDK, Boot, SDK, Maven, dependências e plugins homologados; build limpo reproduzível.
- [ ] Execução local autenticada, loopback, logs sanitizados, secrets fora de código/artefatos.
- [ ] Provider efetivo, conta, role, região e todos os recursos validados em DEV/HML.
- [ ] Operação tipada com regra revisada, dry-run, aprovação do plano e canary.
- [ ] Paginação/Scan limitada, capacidade, cancelamento e error budget ensaiados.
- [ ] Escrita mínima condicional, idempotência verificável e estratégia para unknowns.
- [ ] Ledger/checkpoint/relatório validados contra crash, storage failure e resume.
- [ ] Efeitos em SQS/SNS/Lambda/HTTP consistentes conforme contrato, sem retry inseguro.
- [ ] CSV/XLSX/privacy/disco/retention e eventual destino S3 aprovados.
- [ ] Reconciliação pós-operação, pendências explícitas e responsável pela decisão final.
- [ ] Cenários A–K exercitados conforme escopo da versão e evidências preservadas.
- [ ] Runbook usado por outro engenheiro e caminho de cancelamento/recuperação compreendido.

Critério de conclusão documental: um engenheiro encontra decisões, contratos, classes exemplificadas, fontes, riscos e plano de implementação sem precisar inferir uma arquitetura inteira. Critério de liberação operacional: capacidades realmente implementadas e validadas para o ambiente autorizado. A documentação atende ao primeiro; o segundo depende da execução incremental do [roadmap](20-implementation-roadmap.md).
