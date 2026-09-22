# Runtime operacional e laboratório Docker

Status conferido no código em **2026-09-22**. Este capítulo descreve o runtime em `io.github.awsopstoolkit.runtime` e o laboratório local composto por Moto e uma API HTTP sintética. Ele substitui, para essas capacidades, as descrições antigas de skeleton; os capítulos anteriores continuam sendo a arquitetura de referência. A implementação ainda não é certificação para produção nem evidência de homologação corporativa.

## O que está implementado

O runtime expõe jobs assíncronos em `/api/v1/jobs`, admite um job operacional ativo por processo e separa planejamento de execução. O planejamento pagina a fonte, persiste candidatos e cursores no SQLite e sela o conjunto com SHA-256 sobre request, versão da regra, identidade e registros. A execução exige aprovação ligada a esse hash. Primeiro processa até `canaryRecords`; se houver itens restantes, termina em `CANARY_COMPLETE` e exige nova aprovação com `promote=true`.

O banco fica em `<toolkit.data-directory>/operations.sqlite`, com `journal_mode=WAL`, `synchronous=FULL`, foreign keys e `busy_timeout=5000`. As tabelas duráveis registram jobs, cursores, tarefas, efeitos e auditoria. Uma intenção é gravada antes de cada efeito remoto. Resposta confirmada vira `SUCCEEDED`; rejeição AWS 4xx vira `NOT_SENT`; timeout, erro de transporte ou falha AWS ambígua vira `UNKNOWN` e leva a `RECONCILIATION_REQUIRED`. Efeitos desconhecidos não são repetidos automaticamente.

Leituras usam até três tentativas para throttling, falhas AWS 5xx e transporte. Um limitador compartilhado combina semáforo, taxa persistida por job e ajuste AIMD: throttling reduz pela metade **taxa e concorrência efetiva**; a recuperação aumenta ambas gradualmente após vinte respostas saudáveis, até seus tetos. HTTP 429 alimenta o mesmo sinal de backpressure. Cinco falhas consecutivas abrem uma pausa de dez segundos e interrompem o job como `PAUSED`. O planejamento de Scan pode usar segmentos em paralelo; os efeitos das tarefas são confirmados sequencialmente para preservar budgets e ledger.

Pausa e cancelamento são cooperativos. O shutdown solicita `INTERRUPTED`, aguarda até 25 segundos e então interrompe o executor se necessário. Ao abrir o journal, execuções encontradas em `PLANNING`, `RUNNING` ou `APPROVED` passam a `INTERRUPTED` e a aprovação expira. Planejamento incompleto retorna por `resume-plan`; execução planejada exige nova aprovação. O limite de tempo considera execuções anteriores do mesmo job.

Relatórios vêm da fonte durável: CSV streaming, XLSX com janela SXSSF de 100 linhas e limite de um milhão de registros, manifesto, plano e auditoria paginados. A chave do registro é substituída por prefixo SHA-256 de 16 caracteres nos relatórios. A manutenção diária remove payloads e resultados de efeitos de jobs terminais após `operations.retention-days`; ela preserva as linhas e os totais.

## Operações registradas

