[CmdletBinding()]
param(
    [string]$RepositoryRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($RepositoryRoot)) {
    $RepositoryRoot = [System.IO.Path]::GetFullPath(
        (Join-Path $PSScriptRoot '..\..\..\..')
    )
} else {
    $RepositoryRoot = (Resolve-Path -LiteralPath $RepositoryRoot).Path
}

$requiredPaths = @(
    'AGENTS.md',
    '.agent\AGENTS.md',
    '.agents\skills\payflow-backend\SKILL.md',
    '.agents\skills\payflow-backend\agents\openai.yaml',
    '.agents\skills\payflow-backend\references\feature-workflow.md',
    '.agents\skills\payflow-backend\references\financial-invariants.md',
    '.agents\skills\payflow-backend\references\verification.md',
    '.agents\skills\payflow-backend\scripts\validate-governance.ps1',
    '.docs\README.md',
    '.docs\PROJECT_OVERVIEW.md',
    '.docs\ARCHITECTURE.md',
    '.docs\MODULE_MAP.md',
    '.docs\DELIVERY_ROADMAP.md',
    '.docs\TESTING_STRATEGY.md',
    '.docs\IMPLEMENTATION_STATUS.md',
    '.docs\OPEN_DECISIONS.md',
    '.docs\adr\README.md',
    '.docs\adr\ADR-TEMPLATE.md'
)

$issues = [System.Collections.Generic.List[string]]::new()

foreach ($relativePath in $requiredPaths) {
    $fullPath = Join-Path $RepositoryRoot $relativePath
    if (-not (Test-Path -LiteralPath $fullPath)) {
        $issues.Add("Missing required path: $relativePath")
    }
}

$markdownFiles = [System.Collections.Generic.List[System.IO.FileInfo]]::new()
$scanRoots = @('AGENTS.md', '.agent', '.agents', '.docs')

foreach ($relativeRoot in $scanRoots) {
    $fullRoot = Join-Path $RepositoryRoot $relativeRoot
    if (-not (Test-Path -LiteralPath $fullRoot)) {
        continue
    }

    $item = Get-Item -LiteralPath $fullRoot
    if ($item -is [System.IO.FileInfo] -and $item.Extension -eq '.md') {
        $markdownFiles.Add($item)
        continue
    }

    Get-ChildItem -File -Recurse -LiteralPath $fullRoot -Filter '*.md' |
        ForEach-Object { $markdownFiles.Add($_) }
}

$uniqueMarkdownFiles = $markdownFiles |
    Sort-Object -Property FullName -Unique

foreach ($file in $uniqueMarkdownFiles) {
    $content = Get-Content -Raw -Encoding UTF8 -LiteralPath $file.FullName
    $lines = Get-Content -Encoding UTF8 -LiteralPath $file.FullName

    if ($content -match '(?i)\bTODO\b|\[TODO') {
        $issues.Add("Placeholder marker in: $($file.FullName)")
    }

    if ($content -match '(?m)^(<<<<<<<|=======|>>>>>>>)') {
        $issues.Add("Merge conflict marker in: $($file.FullName)")
    }

    $fenceCount = @($lines | Where-Object { $_ -match '^```' }).Count
    if (($fenceCount % 2) -ne 0) {
        $issues.Add("Unbalanced code fence in: $($file.FullName)")
    }

    foreach ($match in [regex]::Matches($content, '\[[^\]]+\]\(([^)]+)\)')) {
        $target = $match.Groups[1].Value.Trim().Trim('<', '>')
        if ($target -match '^(https?://|mailto:|#)') {
            continue
        }

        $targetPath = ($target -split '#')[0]
        if ([string]::IsNullOrWhiteSpace($targetPath)) {
            continue
        }

        $resolvedTarget = Join-Path $file.DirectoryName $targetPath
        if (-not (Test-Path -LiteralPath $resolvedTarget)) {
            $issues.Add("Broken local link in $($file.FullName): $target")
        }
    }
}

if ($issues.Count -gt 0) {
    $issues | ForEach-Object { Write-Error $_ }
    exit 1
}

Write-Output "Governance validation passed: $($requiredPaths.Count) required paths and $($uniqueMarkdownFiles.Count) Markdown files checked."

