#Requires -Version 7.4
# RECIPE ONLY: NOT executed/validated. Diagnostic-only until a native Windows TUI backend is validated.
param([switch]$AcknowledgeUnverified, [switch]$DiagnosticOnly)
$ErrorActionPreference = 'Stop'
if (-not $AcknowledgeUnverified -or -not $DiagnosticOnly) {
    throw 'UNVERIFIED Windows diagnostic-only recipe. Requires -AcknowledgeUnverified -DiagnosticOnly. Not a Windows TUI release.'
}
if (-not $IsWindows -or [Runtime.InteropServices.RuntimeInformation]::OSArchitecture -ne 'X64') { throw 'Native Windows x64 required.' }
$Root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Set-Location $Root
function Invoke-Checked {
    param([string]$File, [string[]]$Arguments)
    & $File @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Packaging command failed: $File" }
}
foreach ($Tool in @('java','jdeps','jlink','jpackage','git')) { Get-Command $Tool -ErrorAction Stop | Out-Null }
$Bundle = Join-Path $Root 'native-build/bundles/windows-x86_64'
if (-not (Test-Path "$Bundle/manifest.json")) { throw 'Build and inspect the Windows native bundle first.' }
Invoke-Checked -File './mvnw.cmd' -Arguments @('-Pnative','clean','verify')
Invoke-Checked -File 'java' -Arguments @('-jar','target/telegram-cli.jar','native-check','--bundle',$Bundle,'--json')
[xml]$Pom = Get-Content pom.xml -Raw
$Version = $Pom.project.version.Replace('-SNAPSHOT','')
$Lanterna = $Pom.project.properties.'lanterna.version'
$SourceLock = Get-Content licenses/lanterna-source.json -Raw | ConvertFrom-Json
if ($SourceLock.version -ne $Lanterna) { throw 'Lanterna source version mismatch.' }
$InputDirectory = Join-Path $Root 'target/package-input'
New-Item "$InputDirectory/lib","$InputDirectory/licenses","$InputDirectory/build-provenance" -ItemType Directory -Force | Out-Null
Copy-Item target/telegram-cli.jar "$InputDirectory/"
Copy-Item "target/lib/lanterna-$Lanterna.jar" "$InputDirectory/lib/"
Copy-Item $Bundle "$InputDirectory/native" -Recurse
Copy-Item LICENSE,THIRD-PARTY-NOTICES.txt,licenses/*.txt "$InputDirectory/licenses/"
Copy-Item native/tdlib.lock.json,native/windows/build-settings.json,pom.xml "$InputDirectory/build-provenance/"
foreach ($Classifier in @('jar:sources','pom')) {
    Invoke-Checked -File './mvnw.cmd' -Arguments @('org.apache.maven.plugins:maven-dependency-plugin:3.11.0:copy',"-Dartifact=com.googlecode.lanterna:lanterna:${Lanterna}:$Classifier","-DoutputDirectory=$InputDirectory/licenses")
}
$SourceArchive = Join-Path $Root 'native-build/downloads/lanterna-3.1.5-source.tar.gz'
New-Item (Split-Path $SourceArchive) -ItemType Directory -Force | Out-Null
if (-not (Test-Path $SourceArchive)) { Invoke-WebRequest $SourceLock.url -OutFile $SourceArchive }
if ((Get-FileHash $SourceArchive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $SourceLock.sha256) { throw 'Lanterna source checksum mismatch.' }
Copy-Item $SourceArchive "$InputDirectory/licenses/lanterna-source.tar.gz"
$Modules = (& jdeps --multi-release 25 --ignore-missing-deps --print-module-deps target/telegram-cli.jar "target/lib/lanterna-$Lanterna.jar" "$Bundle/tdlib-java.jar") -join ''
if ($LASTEXITCODE -ne 0) { throw 'jdeps failed.' }
$Modules | Set-Content "$InputDirectory/build-provenance/jdeps-modules.txt" -Encoding utf8NoBOM
Invoke-Checked -File 'jlink' -Arguments @('--add-modules',"$Modules,jdk.crypto.ec",'--output','target/runtime','--strip-debug','--no-man-pages','--no-header-files','--compress=zip-6')
if (-not (Test-Path target/runtime/bin/java.exe)) { throw 'Child JVM launcher must be retained.' }
@{schemaVersion=1;sourceCommit=(& git rev-parse HEAD).Trim();workingTreeDirty=[bool](& git status --porcelain);
  windowsValidated=$false;distribution='unverified-diagnostic-only';tuiBackendValidated=$false} |
    ConvertTo-Json | Set-Content "$InputDirectory/build-provenance/application.json" -Encoding utf8NoBOM
if (Test-Path dist/telegram-cli) { Remove-Item dist/telegram-cli -Recurse -Force }
# --license-file is installer-only; app-image notices are already staged in licenses/.
Invoke-Checked -File 'jpackage' -Arguments @('--type','app-image','--name','telegram-cli','--app-version',$Version,
    '--vendor','Yeo Wen Qin','--description','UNVERIFIED diagnostic-only offline prototype','--input',$InputDirectory,
    '--main-jar','telegram-cli.jar','--main-class','com.gitlab.wqyeo.telegramcli.Main','--runtime-image','target/runtime',
    '--dest','dist','--win-console','--java-options','-Dtgc.native.bundle=$APPDIR/native',
    '--java-options','-Dfile.encoding=UTF-8','--java-options','-XX:-UsePerfData')
$Image = Join-Path $Root 'dist/telegram-cli'
$Hashes = Get-ChildItem $Image -File -Recurse | Sort-Object FullName | ForEach-Object {
    (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + [IO.Path]::GetRelativePath($Image,$_.FullName).Replace('\','/')
}
$Hashes | Set-Content "$Image/SHA256SUMS" -Encoding utf8NoBOM
$Zip = Join-Path $Root "dist/telegram-cli-$Version-windows-x86_64-UNVERIFIED.zip"
Compress-Archive -Path $Image -DestinationPath $Zip -Force
((Get-FileHash $Zip -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + (Split-Path $Zip -Leaf)) |
    Set-Content "$Zip.sha256" -Encoding utf8NoBOM
Write-Warning 'UNVERIFIED diagnostic-only app image. Check DLL dependencies, licenses, read-only relocation, no-network probe and console behavior on a clean native Windows VM. Native Windows TUI is intentionally unavailable.'
