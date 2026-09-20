# Autenticação AWS e ciclo de acesso temporário

Fontes consultadas em **2026-09-18**. **DECISÃO:** usar `ProfileCredentialsProvider` com profile explicitamente selecionado, região explícita e validação STS antes de acessar recursos. Não salvar credenciais em YAML, banco de checkpoints, código ou relatórios.

## IntelliJ, Toolkit e processo Java são contextos distintos

O AWS Toolkit autentica suas próprias ações. Ele pode ler arquivos compartilhados usados também pelo SDK, mas ver uma conta no AWS Explorer não prova qual identidade o processo Java utilizará. O processo recebe o ambiente e as JVM properties da Run Configuration; SDK resolve suas credenciais independentemente. Compartilhar profile/config/cache é um caminho possível, que deve ser confirmado pela chamada STS realizada **pela aplicação**. [Autenticação Toolkit](https://docs.aws.amazon.com/toolkit-for-jetbrains/latest/userguide/setup-credentials.html), [profiles no SDK Java](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/credentials-profiles.html).

Não copiar chaves da UI do Toolkit nem ler caches internos de plugin. Também não presumir que uma variável alterada depois de iniciar o IntelliJ atualiza o ambiente de um processo já existente. Encerrar/reiniciar a execução com a configuração correta é um procedimento legítimo; recuperação durável existe justamente para isso.

## Providers avaliados

| Origem | Comportamento/risco | Decisão |
|---|---|---|
| `ProfileCredentialsProvider` | Profile escolhido delega ao mecanismo descrito nele | Padrão do toolkit; nome obrigatório |
| `DefaultCredentialsProvider` | Procura properties, ambiente, web identity, profiles, container e instance profile em ordem | Só habilitar conscientemente quando o ambiente corporativo exigir; STS permanece obrigatório |
| IAM Identity Center | Profile com `sso_session`, account e role usa autenticação corporativa | Preferido quando homologado |
| `credential_process` | SDK executa programa indicado pelo shared config | Aceitar somente programa corporativo confiável; não construir comando de request |
| STS assume role | Sessão temporária depende da credencial fonte e políticas | Declarar profile/role autorizados; sem elevação automática |
| Environment/static session | Credenciais temporárias não se renovam apenas porque o processo aguarda | Evitar para jobs longos; expiração exige substituição legítima/restart |

**FATO:** passar `profileName` ao `DefaultCredentialsProvider` não coloca profile no topo da cadeia; properties e ambiente ainda podem ganhar precedência. Essa é a razão da escolha explícita do provider. [Cadeia do SDK Java 2.x](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/credentials-chain.html).

**FATO:** profile pode delegar a SSO, assume role, processo ou credenciais temporárias. Configurar recarga de arquivo não equivale a recarregar toda configuração SSO/role/região; a recarga documentada de credenciais tem limitações. Mudança de mecanismo exige reconstrução controlada do provider/clients sob pausa. [Configuração e reload de profiles](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/credentials-profiles.html).

## Configuração ilustrativa, sem segredos

Exemplo para o arquivo compartilhado AWS, **não** para o repositório:

```ini
[profile ops-hml]
sso_session = corporate-session
sso_account_id = <ACCOUNT_ID_HML>
sso_role_name = <AUTHORIZED_ROLE>
region = <AWS_REGION>

[sso-session corporate-session]
sso_start_url = <CORPORATE_SSO_START_URL>
sso_region = <SSO_REGION>
sso_registration_scopes = sso:account:access
```

Os placeholders precisam ser substituídos pelo operador segundo procedimento corporativo. Região do IAM Identity Center pode diferir da região do recurso. Com AWS CLI homologada e esse mecanismo autorizado, `aws sso login --profile ops-hml` inicia autenticação legítima; não é uma etapa automática da API de operações. A sessão SSO pode renovar dentro de seus limites, mas após a expiração do período autorizado é necessário novo login. [IAM Identity Center credential provider](https://docs.aws.amazon.com/sdkref/latest/guide/feature-sso-credentials.html).

Um `credential_process` é alternativa quando a organização já entrega credenciais assim. O programa externo retorna formato documentado, incluindo expiração quando temporário. Proteger shared config contra escrita indevida e impedir segredos em stderr/stdout de diagnóstico. Não criar um broker próprio para contornar o mecanismo existente. [Process credential provider](https://docs.aws.amazon.com/sdkref/latest/guide/feature-process-credentials.html).

Módulos `sts`, `sso` e `ssooidc` devem estar presentes quando os profiles usados precisarem deles. Dependências presentes não significam que o acesso corporativo já foi provisionado.

## `CredentialHealthService`

Contrato alvo retorna um record sanitizado:

```text
CredentialHealth(
  status, profileName, accountId, callerArn, normalizedRole,
  configuredRegion, checkedAt, knownExpiration?, failureCategory?
)
```

Não retorna access key, secret, session token, token SSO nem uma cópia do provider. `knownExpiration` é opcional: `AwsCredentialsProvider` genérico não garante esse dado. Não ler caches privados para preencher um campo bonito. Registrar `UNKNOWN` quando expiração não estiver disponível e usar saúde ativa/erros para interromper com segurança.

Verificações: resolver provider, STS `GetCallerIdentity`, validar identidade contra política e executar probes de recurso não mutantes. STS fornece account/ARN/userId; não fornece mapa de permissões efetivas nem confirma região de todos os recursos. [API STS](https://docs.aws.amazon.com/STS/latest/APIReference/API_GetCallerIdentity.html).

Estado alvo: `AVAILABLE`, `EXPIRING`, `AUTHENTICATION_REQUIRED`, `AUTHORIZATION_DENIED`, `MISCONFIGURED`, `UNREACHABLE`. Um timeout STS não prova expiração: falhar fechado para escrita, manter categoria de rede e tentar apenas segundo orçamento. A identidade atual é preservada como evidência histórica, sem tratá-la como válida indefinidamente.

## Classificação de falhas

| Sinal | Interpretação | Ação |
|---|---|---|
| `ExpiredToken`, token SSO expirado, falha de refresh que exige login | Autenticação não disponível | Parar admissão, drenar, checkpoint, AUTHENTICATION_REQUIRED |
| Profile inexistente ou configuração incompleta | Configuração inválida | Preflight falha; sem fallback para default |
| `AccessDenied` / HTTP 403 | Autorização negada, política ou recurso errado | Pausar, auditar e investigar; não pedir privilégio automaticamente |
| Role efetiva diferente | Seleção ou assunção de role não ocorreu como previsto | Bloquear mesmo se leitura aparentemente funciona |
| Região incorreta/recurso inexistente | Contexto ou target inválido | Bloquear; não pesquisar todas as contas/regiões automaticamente |
| Erro de assinatura/relógio | Configuração/clock skew ou credencial inválida | Pausar e diagnosticar; sem repetir indefinidamente |
| STS indisponível/rede | Saúde não verificável | Bloquear nova escrita; retry limitado da verificação |

Refresh automático pelo provider é aceitável somente enquanto o mecanismo corporativo o permite; a aplicação não prolonga a concessão. Providers diferem em cache/refresh; validar o mecanismo efetivo com um ensaio de expiração em DEV/HML. [Cache de credenciais SDK Java](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/credential-caching.html).

## Expiração em 48% de um job

```mermaid
sequenceDiagram
    participant E as Engine
    participant L as Ledger
    participant A as AWS
    participant O as Operador
    participant P as Provider corporativo
    E->>L: Persistir intenção de etapa
    E->>A: Chamada assinada
    A-->>E: ExpiredToken
    E->>E: Fechar admissão de novas chamadas
    E->>L: Registrar falha; drenar/registrar in-flight
    E->>L: Commit cursores seguros e AUTHENTICATION_REQUIRED
    E-->>O: Status, progresso, pendências e profile
    O->>P: Reautenticação legítima fora da engine
    O->>E: Resume com o mesmo operationId
    E->>P: Resolver credencial atual
    E->>A: GetCallerIdentity e preflight
    A-->>E: Account/role/contexto conferidos
    E->>L: Carregar pendências e reconciliar UNKNOWN
    E->>E: Renovar permit vinculado ao plano
    E->>A: Continuar somente etapas pendentes seguras
```

A resposta explícita `ExpiredToken` não transforma automaticamente todas as chamadas concorrentes em falhas. Algumas podem ter concluído, outras podem ter resposta perdida. Cada etapa mantém seu outcome. O cursor avança somente sobre trabalho contabilizado duravelmente; páginas incompletas são retomadas com ledger de deduplicação.

No resume, comparar account, partition, região, recursos e role normalizada, além do hash da regra/plano. Session ARN novo da mesma role é esperado em renovação, mas é auditado. Mudança de conta/role não é aceita por simples `resume`; exige replanejamento/revisão conforme política. Não atualizar perfil no meio de lote ativo.

## Break Glass

**VALIDAR NO AMBIENTE / ITAÚ:** canal de solicitação, aprovadores, justificativa, duração, roles, entrega/revogação de credencial e trilha corporativa. O projeto não conhece nem implementa esse fluxo. Sequência prevista: concessão externa → seleção explícita do profile autorizado → preflight → dry-run/canary → execução limitada → reconciliação → encerramento externo da concessão e limpeza conforme retenção.

Uma concessão vigente pode permitir escrita, mas não desliga guardrails. Expiração/revogação bloqueia novos efeitos. Dados já lidos e ações já concluídas continuam no ledger para reconciliação; não apagar evidências ao encerrar o acesso.
