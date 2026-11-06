param(
    [string]$Zig = 'zig',
    [string]$Python = 'python',
    [Parameter(Mandatory = $true)][string]$TinyCrypt,
    [string]$Javac = 'javac',
    [string]$Java = 'java'
)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$repoRoot = (Resolve-Path (Join-Path $projectRoot '..')).Path
$buildRoot = Join-Path $projectRoot 'build/tests/registration'
New-Item -ItemType Directory -Force -Path $buildRoot | Out-Null
$profileFixtures = Join-Path $projectRoot 'build/tests/profile'
$env:KR_PROFILE_FIXTURES = $profileFixtures
& $Python -m unittest discover -s (Join-Path $projectRoot 'tests') -p test_profile_payload.py -v
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$env:ZIG_GLOBAL_CACHE_DIR = Join-Path $buildRoot 'zig-cache'
$testExe = Join-Path $buildRoot 'test-core.exe'
$sources = @('src/proof.c', 'src/settings_auth.c', 'src/hash.c', 'src/json.c', 'src/request.c', 'src/frame.c', 'src/reset.c', 'src/profile.c', 'tests/test_core.c',
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
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$resetExe = Join-Path $buildRoot 'test-reset.exe'
$resetSources = $sources | Where-Object { $_ -ne (Join-Path $projectRoot 'tests/test_core.c') }
& $Zig cc -target x86_64-windows-gnu -std=c11 -O0 -g -UNDEBUG -DKR_HOST -Wall -Wextra -Werror `
    -I (Join-Path $projectRoot 'include') -I (Join-Path $projectRoot 'vendor/monocypher') `
    -I (Join-Path $TinyCrypt 'lib/include') @resetSources (Join-Path $projectRoot 'tests/test_reset.c') `
    (Join-Path $TinyCrypt 'lib/source/sha256.c') (Join-Path $TinyCrypt 'lib/source/utils.c') -o $resetExe
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$resetVectors = Join-Path $buildRoot 'reset-vectors.properties'
& $resetExe $resetVectors
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $Javac --release 17 -encoding UTF-8 -d $buildRoot (Join-Path $projectRoot 'tests/ResetInterop.java')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $Java -cp $buildRoot ResetInterop $resetVectors
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$profileExe = Join-Path $buildRoot 'test-profile.exe'
& $Zig cc -target x86_64-windows-gnu -std=c11 -O0 -g -UNDEBUG -DKR_HOST -Wall -Wextra -Werror `
    -I (Join-Path $projectRoot 'include') -I (Join-Path $projectRoot 'vendor/monocypher') `
    -I (Join-Path $TinyCrypt 'lib/include') @resetSources (Join-Path $projectRoot 'tests/test_profile.c') `
    (Join-Path $TinyCrypt 'lib/source/sha256.c') (Join-Path $TinyCrypt 'lib/source/utils.c') -o $profileExe
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$profileVectors = Join-Path $buildRoot 'profile-vectors.properties'
& $profileExe $profileFixtures $profileVectors
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $Javac --release 17 -encoding UTF-8 -cp $buildRoot -d $buildRoot (Join-Path $projectRoot 'tests/ProfileInterop.java')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $Java -cp $buildRoot ProfileInterop $profileVectors
exit $LASTEXITCODE
