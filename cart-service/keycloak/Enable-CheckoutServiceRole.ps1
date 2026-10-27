param(
    [Parameter(Mandatory = $true)][Security.SecureString]$AdminPassword,
    [string]$AdminUser = 'admin',
    [string]$KeycloakUrl = 'http://localhost:8180'
)

$ErrorActionPreference = 'Stop'
$realmUrl = "$KeycloakUrl/admin/realms/shopsphere"
$roleName = 'CART_CHECKOUT_SERVICE'
$headers = $null
$adminToken = $null
$plainPassword = $null
try {
    $plainPassword = [Net.NetworkCredential]::new('', $AdminPassword).Password
    $adminToken = Invoke-RestMethod -Method Post -Uri "$KeycloakUrl/realms/master/protocol/openid-connect/token" `
        -Body @{ grant_type = 'password'; client_id = 'admin-cli'; username = $AdminUser; password = $plainPassword }
    $plainPassword = $null
    $headers = @{ Authorization = "Bearer $($adminToken.access_token)" }
    $clients = @(Invoke-RestMethod -Uri "$realmUrl/clients?clientId=shopsphere-order" -Headers $headers)
    if ($clients.Count -ne 1) { throw 'Expected exactly one existing shopsphere-order client.' }
    $client = $clients[0]
    if ($client.publicClient -or !$client.serviceAccountsEnabled -or $client.fullScopeAllowed) {
        throw 'Existing client must be confidential, use service accounts and restrict role scopes.'
    }
    if ($client.standardFlowEnabled -or $client.implicitFlowEnabled -or $client.directAccessGrantsEnabled) {
        throw 'Existing service client must keep user login flows disabled.'
    }
    $audienceMapper = @($client.protocolMappers | Where-Object {
        $_.protocolMapper -eq 'oidc-audience-mapper' -and
        $_.config.'included.custom.audience' -eq 'shopsphere-api' -and $_.config.'access.token.claim' -eq 'true'
    })
    if ($audienceMapper.Count -eq 0) { throw 'Existing shopsphere-api audience mapper is required.' }
    $clientUrl = "$realmUrl/clients/$($client.id)"
    $account = Invoke-RestMethod -Uri "$clientUrl/service-account-user" -Headers $headers
    $accountRolesUrl = "$realmUrl/users/$($account.id)/role-mappings/realm"
    $scopeRolesUrl = "$clientUrl/scope-mappings/realm"
    $beforeAccount = @(Invoke-RestMethod -Uri $accountRolesUrl -Headers $headers)
    $beforeScope = @(Invoke-RestMethod -Uri $scopeRolesUrl -Headers $headers)

    $role = $null
    try {
        $role = Invoke-RestMethod -Uri "$realmUrl/roles/$roleName" -Headers $headers
    } catch {
        if ($null -eq $_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne 404) { throw }
    }
    if ($null -eq $role) {
        $body = @{ name = $roleName } | ConvertTo-Json -Compress
        Invoke-RestMethod -Method Post -Uri "$realmUrl/roles" -Headers $headers -ContentType 'application/json' -Body $body | Out-Null
        $role = Invoke-RestMethod -Uri "$realmUrl/roles/$roleName" -Headers $headers
    }
    if ($role.composite) { throw 'Checkout role must not be composite.' }
    $roleBody = ConvertTo-Json -InputObject @(@{ id = $role.id; name = $role.name }) -Compress
    if ($roleName -notin @($beforeAccount.name)) {
        Invoke-RestMethod -Method Post -Uri $accountRolesUrl -Headers $headers -ContentType 'application/json' -Body $roleBody | Out-Null
    }
    if ($roleName -notin @($beforeScope.name)) {
        Invoke-RestMethod -Method Post -Uri $scopeRolesUrl -Headers $headers -ContentType 'application/json' -Body $roleBody | Out-Null
    }
    $afterAccount = @(Invoke-RestMethod -Uri $accountRolesUrl -Headers $headers)
    $afterScope = @(Invoke-RestMethod -Uri $scopeRolesUrl -Headers $headers)
    if ($roleName -notin @($afterAccount.name) -or $roleName -notin @($afterScope.name)) {
        throw 'Checkout role assignment verification failed.'
    }
    foreach ($existing in $beforeAccount.name) {
        if ($existing -notin @($afterAccount.name)) { throw 'A pre-existing service-account role was lost.' }
    }
    foreach ($existing in $beforeScope.name) {
        if ($existing -notin @($afterScope.name)) { throw 'A pre-existing role scope was lost.' }
    }
    Write-Output 'Checkout role assigned and scoped to shopsphere-order; pre-existing roles preserved.'
} finally {
    $plainPassword = $null
    $headers = $null
    $adminToken = $null
}
