[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$envFile = Join-Path $repositoryRoot '.env'
$merchantId = '11111111-1111-4111-8111-111111111111'

function Import-DotEnv {
    if (-not (Test-Path -LiteralPath $envFile)) {
        throw 'Missing .env. Run infrastructure/scripts/prepare-phase3-env.ps1 first.'
    }
    $values = @{}
    foreach ($line in Get-Content -LiteralPath $envFile) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#')) {
            continue
        }
        $separator = $trimmed.IndexOf('=')
        if ($separator -lt 1) {
            throw "Invalid .env line (expected NAME=value): $trimmed"
        }
        $values[$trimmed.Substring(0, $separator).Trim()] = $trimmed.Substring($separator + 1).Trim()
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

function Get-KeycloakUserSubject {
    param([string]$AdminBase, [hashtable]$Headers, [string]$Username)
    $encoded = [Uri]::EscapeDataString($Username)
    $users = @(Invoke-RestMethod -Method Get -Uri "$AdminBase/users?username=$encoded&exact=true" -Headers $Headers -TimeoutSec 20)
    if ($users.Count -ne 1) {
        throw "Expected exactly one Keycloak user named $Username, found $($users.Count)."
    }
    if ([string]::IsNullOrWhiteSpace($users[0].id) -or $users[0].id -notmatch '^[0-9a-fA-F-]{36}$') {
        throw "Keycloak returned an invalid subject for $Username."
    }
    return [string]$users[0].id
}

$environment = Import-DotEnv
$issuer = (Require-Value $environment 'PAYFLOW_OIDC_ISSUER_URI').TrimEnd('/')
$server = $issuer.Substring(0, $issuer.LastIndexOf('/realms/'))
$token = Invoke-RestMethod -Method Post `
    -Uri "$server/realms/master/protocol/openid-connect/token" `
    -ContentType 'application/x-www-form-urlencoded' `
    -Body @{
        grant_type = 'password'
        client_id = 'admin-cli'
        username = Require-Value $environment 'KEYCLOAK_ADMIN'
        password = Require-Value $environment 'KEYCLOAK_ADMIN_PASSWORD'
    } -TimeoutSec 20
$headers = @{ Authorization = "Bearer $($token.access_token)" }
$adminBase = "$server/admin/realms/payflow"

$merchantAdminSubject = Get-KeycloakUserSubject $adminBase $headers 'merchant.admin'
$merchantUserSubject = Get-KeycloakUserSubject $adminBase $headers 'merchant.user'

# The two fixed row IDs identify local fixtures only. Their user_id values always come
# from Keycloak's authoritative user IDs, which are the JWT `sub` values at runtime.
$sql = @"
BEGIN;
INSERT INTO merchant.members (id, merchant_id, user_id, role, status, created_at)
VALUES
    ('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1', '$merchantId', '$merchantAdminSubject', 'MERCHANT_ADMIN', 'ACTIVE', clock_timestamp()),
    ('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2', '$merchantId', '$merchantUserSubject', 'MERCHANT_USER', 'ACTIVE', clock_timestamp())
ON CONFLICT (id) DO UPDATE
SET user_id = EXCLUDED.user_id,
    role = EXCLUDED.role,
    status = EXCLUDED.status;
COMMIT;
"@

$sql | & docker exec -i payflow-postgres sh -lc 'export PGPASSWORD="$PAYFLOW_MERCHANT_DB_PASSWORD"; psql --set=ON_ERROR_STOP=1 --username "$PAYFLOW_MERCHANT_DB_USERNAME" --dbname payflow_merchant --quiet'
if ($LASTEXITCODE -ne 0) {
    throw 'Failed to synchronize local merchant memberships in PostgreSQL.'
}

Write-Host '[SYNC] merchant.admin -> MERCHANT_ADMIN membership' -ForegroundColor Green
Write-Host '[SYNC] merchant.user -> MERCHANT_USER membership' -ForegroundColor Green
