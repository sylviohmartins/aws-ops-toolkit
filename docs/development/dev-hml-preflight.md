# Preflight de identidade DEV/HML — somente leitura

Este procedimento prepara parte da homologação pendente na [issue #21](https://github.com/sylviohmartins/aws-ops-toolkit/issues/21) sem habilitar efeitos AWS. Ele complementa o [runbook operacional](../17-war-room-runbook.md) e as regras de [autenticação](../04-aws-authentication.md).

## Escopo e limitações

O script scripts/dev-hml-preflight.ps1 **recusa PROD** e exige environment, profile, conta de 12 dígitos, nome da role, região e ao menos um ARN explícito de recurso aprovado. Só aceita ARNs account/region-scoped para DynamoDB, SQS, SNS e Lambda, com mesma conta/região/partition, sem wildcard.

A execução padrão valida apenas essa **consistência declarativa**, sem qualquer chamada AWS. Com o parâmetro -CheckSts, executa exclusivamente AWS CLI STS GetCallerIdentity no profile selecionado e confere account, partition e assumed-role. Isso não comprova permissões, acesso a recursos nem a identidade do **provider Java**. O preflight Java continua obrigatório antes de operações AWS.

Nenhuma execução desse script escreve em DynamoDB/SQS/SNS/Lambda, testa permissões por operações mutáveis, modifica credenciais, faz login SSO, ignora TLS ou habilita writes. Recursos S3 e outras formas de ARN não são suportados; isso é uma limitação intencional, não aprovação tácita.

## Exemplo operacional

Substitua os valores ilustrativos por recursos e identidades **explicitamente autorizados**. Não versionar valores reais nem executar comandos contra uma conta escolhida por tentativa e erro.

### Validação estática, sem rede AWS

~~~powershell
.\scripts\dev-hml-preflight.ps1 -Environment HML -AwsProfile 'perfil-aprovado' -ExpectedAccountId '123456789012' -ExpectedRoleName 'RoleAprovada' -Region 'sa-east-1' -ApprovedResourceArns @('arn:aws:dynamodb:sa-east-1:123456789012:table/tabela-aprovada')
~~~

O status CONFIG_ONLY **não significa** autorização para DEV/HML. Apenas confirma que os parâmetros fornecidos não se contradizem.

### Validação opcional de identidade AWS CLI

Após autenticação corporativa legítima pelo procedimento aprovado, repetir o comando com -CheckSts. O status CLI_STS_IDENTITY_MATCHED confirma somente a identidade retornada para a **AWS CLI**, não para a JVM.

O script não inicia login, não escolhe profile default e não faz fallback para outra conta/role. Falha de STS ou divergência de account/role/partition interrompe a execução.

## Evidência e critérios de interpretação

O JSON é salvo em target/dev-hml-preflight/, fora do Git. Registra hora UTC, ambiente, quantidade de recursos, status, flags javaSdkProviderVerified=false, awsPermissionsVerified=false, writesAuthorized=false e remoteWriteCalls=0, commit e estado dirty do checkout.

Não registra profile, account ID, role, ARNs de recursos, access key, token SSO, secret, UserId, ARN do caller ou a resposta da CLI. Um resultado positivo não substitui aprovação, STS pelo provider Java, validação de recursos/quotas, dry-run, canary, reconciliação nem supervisão de DEV/HML.

## Contrato de testes offline

~~~powershell
.\scripts\test-dev-hml-preflight.ps1
~~~

O teste usa conta e ARNs sintéticos e mock em memória do comando AWS CLI para verificar ausência de rede, mismatch de região/conta, wildcard rejeitado, erro de STS, identidade válida e role divergente. Nunca chama AWS real. Ele também é executado pelo workflow Verify.

## Itens bloqueados externamente

A [issue #21](https://github.com/sylviohmartins/aws-ops-toolkit/issues/21) permanece aberta até que ambiente real, provider Java, aprovações e recursos DEV/HML sejam efetivamente validados. O script reduz erros de preparação; **não encerra nenhum gate A–K por si só**.
