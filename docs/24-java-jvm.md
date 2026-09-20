# Java 25, JVM e profiling

Pesquisa em 18/09/2026, usando documentação específica do Java 25. **DECISÃO:** compilar com `release=25`, executar em distribuição Java 25 homologada e sem `--enable-preview`. O `pom.xml` fixa a major e o runtime real de cada validação deve constar da evidência. Não confundir versão de linguagem com SLA de suporte de um fornecedor.

## Recursos e status

| Recurso | Status no Java 25 | Aplicação no toolkit |
| --- | --- | --- |
| Records, sealed types, switch expressions e pattern matching já finalizado | Estáveis | Inputs/contextos imutáveis, resultados tipados e estados claros; sem hierarquia excessiva |
| Virtual Threads | Estável | Execução de jobs e chamadas síncronas; limites externos continuam obrigatórios |
| Scoped Values | Final no Java 25, JEP 506 | Contexto imutável no escopo da operação/tarefa |
| Structured Concurrency | Quinta preview, JEP 505 | Fora da base produtiva; experimento isolado se futuramente justificado |
| Primitive types em patterns/instanceof/switch | Terceira preview, JEP 507 | Não usar |
| Stable Values | Preview, JEP 502 | Não usar; `final` e inicialização explícita bastam |
| PEM encodings | Preview, JEP 470 | Não usar; credenciais ficam no provider corporativo |
| Vector API | Décima incubação, JEP 508 | Sem necessidade no workload de I/O |
| Module imports, compact source files/instance main e flexible constructors | Finalizados no Java 25 | Sem adoção automática; classes/pacotes explícitos favorecem manutenção |
| Compact Object Headers | Feature de produto, JEP 519 | Avaliar apenas por benchmark/compatibilidade, sem flag preventiva |
| JFR CPU-time profiling | Experimental e direcionado a Linux, JEP 509 | Não pressupor disponível no Windows nem ativar experimental na base |

