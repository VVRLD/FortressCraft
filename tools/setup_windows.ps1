# FortCraft setup for Windows: run once (tools\setup_windows.bat, or START-HERE.cmd in a release).
# Finds Java 25, Git, Visual Studio's C++ build tools and your Steam games; downloads Valve's
# Source SDK 2013 next to this folder, applies FortCraft's changes, builds the TF2 side, installs
# the test map, and prepares the Minecraft side. Safe to run again: finished steps are skipped.
# Nothing is copied from your game installs; local paths are saved only in tools\config.local.cmd.
# 'Continue': Windows PowerShell 5.1 turns a program's normal stderr output (java -version,
# git progress) into errors under 'Stop'. Failures are caught with exit codes instead (Run, Fail).
$ErrorActionPreference = 'Continue'

# START-HERE.cmd runs this script's text (so PCs that block .ps1 files still work); it then
# passes the tools folder in FORTCRAFT_TOOLS because $PSScriptRoot is empty in that case.
$tools = if ($PSScriptRoot) { $PSScriptRoot } else { $env:FORTCRAFT_TOOLS }
$project = (Resolve-Path -LiteralPath (Join-Path $tools '..')).Path
$root = Split-Path -Parent $project
$sdk = Join-Path $root 'source-sdk-2013'
$baseCommit = 'b8cfb12c0e083a2ef5b2f9f9b50f3902fa034474'
$patch = Join-Path $project 'tf2mod\sdk-changes.patch'
$config = Join-Path $tools 'config.local.cmd'

function Step([string] $text) { Write-Host ''; Write-Host "== $text" -ForegroundColor Cyan }
function Fail([string] $text) { Write-Host ''; Write-Host "SETUP STOPPED: $text" -ForegroundColor Red; exit 1 }
function Run([scriptblock] $action, [string] $failure) {
    & $action
    if ($LASTEXITCODE -ne 0) { Fail $failure }
}
function Ask([string] $question) {
    $answer = Read-Host "$question [Y/n]"
    return (-not $answer) -or $answer.Trim().ToLower().StartsWith('y')
}

Write-Host 'FortCraft setup (Windows). Single-player / offline only.' -ForegroundColor Green
# The release download carries the TF2 side already built (prebuilt\win64, made by
# tools\build_release_tf2.bat): then Visual Studio and Python aren't needed, which caused most of
# the setup problems in the GitHub issues. Without it (a source checkout) TF2 is built here.
$prebuiltTf2 = Join-Path $project 'prebuilt\win64'
$usePrebuilt = Test-Path -LiteralPath (Join-Path $prebuiltTf2 'client.dll')
if ($usePrebuilt) {
    Write-Host 'Needs: Steam with Team Fortress 2 and Source SDK Base 2013 Multiplayer, Java 25, Git,'
    Write-Host 'and about 3 GB free. The TF2 side comes ready-built.'
} else {
    Write-Host 'Needs: Steam with Team Fortress 2 and Source SDK Base 2013 Multiplayer, Java 25, Git,'
    Write-Host 'Visual Studio (or Build Tools) with "Desktop development with C++", and about 6 GB free.'
}

# ---- Folder name -------------------------------------------------------------------------------
# FortCraft's changes to Valve's SDK refer to this folder as "my-passthrough" next to
# "source-sdk-2013". A GitHub clone may have another name, so link that name to it.
$expected = Join-Path $root 'my-passthrough'
if ((Split-Path -Leaf $project) -ne 'my-passthrough') {
    if (-not (Test-Path -LiteralPath $expected)) {
        Step "Linking $expected to this folder (the SDK changes expect that name)"
        Run { cmd /c mklink /J "$expected" "$project" | Out-Null } 'Could not create the my-passthrough link. Rename this folder to my-passthrough and run setup again.'
    } else {
        $existing = Get-Item -LiteralPath $expected
        if (-not ($existing.LinkType -and (@($existing.Target) -contains $project))) {
            Fail "$expected already exists and is a different folder. Move this folder somewhere else, or rename it to my-passthrough."
        }
    }
}