| `operation` | Parâmetros de `parameters` | Comportamento implementado |
| --- | --- | --- |
| `dynamodb-inventory` | `table`; `queryId` opcional | Query por `id` com um segmento, ou Scan segmentado/paginado. Projeta `id`, `status`, `version`, `opsMarker` e `opsPreviousStatus`; execução registra `OBSERVED`. |
| `payment-repair` | `table`, `queue`, `topic`, `function`, `evidenceBucket` | Para item `PENDING`, confirma `SETTLED` via HTTP, faz update condicional com versão e marcador, envia SQS/SNS, invoca Lambda qualificada e grava evidência S3. Só é registrado quando `operations.payment-endpoint` existe. |
| `payment-compensate` | `table`, `sourceJob` | Seleciona itens marcados pelo job fonte e restaura apenas o status DynamoDB por condição. Não desfaz mensagens, publicações, invocações ou evidências. |
| `sqs-inspect` | `queue`, `messages` | Recebe de 1 a 1.000 mensagens com visibility timeout de 120 s, audita e não confirma. Em `EXECUTE`, exige os dois consentimentos explícitos de impacto do request; `DRY_RUN` não realiza receive. |
| `sqs-consume` | `queue`, `table`, `messages` | Deduplica o `eventId` em DynamoDB por hash do payload e só então exclui a mensagem. Exige os dois consentimentos. |
| `dlq-replay` | `queue`, `destinationQueue`, `messages`; `groupId` para FIFO | Preserva body e message attributes, envia ao destino e depois confirma a origem. Exige os dois consentimentos. |
| `sns-publish` | `topic`, `payload`; `groupId` para FIFO | Publica payload limitado a 65.536 caracteres; FIFO usa deduplication id derivado do job/tarefa. |
| `lambda-invoke` | `function`, `invocationType`, `payload` | Exige função qualificada e `RequestResponse`, `Event` ou `DryRun`; distingue `FunctionError` de aceite de transporte. |
| `s3-copy` | `bucket`, `key`, `versionId`, `destinationBucket`, `destinationKey`, `maxBytes`; `multipart` opcional | Fixa uma versão imutável, verifica orçamento de até 5 TiB e faz cópia server-side. Acima de 5 GiB, ou com `multipart=true`, persiste upload e partes para retomada. |
| `s3-delete` | `bucket`, `key`, `versionId`, `maxBytes` | Recusa retenção/legal hold detectável, exige versão e registra a intenção antes de excluir. |
| `s3-abort-multipart` | `bucket`, `key`, `uploadId` | Aborta explicitamente um multipart upload conhecido. |

Todos os recursos calculados pela regra precisam aparecer exatamente em `operations.resources`; wildcard é recusado. A identidade retornada pelo STS precisa coincidir com `toolkit.aws.expected-account`; sessões `assumed-role/.../<session>` são normalizadas para o ARN IAM estável da role e comparadas à allowlist de principals. Recursos ARN são checados também contra região/conta quando aplicável. Antes de cada chamada, o runtime repete identidade, allowlist, orçamento de chamadas, tempo e espaço livre. Fora de LOCAL, efeitos exigem **os dois gates** `toolkit.write-enabled=true` e `operations.writes=true`, além de request `EXECUTE`, confirmação explícita, referência operacional, motivo, plano selado e aprovação vigente.

## Contrato de criação

`POST /api/v1/jobs` recebe:

```json
{
  "operation": "dynamodb-inventory",
  "parameters": { "table": "lab-payments" },
  "mode": "EXECUTE",
  "incidentId": "INC-12345",
  "changeId": "CHG-67890",
  "reason": "reviewed bounded inventory execution",
  "explicitConfirmation": true,
  "maxRecords": 200,
  "maxCalls": 2000,
  "maxSeconds": 300,
  "canaryRecords": 10,
  "maxConflicts": 0,
  "maxErrors": 0,
  "maxErrorRate": 0.0,
  "minErrorSample": 100,
  "segments": 4,
  "visibilityImpactAccepted": false,
  "sharedConsumerImpactAccepted": false
}
```

