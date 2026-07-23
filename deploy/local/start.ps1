[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [ValidateRange(30, 600)]
    [int]$TimeoutSeconds = 240
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$repositoryRoot = (Resolve-Path (Join-Path $scriptDirectory "..\..")).Path
$composeFile = Join-Path $scriptDirectory "compose.yaml"
$environmentFile = Join-Path $repositoryRoot ".env"
$projectName = "openagent-local"
$mavenWrapper = Join-Path $repositoryRoot "mvnw.cmd"
$executableJar = Join-Path $repositoryRoot "bootstrap\target\bootstrap-0.1.0-SNAPSHOT-exec.jar"

function Assert-LastExitCode {
    param([string]$Operation)

    if ($LASTEXITCODE -ne 0) {
        throw "$Operation failed with exit code $LASTEXITCODE"
    }
}

function Invoke-Compose {
    param([string[]]$ComposeArguments)

    & docker compose --project-name $projectName --env-file $environmentFile `
        --file $composeFile @ComposeArguments
    Assert-LastExitCode "docker compose $($ComposeArguments -join ' ')"
}

function Read-DotEnv {
    param([string]$Path)

    $values = @{}
    foreach ($line in Get-Content -LiteralPath $Path) {
        if ($line -match '^\s*(?<name>[A-Za-z_][A-Za-z0-9_]*)\s*=\s*(?<value>.*)\s*$') {
            $value = $Matches.value.Trim()
            if (($value.StartsWith('"') -and $value.EndsWith('"')) -or
                    ($value.StartsWith("'") -and $value.EndsWith("'"))) {
                $value = $value.Substring(1, $value.Length - 2)
            }
            $values[$Matches.name] = $value
        }
    }
    return $values
}

function Test-JavaHome {
    param([string]$Path)

    if ([string]::IsNullOrWhiteSpace($Path) -or
            -not (Test-Path -LiteralPath (Join-Path $Path "bin\java.exe") -PathType Leaf) -or
            -not (Test-Path -LiteralPath (Join-Path $Path "bin\javac.exe") -PathType Leaf)) {
        return $false
    }
    $releaseFile = Join-Path $Path "release"
    if (-not (Test-Path -LiteralPath $releaseFile -PathType Leaf)) {
        return $false
    }
    $versionLine = Get-Content -LiteralPath $releaseFile | Where-Object { $_ -match '^JAVA_VERSION=' } | Select-Object -First 1
    if ($versionLine -notmatch '^JAVA_VERSION="(?<major>\d+)') {
        return $false
    }
    return [int]$Matches.major -ge 17
}
function Resolve-JavaHome {
    if (Test-JavaHome $env:JAVA_HOME) {
        return $env:JAVA_HOME
    }

    $registryPaths = @(
        "HKLM:\SOFTWARE\JavaSoft\JDK",
        "HKLM:\SOFTWARE\WOW6432Node\JavaSoft\JDK"
    )
    foreach ($registryPath in $registryPaths) {
        foreach ($versionKey in Get-ChildItem -LiteralPath $registryPath -ErrorAction SilentlyContinue) {
            $javaHome = (Get-ItemProperty -LiteralPath $versionKey.PSPath -ErrorAction SilentlyContinue).JavaHome
            if (Test-JavaHome $javaHome) {
                return $javaHome
            }
        }
    }
    return $null
}

function Invoke-ApplicationBuild {
    param([string]$Dns)

    $javaHome = Resolve-JavaHome
    if ($null -ne $javaHome) {
        $previousJavaHome = $env:JAVA_HOME
        try {
            $env:JAVA_HOME = $javaHome
            Push-Location $repositoryRoot
            try {
                & $mavenWrapper "-DskipTests" "-Dopenagent.boot.classifier=exec" package
                Assert-LastExitCode "Maven application build"
            } finally {
                Pop-Location
            }
        } finally {
            $env:JAVA_HOME = $previousJavaHome
        }
    } else {
        Write-Host "No local JDK was found; building with a temporary JDK container."
        & docker run --rm --dns $Dns `
            --volume "${repositoryRoot}:/workspace" --workdir /workspace `
            eclipse-temurin:17-jdk-jammy sh -c `
            "chmod +x mvnw && ./mvnw -DskipTests -Dopenagent.boot.classifier=exec package"
        Assert-LastExitCode "containerized Maven application build"
    }

    if (-not (Test-Path -LiteralPath $executableJar -PathType Leaf)) {
        throw "Application build did not produce $executableJar"
    }
}
function Find-PortConflict {
    param(
        [int]$Port,
        [string[]]$RunningContainers
    )

    foreach ($container in $RunningContainers) {
        $parts = $container -split '\|', 2
        if ($parts.Length -ne 2 -or $parts[0] -like "$projectName-*") {
            continue
        }
        if ($parts[1] -match ":$Port->") {
            return $parts[0]
        }
    }
    return $null
}

