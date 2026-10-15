# UNVERIFIED ON WINDOWS. Run from PowerShell with the matching native-profile JAR.
param([Parameter(Mandatory=$true)][string]$Bundle)
$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
& java -jar (Join-Path $Root 'target/telegram-cli.jar') native-check --bundle $Bundle --json
exit $LASTEXITCODE