Os estados novos/preview/incubação acima constam das [release notes oficiais Java 25](https://www.oracle.com/java/technologies/javase/25-relnote-issues.html). Features estáveis não implicam benefício automático; o desenho privilegia clareza da regra e comportamento verificável.

## Contexto e ciclo de vida de tarefas

O skeleton faz binding explícito do `operationId` em `ScopedValue` dentro da tarefa. Na evolução, um `record` com metadados imutáveis pode transportar contexto comum. Executor/future arbitrário não herda bindings como uma árvore estruturada: a tarefa filha recebe contexto explicitamente e estabelece seu binding. MDC e tracing também precisam de ponte própria. [Java 25 ScopedValue API](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/ScopedValue.html).

Sem Structured Concurrency, a engine deve acompanhar futures/tarefas admitidos, fechar admissão, drenar por deadline e observar todas as falhas. Não ignorar exceção de tarefa por nunca chamar `get`/join nem manter fan-out sem dono. A API preview poderia ajudar no ciclo de vida, mas não elimina idempotência remota, rollback ou necessidade de limites. [Oracle: Structured Concurrency](https://docs.oracle.com/en/java/javase/25/core/structured-concurrency.html).

Java 25 já incorpora a melhoria que remove pinning por monitores `synchronized` na situação histórica do Java 21. Não trocar todos os monitores por `ReentrantLock` sem evidência. Bloqueio nativo/foreign ainda pode prender carrier; contenção de lock continua relevante mesmo sem pinning. Observar JFR antes de mudar código. [Oracle: Virtual Threads no Java 25](https://docs.oracle.com/en/java/javase/25/core/virtual-threads.html).

`spring.threads.virtual.enabled=true` configura recursos suportados do Spring; a engine mantém executor explícito para seus jobs. Propriedades de pools antigos não se transformam automaticamente em limitadores de VT. `spring.main.keep-alive=true` pode ser pertinente a modo sem servidor/scheduler porque VT é daemon; não substituir controle explícito de lifecycle. [Spring Boot: Virtual Threads](https://docs.spring.io/spring-boot/reference/features/spring-application.html).

## Heap e GC

**FATO:** G1 é o default em grande parte das configurações; ZGC é opção para baixa latência. **DECISÃO:** iniciar com ergonomia/G1 e heap moderado; comparar ZGC se pausas forem limitantes. Throughput, RSS, CPU de GC e tamanho do conjunto vivo entram na decisão. A documentação recomenda começar pela ergonomia, ajustando com medição. Não prometer limite de pausa observado no toolkit apenas a partir da descrição do coletor. [Oracle: coletores Java 25](https://docs.oracle.com/en/java/javase/25/gctuning/available-collectors.html).

| Máquina de referência | Perfil inicial de laboratório | Justificativa/observação |
| --- | --- | --- |
| 8 cores / 16 GiB | `-Xmx2g`, GC default, 1 job, 2–4 chamadas por destino | Reserva para IntelliJ, SO, buffers nativos e cache do filesystem |
| 16 cores / 32 GiB | `-Xmx4g`, GC default, 1–2 jobs, teto de 8 chamadas inicialmente | Aumentar somente após medir downstream e heap vivo |
| 32 cores / 64 GiB | `-Xmx8g`, GC default, 1–2 jobs, teto de 16 chamadas inicialmente | Muitos cores não autorizam 100 segmentos simultâneos |

São **HIPÓTESES de ensaio**, não defaults aprovados nem requisitos mínimos. Uma operação paginada com milhões de itens pode usar menos heap que isso; quantidade total de registros não determina heap necessário. `Xms` fica na ergonomia inicialmente. O processo usa memória além do heap (metaspace, stacks, buffers, bibliotecas nativas); manter folga de RAM e medir RSS. Não usar todo o disco/RAM disponíveis nem desativar limites para perseguir records/s.

Para uma comparação isolada, repetir workload com `-XX:+UseZGC` mantendo manifesto de ambiente/configuração. Não misturar várias flags de GC numa tentativa. Parallel GC pode ser candidato a laboratório dominado por CPU e tolerante a pausas; não há razão para adicioná-lo ao baseline I/O sem evidência. Não habilitar preview/experimental para solucionar um problema não reproduzido.

## Hot paths e alocações

Records ajudam contratos imutáveis, mas continuam objetos. Imutabilidade rasa não torna um `Map` mutável seguro; copiar ou encapsular apenas quando necessário e dentro da página. Não criar três cópias completas de cada item para mapper, auditoria e relatório se projeções menores resolvem. Reusar clients/serializers seguros para concorrência; não reutilizar builders mutáveis entre tarefas.

Loops são preferíveis quando permitem parada imediata, remoção de temporários ou contadores claros. Streams sequenciais curtos podem ser legíveis; `map`/`collect` sobre páginas não é proibido. Medir collectors/boxing/regex/strings quando JFR indicar custo. Para valores monetários, preservar decimal exato e regras do domínio em vez de usar `double` como otimização. Bufferizar escrita com tamanho limitado, sem `flush` a cada campo e sem esconder erro de I/O.

## Perfil de diagnóstico

Criar diretório protegido antes de iniciar. Flags opcionais de laboratório:

```text
-Xmx2g
-Xlog:gc*:file=diagnostics/gc.log:time,uptime,level,tags:filecount=5,filesize=10M
-XX:StartFlightRecording=name=aws-ops,settings=default,maxsize=256m,maxage=30m
```

JFR iniciado sem destino precisa de dump explícito para preservar a evidência. Coleta com `settings=profile` fica em janela curta. Não habilitar heap dump automático indiscriminadamente: pode conter dados sensíveis e consumir disco em momento crítico. `jcmd <PID> JFR.dump name=aws-ops filename=diagnostics/operation.jfr` salva uma gravação quando permitido. Verificar sintaxe/opções na distribuição instalada. [Oracle: jcmd](https://docs.oracle.com/en/java/javase/25/docs/specs/man/jcmd.html).

JMC é ferramenta de análise, não dependência do runtime. Async-profiler é alternativa para CPU/alocação/locks em Linux/macOS suportados; Windows usa JFR/JMC como caminho base. [JMC 9](https://docs.oracle.com/en/java/java-components/jdk-mission-control/9/user-guide/jdk-mission-control-users-guide.pdf), [async-profiler oficial](https://github.com/async-profiler/async-profiler).

**VALIDAR NO AMBIENTE:** JDK/patch homologado, licença/suporte, acesso a attach/profilers, armazenamento de dumps, política de antivírus e uso de WSL. Não presumir que performance em máquina de laboratório reproduz a máquina conectada à VPN corporativa. [Plano de benchmark](19-benchmark-plan.md).
