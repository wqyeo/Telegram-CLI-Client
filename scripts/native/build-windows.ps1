#Requires -Version 7.4
# RECIPE ONLY: NOT executed/validated. No Windows release claim is implied.
param([switch]$AcknowledgeUnverified)
$ErrorActionPreference = 'Stop'
if (-not $AcknowledgeUnverified) { throw 'UNVERIFIED Windows recipe. Re-run with -AcknowledgeUnverified only in a native Windows developer environment.' }
if (-not $IsWindows -or [Runtime.InteropServices.RuntimeInformation]::OSArchitecture -ne 'X64') { throw 'Native Windows x64 is required.' }
$Root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Set-Location $Root
$Lock = Get-Content native/tdlib.lock.json -Raw | ConvertFrom-Json
$Settings = Get-Content native/windows/build-settings.json -Raw | ConvertFrom-Json
function Invoke-Checked {
    param([string]$File, [string[]]$Arguments)
    & $File @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Build command failed: $File" }
}
foreach ($Tool in @('cmake','cl','git','java','javac','jar','tar')) { Get-Command $Tool -ErrorAction Stop | Out-Null }
if (-not $env:JAVA_HOME -or -not $env:VCPKG_ROOT) { throw 'Use a VS 2022 x64 Developer PowerShell with JDK 25 JAVA_HOME and VCPKG_ROOT.' }
$VcpkgRevision = (& git -C $env:VCPKG_ROOT rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $VcpkgRevision -ne $Settings.vcpkgRevision) { throw 'VCPKG_ROOT must use the exact revision in native/windows/build-settings.json.' }
$Vcpkg = Join-Path $env:VCPKG_ROOT 'vcpkg.exe'
if (-not (Test-Path $Vcpkg)) { throw 'Bootstrap the pinned vcpkg checkout first.' }
Invoke-Checked -File $Vcpkg -Arguments @('install',"openssl:$($Settings.vcpkgTriplet)","zlib:$($Settings.vcpkgTriplet)")
$Work = Join-Path $Root 'native-build/windows'
New-Item $Work -ItemType Directory -Force | Out-Null
$Archive = Join-Path $Work "tdlib-$($Lock.revision).tar.gz"
if (-not (Test-Path $Archive)) { Invoke-WebRequest $Lock.sourceUrl -OutFile $Archive }
if ((Get-FileHash $Archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $Lock.sourceSha256) { throw 'TDLib source checksum mismatch.' }
$Source = Join-Path $Work "tdlib-$($Lock.revision)"
if (-not (Test-Path $Source)) {
    New-Item $Source -ItemType Directory | Out-Null
    Invoke-Checked -File 'tar' -Arguments @('-xzf',$Archive,'-C',$Source,'--strip-components=1')
}
$Toolchain = Join-Path $env:VCPKG_ROOT 'scripts/buildsystems/vcpkg.cmake'
$Common = @('-G',$Settings.generator,'-A','x64',"-DCMAKE_TOOLCHAIN_FILE=$Toolchain", "-DVCPKG_TARGET_TRIPLET=$($Settings.vcpkgTriplet)",'-DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded')
$CoreBuild = Join-Path $Source 'jnibuild'
$Install = Join-Path $Source 'example/java/td'
Invoke-Checked -File 'cmake' -Arguments (@('-S',$Source,'-B',$CoreBuild,'-DTD_ENABLE_JNI=ON',"-DCMAKE_INSTALL_PREFIX=$Install") + $Common)
Invoke-Checked -File 'cmake' -Arguments @('--build',$CoreBuild,'--config','Release','--target','install','--parallel','2')
$Example = Join-Path $Source 'example/java'
$JniBuild = Join-Path $Example 'build'
Invoke-Checked -File 'cmake' -Arguments (@('-S',$Example,'-B',$JniBuild,"-DTd_DIR=$Install/lib/cmake/Td","-DCMAKE_INSTALL_PREFIX=$Example") + $Common)
Invoke-Checked -File 'cmake' -Arguments @('--build',$JniBuild,'--config','Release','--target','install','--parallel','2')
$Bundle = Join-Path $Root 'native-build/bundles/windows-x86_64'
$Classes = Join-Path $Work 'sdk-classes'
New-Item $Bundle,"$Bundle/licenses","$Bundle/sources",$Classes -ItemType Directory -Force | Out-Null
Copy-Item "$Example/bin/tdjni.dll" "$Bundle/tdjni.dll"
Copy-Item "$Example/org/drinkless/tdlib/Client.java","$Example/org/drinkless/tdlib/TdApi.java" "$Bundle/sources/"
Invoke-Checked -File 'javac' -Arguments @('--release','25','-encoding','UTF-8','-d',$Classes,"$Bundle/sources/Client.java","$Bundle/sources/TdApi.java")
Invoke-Checked -File 'jar' -Arguments @('--create','--file',"$Bundle/tdlib-java.jar",'--date=2026-10-15T00:00:00Z','-C',$Classes,'org')
Copy-Item "$Source/LICENSE_1_0.txt" "$Bundle/licenses/TDLib-LICENSE"
$VcpkgInstalled = Join-Path $env:VCPKG_ROOT "installed/$($Settings.vcpkgTriplet)/share"
Copy-Item "$VcpkgInstalled/openssl/copyright" "$Bundle/licenses/OpenSSL-copyright"
Copy-Item "$VcpkgInstalled/zlib/copyright" "$Bundle/licenses/zlib-copyright"
$Files = [ordered]@{}
Get-ChildItem $Bundle -File -Recurse | Where-Object Name -ne 'manifest.json' | Sort-Object FullName | ForEach-Object {
    $Relative = [IO.Path]::GetRelativePath($Bundle,$_.FullName).Replace('\','/')
    $Files[$Relative] = @{sha256=(Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant();size=$_.Length}
}
$Manifest = @{schemaVersion=1;platform='windows-x86_64';tdlibRevision=$Lock.revision;tdlibSourceSha256=$Lock.sourceSha256;
    builderImage="native-windows-vcpkg-$VcpkgRevision";javaVersion=(& java --version)[0];files=$Files}
$Manifest | ConvertTo-Json -Depth 8 | Set-Content "$Bundle/manifest.json" -Encoding utf8NoBOM
Invoke-Checked -File './mvnw.cmd' -Arguments @('org.apache.maven.plugins:maven-install-plugin:3.2.0:install-file',"-Dfile=$Bundle/tdlib-java.jar",'-DgroupId=com.gitlab.wqyeo.thirdparty','-DartifactId=tdlib-java',"-Dversion=$($Lock.revision)",'-Dpackaging=jar','-DgeneratePom=true')
Write-Warning 'UNVERIFIED. Inspect dumpbin /DEPENDENTS, runtime licenses and no-network/no-write behavior before packaging. The native Windows TUI backend remains blocked.'
Write-Host 'Next: .\mvnw.cmd -Pnative clean verify; .\scripts\native\verify-bundle.ps1 -Bundle native-build/bundles/windows-x86_64'
