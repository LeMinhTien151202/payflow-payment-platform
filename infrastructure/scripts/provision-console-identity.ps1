[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$EnvFile = Join-Path $RepositoryRoot '.env'
$RealmName = 'payflow'
$MerchantId = '11111111-1111-4111-8111-111111111111'

function Import-DotEnv([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Missing $Path. Copy .env.example to .env first."
    }
    $values = @{}
    foreach ($line in Get-Content -LiteralPath $Path) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#')) { continue }
        $separator = $trimmed.IndexOf('=')
        if ($separator -lt 1) { throw "Invalid .env line: $trimmed" }
        $values[$trimmed.Substring(0, $separator).Trim()] = $trimmed.Substring($separator + 1).Trim()
    }
    return $values
}

function Require-Value([hashtable]$Values, [string]$Name) {
    $value = $Values[$Name]
    if ([string]::IsNullOrWhiteSpace($value)) { throw "Required value $Name is missing from .env." }
    return $value
}

function Invoke-KeycloakAdmin {
    param(
        [ValidateSet('Get', 'Post', 'Put', 'Delete')][string]$Method,
        [string]$Uri,
        [hashtable]$Headers,
        [object]$Body = $null
    )
    $arguments = @{ Method = $Method; Uri = $Uri; Headers = $Headers; TimeoutSec = 20 }
    if ($null -ne $Body) {
        $arguments.ContentType = 'application/json'
        $arguments.Body = ConvertTo-Json -InputObject $Body -Depth 30 -Compress
    }
    try {
        $response = Invoke-RestMethod @arguments
        return $response
    }
    catch { throw "Keycloak admin $Method $Uri failed: $($_.Exception.Message)" }
}

$environment = Import-DotEnv $EnvFile
$issuer = (Require-Value $environment 'PAYFLOW_OIDC_ISSUER_URI').TrimEnd('/')
$server = $issuer.Substring(0, $issuer.LastIndexOf('/realms/'))
$origin = (Require-Value $environment 'PAYFLOW_CONSOLE_ORIGIN').TrimEnd('/')
$clientId = Require-Value $environment 'PAYFLOW_CONSOLE_CLIENT_ID'

