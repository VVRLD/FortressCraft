$ErrorActionPreference = 'Stop'

# Only the FortCraft Normal test world's End is in scope. Never touch the
# overworld, Nether, player data, or the BEST working backup.
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$saveRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot 'fabric\run\saves\FortCraft Normal'))
$endRoot = [IO.Path]::GetFullPath((Join-Path $saveRoot 'dimensions\minecraft\the_end'))
$backupRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot 'backups\end-resets'))
$comparison = [StringComparison]::OrdinalIgnoreCase

if (-not $saveRoot.StartsWith($projectRoot + [IO.Path]::DirectorySeparatorChar, $comparison) -or
    -not $endRoot.StartsWith($saveRoot + [IO.Path]::DirectorySeparatorChar, $comparison) -or
    -not $backupRoot.StartsWith($projectRoot + [IO.Path]::DirectorySeparatorChar, $comparison)) {
    throw 'Reset paths are outside the FortCraft project or selected save.'
}
if (-not (Test-Path -LiteralPath (Join-Path $saveRoot 'level.dat') -PathType Leaf)) {
    throw "FortCraft Normal save not found: $saveRoot"
}
if (-not (Test-Path -LiteralPath $endRoot -PathType Container)) {
    throw "No existing End folder found at $endRoot. Nothing was changed."
}
if (((Get-Item -LiteralPath $saveRoot).Attributes -band [IO.FileAttributes]::ReparsePoint) -or
    ((Get-Item -LiteralPath $endRoot).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
    throw 'Refusing to reset through a linked save or End folder.'
}

# A live world may still be writing region files or dragon-fight state.
$running = Get-CimInstance Win32_Process -Filter "Name = 'java.exe' OR Name = 'javaw.exe' OR Name = 'hl2.exe'"
foreach ($process in $running) {
    $commandLine = [string]$process.CommandLine
    if (($process.Name -ieq 'hl2.exe') -or
        ($commandLine.IndexOf($projectRoot, $comparison) -ge 0 -and
         ($commandLine.IndexOf('runClient', $comparison) -ge 0 -or
          $commandLine.IndexOf('fabric.dli', $comparison) -ge 0))) {
        throw 'Minecraft or TF2 is still running. Close both games, return to the Overworld, then try again.'
    }
}

Write-Host 'This resets ONLY the FortCraft Normal End. Its old terrain, entities, and dragon-fight state will be backed up.'
Write-Host 'Before continuing, your character must have left the End and be in the Overworld.'
$answer = Read-Host 'Type RESET END to continue'
if ($answer -cne 'RESET END') {
    Write-Host 'Cancelled. Nothing was changed.'
    exit 0
}

$stamp = Get-Date -Format 'yyyy-MM-dd-HHmmss'
$backupDir = [IO.Path]::GetFullPath((Join-Path $backupRoot $stamp))
$backupEnd = [IO.Path]::GetFullPath((Join-Path $backupDir 'the_end'))
if (-not $backupEnd.StartsWith($backupRoot + [IO.Path]::DirectorySeparatorChar, $comparison) -or
    (Test-Path -LiteralPath $backupDir)) {
    throw 'Backup destination is invalid or already exists. Nothing was changed.'
}
New-Item -ItemType Directory -Path $backupDir -ErrorAction Stop | Out-Null
Move-Item -LiteralPath $endRoot -Destination $backupEnd -ErrorAction Stop
Write-Host "End reset ready. Old End saved at: $backupEnd"
Write-Host 'Next time you enter the End, Minecraft will generate it again with a fresh dragon fight.'
