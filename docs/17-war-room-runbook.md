# Runbook operacional

Este runbook distingue o que pode ser feito **agora no skeleton LOCAL** do procedimento futuro de uma versão homologada para AWS. Ler o [roadmap](20-implementation-roadmap.md) e não interpretar exemplos de arquitetura como autorização corporativa ou capacidade produtiva pronta.

## Preparar antes do incidente

1. Manter a branch principal revisada e o build conhecido. Criar branch curta `<tipo>/<assunto>` para alterações, usar commits pequenos com Conventional Commits e revisão por PR. Não fazer alterações improvisadas diretamente na principal. Não incluir credenciais, checkpoints, relatórios ou dumps em Git.
2. Confirmar JDK 25 no Project SDK e no Maven Runner do IntelliJ. `java -version` e `./mvnw.cmd -version` devem apontar para runtime compatível. Usar Wrapper e mirrors corporativos permitidos; não baixar binários de fontes informais.
3. Executar `./mvnw.cmd verify`; conferir análise estática disponível e dependências. Documentar commit, JDK, sistema operacional e configuração sem segredos.
4. Rodar demonstração LOCAL e smoke: criação, status, relatório, cancelamento e retomada do fluxo sintético. O resultado dessa demonstração não valida IAM, quotas, SDK remoto ou regras financeiras.
5. Manter disponível o pacote da versão homologada, runbook, modelo de plano de mudança, diretórios aprovados, retenção e contatos de donos dos serviços.

## Iniciar a demonstração entregue

Iniciar a aplicação pelo IntelliJ com a configuração LOCAL documentada no [README](../README.md), ou pelo Maven Wrapper. API deve escutar em `127.0.0.1`, e o cliente local deve apresentar a autenticação configurada. Requisições da demonstração, contratos e comandos exatos estão no README e na documentação da API, que prevalecem sobre exemplos conceituais deste runbook.

Executar somente o inventário sintético. Conferir geração incremental de CSV, diretório de estado e erro claro para modos/ambientes não suportados. A presença de classes de adapters AWS não habilita operação produtiva completa. Não chamar métodos de escrita de exemplo para substituir autorização ou ledger ausente.

## Pré-operação AWS, após homologação

| Passo | Evidência necessária | Se falhar |
| --- | --- | --- |
| Incidente/change e responsável | Identificação, motivo, janela, dono e plano de contingência | Não iniciar escrita |
| Autenticar legitimamente | Profile/SSO/processo corporativo aprovado | Reautenticar fora da engine |
| Identidade efetiva | STS executado com o mesmo provider do adapter, conta/role esperadas | Bloquear, corrigir origem de credenciais |
| Região e recursos | Allowlist e existência dos alvos, aliases/version de Lambda | Bloquear alvo divergente |
| Permissões mínimas | Leituras de preflight e escopo aprovado | Não testar permissão por escrita destrutiva |
| Configuração | Hash da regra/plano, budgets, timeout, recursos, colunas | Falhar antes de admitir itens |
| Disco | Espaço dos volumes de ledger, reports e temporários | Corrigir capacidade antes do job |
| Dry-run | Candidatos, descartes, before/proposed-after, chamadas e riscos | Revisar seleção/transformação |
| Aprovação de EXECUTE | Identidade + write flag + modo + incidente + confirmação vinculada ao plano | Permanecer em DRY_RUN |
| Canary | Limite 10, depois 100 ou 1.000 conforme risco e revisão de resultado | Pausar e reconciliar |

**VALIDAR NO AMBIENTE:** forma exata de solicitar/encerrar Break Glass, prazo de credenciais, roles, provedores, confirmação, dupla revisão e armazenamento de evidências. Estar conectado ao painel AWS Toolkit não prova que o processo Java herdou credenciais. Ver [autenticação](04-aws-authentication.md).

Exemplo opcional de diagnóstico da CLI, apenas se AWS CLI e SSO forem o mecanismo corporativo aprovado:

```text
aws sso login --profile <perfil-aprovado>
aws sts get-caller-identity --profile <perfil-aprovado> --region <regiao-aprovada>
```

