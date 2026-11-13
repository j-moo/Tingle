param(
    [string]$Zig = 'zig',
    [string]$TinyCrypt = ''
)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (!$TinyCrypt) { $TinyCrypt = Join-Path $projectRoot 'build/toolchain/modules/crypto/tinycrypt' }
$testRoot = Join-Path $projectRoot 'build/tests/peer'
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
$env:ZIG_GLOBAL_CACHE_DIR = Join-Path $testRoot 'zig-cache'
$sources = @('src/codec.c', 'src/engine.c', 'src/hash.c', 'src/proof.c', 'src/profile.c', 'src/settings_auth.c',
    'src/matching.c', 'src/peer.c', 'src/peer_auth.c', 'src/policy.c', 'tests/test_peer.c', 'tests/peer_fixture.c',
    'vendor/monocypher/monocypher.c', 'vendor/monocypher/monocypher-ed25519.c') |
    ForEach-Object { Join-Path $projectRoot $_ }
$testExe = Join-Path $testRoot 'test-peer.exe'
& $Zig cc -target x86_64-windows-gnu -std=c11 -O0 -g -UNDEBUG -DKR_HOST -Wall -Wextra -Werror `
    -I (Join-Path $projectRoot 'include') -I (Join-Path $projectRoot 'vendor/monocypher') `
    -I (Join-Path $TinyCrypt 'lib/include') @sources `
    (Join-Path $TinyCrypt 'lib/source/sha256.c') (Join-Path $TinyCrypt 'lib/source/utils.c') -o $testExe
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $testExe (Join-Path $projectRoot 'build/tests/profile/normal.bin')
exit $LASTEXITCODE