if ($null -eq (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "Docker CLI was not found. Install and start Docker Desktop first."
}

& docker info *> $null
Assert-LastExitCode "docker info"

& docker compose version *> $null
Assert-LastExitCode "docker compose version"

if (-not (Test-Path -LiteralPath $environmentFile -PathType Leaf)) {
    throw "Missing $environmentFile. Create it from the model configuration in README.md."
}

$environment = Read-DotEnv $environmentFile
if (-not $environment.ContainsKey("OPENAGENT_MODEL_API_KEY") -or
        [string]::IsNullOrWhiteSpace($environment["OPENAGENT_MODEL_API_KEY"])) {
    throw "OPENAGENT_MODEL_API_KEY is missing or blank in .env."
}

function Get-LocalSetting {
    param(
        [string]$Name,
        [string]$DefaultValue
    )

    $processValue = [Environment]::GetEnvironmentVariable($Name, "Process")
    if (-not [string]::IsNullOrWhiteSpace($processValue)) {
        return $processValue
    }
    if ($environment.ContainsKey($Name)) {
        return $environment[$Name]
    }
    return $DefaultValue
}
function Get-LocalPort {
    param(
        [string]$Name,
        [int]$DefaultValue
    )

    $processValue = [Environment]::GetEnvironmentVariable($Name, "Process")
    if (-not [string]::IsNullOrWhiteSpace($processValue)) {
        return [int]$processValue
    }
    if ($environment.ContainsKey($Name)) {
        return [int]$environment[$Name]
    }
    return $DefaultValue
}

$appPort = Get-LocalPort "OPENAGENT_LOCAL_APP_PORT" 18956
$postgresPort = Get-LocalPort "OPENAGENT_LOCAL_POSTGRES_PORT" 15432
$redisPort = Get-LocalPort "OPENAGENT_LOCAL_REDIS_PORT" 16379

$runningContainers = @(& docker ps --format "{{.Names}}|{{.Ports}}")
Assert-LastExitCode "docker ps"
foreach ($port in @($appPort, $postgresPort, $redisPort)) {
    $conflict = Find-PortConflict -Port $port -RunningContainers $runningContainers
    if ($null -ne $conflict) {
        throw "Port $port is already published by container '$conflict'. Stop it first. For the old V11 environment, run: powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\deploy\local\shutdown.ps1 -IncludeLegacy"
    }
}

$localDns = Get-LocalSetting "OPENAGENT_LOCAL_DNS" "114.114.114.114"
if (-not $SkipBuild) {
    Invoke-ApplicationBuild $localDns
}

Invoke-Compose @("config", "--quiet")

$upArguments = @("up", "--detach", "--remove-orphans")
if (-not $SkipBuild) {
    $upArguments += "--build"
}
Invoke-Compose $upArguments

$deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
$readinessUri = "http://127.0.0.1:$appPort/actuator/health/readiness"
$ready = $false
while ([DateTime]::UtcNow -lt $deadline) {
    try {
        $health = Invoke-RestMethod -Uri $readinessUri -TimeoutSec 3
        if ($health.status -eq "UP") {
            $ready = $true
            break
        }
    } catch {
        # The application or its dependencies are still starting.
    }
    Start-Sleep -Seconds 3
}

if (-not $ready) {
    Write-Host "OpenAgent did not become ready. Current service state:" -ForegroundColor Red
    Invoke-Compose @("ps")
    Invoke-Compose @("logs", "--tail", "120", "app")
    throw "Readiness timeout after $TimeoutSeconds seconds. See docs/LOCAL_E2E_TROUBLESHOOTING.md."
}

Invoke-Compose @("ps")
Write-Host ""
Write-Host "OpenAgent local environment is ready." -ForegroundColor Green
Write-Host "Application: http://127.0.0.1:$appPort"
Write-Host "Readiness:   $readinessUri"
Write-Host "PostgreSQL:  127.0.0.1:$postgresPort"
Write-Host "Redis:       127.0.0.1:$redisPort"
Write-Host "Logs:        docker compose --project-name $projectName --env-file .env --file deploy/local/compose.yaml logs --follow app"

