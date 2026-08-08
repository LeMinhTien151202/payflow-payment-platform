[CmdletBinding()]
param(
    [ValidateRange(30, 600)]
    [int]$TimeoutSeconds = 180,
    [ValidateSet('mvp', 'full')]
    [string]$Profile = 'mvp'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$EnvFile = Join-Path $RepositoryRoot '.env'
$HappyCustomerId = '3beff442-7f10-4504-aab4-12d985cf3e95'
$HappySourceAccountId = '039bedb6-b2d6-47df-aa25-2035e39136a3'

function Import-DotEnv {
    param([string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Missing $Path. Copy .env.example to .env and configure local-only values first."
    }

    foreach ($line in Get-Content -LiteralPath $Path) {
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
            throw "Invalid environment variable name in .env: $name"
        }
        Set-Item -Path "Env:$name" -Value $value
    }
}

function Require-EnvironmentValue {
    param([string]$Name)

    $value = [Environment]::GetEnvironmentVariable($Name)
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

function Invoke-PsqlScalar {
    param(
        [string]$Database,
        [string]$Sql
    )

    $superuser = Require-EnvironmentValue 'POSTGRES_USER'
    $output = & docker compose --env-file $EnvFile --profile $Profile exec -T postgres `
        psql -X -U $superuser -d $Database -At -v ON_ERROR_STOP=1 -c $Sql
    if ($LASTEXITCODE -ne 0) {
        throw "PostgreSQL assertion query failed for database $Database."
    }
    return (($output | Out-String).Trim())
}

function Wait-HttpHealthy {
    param(
        [string]$Name,
        [string]$Uri,
        [datetime]$Deadline
    )

    do {
        try {
            $health = Invoke-RestMethod -Method Get -Uri $Uri -TimeoutSec 5
            if ($health.status -eq 'UP') {
                Write-Host "[UP] $Name"
                return
            }
        }
        catch {
            # Startup races are expected; the bounded deadline below is the failure policy.
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $Deadline)

    throw "$Name did not become healthy before timeout: $Uri"
}

function Assert-Equal {
    param(
        [string]$Label,
        [string]$Actual,
        [string]$Expected
    )

    if ($Actual -ne $Expected) {
        throw "$Label expected '$Expected' but was '$Actual'."
    }
    Write-Host "[OK] $Label = $Actual"
}

Push-Location $RepositoryRoot
try {
    Import-DotEnv $EnvFile

    $requiredSecrets = [System.Collections.Generic.List[string]]@(
        'POSTGRES_PASSWORD',
        'PAYFLOW_PAYMENT_DB_PASSWORD',
        'PAYFLOW_ACCOUNT_LEDGER_DB_PASSWORD',
        'PAYFLOW_RISK_DB_PASSWORD',
        'PAYFLOW_NOTIFICATION_DB_PASSWORD',
        'KEYCLOAK_DB_PASSWORD',
        'KEYCLOAK_ADMIN_PASSWORD',
        'PAYFLOW_SERVICE_CLIENT_SECRET')
    if ($Profile -eq 'full') {
        $requiredSecrets.AddRange([string[]]@(
            'PAYFLOW_ACCOUNT_DB_PASSWORD',
            'PAYFLOW_LEDGER_DB_PASSWORD',
            'PAYFLOW_MERCHANT_DB_PASSWORD',
            'PAYFLOW_REPORTING_DB_PASSWORD',
            'PAYFLOW_NOTIFICATION_CLIENT_SECRET',
            'PAYFLOW_PAYMENT_INTERNAL_CLIENT_SECRET'))
    }
    foreach ($secretName in $requiredSecrets) {
        $secret = Require-EnvironmentValue $secretName
        if ($secret -eq 'change-me-local-only') {
            throw "$secretName still uses the template placeholder. Set a disposable local value in .env."
        }
    }

    Write-Host "Validating the resolved $Profile Compose model..."
    Invoke-Compose config --quiet

    $healthDeadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $healthTargets = [ordered]@{
        'payment-service'      = "http://localhost:$(Require-EnvironmentValue 'PAYFLOW_PAYMENT_PORT')/actuator/health/readiness"
        'risk-service'         = "http://localhost:$(Require-EnvironmentValue 'PAYFLOW_RISK_PORT')/actuator/health/readiness"
        'notification-service' = "http://localhost:$(Require-EnvironmentValue 'PAYFLOW_NOTIFICATION_PORT')/actuator/health/readiness"
        'api-gateway'          = "http://localhost:$(Require-EnvironmentValue 'PAYFLOW_GATEWAY_PORT')/actuator/health/readiness"
    }
    if ($Profile -eq 'full') {
        $healthTargets['account-service'] = "http://localhost:$(Require-EnvironmentValue 'PAYFLOW_ACCOUNT_PORT')/actuator/health/readiness"
        $healthTargets['ledger-service'] = "http://localhost:$(Require-EnvironmentValue 'PAYFLOW_LEDGER_PORT')/actuator/health/readiness"
        $healthTargets['merchant-service'] = "http://localhost:$(Require-EnvironmentValue 'PAYFLOW_MERCHANT_PORT')/actuator/health/readiness"
        $healthTargets['reporting-service'] = "http://localhost:$(Require-EnvironmentValue 'PAYFLOW_REPORTING_PORT')/actuator/health/readiness"
        $accountDatabase = 'payflow_account'
        $ledgerDatabase = 'payflow_ledger'
    }
    else {
        $healthTargets['account-ledger-service'] = "http://localhost:$(Require-EnvironmentValue 'PAYFLOW_ACCOUNT_LEDGER_PORT')/actuator/health/readiness"
        $accountDatabase = 'payflow_account_ledger'
        $ledgerDatabase = 'payflow_account_ledger'
    }
    foreach ($target in $healthTargets.GetEnumerator()) {
        Wait-HttpHealthy $target.Key $target.Value $healthDeadline
    }

    $balanceBefore = Invoke-PsqlScalar $accountDatabase `
        "select available_balance::text || '|' || reserved_balance::text from account.accounts where id = '$HappySourceAccountId';"
    if ($balanceBefore -ne '1000000.0000|0.0000') {
        throw "The happy-path account is not pristine (actual $balanceBefore). Reset only disposable local data before retrying."
    }
    Write-Host '[OK] pristine account = 1000000.0000 available, 0.0000 reserved'

    $issuer = (Require-EnvironmentValue 'PAYFLOW_OIDC_ISSUER_URI').TrimEnd('/')
    $tokenResponse = Invoke-RestMethod -Method Post `
        -Uri "$issuer/protocol/openid-connect/token" `
        -ContentType 'application/x-www-form-urlencoded' `
        -Body @{
            grant_type    = 'client_credentials'
            client_id     = 'payflow-service'
            client_secret = Require-EnvironmentValue 'PAYFLOW_SERVICE_CLIENT_SECRET'
        } `
        -TimeoutSec 15
    if ([string]::IsNullOrWhiteSpace($tokenResponse.access_token)) {
        throw 'Keycloak returned no access_token.'
    }
    Write-Host '[OK] Keycloak issued a service token (token content is not printed)'

    $runId = "{0}-{1}" -f (Get-Date).ToUniversalTime().ToString('yyyyMMddHHmmss'), ([guid]::NewGuid().ToString('N').Substring(0, 8))
    $idempotencyKey = "$Profile-$runId"
    $requestBody = [ordered]@{
        merchantReference = "ORDER-$runId"
        customerId         = $HappyCustomerId
        sourceAccountId    = $HappySourceAccountId
        amount             = 500000
        currency           = 'VND'
        description        = "PayFlow local $Profile smoke payment"
        metadata           = @{ scenario = "$Profile-happy-path" }
    } | ConvertTo-Json -Depth 5 -Compress
    $gateway = "http://localhost:$(Require-EnvironmentValue 'PAYFLOW_GATEWAY_PORT')"
    $headers = @{
        Authorization      = "Bearer $($tokenResponse.access_token)"
        'Idempotency-Key'  = $idempotencyKey
        'X-Correlation-Id' = "smoke-$runId"
    }

    $created = Invoke-RestMethod -Method Post -Uri "$gateway/api/v1/payments" `
        -Headers $headers -ContentType 'application/json' -Body $requestBody -TimeoutSec 15
    $paymentId = ([guid]$created.data.paymentId).ToString()
    Write-Host "[OK] payment accepted: $paymentId"

    $replayed = Invoke-RestMethod -Method Post -Uri "$gateway/api/v1/payments" `
        -Headers $headers -ContentType 'application/json' -Body $requestBody -TimeoutSec 15
    Assert-Equal 'idempotent replay paymentId' ([guid]$replayed.data.paymentId).ToString() $paymentId

    $paymentDeadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $terminalFailureStates = @('FAILED', 'RISK_REJECTED', 'MANUAL_REVIEW_REQUIRED', 'CANCELLED')
    do {
        $detail = Invoke-RestMethod -Method Get -Uri "$gateway/api/v1/payments/$paymentId" `
            -Headers @{ Authorization = "Bearer $($tokenResponse.access_token)"; 'X-Correlation-Id' = "smoke-get-$runId" } `
            -TimeoutSec 15
        $status = [string]$detail.data.status
        if ($status -eq 'SUCCEEDED') {
            break
        }
        if ($terminalFailureStates -contains $status) {
            throw "Payment reached terminal non-success state $status. Inspect Compose logs with the payment id $paymentId."
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $paymentDeadline)
    Assert-Equal 'payment status' $status 'SUCCEEDED'

    # Delivery is asynchronous even after Payment reports success, so poll boundedly for the final
    # read-side assertions instead of adding sleeps that are either flaky or needlessly slow.
    $notificationDeadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $notification = Invoke-PsqlScalar 'payflow_notification' `
            "select status from notification.notifications where business_reference_type = 'PAYMENT_OUTCOME' and business_reference_id = '$paymentId';"
        if ($notification -eq 'SENT') {
            break
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $notificationDeadline)

    $riskDecision = Invoke-PsqlScalar 'payflow_risk' `
        "select decision from risk.risk_assessments where payment_id = '$paymentId';"
    $balanceAfter = Invoke-PsqlScalar $accountDatabase `
        "select available_balance::text || '|' || reserved_balance::text from account.accounts where id = '$HappySourceAccountId';"
    $reservation = Invoke-PsqlScalar $accountDatabase `
        "select status from account.balance_reservations where payment_id = '$paymentId';"
    $journal = Invoke-PsqlScalar $ledgerDatabase `
        "select count(*)::text || '|' || sum(case when e.direction = 'DEBIT' then e.amount else -e.amount end)::text from ledger.journals j join ledger.entries e on e.journal_id = j.id where j.reference_type = 'PAYMENT' and j.reference_id = '$paymentId' and j.status = 'POSTED' group by j.id;"
    $paymentCount = Invoke-PsqlScalar 'payflow_payment' `
        "select count(*)::text from payment.payments where id = '$paymentId';"

    Assert-Equal 'risk decision' $riskDecision 'APPROVED'
    Assert-Equal 'account balance available|reserved' $balanceAfter '500000.0000|0.0000'
    Assert-Equal 'reservation status' $reservation 'CAPTURED'
    Assert-Equal 'journal entry-count|net' $journal '2|0.0000'
    Assert-Equal 'notification status' $notification 'SENT'
    Assert-Equal 'payment rows after replay' $paymentCount '1'

    Write-Host ''
    Write-Host "$($Profile.ToUpperInvariant()) SMOKE PASSED: $paymentId" -ForegroundColor Green
    Write-Host 'Payment -> Risk -> Account reserve -> Ledger post -> Account capture -> Payment success -> Notification completed.'
}
finally {
    Pop-Location
}
