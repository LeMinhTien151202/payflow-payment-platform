[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('start', 'rebuild', 'stop', 'status', 'logs', 'token', 'smoke')]
    [string]$Action = 'status',

    [Parameter(Position = 1)]
    [string]$Service,

    [ValidateSet('service', 'operations')]
    [string]$Client = 'service',

    [ValidateRange(30, 900)]
    [int]$TimeoutSeconds = 300,

    [switch]$Follow
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$RepositoryRoot = $PSScriptRoot
$EnvFile = Join-Path $RepositoryRoot '.env'
$Profile = 'full'
$RuntimeServices = @(
    'postgres',
    'kafka',
    'redis',
    'keycloak',
    'merchant-service',
    'account-service',
    'ledger-service',
    'reporting-service',
    'settlement-service',
    'risk-service',
    'notification-service',
    'payment-service',
    'api-gateway'
)

function Require-EnvFile {
    if (-not (Test-Path -LiteralPath $EnvFile)) {
        throw 'Missing .env. Copy .env.example to .env, then run .\payflow.ps1 rebuild.'
    }
}

function Import-DotEnv {
    Require-EnvFile
    $values = @{}
    foreach ($line in Get-Content -LiteralPath $EnvFile) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#')) {
            continue
        }
        $separator = $trimmed.IndexOf('=')
        if ($separator -lt 1) {
            throw "Invalid .env line (expected NAME=value): $trimmed"
        }
        $name = $trimmed.Substring(0, $separator).Trim()
        $value = $trimmed.Substring($separator + 1).Trim()
        if ($name -notmatch '^[A-Z][A-Z0-9_]*$') {
            throw "Invalid environment variable name: $name"
        }
        $values[$name] = $value
        Set-Item -Path "Env:$name" -Value $value
    }
    return $values
}

function Require-Value {
    param([hashtable]$Values, [string]$Name)
    $value = $Values[$Name]
    if ([string]::IsNullOrWhiteSpace($value)) {
        throw "Required value $Name is missing from .env."
    }
    return $value
}

function Invoke-Compose {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    & docker compose --env-file $EnvFile --profile $Profile @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose failed with exit code $LASTEXITCODE."
    }
}

function Require-Docker {
    & docker info *> $null
    if ($LASTEXITCODE -ne 0) {
        throw 'Docker Engine is not available. Start Docker Desktop and retry.'
    }
}

