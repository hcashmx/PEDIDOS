param(
    [Parameter(Mandatory = $true)]
    [string]$Version
)

$ErrorActionPreference = "Stop"

function Format-FileSize {
    param([long]$Bytes)
    $mib = $Bytes / 1MB
    return ("{0:N0} bytes ({1:N2} MiB)" -f $Bytes, $mib)
}

function Get-JavaVersionOutput {
    param([string]$JavaExe)
    return (cmd /c "`"$JavaExe`" -version 2>&1") -join "`n"
}

function Test-Jdk21 {
    param([string]$JavaExe)
    $versionOutput = Get-JavaVersionOutput -JavaExe $JavaExe
    return $versionOutput -match 'version "21\.'
}

if ($Version -notmatch '^\d+\.\d+\.\d+$') {
    throw "Version invalida '$Version'. Usa formato numerico compatible con jpackage/MSI, por ejemplo 0.9.1."
}

$projectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$gradlew = Join-Path $projectDir "gradlew.bat"
if (-not (Test-Path -LiteralPath $gradlew)) {
    throw "No se encontro gradlew.bat en $projectDir"
}

$defaultJdk = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
$candidateJdk = $null

if ($env:JAVA_HOME) {
    $javaFromEnv = Join-Path $env:JAVA_HOME "bin\java.exe"
    if (Test-Path -LiteralPath $javaFromEnv) {
        if (Test-Jdk21 -JavaExe $javaFromEnv) {
            $candidateJdk = $env:JAVA_HOME
        }
    }
}

if (-not $candidateJdk -and (Test-Path -LiteralPath (Join-Path $defaultJdk "bin\java.exe"))) {
    $candidateJdk = $defaultJdk
}

if (-not $candidateJdk) {
    throw "No se encontro JDK 21. Instala o configura JAVA_HOME con JDK 21. Ruta esperada: $defaultJdk"
}

$javaExe = Join-Path $candidateJdk "bin\java.exe"
$jpackageExe = Join-Path $candidateJdk "bin\jpackage.exe"
if (-not (Test-Path -LiteralPath $jpackageExe)) {
    throw "El JDK seleccionado no contiene jpackage.exe: $candidateJdk"
}

if (-not (Test-Jdk21 -JavaExe $javaExe)) {
    throw "El JDK seleccionado no es JDK 21: $candidateJdk"
}

$msiDir = Join-Path $projectDir "composeApp\build\compose\binaries\main\msi"
$msiPath = Join-Path $msiDir "EffyFlow-$Version.msi"
if (Test-Path -LiteralPath $msiPath) {
    Remove-Item -LiteralPath $msiPath -Force
}

$startedAt = Get-Date
$env:JAVA_HOME = $candidateJdk
$env:PATH = "$candidateJdk\bin;$env:PATH"

Push-Location $projectDir
try {
    & $gradlew ":composeApp:packageMsi" "-PappVersion=$Version"
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle fallo con codigo $LASTEXITCODE"
    }
} catch {
    Write-Host "BUILD FAIL"
    throw
} finally {
    Pop-Location
}

if (-not (Test-Path -LiteralPath $msiPath)) {
    Write-Host "BUILD FAIL"
    throw "Gradle termino, pero no se encontro el MSI esperado: $msiPath"
}

$msi = Get-Item -LiteralPath $msiPath
if ($msi.LastWriteTime -lt $startedAt) {
    Write-Host "BUILD FAIL"
    throw "El MSI encontrado parece anterior a esta ejecucion: $msiPath"
}

$sha256 = (Get-FileHash -LiteralPath $msiPath -Algorithm SHA256).Hash

Write-Host "BUILD SUCCESS"
Write-Host "Version: $Version"
Write-Host "MSI: $($msi.FullName)"
Write-Host "Tamano: $(Format-FileSize -Bytes $msi.Length)"
Write-Host "SHA-256: $sha256"
