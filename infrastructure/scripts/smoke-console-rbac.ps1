[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$envFile = Join-Path $repositoryRoot '.env'
$redirectUri = 'http://localhost:8084/console.html'
$merchantId = '11111111-1111-4111-8111-111111111111'

function Import-DotEnv {
    if (-not (Test-Path -LiteralPath $envFile)) {
        throw 'Missing .env. Run .\payflow.ps1 rebuild first.'
    }
    $values = @{}
    foreach ($line in Get-Content -LiteralPath $envFile) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#')) { continue }
        $separator = $trimmed.IndexOf('=')
        if ($separator -lt 1) { throw "Invalid .env line: $trimmed" }
        $values[$trimmed.Substring(0, $separator).Trim()] = $trimmed.Substring($separator + 1).Trim()
    }
    return $values
}

function Require-Value {
    param([hashtable]$Values, [string]$Name)
    $value = $Values[$Name]
    if ([string]::IsNullOrWhiteSpace($value)) { throw "Required value $Name is missing from .env." }
    return $value
}

function ConvertTo-Base64Url {
    param([byte[]]$Bytes)
    return [Convert]::ToBase64String($Bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function New-RandomBase64Url {
    $bytes = [byte[]]::new(32)
    [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    return ConvertTo-Base64Url $bytes
}

function ConvertTo-QueryString {
    param([hashtable]$Values)
    return (($Values.GetEnumerator() | ForEach-Object {
        "{0}={1}" -f [Uri]::EscapeDataString([string]$_.Key), [Uri]::EscapeDataString([string]$_.Value)
    }) -join '&')
}

function Get-QueryValue {
    param([Uri]$Uri, [string]$Name)
    foreach ($part in $Uri.Query.TrimStart('?').Split('&', [StringSplitOptions]::RemoveEmptyEntries)) {
        $pair = $part.Split('=', 2)
        if ([Uri]::UnescapeDataString($pair[0]) -eq $Name) {
            return [Uri]::UnescapeDataString($pair[1])
        }
    }
    return $null
}

function Get-JwtClaims {
    param([string]$AccessToken)
    $segment = $AccessToken.Split('.')[1].Replace('-', '+').Replace('_', '/')
    switch ($segment.Length % 4) { 2 { $segment += '==' }; 3 { $segment += '=' } }
    return ([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($segment)) | ConvertFrom-Json)
}

function Get-UserAccessToken {
    param([string]$Username, [string]$Password, [string]$Issuer, [string]$ClientId)

    $verifier = New-RandomBase64Url
    $state = New-RandomBase64Url
    $challengeBytes = [Security.Cryptography.SHA256]::HashData([Text.Encoding]::ASCII.GetBytes($verifier))
    $challenge = ConvertTo-Base64Url $challengeBytes
    $authQuery = ConvertTo-QueryString @{
        client_id = $ClientId
        redirect_uri = $redirectUri
        response_type = 'code'
        scope = 'openid'
        state = $state
        code_challenge = $challenge
        code_challenge_method = 'S256'
    }

    $handler = [Net.Http.HttpClientHandler]::new()
    $handler.AllowAutoRedirect = $true
    $handler.CookieContainer = [Net.CookieContainer]::new()
    $client = [Net.Http.HttpClient]::new($handler)
    try {
        $loginPage = $client.GetAsync("$Issuer/protocol/openid-connect/auth?$authQuery").GetAwaiter().GetResult()
        if (-not $loginPage.IsSuccessStatusCode) { throw "Keycloak login page returned HTTP $([int]$loginPage.StatusCode)." }
        $html = $loginPage.Content.ReadAsStringAsync().GetAwaiter().GetResult()

        # Keycloak marks auth cookies Secure. Browsers treat localhost as a secure context;
        # this local HTTP-only smoke client must relax that flag to emulate the browser round trip.
        foreach ($cookie in $handler.CookieContainer.GetAllCookies()) { $cookie.Secure = $false }

        $formMatch = [regex]::Match($html, '<form[^>]+id=["'']kc-form-login["''][^>]+action=["'']([^"'']+)["'']', 'IgnoreCase')
        if (-not $formMatch.Success) { throw 'Keycloak login form was not found.' }
        $action = [Net.WebUtility]::HtmlDecode($formMatch.Groups[1].Value)
        $fields = [Collections.Generic.Dictionary[string,string]]::new()
        $fields['username'] = $Username
        $fields['password'] = $Password
        $fields['credentialId'] = ''
        $fields['login'] = 'Sign In'
        $loginResponse = $client.PostAsync($action, [Net.Http.FormUrlEncodedContent]::new($fields)).GetAwaiter().GetResult()
        $finalUri = $loginResponse.RequestMessage.RequestUri
        $code = Get-QueryValue $finalUri 'code'
        $returnedState = Get-QueryValue $finalUri 'state'
        if ([string]::IsNullOrWhiteSpace($code)) {
            $loginHtml = $loginResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            $feedback = [regex]::Match($loginHtml, '<[^>]+id=["'']input-error["''][^>]*>(.*?)</[^>]+>', 'IgnoreCase,Singleline')
            $reason = if ($feedback.Success) { ([regex]::Replace([Net.WebUtility]::HtmlDecode($feedback.Groups[1].Value), '<[^>]+>', '')).Trim() } else { 'no Keycloak feedback was returned' }
            throw "Interactive login for $Username returned no authorization code ($reason; final path $($finalUri.AbsolutePath))."
        }
        if ($returnedState -ne $state) { throw "Interactive login for $Username returned an invalid state." }

        $tokenFields = [Collections.Generic.Dictionary[string,string]]::new()
        $tokenFields['grant_type'] = 'authorization_code'
        $tokenFields['client_id'] = $ClientId
        $tokenFields['redirect_uri'] = $redirectUri
        $tokenFields['code'] = $code
        $tokenFields['code_verifier'] = $verifier
        $tokenResponse = $client.PostAsync("$Issuer/protocol/openid-connect/token", [Net.Http.FormUrlEncodedContent]::new($tokenFields)).GetAwaiter().GetResult()
        if (-not $tokenResponse.IsSuccessStatusCode) { throw "Token exchange for $Username returned HTTP $([int]$tokenResponse.StatusCode)." }
        $tokens = $tokenResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
        if ([string]::IsNullOrWhiteSpace($tokens.access_token)) { throw "Token exchange for $Username returned no access token." }
        return [string]$tokens.access_token
    }
    finally {
        $client.Dispose()
        $handler.Dispose()
    }
}

function Invoke-ApiStatus {
    param([string]$AccessToken, [string]$Method, [string]$Uri, [string]$JsonBody)
    $client = [Net.Http.HttpClient]::new()
    try {
        $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::new($Method), $Uri)
        $request.Headers.Authorization = [Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $AccessToken)
        if (-not [string]::IsNullOrWhiteSpace($JsonBody)) {
            $request.Content = [Net.Http.StringContent]::new($JsonBody, [Text.Encoding]::UTF8, 'application/json')
        }
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        return [int]$response.StatusCode
    }
    finally { $client.Dispose() }
}

function Assert-Status {
    param([string]$Label, [int]$Actual, [int]$Expected)
    if ($Actual -ne $Expected) { throw "$Label returned HTTP $Actual; expected $Expected." }
    Write-Host "[OK] $Label -> HTTP $Actual" -ForegroundColor Green
}

$environment = Import-DotEnv
$issuer = (Require-Value $environment 'PAYFLOW_OIDC_ISSUER_URI').TrimEnd('/')
$clientId = Require-Value $environment 'PAYFLOW_CONSOLE_CLIENT_ID'
$gatewayPort = Require-Value $environment 'PAYFLOW_GATEWAY_PORT'
$gateway = "http://localhost:$gatewayPort"

$merchantAdminToken = Get-UserAccessToken 'merchant.admin' (Require-Value $environment 'PAYFLOW_MERCHANT_ADMIN_PASSWORD') $issuer $clientId
$merchantUserToken = Get-UserAccessToken 'merchant.user' (Require-Value $environment 'PAYFLOW_MERCHANT_USER_PASSWORD') $issuer $clientId
$operationsToken = Get-UserAccessToken 'operations.admin' (Require-Value $environment 'PAYFLOW_OPERATIONS_USER_PASSWORD') $issuer $clientId

$merchantAdminClaims = Get-JwtClaims $merchantAdminToken
$merchantUserClaims = Get-JwtClaims $merchantUserToken
$operationsClaims = Get-JwtClaims $operationsToken
if ($merchantAdminClaims.merchant_id -ne $merchantId -or $merchantUserClaims.merchant_id -ne $merchantId) {
    throw 'Merchant users are missing the expected merchant_id claim.'
}
if ($merchantAdminClaims.realm_access.roles -notcontains 'MERCHANT_ADMIN') { throw 'merchant.admin is missing MERCHANT_ADMIN.' }
if ($merchantUserClaims.realm_access.roles -notcontains 'MERCHANT_USER') { throw 'merchant.user is missing MERCHANT_USER.' }
if ($operationsClaims.realm_access.roles -notcontains 'OPERATIONS') { throw 'operations.admin is missing OPERATIONS.' }
Write-Host '[OK] three real Keycloak user JWTs contain the expected persona and tenant claims' -ForegroundColor Green

Assert-Status 'merchant.admin reads own merchant' (Invoke-ApiStatus $merchantAdminToken 'GET' "$gateway/api/v1/merchants/$merchantId" '') 200
Assert-Status 'merchant.user reads own merchant' (Invoke-ApiStatus $merchantUserToken 'GET' "$gateway/api/v1/merchants/$merchantId" '') 200
Assert-Status 'merchant.user cannot create payment' (Invoke-ApiStatus $merchantUserToken 'POST' "$gateway/api/v1/payments" '{}') 403
Assert-Status 'merchant.admin cannot open operations queue' (Invoke-ApiStatus $merchantAdminToken 'GET' "$gateway/api/v1/operations/payments/manual-review" '') 403
Assert-Status 'operations.admin opens operations queue' (Invoke-ApiStatus $operationsToken 'GET' "$gateway/api/v1/operations/payments/manual-review" '') 200
Assert-Status 'operations.admin reads any merchant' (Invoke-ApiStatus $operationsToken 'GET' "$gateway/api/v1/merchants/$merchantId" '') 200

Write-Host 'Real-user Authorization Code + PKCE and RBAC smoke test passed; no token or password was printed.' -ForegroundColor Green