function Wait-ServiceHealthy {
    param([string]$Name, [datetime]$Deadline)
    do {
        $containerId = (& docker compose --env-file $EnvFile --profile $Profile ps -q $Name | Out-String).Trim()
        if ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace($containerId)) {
            $status = (& docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' $containerId | Out-String).Trim()
            if ($LASTEXITCODE -eq 0 -and ($status -eq 'healthy' -or $status -eq 'running')) {
                Write-Host "[UP] $Name"
                return
            }
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $Deadline)
    throw "$Name did not become healthy within $TimeoutSeconds seconds. Run .\payflow.ps1 logs $Name."
}

function Wait-Gateway {
    param([hashtable]$Values, [datetime]$Deadline)
    $port = Require-Value $Values 'PAYFLOW_GATEWAY_PORT'
    $uri = "http://localhost:$port/actuator/health/readiness"
    do {
        try {
            $response = Invoke-RestMethod -Method Get -Uri $uri -TimeoutSec 5
            if ($response.status -eq 'UP') {
                Write-Host '[UP] api-gateway public endpoint'
                return
            }
        }
        catch {
            # A bounded startup race is expected while the JVM and route table initialize.
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $Deadline)
    throw "API Gateway did not become reachable at $uri."
}

function Prepare-And-Start {
    param([bool]$Build)
    Require-Docker
    Require-EnvFile

    & (Join-Path $RepositoryRoot 'infrastructure\scripts\prepare-phase3-env.ps1')
    if ($LASTEXITCODE -ne 0) {
        throw 'Phase 3 environment preparation failed.'
    }
    $values = Import-DotEnv
    Invoke-Compose config --quiet

    Invoke-Compose up --detach postgres
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    Wait-ServiceHealthy 'postgres' $deadline
    & docker exec payflow-postgres bash /docker-entrypoint-initdb.d/02-provision-phase2-databases.sh
    if ($LASTEXITCODE -ne 0) {
        throw 'PostgreSQL database provisioning failed.'
    }

    Invoke-Compose up --detach keycloak
    Wait-ServiceHealthy 'keycloak' $deadline
    & (Join-Path $RepositoryRoot 'infrastructure\scripts\provision-phase3-keycloak.ps1')
    if ($LASTEXITCODE -ne 0) {
        throw 'Keycloak scope provisioning failed.'
    }

    if ($Build) {
        Invoke-Compose up --detach --build
    }
    else {
        try {
            Invoke-Compose up --detach --no-build
        }
        catch {
            throw "Starting existing images failed. Run '.\payflow.ps1 rebuild' once to build them. $($_.Exception.Message)"
        }
    }

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    foreach ($name in $RuntimeServices) {
        Wait-ServiceHealthy $name $deadline
    }
    Wait-Gateway $values $deadline

    $gatewayPort = Require-Value $values 'PAYFLOW_GATEWAY_PORT'
    $issuer = (Require-Value $values 'PAYFLOW_OIDC_ISSUER_URI').TrimEnd('/')
    Write-Host ''
    Write-Host 'PayFlow is ready.' -ForegroundColor Green
    Write-Host "Gateway:  http://localhost:$gatewayPort"
    Write-Host "Swagger:  http://localhost:$gatewayPort/swagger-ui.html"
    Write-Host "Keycloak: $issuer"
}

function Copy-AccessToken {
    Require-Docker
    $values = Import-DotEnv
    $issuer = (Require-Value $values 'PAYFLOW_OIDC_ISSUER_URI').TrimEnd('/')
    if ($Client -eq 'operations') {
        $clientId = 'payflow-operations'
        $secretName = 'PAYFLOW_OPERATIONS_CLIENT_SECRET'
    }
    else {
        $clientId = 'payflow-service'
        $secretName = 'PAYFLOW_SERVICE_CLIENT_SECRET'
    }
    $response = Invoke-RestMethod -Method Post `
        -Uri "$issuer/protocol/openid-connect/token" `
        -ContentType 'application/x-www-form-urlencoded' `
        -Body @{
            grant_type = 'client_credentials'
            client_id = $clientId
            client_secret = Require-Value $values $secretName
        } `
        -TimeoutSec 20
    if ([string]::IsNullOrWhiteSpace($response.access_token)) {
        throw 'Keycloak returned no access token.'
    }
    Set-Clipboard -Value $response.access_token

    $segment = $response.access_token.Split('.')[1].Replace('-', '+').Replace('_', '/')
    switch ($segment.Length % 4) {
        2 { $segment += '==' }
        3 { $segment += '=' }
    }
    $claims = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($segment)) | ConvertFrom-Json
    $expiresAt = [DateTimeOffset]::FromUnixTimeSeconds([long]$claims.exp).ToLocalTime()
    Write-Host "Access token for $clientId copied to clipboard; it was not printed or persisted." -ForegroundColor Green
    Write-Host "Expires: $expiresAt"
    Write-Host "Scopes:  $($claims.scope)"
}

Push-Location $RepositoryRoot
try {
    switch ($Action) {
        'start' {
            Prepare-And-Start $false
        }
        'rebuild' {
            Prepare-And-Start $true
        }
        'stop' {
            Require-Docker
            Require-EnvFile
            Invoke-Compose stop
            Write-Host 'PayFlow containers stopped; volumes and data were preserved.' -ForegroundColor Green
        }
        'status' {
            Require-Docker
            Require-EnvFile
            Invoke-Compose ps --all
        }
        'logs' {
            Require-Docker
            Require-EnvFile
            if ([string]::IsNullOrWhiteSpace($Service)) {
                throw "Service is required. Example: .\payflow.ps1 logs payment-service"
            }
            $available = @(& docker compose --env-file $EnvFile --profile $Profile config --services)
            if ($available -notcontains $Service) {
                throw "Unknown service '$Service'. Available: $($available -join ', ')"
            }
            if ($Follow) {
                Invoke-Compose logs --tail 200 --follow $Service
            }
            else {
                Invoke-Compose logs --tail 200 $Service
            }
        }
        'token' {
            Copy-AccessToken
        }
        'smoke' {
            Require-Docker
            Require-EnvFile
            & (Join-Path $RepositoryRoot 'infrastructure\scripts\smoke-mvp.ps1') `
                -Profile full -TimeoutSeconds $TimeoutSeconds -Amount 1000
            if ($LASTEXITCODE -ne 0) {
                throw 'Full-profile smoke test failed.'
            }
        }
    }
}
finally {
    Pop-Location
}
