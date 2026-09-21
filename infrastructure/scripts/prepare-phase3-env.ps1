[CmdletBinding()]
param(
    [string]$EnvFile = ".env"
)

$ErrorActionPreference = "Stop"
$resolvedEnv = Join-Path (Get-Location) $EnvFile
if (-not (Test-Path -LiteralPath $resolvedEnv)) {
    throw "Missing $resolvedEnv. Copy .env.example to .env and configure the earlier phase values first."
}

$backup = "$resolvedEnv.phase2-backup"
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

function Add-Setting([string]$Name, [string]$Value) {
    if ($existing.Add($Name)) {
        Add-Content -LiteralPath $resolvedEnv -Value "$Name=$Value" -Encoding utf8
        Write-Host "[ADDED] $Name"
    }
    else {
        Write-Host "[KEEP]  $Name"
    }
}

if (-not $existing.Contains("PAYFLOW_SETTLEMENT_DB_URL")) {
    Add-Content -LiteralPath $resolvedEnv -Value "`n# Phase 3 settlement service" -Encoding utf8
}
Add-Setting "PAYFLOW_SETTLEMENT_DB_URL" "jdbc:postgresql://localhost:5433/payflow_settlement"
Add-Setting "PAYFLOW_SETTLEMENT_DB_USERNAME" "payflow_settlement"
Add-Setting "PAYFLOW_SETTLEMENT_DB_PASSWORD" (New-RandomSecret)
Add-Setting "PAYFLOW_SETTLEMENT_PORT" "8089"
Add-Setting "PAYFLOW_SETTLEMENT_SERVICE_URI" "http://settlement-service:8089"
Add-Setting "PAYFLOW_SETTLEMENT_CONSUMER_ENABLED" "true"
Add-Setting "PAYFLOW_SETTLEMENT_BUSINESS_ZONE" "Asia/Ho_Chi_Minh"

if (-not $existing.Contains("PAYFLOW_CONSOLE_ORIGIN")) {
    Add-Content -LiteralPath $resolvedEnv -Value "`n# Local browser login personas" -Encoding utf8
}
Add-Setting "PAYFLOW_CONSOLE_ORIGIN" "http://localhost:8084"
Add-Setting "PAYFLOW_CONSOLE_CLIENT_ID" "payflow-console"
Add-Setting "PAYFLOW_MERCHANT_ADMIN_PASSWORD" (New-RandomSecret 24)
Add-Setting "PAYFLOW_MERCHANT_USER_PASSWORD" (New-RandomSecret 24)
Add-Setting "PAYFLOW_OPERATIONS_USER_PASSWORD" (New-RandomSecret 24)

Write-Host "Phase 3 environment is ready. Existing values were preserved."
Write-Host "Backup: $backup"
