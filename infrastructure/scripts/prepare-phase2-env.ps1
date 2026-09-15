[CmdletBinding()]
param(
    [string]$EnvFile = ".env"
)

$ErrorActionPreference = "Stop"
$resolvedEnv = Join-Path (Get-Location) $EnvFile
if (-not (Test-Path -LiteralPath $resolvedEnv)) {
    throw "Missing $resolvedEnv. Copy .env.example to .env and configure Phase 1 values first."
}

$backup = "$resolvedEnv.phase1-backup"
if (-not (Test-Path -LiteralPath $backup)) {
    Copy-Item -LiteralPath $resolvedEnv -Destination $backup
}

$existing = [System.Collections.Generic.HashSet[string]]::new(
    [System.StringComparer]::Ordinal
)
foreach ($line in Get-Content -LiteralPath $resolvedEnv) {
    if ($line -match '^([A-Za-z_][A-Za-z0-9_]*)=') {
        $null = $existing.Add($Matches[1])
    }
}

function New-RandomSecret([int]$ByteCount = 32) {
    $bytes = [byte[]]::new($ByteCount)
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $generator.GetBytes($bytes)
    }
    finally {
        $generator.Dispose()
    }
    return [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function New-AesKey {
    $bytes = [byte[]]::new(32)
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $generator.GetBytes($bytes)
    }
    finally {
        $generator.Dispose()
    }
    return [Convert]::ToBase64String($bytes)
}

function Add-Setting([string]$Name, [string]$Value) {
    if ($existing.Add($Name)) {
        Add-Content -LiteralPath $resolvedEnv -Value "$Name=$Value" -Encoding utf8
        Write-Host "[ADDED] $Name"
    }
    else {
        Write-Host "[KEEP]  $Name"
    }
}

if (-not $existing.Contains("PAYFLOW_ACCOUNT_DB_URL")) {
    Add-Content -LiteralPath $resolvedEnv -Value "`n# Phase 2 split services" -Encoding utf8
}
Add-Setting "PAYFLOW_ACCOUNT_DB_URL" "jdbc:postgresql://localhost:5433/payflow_account"
Add-Setting "PAYFLOW_ACCOUNT_DB_USERNAME" "payflow_account"
Add-Setting "PAYFLOW_ACCOUNT_DB_PASSWORD" (New-RandomSecret)
Add-Setting "PAYFLOW_LEDGER_DB_URL" "jdbc:postgresql://localhost:5433/payflow_ledger"
Add-Setting "PAYFLOW_LEDGER_DB_USERNAME" "payflow_ledger"
Add-Setting "PAYFLOW_LEDGER_DB_PASSWORD" (New-RandomSecret)
Add-Setting "PAYFLOW_MERCHANT_DB_URL" "jdbc:postgresql://localhost:5433/payflow_merchant"
Add-Setting "PAYFLOW_MERCHANT_DB_USERNAME" "payflow_merchant"
Add-Setting "PAYFLOW_MERCHANT_DB_PASSWORD" (New-RandomSecret)
Add-Setting "PAYFLOW_REPORTING_DB_URL" "jdbc:postgresql://localhost:5433/payflow_reporting"
Add-Setting "PAYFLOW_REPORTING_DB_USERNAME" "payflow_reporting"
Add-Setting "PAYFLOW_REPORTING_DB_PASSWORD" (New-RandomSecret)
Add-Setting "PAYFLOW_NOTIFICATION_CLIENT_SECRET" (New-RandomSecret)
Add-Setting "PAYFLOW_PAYMENT_INTERNAL_CLIENT_SECRET" (New-RandomSecret)
Add-Setting "PAYFLOW_MERCHANT_ENCRYPTION_KEY_BASE64" (New-AesKey)
Add-Setting "PAYFLOW_ACCOUNT_PORT" "8082"
Add-Setting "PAYFLOW_LEDGER_PORT" "8086"
Add-Setting "PAYFLOW_MERCHANT_PORT" "8087"
Add-Setting "PAYFLOW_REPORTING_PORT" "8088"
Add-Setting "PAYFLOW_ACCOUNT_CONSUMER_ENABLED" "true"
Add-Setting "PAYFLOW_LEDGER_CONSUMER_ENABLED" "true"
Add-Setting "PAYFLOW_REPORTING_CONSUMER_ENABLED" "true"
Add-Setting "PAYFLOW_WEBHOOK_ENABLED" "true"
Add-Setting "PAYFLOW_WEBHOOK_POLL_INTERVAL" "1s"
Add-Setting "PAYFLOW_WEBHOOK_BATCH_SIZE" "25"
Add-Setting "PAYFLOW_WEBHOOK_LEASE" "30s"
Add-Setting "PAYFLOW_WEBHOOK_HTTP_TIMEOUT" "5s"
Add-Setting "PAYFLOW_WEBHOOK_ALLOW_UNSAFE_LOCAL_TARGETS" "false"
Add-Setting "PAYFLOW_MERCHANT_SERVICE_URI" "http://merchant-service:8087"
Add-Setting "PAYFLOW_SERVICE_TOKEN_URI" "http://keycloak:8080/realms/payflow/protocol/openid-connect/token"
Add-Setting "PAYFLOW_NOTIFICATION_CLIENT_ID" "payflow-notification"
Add-Setting "PAYFLOW_PAYMENT_INTERNAL_CLIENT_ID" "payflow-payment-internal"
Add-Setting "PAYFLOW_MERCHANT_CATALOG_MODE" "remote"
Add-Setting "PAYFLOW_MERCHANT_HTTP_TIMEOUT" "3s"

Write-Host "Phase 2 environment is ready. Existing values were preserved."
Write-Host "Backup: $backup"
