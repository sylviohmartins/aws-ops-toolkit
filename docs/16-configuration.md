# Configuração externa: implementação e alvo produtivo

Pesquisa arquitetural original: 18/09/2026. Conferência deste capítulo contra POM, Java, YAML e script de smoke: **20/09/2026**. Referências Spring complementares consultadas nessa data. **DECISÃO:** nenhuma escolha operacional importante deve exigir recompilação no produto completo; o skeleton ainda possui constantes em adapters e não implementa todas as propriedades do blueprint.

## Fontes e precedência

Spring Boot aceita YAML, variáveis de ambiente, propriedades JVM e argumentos. Arquivos específicos de profile sobrepõem a configuração base; argumentos de linha de comando podem sobrepor arquivos. `spring.config.additional-location` acrescenta localização; `spring.config.location` substitui as localizações padrão. Conferir a configuração **efetiva**, sem imprimir segredos, antes de interpretar um default como garantia. [Spring Boot: externalized configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html).

Arquivos ativos: [application.yml](../src/main/resources/application.yml), [DEV](../src/main/resources/application-dev.yml), [HML](../src/main/resources/application-hml.yml), [PROD](../src/main/resources/application-prod.yml). O [record ToolkitProperties](../src/main/java/io/github/awsopstoolkit/configuration/ToolkitProperties.java) usa `@ConfigurationProperties("toolkit")`, `@Validated` e Bean Validation. Seu `toString()` é redigido para não expor bearer token. Isso não autoriza serializar/dumpar o objeto em outros formatos.

## Propriedades reais do skeleton

Faixa aceita pelo código é diferente de faixa segura para produção. A coluna de validação descreve o código; limites operacionais futuros dependem do ambiente.

| Chave | Default real | Unidade / validação implementada | Significado e impacto |
| --- | --- | --- | --- |
| `toolkit.environment` | `LOCAL` | Enum LOCAL/DEV/HML/PROD, obrigatório | Exemplo sintético só aceita LOCAL; profile não é autorização |
| `toolkit.data-directory` | `.aws-ops-toolkit/data` | Path não nulo; relativo ao working directory | Checkpoints, chunks e lock; usar disco local e diretório exclusivo |
| `toolkit.minimum-free-bytes` | `1073741824` | Bytes; mínimo aceito `1048576` | Reserva de 1 GiB no default; 1 MiB existe apenas para ensaio pequeno |
| `toolkit.max-concurrent-operations` | `2` | Jobs inteiros; 1–4 | Limite de admissão global; PROD YAML reduz a 1 |
| `toolkit.page-size` | `100` | Registros por página sintética; 1–1.000 | Memória temporária/arquivo por chunk e granularidade de checkpoint |
| `toolkit.write-enabled` | `false` | Boolean | Gate global de escrita fora de LOCAL; o runtime exige este gate **e** `operations.writes=true`, além de `EXECUTE`, confirmação, referência operacional e aprovação |
| `toolkit.local-token` | `${TOOLKIT_LOCAL_TOKEN:}` | Não branco; 32–256 caracteres | Obrigatório para autenticação API/Actuator; vazio faz startup falhar |
| `toolkit.aws.enabled` | `false` | Boolean | Cria beans opcionais somente se `true`; não registra nova operação nem autoriza efeitos |
| `toolkit.aws.region` | `${AWS_REGION:us-east-1}` | String não branca | Região dos clients opcionais; `us-east-1` é fallback demonstrativo, não alvo corporativo presumido |
| `toolkit.aws.profile` | `${AWS_PROFILE:}` | Ao habilitar AWS, string não branca | Provider explícito; não confundir profile AWS com profile Spring |
| `toolkit.aws.expected-account` | `${TOOLKIT_EXPECTED_ACCOUNT:}` | Ao habilitar AWS, exatamente 12 dígitos | Conta esperada para comparação STS; não comprova permissão da ação |
| `toolkit.aws.max-connections` | `8` | Conexões; 1–64 | Teto do transporte Apache5 compartilhado pelos clients AWS do exemplo |

