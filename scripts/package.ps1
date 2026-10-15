$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
& mvn -B package
if ($LASTEXITCODE -ne 0) { throw 'Maven build failed' }
New-Item -ItemType Directory -Force target/moondrop-package-input,dist | Out-Null
Copy-Item target/moondrop.jar target/moondrop-package-input/
# An app image needs no WiX toolchain: unzip, open Moondrop.exe.
& jpackage --type app-image --name Moondrop --app-version 0.1.0 `
  --vendor 'rayyan' `
  --description 'A minimal Shadowsocks client for Shadowmere community servers' `
  --input target/moondrop-package-input --main-jar moondrop.jar `
  --main-class xyz.shadowmere.desktop.Main --dest dist `
  --add-modules java.base,java.desktop,java.net.http,java.logging,jdk.crypto.ec,jdk.unsupported `
  --java-options '-Dfile.encoding=UTF-8'
if ($LASTEXITCODE -ne 0) { throw 'Native packaging failed' }
Compress-Archive -Path dist/Moondrop -DestinationPath dist/Moondrop-Windows.zip -Force
Write-Host 'Portable Windows application created in dist/ (includes Java runtime).'
