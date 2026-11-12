param(
    [string]$ZephyrBase = '',
    [string]$Python = 'python',
    [string]$CMake = 'cmake',
    [string]$Ninja = 'ninja',
    [string]$ZephyrSdk = '',
    [string]$ArmToolchain = '',
    [string]$ExtraConf = '',
    [string]$DevSettingsPublicKeyFile = '',
    [ValidateSet('d31','revg')][string]$Carrier = 'd31',
    [switch]$StorageInspectOnly
)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (!$ZephyrBase) { $ZephyrBase = Join-Path $projectRoot 'build/toolchain/zephyr' }
if (!$ZephyrSdk -and !$ArmToolchain) {
    $localSdk = Join-Path $projectRoot 'build/toolchain/zephyr-sdk-0.17.2'
    if (Test-Path -LiteralPath $localSdk) { $ZephyrSdk = $localSdk }
}
$env:ZEPHYR_BASE = (Resolve-Path -LiteralPath $ZephyrBase).Path
if ($ZephyrSdk -and $ArmToolchain) {
    throw 'Choose either ZephyrSdk or ArmToolchain.'
}
$BuildDirectory = Join-Path $projectRoot 'build/firmware'
$d31Carrier = if ($Carrier -eq 'd31') { 'ON' } else { 'OFF' }
$storageInspect = if ($StorageInspectOnly) { 'ON' } else { 'OFF' }
$toolchainArgs = @()
if ($ZephyrSdk) {
    $env:ZEPHYR_TOOLCHAIN_VARIANT = 'zephyr'
    $env:ZEPHYR_SDK_INSTALL_DIR = (Resolve-Path -LiteralPath $ZephyrSdk).Path
    $toolchainArgs = @('-DZEPHYR_TOOLCHAIN_VARIANT=zephyr',
        "-DZEPHYR_SDK_INSTALL_DIR=$env:ZEPHYR_SDK_INSTALL_DIR")
} elseif ($ArmToolchain) {
    $env:ZEPHYR_TOOLCHAIN_VARIANT = 'gnuarmemb'
    $env:GNUARMEMB_TOOLCHAIN_PATH = (Resolve-Path -LiteralPath $ArmToolchain).Path
    $toolchainArgs = @('-DZEPHYR_TOOLCHAIN_VARIANT=gnuarmemb',
        "-DGNUARMEMB_TOOLCHAIN_PATH=$env:GNUARMEMB_TOOLCHAIN_PATH")
}
$cachePath = Join-Path $BuildDirectory 'CMakeCache.txt'
if (($ZephyrSdk -or $ArmToolchain) -and (Test-Path -LiteralPath $cachePath)) {
    $cache = Get-Content -Raw -LiteralPath $cachePath
    if ($cache -match '(?m)^ZEPHYR_TOOLCHAIN_VARIANT:[^=]+=(.+)\r?$') {
        if ($Matches[1].Trim() -ne $env:ZEPHYR_TOOLCHAIN_VARIANT) {
            throw 'Toolchain differs from the build cache. Clear iot/build/firmware before changing toolchains.'
        }
    }
}
if ($ExtraConf) {
    $ExtraConf = (Resolve-Path -LiteralPath $ExtraConf).Path
}
if (!$DevSettingsPublicKeyFile) { $DevSettingsPublicKeyFile = Join-Path $projectRoot 'config/dev-settings-public-key.hex' }
$devPublicKey = (Get-Content -Raw -LiteralPath $DevSettingsPublicKeyFile).Trim()
if ($devPublicKey -cnotmatch '^[0-9a-f]{64}$') { throw 'Expected a raw Ed25519 public key in lowercase hex.' }
& $CMake -S $projectRoot -B $BuildDirectory -G Ninja `
    '-DBOARD=xiao_ble/nrf52840' "-DPython3_EXECUTABLE=$Python" "-DCMAKE_MAKE_PROGRAM=$Ninja" `
    "-DEXTRA_CONF_FILE=$ExtraConf" "-DKR_DEV_SETTINGS_PUBLIC_KEY=$devPublicKey" '-DKR_DIAG_INTERACTIVE_BOOT=OFF' `
    "-DKR_CARRIER_D31=$d31Carrier" "-DKR_STORAGE_INSPECT_ONLY=$storageInspect" @toolchainArgs
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $CMake --build $BuildDirectory --parallel 4
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

# Publish only after a successful build. Keep exactly one UF2 for flashing.
$generatedUf2 = Join-Path $BuildDirectory 'zephyr/zephyr.uf2'
$publishedUf2 = Join-Path $projectRoot 'build/zephyr.uf2'
$bytes = [IO.File]::ReadAllBytes($generatedUf2)
if ($bytes.Length -eq 0 -or $bytes.Length % 512 -ne 0 -or
    [BitConverter]::ToUInt32($bytes, 0) -ne 0x0A324655 -or
    [BitConverter]::ToUInt32($bytes, 4) -ne 0x9E5D5157L -or
    [BitConverter]::ToUInt32($bytes, 28) -ne 0xADA52840L) {
    throw 'Invalid nRF52840 UF2 output; the previous image is retained.'
}
$sourceFiles = @('CMakeLists.txt', 'prj.conf', 'app.overlay', 'west.yml') |
    ForEach-Object { Get-Item -LiteralPath (Join-Path $projectRoot $_) }
if ($Carrier -eq 'd31') { $sourceFiles += Get-Item -LiteralPath (Join-Path $projectRoot 'boards/carrier-d31.overlay') }
foreach ($directory in @('src', 'include', 'vendor', 'config')) {
    $sourceFiles += Get-ChildItem -LiteralPath (Join-Path $projectRoot $directory) -File -Recurse
}
$sourceHashes = [ordered]@{}
foreach ($file in ($sourceFiles | Sort-Object FullName)) {
    $relativePath = $file.FullName.Substring($projectRoot.Length + 1).Replace('\', '/')
    $sourceHashes[$relativePath] = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
}
$commit = & git -C $projectRoot rev-parse HEAD
$dirty = [bool](& git -C $projectRoot status --porcelain -- .)
$manifest = [ordered]@{
    artifact = 'iot/build/zephyr.uf2'
    firmwareVersion = "0.9.6-$Carrier"
    storageInspectOnly = [bool]$StorageInspectOnly
    carrierRevision = if ($Carrier -eq 'd31') { 'SIS D3.1 Y3' } else { 'Tingle Rev G 31x50 Connector' }
    sourceCommit = $commit
    sourceDirty = $dirty
    builtAtUtc = [DateTime]::UtcNow.ToString('o')
    serverKeyId = 'dev-ed25519-' + $devPublicKey.Substring(0, 16)
    sha256 = (Get-FileHash -LiteralPath $generatedUf2 -Algorithm SHA256).Hash.ToLowerInvariant()
    bytes = $bytes.Length
    sourceFiles = $sourceHashes
}
$manifestPath = Join-Path $projectRoot 'build/firmware-manifest.json'
$manifestTemp = $manifestPath + '.pending'
$utf8 = New-Object System.Text.UTF8Encoding($false)
[IO.File]::WriteAllText($manifestTemp, ($manifest | ConvertTo-Json -Depth 4) + "`n", $utf8)
Move-Item -LiteralPath $generatedUf2 -Destination $publishedUf2 -Force
Move-Item -LiteralPath $manifestTemp -Destination $manifestPath -Force
Write-Host "UF2: $publishedUf2"
Write-Host "SHA-256: $($manifest.sha256)"
exit 0