$adminToken = Invoke-RestMethod -Method Post `
    -Uri "$server/realms/master/protocol/openid-connect/token" `
    -ContentType 'application/x-www-form-urlencoded' `
    -Body @{
        grant_type = 'password'
        client_id = 'admin-cli'
        username = Require-Value $environment 'KEYCLOAK_ADMIN'
        password = Require-Value $environment 'KEYCLOAK_ADMIN_PASSWORD'
    } -TimeoutSec 20
$headers = @{ Authorization = "Bearer $($adminToken.access_token)" }
$adminBase = "$server/admin/realms/$RealmName"

$permissionRoles = @(
    'payment:read', 'payment:write', 'operations:write',
    'merchant:read', 'merchant:write', 'merchant:read:any', 'merchant:write:any',
    'reporting:read', 'reporting:rebuild', 'webhook:retry',
    'settlement:read', 'settlement:run', 'reconciliation:read', 'reconciliation:run'
)
$personas = [ordered]@{
    'MERCHANT_ADMIN' = @('payment:read', 'payment:write', 'merchant:read', 'merchant:write', 'reporting:read', 'settlement:read')
    'MERCHANT_USER' = @('payment:read', 'merchant:read', 'reporting:read', 'settlement:read')
    'OPERATIONS' = @('operations:write', 'merchant:read:any', 'merchant:write:any', 'reporting:read', 'reporting:rebuild', 'webhook:retry', 'settlement:read', 'settlement:run', 'reconciliation:read', 'reconciliation:run')
}

$roles = @(Invoke-KeycloakAdmin Get "$adminBase/roles" $headers)
$rolesByName = @{}
foreach ($role in $roles) { $rolesByName[[string]$role.name] = $role }
foreach ($roleName in @($permissionRoles + @($personas.Keys))) {
    if ($rolesByName.ContainsKey($roleName)) {
        Write-Host "[KEEP] realm role $roleName"
        continue
    }
    Invoke-KeycloakAdmin Post "$adminBase/roles" $headers @{ name = $roleName } | Out-Null
    Write-Host "[ADD] realm role $roleName"
}
$roles = @(Invoke-KeycloakAdmin Get "$adminBase/roles" $headers)
$rolesByName = @{}
foreach ($role in $roles) { $rolesByName[[string]$role.name] = $role }

foreach ($persona in $personas.GetEnumerator()) {
    $personaRole = $rolesByName[$persona.Key]
    $currentComposites = @(Invoke-KeycloakAdmin Get "$adminBase/roles-by-id/$($personaRole.id)/composites" $headers)
    $currentNames = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($role in $currentComposites) { [void]$currentNames.Add([string]$role.name) }
    $missing = @($persona.Value | Where-Object { -not $currentNames.Contains([string]$_) } | ForEach-Object { $rolesByName[[string]$_] })
    if ($missing.Count -gt 0) {
        Invoke-KeycloakAdmin Post "$adminBase/roles-by-id/$($personaRole.id)/composites" $headers $missing | Out-Null
        Write-Host "[SYNC] composite role $($persona.Key)"
    } else {
        Write-Host "[KEEP] composite role $($persona.Key)"
    }
}

$clientBody = @{
    clientId = $clientId
    name = 'PayFlow browser console (local)'
    description = 'Public Authorization Code + PKCE client for human local users.'
    enabled = $true
    protocol = 'openid-connect'
    publicClient = $true
    bearerOnly = $false
    serviceAccountsEnabled = $false
    standardFlowEnabled = $true
    implicitFlowEnabled = $false
    directAccessGrantsEnabled = $false
    fullScopeAllowed = $true
    rootUrl = $origin
    baseUrl = '/console.html'
    redirectUris = @("$origin/console.html")
    webOrigins = @($origin)
    attributes = @{
        'access.token.lifespan' = '900'
        'pkce.code.challenge.method' = 'S256'
        'post.logout.redirect.uris' = "$origin/console.html"
    }
}
$encodedClientId = [Uri]::EscapeDataString($clientId)
$clients = @(Invoke-KeycloakAdmin Get "$adminBase/clients?clientId=$encodedClientId" $headers)
if ($clients.Count -eq 0) {
    Invoke-KeycloakAdmin Post "$adminBase/clients" $headers $clientBody | Out-Null
    Write-Host "[ADD] public PKCE client $clientId"
    $clients = @(Invoke-KeycloakAdmin Get "$adminBase/clients?clientId=$encodedClientId" $headers)
} elseif ($clients.Count -eq 1) {
    Invoke-KeycloakAdmin Put "$adminBase/clients/$($clients[0].id)" $headers $clientBody | Out-Null
    Write-Host "[SYNC] public PKCE client $clientId"
} else {
    throw "Expected at most one Keycloak client named $clientId, found $($clients.Count)."
}
$client = $clients[0]

$mapperBodies = @(
    @{
        name = 'realm-roles'
        protocol = 'openid-connect'
        protocolMapper = 'oidc-usermodel-realm-role-mapper'
        consentRequired = $false
        config = @{
            'claim.name' = 'realm_access.roles'
            'jsonType.label' = 'String'
            'multivalued' = 'true'
            'usermodel.realmRoleMapping.rolePrefix' = ''
            'access.token.claim' = 'true'
            'id.token.claim' = 'true'
            'userinfo.token.claim' = 'true'
        }
    },
    @{
        name = 'preferred-username'
        protocol = 'openid-connect'
        protocolMapper = 'oidc-usermodel-property-mapper'
        consentRequired = $false
        config = @{
            'user.attribute' = 'username'
            'claim.name' = 'preferred_username'
            'jsonType.label' = 'String'
            'access.token.claim' = 'true'
            'id.token.claim' = 'true'
            'userinfo.token.claim' = 'true'
        }
    },
    @{
        name = 'email'
        protocol = 'openid-connect'
        protocolMapper = 'oidc-usermodel-property-mapper'
        consentRequired = $false
        config = @{
            'user.attribute' = 'email'
            'claim.name' = 'email'
            'jsonType.label' = 'String'
            'access.token.claim' = 'true'
            'id.token.claim' = 'true'
            'userinfo.token.claim' = 'true'
        }
    },
    @{
        name = 'merchant-id-from-user'
        protocol = 'openid-connect'
        protocolMapper = 'oidc-usermodel-attribute-mapper'
        consentRequired = $false
        config = @{
            'user.attribute' = 'merchant_id'
            'claim.name' = 'merchant_id'
            'jsonType.label' = 'String'
            'access.token.claim' = 'true'
            'id.token.claim' = 'true'
            'userinfo.token.claim' = 'true'
        }
    }
)
$mappers = @(Invoke-KeycloakAdmin Get "$adminBase/clients/$($client.id)/protocol-mappers/models" $headers)
foreach ($mapperBody in $mapperBodies) {
    $mapper = @($mappers | Where-Object { $_.name -eq $mapperBody.name })
    if ($mapper.Count -eq 0) {
        Invoke-KeycloakAdmin Post "$adminBase/clients/$($client.id)/protocol-mappers/models" $headers $mapperBody | Out-Null
        Write-Host "[ADD] protocol mapper $($mapperBody.name)"
    } else {
        $mapperBody.id = $mapper[0].id
        Invoke-KeycloakAdmin Put "$adminBase/clients/$($client.id)/protocol-mappers/models/$($mapper[0].id)" $headers $mapperBody | Out-Null
        Write-Host "[SYNC] protocol mapper $($mapperBody.name)"
    }
}

# Keycloak 26 ignores unmanaged custom attributes by default. Register merchant_id explicitly;
# users may view it in their token/profile, while only administrators may change tenant binding.
$userProfile = Invoke-KeycloakAdmin Get "$adminBase/users/profile" $headers
$profileAttributes = @($userProfile.attributes)
if (@($profileAttributes | Where-Object { $_.name -eq 'merchant_id' }).Count -eq 0) {
    $profileAttributes += @{
        name = 'merchant_id'
        displayName = 'Merchant ID'
        multivalued = $false
        permissions = @{ view = @('admin', 'user'); edit = @('admin') }
    }
    $userProfile.attributes = $profileAttributes
    Invoke-KeycloakAdmin Put "$adminBase/users/profile" $headers $userProfile | Out-Null
    Write-Host '[ADD] managed user attribute merchant_id'
} else {
    Write-Host '[KEEP] managed user attribute merchant_id'
}

# Provisioning the complete role/client graph can outlive Keycloak's short admin token.
# Renew before user writes instead of weakening the realm token lifetime.
$adminToken = Invoke-RestMethod -Method Post `
    -Uri "$server/realms/master/protocol/openid-connect/token" `
    -ContentType 'application/x-www-form-urlencoded' `
    -Body @{
        grant_type = 'password'
        client_id = 'admin-cli'
        username = Require-Value $environment 'KEYCLOAK_ADMIN'
        password = Require-Value $environment 'KEYCLOAK_ADMIN_PASSWORD'
    } -TimeoutSec 20