| Campo | Faixa/semântica |
| --- | --- |
| `operation` | Nome não vazio de uma regra registrada. |
| `parameters` | Objeto interpretado pela regra; não aceita código ou expressão executável. |
| `maxRecords` | 1 a 10.000.000 candidatos persistidos. |
| `maxCalls` | 1 a 10.000.000 chamadas reservadas; tentativas contam separadamente. |
| `maxSeconds` | 1 a 86.400 segundos acumulados. |
| `canaryRecords` | 1 a 1.000 tarefas antes de exigir promoção. |
| `maxConflicts` | 0 a 10.000 outcomes `CONFLICT` tolerados. |
| `segments` | 1 a 1.024; Query e operações de comando exigem 1. |
| `visibilityImpactAccepted` | Ausente assume `false`; deve ser `true` para `EXECUTE` que realiza receive SQS. |
| `sharedConsumerImpactAccepted` | Ausente assume `false`; deve ser `true` no `EXECUTE` SQS para confirmar impacto sobre consumidores concorrentes. |
| `mode` | Ausente falha seguro para `DRY_RUN`; `EXECUTE` é obrigatório para qualquer efeito remoto. |
| `incidentId` / `changeId` | Referência operacional; escrita `EXECUTE` exige ao menos uma e PROD exige ambas. |
| `reason` / `explicitConfirmation` | Escrita `EXECUTE` exige motivo de pelo menos 8 caracteres e confirmação `true`. |
| `maxErrors` / `maxErrorRate` / `minErrorSample` | Error budget absoluto e percentual; defaults fail-safe são 0/0/100. |
| `maxScannedRecords` / `maxReadCapacity` | Budgets independentes para leitura DynamoDB; defaults derivam de `maxRecords`. |

O preflight também reserva `toolkit.minimum-free-bytes + maxRecords × 8192` bytes. Portanto, escolher dez milhões de registros exige espaço local compatível antes mesmo do planejamento.

## API operacional

Todas as rotas, inclusive Actuator, exigem `Authorization: Bearer <TOOLKIT_LOCAL_TOKEN>`. O bind padrão continua em `127.0.0.1:8080`.

| Método e rota | Uso |
| --- | --- |
| `GET /api/v1/jobs/types` | Lista operações registradas nesta configuração. |
| `POST /api/v1/jobs` | Cria e inicia o planejamento; retorna `202`. |
| `GET /api/v1/jobs/{id}` | Retorna view sanitizada: estado, hash, identidade mascarada, recursos, budgets/uso e totais; não devolve request/payload bruto. |
| `GET /api/v1/jobs/{id}/plan?after={seq}` | Lê até 100 propostas sanitizadas com identificador mascarado e before/after controlado pela regra. |
| `GET /api/v1/jobs/{id}/dry-run` | Após `DRY_RUN_COMPLETE`, retorna contagens, hash, recursos, chamadas estimadas, riscos e amostras before/after mascaradas. |
| `GET /api/v1/jobs/{id}/summary` | Retorna outcomes, effects, error rate, throughput local e unknowns pendentes. |
| `GET /api/v1/jobs/{id}/errors?after={seq}` | Retorna página sanitizada de conflitos/erros de negócio/função. |
| `POST /api/v1/jobs/{id}/approve` | Aprova hash/motivo e inicia canary ou promoção. |
| `POST /api/v1/jobs/{id}/resume-plan` | Retoma apenas um planejamento ainda não selado. |
| `POST /api/v1/jobs/{id}/pause` | Solicita pausa cooperativa. |
| `POST /api/v1/jobs/{id}/cancel` | Solicita cancelamento cooperativo terminal. |
| `POST /api/v1/jobs/{id}/tuning` | Reduz ou restaura `requestsPerSecond` até o teto configurado. |
| `POST /api/v1/jobs/{id}/reconcile` | Resolve manualmente um efeito `INTENT`/`UNKNOWN`, com evidência de 12 a 512 caracteres. |
| `GET /api/v1/jobs/{id}/audit?after={seq}` | Lê até 500 eventos depois da sequência. |
| `GET /api/v1/jobs/{id}/manifest` | Retorna schema, job, totais, política de redação e fonte do relatório. |
| `GET /api/v1/jobs/{id}/report.csv` | Gera CSV por streaming. |
| `GET /api/v1/jobs/{id}/report.xlsx` | Gera XLSX streaming até um milhão de tarefas. |

Fluxo PowerShell após criar o job e aguardar `READY`:

