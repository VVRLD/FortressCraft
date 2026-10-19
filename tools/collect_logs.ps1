# Windows: packs everything FortCraft needs to see from a run into one zip to attach to a GitHub
# issue: both games' logs, FortCraft's own logs and basic system facts (Windows version, graphics
# card, Python, Git, setup's settings). No saves, no game files, no account details. Same idea as
# tools/collect_logs.sh on Linux. Run it through tools\collect_logs.bat (or COLLECT-LOGS.cmd).
$ErrorActionPreference = 'SilentlyContinue'
$project = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$root = Split-Path -Parent $project
$game = Join-Path $root 'source-sdk-2013\game'
$stamp = Get-Date -Format 'yyyy-MM-dd-HHmmss'
$work = Join-Path $env:TEMP "fortcraft-logs-$stamp"
New-Item -ItemType Directory -Force -Path $work | Out-Null

$os = Get-CimInstance Win32_OperatingSystem
$lines = New-Object System.Collections.Generic.List[string]
$lines.Add("date: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')")
$lines.Add("windows: $($os.Caption) $($os.Version)")
$lines.Add("memory: $([math]::Round($os.TotalVisibleMemorySize / 1MB, 1)) GB")
$lines.Add('graphics:')
foreach ($g in Get-CimInstance Win32_VideoController) { $lines.Add("  $($g.Name) (driver $($g.DriverVersion))") }
$lines.Add("python: $(cmd /c 'python --version 2>&1')")
$lines.Add("git: $(cmd /c 'git --version 2>&1')")
$lines.Add("folder path has a space: $($root -match ' ')")
$lines.Add('setup settings (tools\config.local.cmd):')
foreach ($l in Get-Content (Join-Path $project 'tools\config.local.cmd')) { if ($l -like 'set *') { $lines.Add("  $l") } }
$lines.Add('installed TF2 files:')
foreach ($f in @(Get-ChildItem (Join-Path $game 'mod_tf\bin\x64\*.dll')) + @(Get-Item (Join-Path $game 'mod_tf_win64.exe'))) {
    if ($f) { $lines.Add("  $($f.Name) $($f.Length) bytes, $($f.LastWriteTime.ToString('yyyy-MM-dd HH:mm'))") }
}
$lines | Set-Content -LiteralPath (Join-Path $work 'system.txt') -Encoding utf8

Copy-Item (Join-Path $project 'logs') (Join-Path $work 'fortcraft-logs') -Recurse
Copy-Item (Join-Path $game 'mod_tf\console.log') (Join-Path $work 'tf2_console.log')
Copy-Item (Join-Path $project 'fabric\run\logs\latest.log') (Join-Path $work 'minecraft_latest.log')
Copy-Item (Join-Path $project 'fabric\run\logs\debug.log') (Join-Path $work 'minecraft_debug.log')
Get-ChildItem (Join-Path $project 'fabric\run\hs_err_pid*.log') | Copy-Item -Destination $work

$dest = Join-Path $root "FortCraft-windows-logs-$stamp.zip"
Compress-Archive -Path (Join-Path $work '*') -DestinationPath $dest -Force
Remove-Item -LiteralPath $work -Recurse -Force
Write-Host "Wrote $dest"
Write-Host 'Attach that file to your GitHub issue. Have a quick look first if you want: it holds logs and system facts only.'
