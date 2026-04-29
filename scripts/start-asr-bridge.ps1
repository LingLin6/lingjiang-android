param(
    [string]$AsrProject = "",
    [int]$Port = 8765,
    [int]$ReadyTimeoutSeconds = 120
)

$ErrorActionPreference = "Stop"

function Read-SdkDir {
    $localProps = Join-Path (Resolve-Path ".") "local.properties"
    if (-not (Test-Path $localProps)) {
        throw "local.properties not found. Run this script from E:\AI\Android."
    }
    $line = Select-String -Path $localProps -Pattern "^sdk.dir=" | Select-Object -First 1
    if ($null -eq $line) {
        throw "sdk.dir is missing in local.properties."
    }
    return (($line.Line -replace "^sdk.dir=", "") -replace "\\\\", "\")
}

function Wait-BridgeReady {
    param([int]$Port, [int]$TimeoutSeconds)

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        try {
            $health = Invoke-RestMethod "http://127.0.0.1:$Port/health"
            if ($health.ready -eq $true) {
                return $health
            }
            Write-Host "ASR bridge warming: ready=$($health.ready), warming=$($health.warming)"
        } catch {
            Write-Host "ASR bridge not reachable yet: $($_.Exception.Message)"
        }
        Start-Sleep -Seconds 3
    } while ((Get-Date) -lt $deadline)

    throw "ASR bridge did not become ready within $TimeoutSeconds seconds."
}

if ([string]::IsNullOrWhiteSpace($AsrProject)) {
    $workspace = Split-Path -Parent $PSScriptRoot
    $aiRoot = Split-Path -Parent $workspace
    $voiceDir = -join ([char]0x8BED, [char]0x97F3, [char]0x8BC6, [char]0x522B)
    $AsrProject = Join-Path $aiRoot $voiceDir
}

if (-not (Test-Path $AsrProject)) {
    throw "ASR project not found: $AsrProject"
}

$old = Get-CimInstance Win32_Process -Filter "name = 'python.exe'" |
    Where-Object { $_.CommandLine -like "*voiceid_asr.http_bridge*" }
foreach ($process in $old) {
    Write-Host "Stopping old ASR bridge pid=$($process.ProcessId)"
    Stop-Process -Id $process.ProcessId -Force -ErrorAction SilentlyContinue
}
Start-Sleep -Milliseconds 500

$stdout = Join-Path $AsrProject "asr_bridge.out.log"
$stderr = Join-Path $AsrProject "asr_bridge.err.log"
Remove-Item -LiteralPath $stdout,$stderr -ErrorAction SilentlyContinue

$bridge = Start-Process -FilePath "python" `
    -ArgumentList @("-u", "-m", "voiceid_asr.http_bridge", "--host", "127.0.0.1", "--port", "$Port", "--engine", "funasr") `
    -WorkingDirectory $AsrProject `
    -RedirectStandardOutput $stdout `
    -RedirectStandardError $stderr `
    -PassThru `
    -WindowStyle Hidden
Write-Host "Started ASR bridge pid=$($bridge.Id)"

$health = Wait-BridgeReady -Port $Port -TimeoutSeconds $ReadyTimeoutSeconds

$sdkDir = Read-SdkDir
$adb = Join-Path $sdkDir "platform-tools\adb.exe"
if (-not (Test-Path $adb)) {
    throw "adb not found: $adb"
}

& $adb devices -l
& $adb reverse --remove-all | Out-Null
& $adb reverse "tcp:$Port" "tcp:$Port"
& $adb reverse --list

Write-Host "ASR bridge ready:"
$health | ConvertTo-Json -Depth 5