```powershell
$headers = @{ Authorization = "Bearer $env:TOOLKIT_LOCAL_TOKEN" }
$job = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8080/api/v1/jobs' `
  -Headers $headers -ContentType 'application/json' -Body (@{
    operation = 'dynamodb-inventory'
    parameters = @{ table = 'lab-payments' }
    mode = 'EXECUTE'
    incidentId = 'INC-LAB-001'; changeId = 'CHG-LAB-001'
    reason = 'reviewed bounded inventory execution'; explicitConfirmation = $true
    maxRecords = 200; maxCalls = 2000; maxSeconds = 300
    canaryRecords = 10; maxConflicts = 0; maxErrors = 0; maxErrorRate = 0.0
    minErrorSample = 100; segments = 4
    visibilityImpactAccepted = $false; sharedConsumerImpactAccepted = $false
  } | ConvertTo-Json -Depth 4)

do {
  Start-Sleep -Milliseconds 200
  $status = Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/v1/jobs/$($job.id)" -Headers $headers
} until ($status.state -ne 'PLANNING')

$approval = @{ hash = $status.hash; reason = 'LAB-CHANGE inventory review'; promote = $false } |
  ConvertTo-Json
Invoke-RestMethod -Method Post `
  -Uri "http://127.0.0.1:8080/api/v1/jobs/$($job.id)/approve" `
  -Headers $headers -ContentType 'application/json' -Body $approval
```

Se o estado chegar a `CANARY_COMPLETE`, revisar plano, audit e relatório; depois repetir `/approve` com o mesmo hash, novo motivo explícito e `promote=true`. Aprovação não é endpoint idempotente nem substitui revisão humana.

## Laboratório local com Docker

Pré-requisitos: Docker Compose, PowerShell, JDK 25 em `JAVA_HOME` e portas locais 4566, 8091 e 8080 disponíveis. O compose fixa Moto `5.2.2`, publica apenas em loopback e monta `lab/` somente para leitura. `bootstrap.py` cria tabelas DynamoDB, filas, tópico/assinatura, buckets versionados, função Lambda e 200 pagamentos sintéticos. Nenhuma credencial real é usada; os clients do perfil `lab` aceitam somente endpoint HTTP de loopback, ambiente `LOCAL` e `toolkit.aws.enabled=false`.

O caminho recomendado é:

```powershell
.\scripts\lab.ps1 -Action up

$bytes = New-Object byte[] 32
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
$env:TOOLKIT_LOCAL_TOKEN = [Convert]::ToBase64String($bytes)
[Array]::Clear($bytes, 0, $bytes.Length)