$headers = @{ Authorization = "Bearer $($adminToken.access_token)" }

$users = @(
    @{ username = 'merchant.admin'; firstName = 'Merchant'; lastName = 'Admin'; email = 'merchant.admin@payflow.local'; role = 'MERCHANT_ADMIN'; passwordVariable = 'PAYFLOW_MERCHANT_ADMIN_PASSWORD'; attributes = @{ merchant_id = @($MerchantId) } },
    @{ username = 'merchant.user'; firstName = 'Merchant'; lastName = 'User'; email = 'merchant.user@payflow.local'; role = 'MERCHANT_USER'; passwordVariable = 'PAYFLOW_MERCHANT_USER_PASSWORD'; attributes = @{ merchant_id = @($MerchantId) } },
    @{ username = 'operations.admin'; firstName = 'Operations'; lastName = 'Admin'; email = 'operations.admin@payflow.local'; role = 'OPERATIONS'; passwordVariable = 'PAYFLOW_OPERATIONS_USER_PASSWORD'; attributes = @{} }
)
foreach ($definition in $users) {
    $encodedUsername = [Uri]::EscapeDataString($definition.username)
    $matches = @(Invoke-KeycloakAdmin Get "$adminBase/users?username=$encodedUsername&exact=true" $headers)
    $userBody = @{
        username = $definition.username
        enabled = $true
        emailVerified = $true
        firstName = $definition.firstName
        lastName = $definition.lastName
        email = $definition.email
        attributes = $definition.attributes
    }
    if ($matches.Count -eq 0) {
        Invoke-KeycloakAdmin Post "$adminBase/users" $headers $userBody | Out-Null
        $matches = @(Invoke-KeycloakAdmin Get "$adminBase/users?username=$encodedUsername&exact=true" $headers)
        Write-Host "[ADD] user $($definition.username)"
    } elseif ($matches.Count -eq 1) {
        Invoke-KeycloakAdmin Put "$adminBase/users/$($matches[0].id)" $headers $userBody | Out-Null
        Write-Host "[SYNC] user $($definition.username)"
    } else {
        throw "Expected at most one user named $($definition.username), found $($matches.Count)."
    }
    $user = $matches[0]
    $password = Require-Value $environment $definition.passwordVariable
    Invoke-KeycloakAdmin Put "$adminBase/users/$($user.id)/reset-password" $headers @{
        type = 'password'; value = $password; temporary = $false
    } | Out-Null

    $assignedRoles = @(Invoke-KeycloakAdmin Get "$adminBase/users/$($user.id)/role-mappings/realm" $headers)
    if (@($assignedRoles | Where-Object { $_.name -eq $definition.role }).Count -eq 0) {
        Invoke-KeycloakAdmin Post "$adminBase/users/$($user.id)/role-mappings/realm" $headers @($rolesByName[$definition.role]) | Out-Null
        Write-Host "[ADD] $($definition.username) -> $($definition.role)"
    } else {
        Write-Host "[KEEP] $($definition.username) -> $($definition.role)"
    }
}

Write-Host 'Console client, users and RBAC roles provisioned without resetting Keycloak.' -ForegroundColor Green
