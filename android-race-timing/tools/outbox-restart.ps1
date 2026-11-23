#Requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $Adb,
    [Parameter(Mandatory)] [string] $Serial,
    [Parameter(Mandatory)] [uri] $DeskAddress,
    [Parameter(Mandatory)] [ValidatePattern('^[0-9a-fA-F-]{36}$')] [string] $PairingCode,
    [Parameter(Mandatory)] [string] $OutputDirectory,
    [Parameter(Mandatory)] [scriptblock] $StartHeat,
    [Parameter(Mandatory)] [scriptblock] $ApplyHeat
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$package = 'de.df.jauswertung.zeitnahme.android'
$runner = "$package.test/androidx.test.runner.AndroidJUnitRunner"
$outputPath = [IO.Directory]::CreateDirectory($OutputDirectory).FullName
$adbPath = (Resolve-Path -LiteralPath $Adb).Path
if ($DeskAddress.Scheme -ne 'https' -or $DeskAddress.AbsolutePath -ne '/' -or
    $DeskAddress.UserInfo -or $DeskAddress.Query -or $DeskAddress.Fragment) {
    throw 'Provide the desk HTTPS origin without credentials or a path.'
}

function Wait-TestFile([string] $Name, [Diagnostics.Process] $Process) {
    $deadline = [DateTime]::UtcNow.AddSeconds(120)
    do {
        $contents = & $adbPath -s $Serial shell run-as $package cat "files/$Name" 2>$null
        if ($LASTEXITCODE -eq 0 -and $contents) {
            return ($contents -join [Environment]::NewLine)
        }
        if ($Process.HasExited) { break }
        Start-Sleep -Milliseconds 300
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Instrumentation did not produce $Name. See $outputPath."
}

function Signal-Test([string] $Name) {
    & $adbPath -s $Serial shell run-as $package touch "files/$Name"
    if ($LASTEXITCODE -ne 0) { throw "Couldn't signal $Name on the emulator." }
}

function Invoke-Phase([string] $Phase, [string[]] $Extra, [scriptblock] $Coordinate) {
    $stdout = Join-Path $outputPath "$Phase.log"
    $method = "$package.OutboxRecoveryTest#$Phase"
    $launch = @{
        FilePath = $adbPath
        ArgumentList = @('-s', $Serial, 'shell', 'am', 'instrument', '-w', '-e', 'class', $method) + $Extra + $runner
        PassThru = $true
        WindowStyle = 'Hidden'
        RedirectStandardOutput = $stdout
        RedirectStandardError = Join-Path $outputPath "$Phase-error.log"
    }
    $process = Start-Process @launch
    try {
        & $Coordinate $process
        if (!$process.WaitForExit(30000) -or !(Select-String -LiteralPath $stdout -Pattern 'OK \(1 test\)' -Quiet)) {
            throw "$Phase failed. See $stdout."
        }
    } finally {
        if (!$process.HasExited) { $process.Kill() }
        $process.Dispose()
    }
}

foreach ($name in @('outbox-ready.json', 'outbox-first.json', 'outbox-exported', 'outbox-replayed.json',
        'outbox-applied', 'outbox-final.json')) {
    & $adbPath -s $Serial shell run-as $package rm -f "files/$name"
    if ($LASTEXITCODE -ne 0) { throw 'Install the debug app and test APK on the selected device first.' }
}

Invoke-Phase 'dropAcknowledgement' @('-e', 'restart', 'first', '-e', 'desk', $DeskAddress.GetLeftPart('Authority'), '-e', 'code', $PairingCode) {
    param($process)
    $ready = Wait-TestFile 'outbox-ready.json' $process
    $ready | Set-Content -LiteralPath (Join-Path $outputPath 'outbox-ready.json') -Encoding utf8
    & $StartHeat
    $first = Wait-TestFile 'outbox-first.json' $process
    $first | Set-Content -LiteralPath (Join-Path $outputPath 'outbox-first.json') -Encoding utf8
    Signal-Test 'outbox-exported'
}

& $adbPath -s $Serial shell am force-stop $package
if ($LASTEXITCODE -ne 0) { throw 'Could not stop the Android process.' }

Invoke-Phase 'replayAfterRestart' @('-e', 'restart', 'second') {
    param($process)
    $replayed = Wait-TestFile 'outbox-replayed.json' $process
    $replayed | Set-Content -LiteralPath (Join-Path $outputPath 'outbox-replayed.json') -Encoding utf8
    & $ApplyHeat
    Signal-Test 'outbox-applied'
    $final = Wait-TestFile 'outbox-final.json' $process
    $final | Set-Content -LiteralPath (Join-Path $outputPath 'outbox-final.json') -Encoding utf8
}