.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=lab'
```

Em outro PowerShell com o mesmo token, usar as chamadas acima. Para executar build, análise estática, testes e smokes do laboratório em uma só entrada:

```powershell
.\scripts\lab.ps1 -Action test
```

Esse comando inicia e provisiona os containers, mas não os remove ao terminar. Consultar ou encerrar explicitamente:

```powershell
.\scripts\lab.ps1 -Action status
.\scripts\lab.ps1 -Action down
Remove-Item Env:TOOLKIT_LOCAL_TOKEN -ErrorAction SilentlyContinue
```

Os comandos Compose equivalentes são:

```powershell
docker compose up -d --wait
docker compose exec -T aws python /lab/bootstrap.py
docker compose ps
docker compose down
```

`docker compose down` remove os containers e a rede do laboratório. Os fixtures Moto vivem apenas nos containers atuais; o journal SQLite e logs da aplicação ficam no diretório local configurado e não são removidos pelo Compose.

## Configuração do runtime

| Property `operations.*` | Default/limite | Efeito |
| --- | --- | --- |
| `enabled` | `false` | Registra controller, journal, manutenção e workflows. |
| `writes` | `false` | Gate adicional para qualquer `effect`; o perfil `lab` usa `true` somente contra emuladores. |
| `lab-endpoint` | ausente | Ativa clients com credenciais dummy e endpoint loopback validado. |
| `resources` | sem default útil | Allowlist exata e não vazia; `*` impede startup. |
| `principals` | sem default útil | ARNs STS exatos e não vazios. |
| `requests-per-second` | `10`, faixa 1–1.000 | Teto de dispatch e de hot tuning. |
| `workers` | `2`, faixa 1–32 | Concorrência máxima compartilhada e de segmentos de planejamento. |
| `page-size` | `100`, faixa 1–1.000 | Limite de página e lote local. |
| `plan-lifetime-seconds` | `86400`, faixa 60–86.400 | Janela entre criação e aprovação do plano. |
| `approval-lifetime-seconds` | `900`, faixa 30–3.600 | Validade reavaliada antes de cada efeito. |
| `retention-days` | `30`, mínimo 1 | Idade para redigir payload/result de jobs terminais. |
| `payment-endpoint` | ausente | Registra `payment-repair`; HTTPS é exigido fora do lab. |
| `payment-hosts` | vazio | Allowlist de hostname para a integração HTTP. |

Continuam relevantes `toolkit.data-directory`, `toolkit.minimum-free-bytes`, `toolkit.local-token`, `toolkit.environment`, `toolkit.aws.expected-account` e `toolkit.aws.region`. `TOOLKIT_PAYMENT_TOKEN` é opcional para a integração HTTP e nunca deve ser versionado ou registrado.

## Implementado versus arquitetura alvo

| Tema | Implementado agora | Ainda depende de validação/evolução |
| --- | --- | --- |
| Persistência | SQLite schema 5, WAL/FULL, cursores por segmento, tarefas, efeitos, audit e contador transacional de candidatos planejados sem `COUNT(*)` global por página | Crash/power-loss no filesystem corporativo, backup aprovado, proteção/criptografia local conforme política |
| Segurança | Loopback, bearer local, STS account/principal, allowlists exatas, write gate e aprovação expirada por tempo | IAM/SSO/Break Glass reais, segregação de aprovador, políticas e retenção corporativas |
| Execução | `DRY_RUN`/`EXECUTE`, plano selado, before/after sanitizado, canary, promoção, pausa/cancelamento, auth pause, budgets absolutos e por taxa, estimativa de chamadas, summary e reconciliação | Múltiplos jobs, distribuição entre hosts e estimativa financeira/RCU/WCU calibrada por ambiente |
| AWS/HTTP | Fluxos concretos e laboratório Moto/HTTP; conditions e marcadores em pontos críticos | DEV/HML reais, semântica de cada downstream, quotas, proxy/TLS e fault injection homologados |
| Entrega entre serviços | Ledger impede replay cego de efeito confirmado e expõe estado incerto | Não há transação distribuída nem exactly-once; SNS/SQS/Lambda ambíguos podem exigir evidência externa |
| Rollback | Compensação DynamoDB condicionada e auditável como novo job | Eventos já emitidos não são desfeitos; rollback universal continua proibido |
| Relatórios | Manifesto, audit, plano, CSV e XLSX streaming | Política de colunas, proteção de pre-images, upload corporativo e cadeia de custódia |
| Observabilidade/performance | Logs estruturados, Actuator, records/capacity, AWS/HTTP requests/failures/retries/throttling/latência, transições, hot tuning e AIMD de taxa+concorrência; ledger medido em 1M/5M/10M sob `-Xmx256m` e sweep local 1→256 concluído | OTel/Datadog/dashboard corporativos e limites sustentáveis em AWS real continuam dependentes de DEV/HML autorizado |

O laboratório prova integração funcional emulada quando seus checks são executados; ele não reproduz throttling, consistência, IAM, quotas, falhas de rede e comportamento de todos os serviços AWS reais. Nenhum resultado local autoriza escrita em DEV, HML ou PROD. A entrada para adoção fora do lab continua sendo o conjunto de gates do [roadmap](20-implementation-roadmap.md).
