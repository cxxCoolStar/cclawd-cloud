[CmdletBinding()]
param(
    [switch]$IncludeLegacy,
    [switch]$PurgeData,
    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$repositoryRoot = (Resolve-Path (Join-Path $scriptDirectory "..\..")).Path
$composeFile = Join-Path $scriptDirectory "compose.yaml"
$environmentFile = Join-Path $repositoryRoot ".env"
$projectName = "openagent-local"

function Assert-LastExitCode {
    param([string]$Operation)

    if ($LASTEXITCODE -ne 0) {
        throw "$Operation failed with exit code $LASTEXITCODE"
    }
}

function Invoke-Compose {
    param([string[]]$ComposeArguments)

    $arguments = @("compose", "--project-name", $projectName)
    if (Test-Path -LiteralPath $environmentFile -PathType Leaf) {
        $arguments += @("--env-file", $environmentFile)
    }
    $arguments += @("--file", $composeFile)
    $arguments += $ComposeArguments
    & docker @arguments
    Assert-LastExitCode "docker compose $($ComposeArguments -join ' ')"
}

if ($null -eq (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "Docker CLI was not found."
}

& docker info *> $null
Assert-LastExitCode "docker info"

if ($PurgeData -and -not $Force) {
    throw "-PurgeData permanently deletes local PostgreSQL and Redis volumes. Add -Force to confirm."
}

$downArguments = @("down", "--remove-orphans", "--timeout", "30")
if ($PurgeData) {
    $downArguments += "--volumes"
}
Invoke-Compose $downArguments

if ($IncludeLegacy) {
    $legacyContainers = @(
        "openagent-v11-e2e-app",
        "openagent-v11-e2e-app-before-dns-fix",
        "openagent-v11-e2e-postgres",
        "openagent-v11-e2e-redis"
    )
    foreach ($container in $legacyContainers) {
        $state = & docker inspect --format "{{.State.Running}}" $container 2>$null
        if ($LASTEXITCODE -eq 0 -and $state -eq "true") {
            & docker stop --timeout 30 $container
            Assert-LastExitCode "docker stop $container"
        }
    }
}

if ($PurgeData) {
    Write-Host "OpenAgent local environment stopped; local database and Redis volumes were deleted." -ForegroundColor Yellow
} else {
    Write-Host "OpenAgent local environment stopped. PostgreSQL and Redis volumes were preserved." -ForegroundColor Green
}
if ($IncludeLegacy) {
    Write-Host "Known V11 legacy containers were stopped but not removed."
}

