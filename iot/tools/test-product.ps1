param([string]$Zig = 'zig', [string]$TinyCrypt = '', [string]$Node = 'node')
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (!$TinyCrypt) { $TinyCrypt = Join-Path $projectRoot 'build/toolchain/modules/crypto/tinycrypt' }
$testRoot = Join-Path $projectRoot 'build/tests/product'
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
$env:ZIG_GLOBAL_CACHE_DIR = Join-Path $testRoot 'zig-cache'
$sources = @('src/codec.c', 'src/engine.c', 'src/hash.c', 'src/proof.c', 'src/profile.c', 'src/settings_auth.c',
    'src/json.c', 'src/request.c', 'src/reset.c', 'src/matching.c', 'src/peer.c', 'src/peer_auth.c', 'src/policy.c', 'src/product.c', 'src/battery.c', 'src/service.c',
    'tests/test_product.c', 'vendor/monocypher/monocypher.c', 'vendor/monocypher/monocypher-ed25519.c') |
    ForEach-Object { Join-Path $projectRoot $_ }
$testExe = Join-Path $testRoot 'test-product.exe'
& $Zig cc -target x86_64-windows-gnu -std=c11 -O0 -g -UNDEBUG -DKR_HOST -Wall -Wextra -Werror `
    -I (Join-Path $projectRoot 'include') -I (Join-Path $projectRoot 'vendor/monocypher') `
    -I (Join-Path $TinyCrypt 'lib/include') @sources `
    (Join-Path $TinyCrypt 'lib/source/sha256.c') (Join-Path $TinyCrypt 'lib/source/utils.c') -o $testExe
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$vectors = Join-Path $testRoot 'vectors.properties'
& $testExe (Join-Path $projectRoot 'build/tests/profile/normal.bin') $vectors
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $Node (Join-Path $projectRoot 'tools/verify-app-flow.mjs') $vectors
exit $LASTEXITCODE
