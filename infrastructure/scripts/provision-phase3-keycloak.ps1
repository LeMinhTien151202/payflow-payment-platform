[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$EnvFile = Join-Path $RepositoryRoot '.env'
$RealmFile = Join-Path $RepositoryRoot 'infrastructure\keycloak\realm-payflow.json'
$RealmName = 'payflow'

function Import-DotEnv {
    param([string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Missing $Path. Run prepare-phase3-env.ps1 first."
    }

    $values = @{}
    foreach ($line in Get-Content -LiteralPath $Path) {
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
    param(
        [hashtable]$Values,
        [string]$Name
    )

    $value = $Values[$Name]
    if ([string]::IsNullOrWhiteSpace($value)) {
        throw "Required value $Name is missing from .env."
    }
    return $value
}

function Invoke-KeycloakAdmin {
    param(
        [ValidateSet('Get', 'Post', 'Put')]
        [string]$Method,
        [string]$Uri,
        [hashtable]$Headers,
        [object]$Body
    )

    $arguments = @{
        Method = $Method
        Uri = $Uri
        Headers = $Headers
        TimeoutSec = 20
    }
    if ($null -ne $Body) {
        $arguments.ContentType = 'application/json'
        $arguments.Body = $Body | ConvertTo-Json -Depth 30 -Compress
    }
    try {
        return Invoke-RestMethod @arguments
    }
    catch {
        throw "Keycloak admin $Method $Uri failed: $($_.Exception.Message)"
    }
}

$environment = Import-DotEnv $EnvFile
$issuer = (Require-Value $environment 'PAYFLOW_OIDC_ISSUER_URI').TrimEnd('/')
$server = $issuer.Substring(0, $issuer.LastIndexOf('/realms/'))
$adminToken = Invoke-RestMethod -Method Post `
    -Uri "$server/realms/master/protocol/openid-connect/token" `
    -ContentType 'application/x-www-form-urlencoded' `
    -Body @{
        grant_type = 'password'
        client_id = 'admin-cli'
        username = Require-Value $environment 'KEYCLOAK_ADMIN'
        password = Require-Value $environment 'KEYCLOAK_ADMIN_PASSWORD'
    } `
    -TimeoutSec 20

if ([string]::IsNullOrWhiteSpace($adminToken.access_token)) {
    throw 'Keycloak returned no admin access token.'
}

$headers = @{ Authorization = "Bearer $($adminToken.access_token)" }
$realm = Get-Content -LiteralPath $RealmFile -Raw | ConvertFrom-Json
$requiredScopeNames = @(
    'settlement:read',
    'settlement:run',
    'reconciliation:read',
    'reconciliation:run'
)
$desiredScopes = @($realm.clientScopes | Where-Object { $requiredScopeNames -contains $_.name })
$scopeEndpoint = "$server/admin/realms/$RealmName/client-scopes"
$currentScopes = @(Invoke-KeycloakAdmin -Method Get -Uri $scopeEndpoint -Headers $headers -Body $null)

foreach ($desiredScope in $desiredScopes) {
    if (@($currentScopes | Where-Object { $_.name -eq $desiredScope.name }).Count -gt 0) {
        Write-Host "[KEEP] client scope $($desiredScope.name)"
        continue
    }
    Invoke-KeycloakAdmin -Method Post -Uri $scopeEndpoint -Headers $headers -Body $desiredScope | Out-Null
    Write-Host "[ADD] client scope $($desiredScope.name)"
}

$currentScopes = @(Invoke-KeycloakAdmin -Method Get -Uri $scopeEndpoint -Headers $headers -Body $null)
$scopeIdsByName = @{}
foreach ($currentScope in $currentScopes) {
    foreach ($scopeItem in @($currentScope)) {
        $scopeIdsByName[[string]$scopeItem.name] = [string]$scopeItem.id
    }
}
$clientAssignments = [ordered]@{
    'payflow-service' = @('settlement:read')
    'payflow-operations' = @('settlement:run', 'reconciliation:read', 'reconciliation:run')
}

foreach ($entry in $clientAssignments.GetEnumerator()) {
    $encodedClientId = [Uri]::EscapeDataString($entry.Key)
    $clients = @(Invoke-KeycloakAdmin -Method Get `
        -Uri "$server/admin/realms/$RealmName/clients?clientId=$encodedClientId" `
        -Headers $headers -Body $null)
    if ($clients.Count -ne 1) {
        throw "Expected one Keycloak client named $($entry.Key), found $($clients.Count)."
    }

    $client = $clients[0]
    $defaultScopesEndpoint = "$server/admin/realms/$RealmName/clients/$($client.id)/default-client-scopes"
    $assignedScopes = @(Invoke-KeycloakAdmin -Method Get -Uri $defaultScopesEndpoint -Headers $headers -Body $null)
    $assignedScopeNames = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($assignedScope in $assignedScopes) {
        if ($null -eq $assignedScope) {
            continue
        }
        foreach ($assignedName in @($assignedScope.name)) {
            [void]$assignedScopeNames.Add([string]$assignedName)
        }
    }
    foreach ($scopeName in $entry.Value) {
        if ($assignedScopeNames.Contains([string]$scopeName)) {
            Write-Host "[KEEP] $($entry.Key) -> $scopeName"
            continue
        }
        if (-not $scopeIdsByName.ContainsKey([string]$scopeName)) {
            throw "Keycloak client scope $scopeName was not found after provisioning."
        }
        $scopeId = $scopeIdsByName[[string]$scopeName]
        Invoke-KeycloakAdmin -Method Put `
            -Uri "$defaultScopesEndpoint/$scopeId" `
            -Headers $headers -Body $null | Out-Null
        [void]$assignedScopeNames.Add([string]$scopeName)
        Write-Host "[ADD] $($entry.Key) -> $scopeName"
    }
}

Write-Host 'Phase 3 Keycloak scopes provisioned without resetting the realm.' -ForegroundColor Green

& (Join-Path $PSScriptRoot 'provision-console-identity.ps1')
if ($LASTEXITCODE -ne 0) {
    throw 'Console identity provisioning failed.'
}
