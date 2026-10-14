param(
    [Parameter(Mandatory=$true)][string]$TestName,
    [Parameter(Mandatory=$true)][string]$SqlitePath,
    [switch]$ExistingSQLite,
    [string]$SyncUrl='http://127.0.0.1:18082',
    [string]$ProxyUrl='http://127.0.0.1:18083',
    [string]$UatRoot,
    [string]$JdkPath,
    [string]$PsqlPath
)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..'))
$project=Join-Path $repo 'App Pedidos\KotlinProject'
if([string]::IsNullOrWhiteSpace($UatRoot)) { $UatRoot=$env:COT_UAT_ROOT }
if([string]::IsNullOrWhiteSpace($UatRoot)) {
    if([string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) { throw 'LOCALAPPDATA or COT_UAT_ROOT is required' }
    $UatRoot=Join-Path $env:LOCALAPPDATA 'CotizacionesUAT\05C3A'
}
$uat=[IO.Path]::GetFullPath($UatRoot)
if([string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) { throw 'LOCALAPPDATA is required to protect the operational SQLite boundary' }
$expectedUat=[IO.Path]::GetFullPath((Join-Path $env:LOCALAPPDATA 'CotizacionesUAT\05C3A'))
if($uat -ne $expectedUat) { throw 'UAT root must be the isolated CotizacionesUAT\05C3A directory' }
$sourceDir=Join-Path $uat 'sync-kotlin-05c3p'
$runDir=Join-Path $uat 'sync-kotlin-05c3p3'
$sqliteRoot=Join-Path $runDir 'sqlite'
$sqlite=[IO.Path]::GetFullPath($SqlitePath)
$root=[IO.Path]::GetFullPath($sqliteRoot).TrimEnd('\')+'\'
$operational=[IO.Path]::GetFullPath((Join-Path $env:LOCALAPPDATA 'EffyFlow\effy.db'))
if(!$sqlite.StartsWith($root,[StringComparison]::OrdinalIgnoreCase)) { throw 'SQLite path must be below the 05C-3P3 UAT directory' }
if([IO.Path]::GetFullPath($sqlite) -eq $operational) { throw 'Refusing to open operational SQLite' }
function Assert-LoopbackEndpoint([string]$Value,[int]$ExpectedPort,[string]$Name) {
    $uri=$null
    if(![uri]::TryCreate($Value,[UriKind]::Absolute,[ref]$uri) -or $uri.Scheme -ne 'http' -or $uri.Host -ne '127.0.0.1' -or $uri.Port -ne $ExpectedPort -or $uri.AbsolutePath -notin @('','/') -or $uri.UserInfo) {
        throw "$Name must target http://127.0.0.1:$ExpectedPort only"
    }
}
Assert-LoopbackEndpoint $SyncUrl 18082 'SyncUrl'
Assert-LoopbackEndpoint $ProxyUrl 18083 'ProxyUrl'
if($ExistingSQLite -and !(Test-Path -LiteralPath $sqlite -PathType Leaf)) { throw 'Expected existing temporary SQLite file is missing' }
if(!$ExistingSQLite -and (Test-Path -LiteralPath $sqlite)) { throw 'Refusing to overwrite an existing temporary SQLite file' }
if(!(Test-Path (Join-Path $sourceDir 'test-credentials.secret')) -or !(Test-Path (Join-Path $uat 'postgres-app.pw'))) { throw 'UAT synthetic credential files are unavailable' }
if([string]::IsNullOrWhiteSpace($JdkPath)) { $JdkPath=$env:JAVA_HOME }
if([string]::IsNullOrWhiteSpace($JdkPath) -or !(Test-Path (Join-Path $JdkPath 'bin\java.exe'))) { throw 'Set JdkPath or JAVA_HOME to a JDK 21 installation' }
$javaVersion=(& (Join-Path $JdkPath 'bin\java.exe') -version 2>&1 | Out-String)
if($javaVersion -notmatch 'version "21\.') { throw 'JDK 21 is required for Kotlin JVM tests' }
if([string]::IsNullOrWhiteSpace($PsqlPath)) { $PsqlPath=$env:COT_UAT_PSQL }
if([string]::IsNullOrWhiteSpace($PsqlPath)) {
    $psqlCommand=Get-Command psql.exe -ErrorAction SilentlyContinue
    if($psqlCommand) { $PsqlPath=$psqlCommand.Source }
}
if([string]::IsNullOrWhiteSpace($PsqlPath) -or !(Test-Path $PsqlPath -PathType Leaf)) { throw 'Set PsqlPath or COT_UAT_PSQL to the PostgreSQL 17 client' }
$psqlVersion=(& $PsqlPath --version 2>&1 | Out-String)
if($psqlVersion -notmatch '\b17\.') { throw 'PostgreSQL 17 client is required' }
$kv=@{}
Get-Content (Join-Path $sourceDir 'test-credentials.secret') | ForEach-Object {
    if($_ -match '^([^=]+)=(.*)$') { $kv[$matches[1]]=$matches[2] }
}
$env:PGPASSWORD=(Get-Content (Join-Path $uat 'postgres-app.pw') -Raw).Trim()
$identity=& $PsqlPath -X -w -h 127.0.0.1 -p 55433 -U cotizaciones_uat_app -d cotizaciones_uat_05c3p2 -A -t -v ON_ERROR_STOP=1 -c "SELECT current_database()||'|'||current_user||'|'||host(inet_server_addr())||'|'||inet_server_port()||'|'||split_part(version(),' ',2)"
if($LASTEXITCODE -ne 0 -or $identity.Trim() -ne 'cotizaciones_uat_05c3p2|cotizaciones_uat_app|127.0.0.1|55433|17.5') { Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue; throw 'PostgreSQL UAT identity check failed' }
Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $sqliteRoot | Out-Null
$env:COT05C3P_SYNC_URL=$SyncUrl
$env:COT05C3P_SYNC_PROXY_URL=$ProxyUrl
$env:COT05C3P_SIMULATOR_URL='http://127.0.0.1:18081'
$env:COT05C3P_SQLITE_PATH=$sqlite
$env:COT05C3P_SQLITE_ROOT=[IO.Path]::GetFullPath($sqliteRoot)
$env:COT05C3P_USER_ONE='uat-vendedor-uno@cotizaciones.invalid'
$env:COT05C3P_PASSWORD_ONE=$kv.seller1
$env:COT05C3P_USER_TWO='uat-agente-dos@cotizaciones.invalid'
$env:COT05C3P_PASSWORD_TWO=$kv.seller2
$env:COT05C3P_USER_THREE='uat-sin-permiso@cotizaciones.invalid'
$env:COT05C3P_PASSWORD_THREE=$kv.seller3
$env:COT05C3P_CONTROL_KEY=$kv.control
$env:COT05C3P_PSQL=$PsqlPath
$env:COT05C3P_DB_PASSWORD_FILE=Join-Path $uat 'postgres-app.pw'
$env:COT05C3P_DB_NAME='cotizaciones_uat_05c3p2'
$env:JAVA_HOME=$JdkPath
$env:Path="$JdkPath\bin;$env:Path"
Push-Location $project
try {
    & .\gradlew.bat :composeApp:jvmTest --tests "com.cyssoftware.effyflow.CotizacionEstadosRemainingResilienceRealSyncIntegrationTest.$TestName" --no-daemon
    exit $LASTEXITCODE
} finally {
    Pop-Location
    Remove-Item Env:COT05C3P_PASSWORD_ONE,Env:COT05C3P_PASSWORD_TWO,Env:COT05C3P_PASSWORD_THREE,Env:COT05C3P_CONTROL_KEY -ErrorAction SilentlyContinue
}
