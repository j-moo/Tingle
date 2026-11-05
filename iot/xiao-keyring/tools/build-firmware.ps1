param(
    [Parameter(Mandatory = $true)][string]$ZephyrBase,
    [string]$Python = 'python',
    [string]$CMake = 'cmake',
    [string]$Ninja = 'ninja',
    [string]$ZephyrSdk = '',
    [string]$ArmToolchain = '',
    [string]$BuildDirectory = '',
    [string]$ExtraConf = '',
    [string]$DevSettingsPublicKeyFile = ''
)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$env:ZEPHYR_BASE = (Resolve-Path -LiteralPath $ZephyrBase).Path
if ($ZephyrSdk -and $ArmToolchain) {
    throw 'Choose either ZephyrSdk or ArmToolchain.'
}
if (!$BuildDirectory) {
    $BuildDirectory = Join-Path $projectRoot 'build/firmware'
}
$BuildDirectory = [System.IO.Path]::GetFullPath($BuildDirectory)
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
            throw 'Toolchain differs from the build cache. Select a new -BuildDirectory.'
        }
    }
}
if ($ExtraConf) {
    $ExtraConf = (Resolve-Path -LiteralPath $ExtraConf).Path
}
$devPublicKey = ''
if ($DevSettingsPublicKeyFile) {
    $devPublicKey = (Get-Content -Raw -LiteralPath $DevSettingsPublicKeyFile).Trim()
    if ($devPublicKey -cnotmatch '^[0-9a-f]{64}$') { throw 'Expected a raw Ed25519 public key in lowercase hex.' }
}
& $CMake -S $projectRoot -B $BuildDirectory -G Ninja `
    '-DBOARD=xiao_ble/nrf52840' "-DPython3_EXECUTABLE=$Python" "-DCMAKE_MAKE_PROGRAM=$Ninja" `
    "-DEXTRA_CONF_FILE=$ExtraConf" "-DKR_DEV_SETTINGS_PUBLIC_KEY=$devPublicKey" '-DKR_DIAG_INTERACTIVE_BOOT=OFF' @toolchainArgs
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $CMake --build $BuildDirectory --parallel 4
exit $LASTEXITCODE