# ---- Git ---------------------------------------------------------------------------------------
# Valve's SDK build passes folder paths to its tools without quotes, so a space anywhere in the
# path breaks it ("MSB8066: custom build for ...proto exited with code 1", GitHub issue 14).
if ($root -match ' ') {
    Fail "The FortCraft folder's path has a space in it ($root). Valve's SDK can't be built there. Move the FortCraft folder to a path without spaces, for example C:\FortCraft, then run START-HERE.cmd again."
}

Step 'Checking Git'
if (-not (Get-Command git.exe -ErrorAction SilentlyContinue)) {
    if ((Get-Command winget.exe -ErrorAction SilentlyContinue) -and (Ask 'Git is missing. Install it now with winget?')) {
        Run { winget install --id Git.Git -e --accept-package-agreements --accept-source-agreements } 'Installing Git failed.'
        Fail 'Git was installed. Close this window and run setup again so Windows finds it.'
    }
    Fail 'Install Git for Windows (https://git-scm.com/download/win), then run setup again.'
}
Write-Host (git --version)

# ---- Python 3 -----------------------------------------------------------------------------------
# Valve's SDK build runs "python" to turn its VScript files into C++ (without it the server
# build fails with error MSB8066 / exit code 9009 and "g_Script_... undeclared identifier").
$pythonDir = $null
if (-not $usePrebuilt) {
Step 'Checking Python 3 (Valve''s SDK build needs it)'
function PythonOk { $v = (cmd /c "python -c ""import sys; print(sys.version_info[0])"" 2>nul"); return ($LASTEXITCODE -eq 0 -and "$v".Trim() -eq '3') }
# Python can be installed but not on PATH (installer box not ticked, or installed after this
# window opened): then winget says it's already there and setup used to stop (GitHub issue 18).
# Look in the usual install folders and the "py" launcher too, and put the one found on PATH.
function FindPythonDir {
    $dirs = @()
    foreach ($base in @("$env:LOCALAPPDATA\Programs\Python", "$env:ProgramFiles", "${env:ProgramFiles(x86)}", 'C:\')) {
        if (Test-Path -LiteralPath $base) {
            $dirs += (Get-ChildItem -LiteralPath $base -Directory -Filter 'Python3*' -ErrorAction SilentlyContinue |
                      Sort-Object Name -Descending | ForEach-Object FullName)
        }
    }
    # The newer "Python install manager" puts them in %LOCALAPPDATA%\Python\pythoncore-3.x-64.
    if (Test-Path -LiteralPath "$env:LOCALAPPDATA\Python") {
        $dirs += (Get-ChildItem -LiteralPath "$env:LOCALAPPDATA\Python" -Directory -Filter 'pythoncore-3*' -ErrorAction SilentlyContinue |
                  Sort-Object Name -Descending | ForEach-Object FullName)
    }
    $launcher = (cmd /c 'py -3 -c "import sys; print(sys.executable)" 2>nul')
    if ($LASTEXITCODE -eq 0 -and $launcher) { $dirs = @(Split-Path -Parent "$launcher".Trim()) + $dirs }
    foreach ($d in $dirs) { if (Test-Path -LiteralPath (Join-Path $d 'python.exe')) { return $d } }
    return $null
}
function UsePython {
    if (PythonOk) { return $true }
    $dir = FindPythonDir
    if (-not $dir) { return $false }
    $env:PATH = "$dir;$dir\Scripts;$env:PATH"
    $script:pythonDir = $dir
    return (PythonOk)
}
if (-not (UsePython)) {
    if ((Get-Command winget.exe -ErrorAction SilentlyContinue) -and (Ask 'Python 3 is missing. Install it now with winget?')) {
        # winget exits with an error when Python is already installed, so don't stop on its exit
        # code: look again afterwards instead.
        & winget install --id Python.Python.3.13 -e --accept-package-agreements --accept-source-agreements
    }
    if (-not (UsePython)) {
        Fail 'Install Python 3 from python.org (tick "Add python.exe to PATH"), then close this window and run START-HERE.cmd again. If "python" opens the Microsoft Store, turn off its App execution alias in Windows Settings.'
    }
}
Write-Host (cmd /c 'python --version 2>&1')
if ($pythonDir) { Write-Host "Using Python at $pythonDir (it isn't on your PATH; FortCraft remembers where it is)" }
}

# ---- Java 25 -----------------------------------------------------------------------------------
Step 'Looking for Java 25 (Minecraft''s build needs exactly 25)'
function JavaMajor([string] $jdk) {
    $exe = Join-Path $jdk 'bin\java.exe'
    if (-not (Test-Path -LiteralPath $exe)) { return 0 }
    # Not just the first line: a "Picked up JAVA_TOOL_OPTIONS" note can come before the version.
    foreach ($line in @(cmd /c """$exe"" -version 2>&1")) {
        if ("$line" -match 'version "(\d+)') { return [int]$Matches[1] }
    }
    return 0
}
# Every place a JDK is usually installed: machine-wide and per-user folders, and the JDK
# vendors' registry entries. Temurin installed per-user (or elsewhere) wasn't found before, and
# winget then refused to install it again (GitHub issue 20).
function JavaCandidates {
    $list = @()
    if ($env:JAVA_HOME) { $list += $env:JAVA_HOME }
    foreach ($j in @(Get-Command java.exe -All -ErrorAction SilentlyContinue)) { $list += (Split-Path -Parent (Split-Path -Parent $j.Source)) }
    $bases = @()
    foreach ($root in @($env:ProgramFiles, "$env:LOCALAPPDATA\Programs")) {
        foreach ($vendor in @('Eclipse Adoptium', 'Java', 'Microsoft', 'Zulu', 'Amazon Corretto', 'BellSoft', 'Semeru', 'OpenJDK')) {
            $bases += (Join-Path $root $vendor)
        }
    }
    $bases += "$env:USERPROFILE\.jdks"
    foreach ($base in $bases) {
        if (Test-Path -LiteralPath $base) { $list += (Get-ChildItem -LiteralPath $base -Directory -ErrorAction SilentlyContinue | ForEach-Object FullName) }
    }
    foreach ($key in @('HKLM:\SOFTWARE\JavaSoft\JDK', 'HKLM:\SOFTWARE\Eclipse Adoptium\JDK', 'HKCU:\SOFTWARE\Eclipse Adoptium\JDK')) {
        foreach ($k in @(Get-ChildItem -Path $key -Recurse -ErrorAction SilentlyContinue)) {
            foreach ($name in @('JavaHome', 'Path')) {
                $v = (Get-ItemProperty -Path $k.PSPath -Name $name -ErrorAction SilentlyContinue).$name
                if ($v) { $list += "$v".TrimEnd('\') }
            }
        }
    }
    return ($list | Select-Object -Unique)
}
function FindJava25 {
    foreach ($c in (JavaCandidates)) { if ((JavaMajor $c) -eq 25) { return $c } }
    return $null
}
$javaHome = FindJava25
if (-not $javaHome) {
    if ((Get-Command winget.exe -ErrorAction SilentlyContinue) -and (Ask 'Java 25 not found. Install Eclipse Temurin 25 (free) with winget?')) {
        # winget exits with an error when Temurin 25 is already installed: look again instead of stopping.
        & winget install --id EclipseAdoptium.Temurin.25.JDK -e --accept-package-agreements --accept-source-agreements
        $javaHome = FindJava25
    }
    if (-not $javaHome) { Fail 'Install Java 25 (for example Eclipse Temurin 25 from adoptium.net), then run setup again. If it is installed already, set JAVA_HOME to its folder (the one containing bin\java.exe).' }
}
Write-Host "Using Java 25 at $javaHome"

# ---- Visual Studio C++ build tools -------------------------------------------------------------
$msbuild = $null; $toolset = $null
if ($usePrebuilt) {
    Step 'Using the ready-built TF2 side (Visual Studio not needed)'
} else {
Step 'Looking for Visual Studio C++ build tools'
$vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
$msbuild = $null; $toolset = $null
if (Test-Path -LiteralPath $vswhere) {
    $vs = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -format json | ConvertFrom-Json
    if ($vs) {
        $vs = @($vs)[0]
        $msbuild = Join-Path $vs.installationPath 'MSBuild\Current\Bin\amd64\MSBuild.exe'
        $major = [int]($vs.installationVersion.Split('.')[0])
        $toolset = if ($major -ge 18) { 'v145' } else { 'v143' }
    }
}
if (-not $msbuild -or -not (Test-Path -LiteralPath $msbuild)) {
    Fail 'Install Visual Studio 2022 or newer (Community or Build Tools) with "Desktop development with C++", then run setup again.'
}
Write-Host "Using $msbuild (toolset $toolset)"
}

# ---- Steam games ---------------------------------------------------------------------------------
Step 'Looking for Team Fortress 2 and Source SDK Base 2013 Multiplayer in Steam'
$libraries = @()
$steamPath = (Get-ItemProperty -Path 'HKCU:\Software\Valve\Steam' -Name SteamPath -ErrorAction SilentlyContinue).SteamPath
if (-not $steamPath) { $steamPath = Join-Path ${env:ProgramFiles(x86)} 'Steam' }
$steamPath = $steamPath.Replace('/', '\')
$libraries += $steamPath
$vdf = Join-Path $steamPath 'steamapps\libraryfolders.vdf'
if (Test-Path -LiteralPath $vdf) {
    foreach ($m in [regex]::Matches((Get-Content -LiteralPath $vdf -Raw), '"path"\s+"([^"]+)"')) { $libraries += $m.Groups[1].Value.Replace('\\', '\') }
}
function FindGame([string] $folder, [string] $marker) {
    foreach ($lib in ($libraries | Select-Object -Unique)) {
        $dir = Join-Path $lib "steamapps\common\$folder"
        if (Test-Path -LiteralPath (Join-Path $dir $marker)) { return $dir }
    }
    return $null
}
$tf2 = FindGame 'Team Fortress 2' 'tf\tf2_misc_dir.vpk'
$sdkBase = FindGame 'Source SDK Base 2013 Multiplayer' 'hl2\hl2_misc_dir.vpk'
if (-not $tf2) { Fail 'Team Fortress 2 not found in your Steam libraries. Install it through Steam, then run setup again.' }
if (-not $sdkBase) { Fail 'Source SDK Base 2013 Multiplayer not found. In Steam: Library > Tools > install it, then run setup again.' }
Write-Host "TF2: $tf2"
Write-Host "Source SDK Base 2013 Multiplayer: $sdkBase"

@(
    '@echo off',
    'rem Written by tools\setup_windows.ps1 for this computer only. Not part of the release.',
    "set `"FORTCRAFT_MSBUILD=$msbuild`"",
    "set `"FORTCRAFT_TOOLSET=$toolset`"",
    "set `"FORTCRAFT_JAVA_HOME=$javaHome`"",
    "set `"FORTCRAFT_TF2_DIR=$tf2`"",
    "set `"FORTCRAFT_SDK_BASE_DIR=$sdkBase`"",
    "set `"FORTCRAFT_PYTHON_DIR=$pythonDir`"",
    "set `"FORTCRAFT_PREBUILT=$(if ($usePrebuilt) { '1' } else { '' })`""
) | Set-Content -LiteralPath $config -Encoding ascii -ErrorAction Stop

# ---- Valve's SDK + FortCraft's changes ---------------------------------------------------------
Step 'Getting Valve''s Source SDK 2013'
# A download that broke off part way leaves a checkout without the SDK in it: start that over.
if ((Test-Path -LiteralPath (Join-Path $sdk '.git')) -and -not (Test-Path -LiteralPath (Join-Path $sdk 'src\devtools'))) {
    Write-Host 'The last SDK download didn''t finish; starting it again.'
    Remove-Item -LiteralPath $sdk -Recurse -Force -ErrorAction SilentlyContinue
}
if (-not (Test-Path -LiteralPath $sdk)) {
    # Only the one SDK version FortCraft needs, not Valve's whole history: a much smaller
    # download, tried up to 4 times, since a dropped connection stopped setup ("fetch-pack:
    # unexpected disconnect", GitHub issue 15).
    # core.longpaths: the SDK has some very long file paths, which Windows refuses in deep folders.
    Run { git init -q "$sdk" } 'Could not create the SDK folder.'
    Run { git -C "$sdk" config core.longpaths true } 'Could not configure the SDK checkout.'
    Run { git -C "$sdk" remote add origin https://github.com/ValveSoftware/source-sdk-2013.git } 'Could not configure the SDK checkout.'
    $fetched = $false
    for ($try = 1; $try -le 4 -and -not $fetched; $try++) {
        if ($try -gt 1) { Write-Host "Download broke off; trying again ($try of 4)..."; Start-Sleep -Seconds 5 }
        & git -C "$sdk" fetch --depth 1 origin $baseCommit
        $fetched = ($LASTEXITCODE -eq 0)
    }
    if (-not $fetched) {
        Remove-Item -LiteralPath $sdk -Recurse -Force -ErrorAction SilentlyContinue
        Fail 'Downloading Valve''s SDK failed 4 times. Check your internet connection (a VPN or a slow network can drop it), then run setup again.'
    }
    Run { git -C "$sdk" checkout -q FETCH_HEAD } 'Could not unpack the SDK.'
} elseif (-not (Test-Path -LiteralPath (Join-Path $sdk '.git'))) {
    Fail "$sdk exists but isn't a Git checkout. Move it away, then run setup again."
}
& git -C "$sdk" apply --reverse --check "$patch" 2>$null
if ($LASTEXITCODE -eq 0) {
    Write-Host 'FortCraft''s SDK changes are already applied.'
} else {
    Run { git -C "$sdk" apply --check "$patch" } 'FortCraft''s SDK changes don''t fit this SDK checkout (was it changed?). Nothing was changed.'
    Run { git -C "$sdk" apply "$patch" } 'Applying FortCraft''s SDK changes failed.'
}

if ($usePrebuilt) {
    Step 'Installing the ready-built TF2 side'
    $bin = Join-Path $sdk 'game\mod_tf\bin\x64'
    New-Item -ItemType Directory -Force -Path $bin | Out-Null
    foreach ($f in @('client.dll', 'server.dll')) {
        Copy-Item -LiteralPath (Join-Path $prebuiltTf2 $f) -Destination (Join-Path $bin $f) -Force -ErrorAction Stop
    }
    Copy-Item -LiteralPath (Join-Path $prebuiltTf2 'mod_tf_win64.exe') -Destination (Join-Path $sdk 'game\mod_tf_win64.exe') -Force -ErrorAction Stop
} else {
Step 'Generating the SDK''s Visual Studio projects'
Push-Location (Join-Path $sdk 'src')
try { Run { & '.\devtools\bin\vpc.exe' /hl2mp /tf /define:SOURCESDK +everything /mksln everything.sln | Out-Null } 'Generating the SDK projects failed.' }
finally { Pop-Location }

Step 'Building the TF2 side (first time: several minutes)'
$src = Join-Path $sdk 'src'
foreach ($p in @('tier1\tier1_win64.vcxproj', 'mathlib\mathlib_win64.vcxproj', 'raytrace\raytrace_win64.vcxproj',
                 'fgdlib\fgdlib_win64.vcxproj', 'vgui2\vgui_controls\vgui_controls_win64.vcxproj',
                 'vgui2\matsys_controls\matsys_controls_win64.vcxproj', 'launcher_main\launcher_main_win64_tf.vcxproj',
                 'game\client\client_win64_tf.vcxproj', 'game\server\server_win64_tf.vcxproj')) {
    Write-Host "  $p"
    Run { & $msbuild (Join-Path $src $p) /m /p:Configuration=Release /p:Platform=x64 /p:PlatformToolset=$toolset /v:quiet /nologo /clp:ErrorsOnly } "Building $p failed (see the error above)."
}
}

Step 'Installing FortCraft''s test map'
$maps = Join-Path $sdk 'game\mod_tf\maps'
New-Item -ItemType Directory -Force -Path $maps | Out-Null
$prebuilt = @((Join-Path $project 'prebuilt\fortcraft_flat.bsp'), (Join-Path $root 'prebuilt\fortcraft_flat.bsp')) | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $prebuilt) { Fail 'prebuilt\fortcraft_flat.bsp is missing from this download.' }
Copy-Item -LiteralPath $prebuilt -Destination (Join-Path $maps 'fortcraft_flat.bsp') -Force -ErrorAction Stop

Step 'Preparing the Minecraft side (first time: downloads Minecraft and Fabric, several minutes)'
$env:JAVA_HOME = $javaHome
Push-Location (Join-Path $project 'fabric')
try { Run { & '.\gradlew.bat' build } 'Building the Minecraft side failed (see the error above).' }
finally { Pop-Location }

# The Ghost Town's waves run on the Coal Town map (CoalTownMap.java), someone else's map that
# FortCraft can't ship. Its page blocks automatic downloads, so the player downloads it in their
# browser and setup picks the zip up from Downloads (Alex, 2026-10-22: "include it in setup").
Step 'The Ghost Town map (for Mann vs. Machine waves)'
$coalTown = Join-Path $project 'maps\mvm_coaltown.zip'
$coalTownPage = 'https://minecraft-inside.ru/maps/162670-mvm_coaltown-tf2-rp-map.html'
function IsCoalTownZip([string] $zip) {
    try {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $z = [System.IO.Compression.ZipFile]::OpenRead($zip)
        try { return [bool] ($z.Entries | Where-Object { $_.FullName -match '(^|/)region/r\.-?\d+\.-?\d+\.mca$' } | Select-Object -First 1) }
        finally { $z.Dispose() }
    } catch { return $false }
}
function FindCoalTownDownload {
    $downloads = @((Join-Path $env:USERPROFILE 'Downloads'))
    try { $downloads = @((New-Object -ComObject Shell.Application).NameSpace('shell:Downloads').Self.Path) + $downloads } catch {}
    foreach ($d in ($downloads | Select-Object -Unique)) {
        if (-not (Test-Path -LiteralPath $d)) { continue }
        $found = Get-ChildItem -LiteralPath $d -Filter '*coaltown*.zip' -File -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending | Where-Object { IsCoalTownZip $_.FullName } | Select-Object -First 1
        if ($found) { return $found.FullName }
    }
    return $null
}
if (Test-Path -LiteralPath $coalTown) {
    Write-Host 'Already installed.'
} else {
    $download = FindCoalTownDownload
    if (-not $download -and (Ask 'The Ghost Town''s waves need the free mvm_coaltown map. Open its page to download it?')) {
        Start-Process $coalTownPage
        Write-Host "Your browser opened $coalTownPage"
        Write-Host 'Click its download button and save the zip in your Downloads folder.'
        while (-not $download) {
            $answer = Read-Host 'Press Enter when the download has finished (or type S to skip)'
            if ($answer.Trim().ToLower().StartsWith('s')) { break }
            $download = FindCoalTownDownload
            if (-not $download) { Write-Host 'No mvm_coaltown zip with a Minecraft world in it is in Downloads yet.' }
        }
    }
    if ($download) {
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $coalTown) | Out-Null
        Copy-Item -LiteralPath $download -Destination $coalTown -Force -ErrorAction Stop
        Write-Host "Installed $download"
    } else {
        Write-Host 'Skipped. The Ghost Town will be built from TF2''s own map, without waves. To add it later, save'
        Write-Host "the zip as $coalTown"
    }
}

Write-Host ''
Write-Host 'Setup finished. Start FortCraft with PLAY-FORTCRAFT.cmd (or tools\play_normal.bat).' -ForegroundColor Green
Write-Host 'Keys and crafting: CONTROLS.md. Back up your Minecraft worlds before playing.'
