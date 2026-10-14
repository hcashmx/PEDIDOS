param([int]$DropAcks=0,[int]$MutateEventId=0,[string]$UatRoot)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..'))
if([string]::IsNullOrWhiteSpace($UatRoot)) { $UatRoot=$env:COT_UAT_ROOT }
if([string]::IsNullOrWhiteSpace($UatRoot)) {
    if([string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) { throw 'LOCALAPPDATA or COT_UAT_ROOT is required' }
    $UatRoot=Join-Path $env:LOCALAPPDATA 'CotizacionesUAT\05C3A'
}
$uat=[IO.Path]::GetFullPath($UatRoot)
if([string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) { throw 'LOCALAPPDATA is required to confine UAT files' }
$expectedUat=[IO.Path]::GetFullPath((Join-Path $env:LOCALAPPDATA 'CotizacionesUAT\05C3A'))
if($uat -ne $expectedUat) { throw 'UAT root must be the isolated CotizacionesUAT\05C3A directory' }
$dir=Join-Path $uat 'sync-kotlin-05c3p3'
$credentials=Join-Path $uat 'sync-kotlin-05c3p'
$script=Join-Path $repo 'uat-probe\COT-MOBILE-05C-3P\sync-ack-drop-proxy.js'
$node=Get-Command node.exe -ErrorAction Stop
if(!(Test-Path $script)) { throw 'UAT Sync proxy script is missing' }
if(!(Test-Path (Join-Path $credentials 'test-credentials.secret'))) { throw 'UAT control credential file is missing' }
if(@(Get-NetTCPConnection -State Listen -LocalPort 18083 -ErrorAction SilentlyContinue).Count -ne 0) { throw 'Proxy port 18083 is occupied' }
$secret=@{}
Get-Content (Join-Path $credentials 'test-credentials.secret') | ForEach-Object {
    if($_ -match '^([^=]+)=(.*)$') { $secret[$matches[1]]=$matches[2] }
}
$env:COT_SYNC_PROXY_PORT='18083'
$env:COT_SYNC_PROXY_UPSTREAM_PORT='18082'
$env:COT_SYNC_PROXY_DROP_ACKS=[string]$DropAcks
$env:COT_SYNC_PROXY_MUTATE_EVENT_ID=[string]$MutateEventId
$env:COT_SYNC_PROXY_CONTROL_KEY=$secret.control
$logDir=Join-Path $dir 'logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$process=Start-Process -FilePath $node.Source -ArgumentList ('"'+$script+'"') -WorkingDirectory $repo -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $logDir 'sync-proxy-3p3.log') -RedirectStandardError (Join-Path $logDir 'sync-proxy-3p3.err')
Remove-Item Env:COT_SYNC_PROXY_CONTROL_KEY -ErrorAction SilentlyContinue
for($i=0;$i -lt 30;$i++) {
    Start-Sleep -Milliseconds 200
    $listener=Get-NetTCPConnection -State Listen -LocalPort 18083 -ErrorAction SilentlyContinue | Where-Object LocalAddress -eq '127.0.0.1'
    if($listener) {
        $health=Invoke-RestMethod 'http://127.0.0.1:18083/__proxy_health' -TimeoutSec 2
        if($health.status -eq 'UP') { [pscustomobject]@{Pid=$process.Id;Bind='127.0.0.1:18083';Upstream='127.0.0.1:18082'}; return }
    }
}
throw 'Sync proxy did not bind to loopback port 18083'