A CLI valida seu próprio processo. O preflight Java repete a confirmação com a cadeia real da aplicação. Não imprimir valores resolvidos de credenciais nem copiar tokens entre processos manualmente. [AWS CLI: IAM Identity Center](https://docs.aws.amazon.com/cli/latest/userguide/cli-configure-sso.html).

## Durante a execução

Abrir status por `operationId`, acompanhar taxa ativa, p95, throttles, retries, conflitos, errors, espaço, checkpoint age e saúde de credenciais. Correlacionar com métricas do serviço mantidas pelo time responsável: CPU local livre não significa folga de DynamoDB/HTTP. Reduzir taxa quando for necessário; hot tuning só quando a versão o implementar e auditar.

| Evento | Ação da engine alvo | Ação operacional |
| --- | --- | --- |
| Credencial expirada | Fecha admissão, drena com deadline, salva estado e marca `AUTHENTICATION_REQUIRED` | Reautenticar legitimamente, validar mesma identidade autorizada e solicitar resume |
| `AccessDenied` | Bloqueia escrita; não entra em retry infinito | Conferir role/política/recurso, sem elevar privilégios automaticamente |
| Throttle/429 sustentado | Backoff limitado, redução de taxa, pausa ao estourar budget | Alinhar janela e carga com dono do serviço |
| 500/timeout | Retry apenas seguro; resultado ambíguo fica para reconciliação | Não reiniciar job às cegas |
| Conflitos/schema inesperado | Conflito isolado é contabilizado; padrão sistêmico pausa | Validar regra e fonte de verdade |
| Disco baixo | Fecha admissão; preserva último checkpoint confirmado | Liberar/migrar espaço conforme retenção; nunca apagar ledger ativo |
| Cancelamento desejado | Drenagem cooperativa e relatório parcial | Usar endpoint de cancelamento e aguardar estado terminal |

## Interrupção e retomada

1. Preferir cancelamento/pausa da API a Stop do IntelliJ. Aguardar fim da drenagem e registrar checkpoint final.
2. Shutdown gracioso é melhor esforço. Stop forçado, encerramento do processo e perda de energia podem impedir hooks. Na inicialização, job que parecia RUNNING precisa ser reconhecido como interrompido; não iniciar escrita automaticamente.
3. Verificar integridade do estado/ledger, lock de execução única, versão da operação, hash de parâmetros, conta, região, recursos e particionamento. Mudanças materiais exigem nova operação/plano; não adulterar checkpoint para contorná-las.
4. Reconciliar intenções sem resultado antes de novas escritas. Verificar marcador/versão no DynamoDB e status dos efeitos externos, preservando idempotency keys.
5. Retomar explicitamente e com limites reduzidos. Relatório deve identificar a nova tentativa e o vínculo ao job original. Um job CANCELLED pode exigir novo job de continuação, conforme máquina de estados adotada; não forçar transição não suportada pelo contrato.

Spring oferece graceful shutdown, mas isso não é garantia de execução de cleanup sob término abrupto. [Spring Boot: graceful shutdown](https://docs.spring.io/spring-boot/reference/web/graceful-shutdown.html).

## Após a operação

Conferir invariantes de contagem, resultados desconhecidos, pendências de eventos, falhas de negócio e reconciliação amostral/total definida no plano. `COMPLETED_WITH_ERRORS` não equivale a incidente resolvido. Fechar relatório e manifesto, conferir checksums e classificar evidências; upload S3 somente a destino autorizado. Não declarar sucesso a partir apenas de HTTP 200, publish aceito ou taxa elevada.

Registrar commit/configuração, mudanças aplicadas, exceções, intervalos de pausa e ações manuais. Encerrar acesso temporário pelo fluxo corporativo, parar a aplicação, retirar o token local do ambiente da sessão e aplicar retenção aos artefatos conforme política. No skeleton o token é fornecido por configuração do operador; não existe gerador automático de token efêmero. Realizar retrospectiva: transformar a regra útil em operação reutilizável revisada, mantendo o incidente e dados fora do código.
