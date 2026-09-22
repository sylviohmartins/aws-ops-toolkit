# Adicionar uma integração

Primeiro verificar se a necessidade já cabe nos adapters HTTP/AWS existentes.

## HTTP

1. criar DTO específico somente quando o contrato externo justificar;
2. criar adapter/client da integração;
3. reutilizar infraestrutura HTTP comum;
4. criar nova @ConfigurationProperties capability apenas se houver valores próprios;
5. definir URI/host allowlist;
6. classificar timeout/429/5xx e idempotência;
7. não empilhar retries;
8. não logar payload sensível.

Uma integração nova não deve alterar JobController, journal, CSV, AWS scheduler ou checkpoint.

## AWS

Se o serviço já possui client gerenciado, injete-o. Se for serviço AWS novo, a mudança pertence a AwsClientConfiguration e deve reutilizar profile, Region, SdkHttpClient e ClientOverrideConfiguration centralizados.

## Dependência nova

Só adicionar após provar necessidade e avaliar JDK/Spring/AWS SDK, BOM, manutenção, transitivas, overhead e facilidade de remoção.
