$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
if (-not $IsWindows -and $env:OS -ne 'Windows_NT') { throw 'This script requires Windows and a full JDK 21.' }
if (-not (Test-Path 'target/moondrop.jar')) { throw 'Build target/moondrop.jar before packaging.' }
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to a full JDK 21.' }
[xml]$pom = Get-Content -Raw 'pom.xml'
$version = [string]$pom.project.version
if ($version -notmatch '^\d+\.\d+\.\d+$') { throw 'pom.xml must have a numeric application version.' }
function Invoke-Checked([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Executable failed with exit code $LASTEXITCODE" }
}
$jdkBin = Join-Path $env:JAVA_HOME 'bin'
foreach ($path in @('target/native-runtime','target/native-input','target/native-icons','target/native-image','target/native-installer')) {
    if (Test-Path $path) { Remove-Item -Recurse -Force $path }
}
foreach ($path in @('target/native-input','target/native-icons','target/native-image','target/native-installer','dist')) { New-Item -ItemType Directory -Force $path | Out-Null }
Copy-Item 'target/moondrop.jar','README.md','INSTALL.md','LICENSE','THIRD_PARTY.md' -Destination 'target/native-input'
Invoke-Checked (Join-Path $jdkBin 'jlink.exe') @('--add-modules','java.se,jdk.crypto.ec,jdk.unsupported','--strip-debug','--no-header-files','--no-man-pages','--output','target/native-runtime')
Invoke-Checked (Join-Path $jdkBin 'java.exe') @('-Djava.awt.headless=true','--class-path','target/moondrop.jar','scripts/DesktopIcons.java','moondrop.svg','target/native-icons')
Invoke-Checked (Join-Path $jdkBin 'jpackage.exe') @('--type','app-image','--name','Moondrop','--app-version',$version,'--vendor','rayyan',
    '--input','target/native-input','--main-jar','moondrop.jar','--main-class','xyz.shadowmere.desktop.Main',
    '--runtime-image','target/native-runtime','--java-options','-Dmoondrop.distribution=native',
    '--icon','target/native-icons/moondrop.ico','--dest','target/native-image')
Invoke-Checked (Join-Path $jdkBin 'jpackage.exe') @('--type','exe','--name','Moondrop','--app-version',$version,'--vendor','rayyan',
    '--app-image','target/native-image/Moondrop','--dest','target/native-installer',
    '--win-per-user-install','--win-dir-chooser','--win-menu','--win-shortcut-prompt',
    '--win-upgrade-uuid','e47934d8-522a-4b82-8ca1-190f937e22f3')
Move-Item -Force "target/native-installer/Moondrop-$version.exe" "dist/Moondrop-$version-Windows-x64.exe"
Write-Host "Created dist/Moondrop-$version-Windows-x64.exe (unsigned, with a Java runtime)."
