# API local implementada

Esta é a API do **skeleton LOCAL/DRY_RUN**, conferida no [controller](../src/main/java/io/github/awsopstoolkit/api/OperationController.java). A [API alvo](05-operation-engine.md#api-alvo) inclui funções futuras e não deve ser confundida com rotas disponíveis.

## Autenticação e execução

`Authorization: Bearer <token>` é obrigatório em todas as rotas, inclusive health/metrics. O token vem de `TOOLKIT_LOCAL_TOKEN`, tem entre **32 e 256 caracteres** e não é gerado automaticamente pelo servidor. Configuração ausente/inválida impede startup. Requisição sem token correto ou com header `Origin` recebe **401**; não há UI de navegador nesta versão. O servidor usa `127.0.0.1` por default.

A configuração é stateless e não usa login/senha nem cookie de sessão. CORS não é habilitado. Não colocar o token em query string, arquivos versionados ou saída de diagnóstico. O PowerShell abaixo gera um valor aleatório apenas em memória, roda o Wrapper e inicia a aplicação em background para que as chamadas usem o mesmo token, sem copiá-lo entre terminais.

```powershell
# Executar dentro da raiz do projeto, com JAVA_HOME apontando para JDK 25.
if (!$env:JAVA_HOME) { throw 'Configure JAVA_HOME para um JDK 25.' }
.\mvnw.cmd clean verify
if ($LASTEXITCODE -ne 0) { throw 'Build falhou; não iniciar a aplicação.' }

$toolkitTokenBytes = New-Object byte[] 32
$toolkitRandom = [Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $toolkitRandom.GetBytes($toolkitTokenBytes)
} finally {
    $toolkitRandom.Dispose()
}
$env:TOOLKIT_LOCAL_TOKEN = [Convert]::ToBase64String($toolkitTokenBytes)
[Array]::Clear($toolkitTokenBytes, 0, $toolkitTokenBytes.Length)
$toolkitHeaders = @{ Authorization = 'Bearer ' + $env:TOOLKIT_LOCAL_TOKEN }

$toolkitProject = (Get-Location).Path
$toolkitJar = (Resolve-Path 'target/aws-ops-toolkit-0.1.0-SNAPSHOT.jar').Path
$toolkitJava = Join-Path $env:JAVA_HOME 'bin/java.exe'
$toolkitRunDirectory = Join-Path $toolkitProject ('target/manual-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $toolkitRunDirectory | Out-Null
$toolkitArguments = @(
    '-jar', ('"' + $toolkitJar + '"'),
    '--server.port=18082',
    '--toolkit.environment=LOCAL',
    '--toolkit.write-enabled=false',
    '--toolkit.aws.enabled=false',
    ('"--toolkit.data-directory=' + (Join-Path $toolkitRunDirectory 'data') + '"')
)
$toolkitProcess = Start-Process -FilePath $toolkitJava -ArgumentList $toolkitArguments `
    -WorkingDirectory $toolkitProject -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $toolkitRunDirectory 'stdout.log') `
    -RedirectStandardError (Join-Path $toolkitRunDirectory 'stderr.log')
$toolkitBase = 'http://127.0.0.1:18082'
$toolkitReady = $false
for ($toolkitAttempt = 0; $toolkitAttempt -lt 150; $toolkitAttempt++) {
    if ($toolkitProcess.HasExited) { throw "Startup falhou; inspecione $toolkitRunDirectory" }
    try {
        $toolkitHealth = Invoke-RestMethod "$toolkitBase/actuator/health" -Headers $toolkitHeaders
        if ($toolkitHealth.status -eq 'UP') { $toolkitReady = $true; break }
    } catch {
        Start-Sleep -Milliseconds 200
    }
}
if (!$toolkitReady) {
    Stop-Process -Id $toolkitProcess.Id
    throw "Startup excedeu o prazo; inspecione $toolkitRunDirectory"
}
```

Os logs não devem conter o token. O processo filho herda a variável ao iniciar; modificar a variável no shell não troca o token da aplicação já em execução. Para uso foreground, o [README](../README.md) mostra `mvnw.cmd spring-boot:run`. No IntelliJ, configurar JDK 25 e a mesma variável local na Run Configuration, sem compartilhá-la no Git.

## Endpoints de operação

Existem **seis rotas** no controller atual. Dry-run é modo do início, e progresso está no snapshot; não há rotas separadas `/dry-run` ou `/progress`.

| Método e caminho | Entrada | Resultado |
|---|---|---|
| `POST /api/v1/operations/{type}` | JSON com `mode` e `parameters` tipados | **202**, snapshot inicial e `Location` após persistir admissão |
| `GET /api/v1/operations/{id}` | UUID | **200**, snapshot de estado/progresso |
| `POST /api/v1/operations/{id}/pause` | Sem body | **202**, pedido de pausa cooperativa |
| `POST /api/v1/operations/{id}/cancel` | Sem body | **202**, pedido de cancelamento |
| `POST /api/v1/operations/{id}/resume` | Sem body | **202**, retomada de PAUSED/INTERRUPTED após validação |
| `GET /api/v1/operations/{id}/report` | UUID | **200**, CSV streaming dos chunks confirmados |

Rotas auxiliares do Actuator: `GET /actuator/health`, `GET /actuator/metrics` e consulta de meter `GET /actuator/metrics/{name}`. Todas exigem bearer token. `health` não expõe detalhes. `metrics` não significa que toda métrica alvo já foi implementada.

### Iniciar

O único tipo registrado na entrega é `synthetic-inventory`. Entrada:

```json
{
  "mode": "DRY_RUN",
  "parameters": {
    "records": 10000,
    "delayMillis": 25
  }
}
```

