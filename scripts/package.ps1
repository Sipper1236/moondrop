$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
& mvn -B package
if ($LASTEXITCODE -ne 0) { throw 'Maven build failed' }
& java --class-path target/moondrop.jar scripts/PortablePackage.java
if ($LASTEXITCODE -ne 0) { throw 'Portable packaging failed' }
Write-Host 'Created dist/Moondrop.zip. Users install Java separately; no runtime is included.'