A API sintética `/api/v1/operations` continua restrita a LOCAL/DRY_RUN. O runtime `/api/v1/jobs` possui fluxo separado: fora de LOCAL, efeitos exigem simultaneamente `toolkit.write-enabled=true`, `operations.writes=true`, request `EXECUTE`, confirmação explícita, incidente/change conforme ambiente, motivo, plano selado e aprovação vigente. Isso não equivale a homologação corporativa. Campos desconhecidos no JSON da API são recusados pela configuração Jackson; isso **não significa** que toda chave YAML desconhecida é recusada pelo binder atual.

| Chave Spring/servidor/log | Default explícito | Unidade / política | Impacto |
| --- | --- | --- | --- |
| `spring.application.name` | `aws-ops-toolkit` | Nome | Identificação da aplicação |
| `spring.threads.virtual.enabled` | `true` | Boolean | Ativa suporte Spring; executor da engine continua explícito |
| `spring.lifecycle.timeout-per-shutdown-phase` | `30s` | Duração | Orçamento Spring por fase; engine espera até 25 s e interrompe remanescentes |
| `spring.jackson.deserialization.fail-on-unknown-properties` | `true` | Boolean | Evita erro de digitação silencioso em payloads JSON tipados |
| `server.address` | `127.0.0.1` | IP local; manter loopback | Não usar override para expor `0.0.0.0` |
| `server.port` | `8080` | Porta TCP; selecionar uma livre local | Smoke usa 18081; porta ocupada deve falhar claramente |
| `server.shutdown` | `graceful` | Modo Spring | Não garante hooks após término forçado |
| `server.error.include-message` / `include-stacktrace` | `never` / `never` | Enum | Evita detalhes internos nas respostas HTTP |
| `management.endpoints.web.exposure.include` | `health,metrics` | Allowlist | Ambos passam pelo filtro de autenticação local |
| `management.endpoint.health.show-details` | `never` | Enum | Sem detalhes sensíveis no health |
| `logging.file.name` | `.aws-ops-toolkit/logs/application.log` | Path | Evidência de diagnóstico, não ledger de escrita |
| `logging.logback.rollingpolicy.max-file-size` | `10MB` | Tamanho Spring | Rollover por tamanho |
| `logging.logback.rollingpolicy.max-history` | `7` | Períodos de retenção do rolling policy | Política de diagnóstico; não substitui retenção de evidência |
| `logging.logback.rollingpolicy.total-size-cap` | `100MB` | Tamanho Spring | Limita total de arquivos de log arquivados conforme policy |
| `logging.structured.format.file` | `logstash` | Formato | Logs estruturados no arquivo |

## Profiles e variáveis

| Profile Spring | Ambiente aplicado | AWS habilitada? | Escrita habilitada? | Uso atual |
| --- | --- | --- | --- | --- |
| Nenhum adicional | LOCAL | Não | Não | Demonstração funcional |
| `dev` | DEV | Não | Não | Configuração de arquitetura; operação sintética recusada |
| `hml` | HML | Não | Não | Configuração de arquitetura; operação sintética recusada |
| `prod` | PROD, um job | Não | Não | Configuração de arquitetura; operação sintética recusada |

Variáveis explicitamente mapeadas pelo YAML: `TOOLKIT_LOCAL_TOKEN`, `AWS_REGION`, `AWS_PROFILE` e `TOOLKIT_EXPECTED_ACCOUNT`. Variáveis fornecidas ao processo Java devem estar no ambiente da configuração Run/Maven do IntelliJ. Uma alteração no terminal não modifica automaticamente um processo/IDE já aberto. Não guardar token em Run Configuration compartilhada, argumento de comando, Git ou exemplo real; usar ambiente da sessão ou mecanismo corporativo aprovado.

Exemplo **real e bindable** de override local sem segredo embutido:

```yaml
# local-override.yml — arquivo externo do operador, fora do Git
server:
  address: 127.0.0.1
  port: 18080
toolkit:
  environment: LOCAL
  data-directory: .aws-ops-toolkit/local-session
  minimum-free-bytes: 1073741824
  max-concurrent-operations: 1
  page-size: 100
  write-enabled: false
  local-token: ${TOOLKIT_LOCAL_TOKEN}
  aws:
    enabled: false
```