`records`: 1 a 10.000.000. `delayMillis`: 0 a 100 milissegundos **por página**, apenas para permitir observar a demonstração. O tamanho da página vem da configuração, entre 1 e 1.000. Modo EXECUTE e ambientes DEV/HML/PROD são rejeitados pelo preflight da operação sintética. Campos desconhecidos da entrada são rejeitados; não usar expressões/URLs/nomes de classes como parâmetros.

```powershell
$toolkitBody = @{
    mode = 'DRY_RUN'
    parameters = @{ records = 10000; delayMillis = 25 }
} | ConvertTo-Json -Depth 3
$toolkitOperation = Invoke-RestMethod -Method Post `
    -Uri "$toolkitBase/api/v1/operations/synthetic-inventory" `
    -Headers $toolkitHeaders -ContentType 'application/json' -Body $toolkitBody
$toolkitOperationId = $toolkitOperation.operationId
```

O retorno 202 confirma admissão, não conclusão. Inícios repetidos criam IDs distintos: `Idempotency-Key` do contrato alvo **ainda não é implementado**. Se perder a resposta do POST, não repetir presumindo deduplicação.

### Estado e progresso

```powershell
$toolkitStatus = Invoke-RestMethod `
    -Uri "$toolkitBase/api/v1/operations/$toolkitOperationId" -Headers $toolkitHeaders
$toolkitStatus | Select-Object operationId, status, cursor, total, errorCode
```

O snapshot contém `schemaVersion`, `definitionVersion`, `operationId`, `operationType`, `mode`, `parameters`, `status`, `cursor`, `total`, `pageSize`, `createdAt`, `updatedAt` e `errorCode`. `cursor` conta registros confirmados, não itens em execução; `total` é exato apenas porque a fonte é sintética. Report pode ficar atrás do trabalho em voo até o commit da página.

Estados implementados: CREATED, RUNNING, PAUSING, PAUSED, CANCELLING, CANCELLED, INTERRUPTED, COMPLETED e FAILED. No restart, um estado ativo sem worker da instância atual é reconhecido como INTERRUPTED ao ser consultado. Não existe retomada automática de execução no startup.

### Pausar, retomar e cancelar

```powershell
Invoke-RestMethod -Method Post `
    -Uri "$toolkitBase/api/v1/operations/$toolkitOperationId/pause" -Headers $toolkitHeaders
```

A pausa pode confirmar uma página já iniciada. Consultar o status até PAUSED antes do resume; a resposta inicial normalmente mostra PAUSING. Uma operação pequena pode concluir antes do pedido, resultando em 409.

```powershell
Invoke-RestMethod -Method Post `
    -Uri "$toolkitBase/api/v1/operations/$toolkitOperationId/resume" -Headers $toolkitHeaders
```

Resume mantém o ID e o diretório do job; confere formato do checkpoint, parâmetros e versão da definição. PAUSED e INTERRUPTED são os únicos estados retomáveis. Alterar o algoritmo/versão não autoriza reutilizar partes de relatório antigas.

```powershell
Invoke-RestMethod -Method Post `
    -Uri "$toolkitBase/api/v1/operations/$toolkitOperationId/cancel" -Headers $toolkitHeaders
```

Cancelamento é cooperativo e não apaga os resultados já confirmados. CANCELLED não admite resume. Repetir pause/cancel num estado que não aceita a transição pode retornar 409; não atribuir a esta API a idempotência administrativa prevista no produto alvo. `FAILED` exige inspeção e não possui retry/resume automático nesta versão.

### Relatório

```powershell
$toolkitReportPath = Join-Path $toolkitRunDirectory ('report-' + $toolkitOperationId + '.csv')
Invoke-WebRequest `
    -Uri "$toolkitBase/api/v1/operations/$toolkitOperationId/report" `
    -Headers $toolkitHeaders -OutFile $toolkitReportPath
```

CSV UTF-8 com header `recordId,decision`, quoting e CRLF. Download durante execução devolve uma visão parcial limitada ao cursor capturado; consultar status para saber se finalizou. A rota não cria XLSX, manifesto produtivo ou upload S3. `Content-Disposition` usa apenas o UUID conhecido; cliente não fornece caminho do arquivo no servidor.

## Erros da API atual

| Status | Situação |
|---|---|
| 400 | Tipo/parâmetro/modo inválido ou request malformado |
| 401 | Token ausente/incorreto ou Origin presente; filtro pode retornar corpo vazio |
| 404 | Checkpoint de ID inexistente |
| 409 | Estado incompatível, versão da definição divergente, capacidade ou orçamento de disco no preflight |
| 503 | Falha de I/O do armazenamento local |

O handler fornece `ProblemDetail` sanitizado para os erros mapeados. Nenhuma resposta inclui detalhe de credencial AWS. Não há endpoint de catálogo de operações, errors paginado, confirmação de plano, alteração de throughput ou autenticação AWS nesta versão; todos pertencem ao roadmap.

## Encerramento da demonstração em background

Após concluir/cancelar o job e salvar o relatório:

```powershell
if (!$toolkitProcess.HasExited) { Stop-Process -Id $toolkitProcess.Id }
$toolkitHeaders.Clear()
Remove-Item Env:TOOLKIT_LOCAL_TOKEN
```

`Stop-Process` encerra somente o processo criado no exemplo. Se houver job ativo, seu próximo uso dependerá da recuperação do checkpoint; não significa graceful shutdown garantido. Em foreground, preferir encerramento normal e aguardar o fechamento. Manter o diretório de dados se quiser testar recuperação; outputs do exemplo estão em `target/`, ignorado pelo Git. Para um ensaio automatizado local, usar [scripts/smoke.ps1](../scripts/smoke.ps1).
