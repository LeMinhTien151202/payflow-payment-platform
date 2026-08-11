param(
    [Parameter(Mandatory = $true)][string]$Namespace,
    [switch]$ConfirmChaos
)

$ErrorActionPreference = "Stop"
if (-not $ConfirmChaos) {
    throw "This script deletes one disposable settlement pod. Re-run with -ConfirmChaos after verifying the target cluster."
}
if ($Namespace -ne "payflow" -and $Namespace -ne "payflow-staging") {
    throw "Chaos is restricted to payflow or payflow-staging namespaces."
}

$context = kubectl config current-context
if ([string]::IsNullOrWhiteSpace($context)) { throw "No kubectl context is active." }
$pods = @(kubectl -n $Namespace get pods -l app.kubernetes.io/name=settlement-service -o name)
if ($pods.Count -lt 2) { throw "At least two settlement pods are required before restart chaos." }

Write-Host "Context: $context; namespace: $Namespace; deleting: $($pods[0])"
kubectl -n $Namespace delete $pods[0] --wait=false
kubectl -n $Namespace rollout status deployment/settlement-service --timeout=180s
