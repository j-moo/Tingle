param(
    [string]$Zig = 'zig',
    [Parameter(Mandatory = $true)][string]$TinyCrypt,
    [string]$Javac = 'javac',
    [string]$Java = 'java'
)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$repoRoot = (Resolve-Path (Join-Path $projectRoot '../..')).Path
$buildRoot = Join-Path $projectRoot 'build/host-tests'
New-Item -ItemType Directory -Force -Path $buildRoot | Out-Null
$env:ZIG_GLOBAL_CACHE_DIR = Join-Path $buildRoot 'zig-cache'
$testExe = Join-Path $buildRoot 'test-core.exe'
$sources = @('src/proof.c', 'src/hash.c', 'src/json.c', 'src/request.c', 'src/frame.c', 'tests/test_core.c',
    'vendor/monocypher/monocypher.c', 'vendor/monocypher/monocypher-ed25519.c') |
    ForEach-Object { Join-Path $projectRoot $_ }
& $Zig cc -target x86_64-windows-gnu -std=c11 -O0 -g -UNDEBUG -DKR_HOST -Wall -Wextra -Werror `
    -I (Join-Path $projectRoot 'include') -I (Join-Path $projectRoot 'vendor/monocypher') `
    -I (Join-Path $TinyCrypt 'lib/include') @sources `
    (Join-Path $TinyCrypt 'lib/source/sha256.c') (Join-Path $TinyCrypt 'lib/source/utils.c') -o $testExe
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$vectors = Join-Path $buildRoot 'proof-vectors.properties'
& $testExe $vectors
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $Javac --release 17 -encoding UTF-8 -d $buildRoot `
    (Join-Path $repoRoot 'back/src/main/java/com/e106/tingle/keyring/proof/KeyringProofTranscript.java') `
    (Join-Path $repoRoot 'back/src/main/java/com/e106/tingle/keyring/proof/KeyringProofVerifier.java') `
    (Join-Path $projectRoot 'tests/ProofInterop.java')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $Java -cp $buildRoot ProofInterop $vectors
exit $LASTEXITCODE