Carregar esse arquivo com `--spring.config.additional-location=file:./local-override.yml` preserva defaults ausentes. Não executar o exemplo sem disponibilizar o token por canal local apropriado. O script [smoke.ps1](../scripts/smoke.ps1) gera um token aleatório **para o próprio smoke**, restaura a variável anterior ao terminar, usa diretório exclusivo em `target/smoke`, reduz reserva para 1 MiB e limita a um job. Esses overrides são de teste, não recomendação produtiva.

## Constantes ainda não externalizadas

| Local atual | Valor real no Java | Alvo da evolução |
| --- | --- | --- |
| AWS Apache5 | Connect 3 s; acquisition 2 s; socket 25 s; max idle 30 s | Properties por serviço/recurso, com validação cruzada de budgets |
| AWS overrides por client | Attempt 30 s; total 35 s; `maxAttempts=1`; StandardRetryStrategy | Bean prototype cria nova configuração/strategy para cada client; transporte continua compartilhado. Separar leitura segura (até 3) de efeitos ambíguos (1), sem retry duplicado |
| AWS user-agent | `aws-ops-toolkit/0.1` | Derivar versão do build sem incidente/PII |
| HTTP exemplo | Connect 3 s; read 10 s; redirects NEVER; sem retry | Properties tipadas de cliente específico e limites de resposta |
| `AwsCallGate` | Concorrência/taxa passadas ao construtor; taxa 0,01–1.000.000 chamadas lógicas/s aceita | Policy por recurso, deadline de aquisição e taxa física conforme retry |
| Synthetic input | `records` 1–10.000.000; `delayMillis` 0–100 | Parâmetros de request, não knobs AWS |

Esses limites não são todos propriedades aplicadas hoje. Ver os valores exatos em [AwsClientConfiguration](../src/main/java/io/github/awsopstoolkit/configuration/AwsClientConfiguration.java) e [HttpIntegrationClient](../src/main/java/io/github/awsopstoolkit/integration/HttpIntegrationClient.java). A faixa muito alta aceita por um construtor não é faixa operacional segura. No desenho produtivo, todos esses controles passam por plano/allowlist e preflight.

## Blueprint completo, deliberadamente não bindable

[production-blueprint.yml](../examples/production-blueprint.yml) é um **documento TARGET** com raiz `target-blueprint`. Ele não utiliza a raiz `toolkit`, não está em `src/main/resources` e não deve ser passado a `spring.config.*`. Não implementa properties ausentes. Seus valores são propostas iniciais de laboratório/revisão; alvos/limites desconhecidos ficam `null`, vazios ou placeholders, exigindo resolução antes de EXECUTE.

O exemplo cobre metadados/allowlists, aprovação, credenciais, timeouts/retry, concorrência/filas/taxas, DynamoDB e segmentos, SQS/SNS/Lambda/S3, HTTP, ledger, checkpoint, reports, disk, metrics, retenção e hot tuning. Comentários indicam unidade, intervalo de revisão e impacto. Ativar uma feature no documento não a ativa no código.

Validações cruzadas obrigatórias no TARGET: soma de jobs não excede orçamento de recurso; fila também é limitada por bytes; `TotalSegments` imutável no resume; deadlines incluem espera/retry; planos sem conta/região/alvo inequívocos falham; `EXECUTE` exige simultaneamente autorização vigente, identidade correta, flag, modo, incidente, motivo, plano confirmado e canary. Taxa `null` não significa ilimitada: significa **não configurada e bloqueante** para chamada real.

O runtime operacional já expõe hot tuning de `requestsPerSecond` até o teto configurado e o limitador AIMD reduz/adapta taxa e concorrência efetiva após throttling/respostas saudáveis. O TARGET continua exigindo que aumentos permaneçam dentro de teto aprovado e auditável. Conta, região, recurso, regra, modo, idempotency keys e particionamento não mudam no job; modificação exige novo plano. Properties de segurança são capturadas numa configuração imutável por operação, não lidas oportunisticamente de um YAML que pode mudar durante a execução.

**VALIDAR NO AMBIENTE:** locations externas aprovadas, working directory, proxy/TLS, provider/SSO, conta/role, orçamento AWS/HTTP, políticas de criptografia, retenção, logs, dumps e armazenamento. Nenhuma configuração legítima elimina esses requisitos.
