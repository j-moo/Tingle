param(
    [string]$Zig = 'zig',
    [string]$Python = 'python',
    [string]$Javac = 'javac',
    [string]$Java = 'java'
)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$buildRoot = Join-Path $projectRoot 'build/host-tests'
New-Item -ItemType Directory -Force -Path $buildRoot | Out-Null
$env:ZIG_GLOBAL_CACHE_DIR = Join-Path $buildRoot 'zig-cache'
$testExe = Join-Path $buildRoot 'test-engine.exe'
$fixture = Join-Path $projectRoot 'tests/sync-v1.hex'
& $Zig cc -target x86_64-windows-gnu -std=c11 -O0 -UNDEBUG -Wall -Wextra -Werror `
    -I (Join-Path $projectRoot 'include') `
    (Join-Path $projectRoot 'src/codec.c') (Join-Path $projectRoot 'src/engine.c') `
    (Join-Path $projectRoot 'src/phone.c') (Join-Path $projectRoot 'tests/test_engine.c') -o $testExe
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $testExe $fixture
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $Python -m unittest discover -s (Join-Path $projectRoot 'tests') -p test_lab_server.py -v
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $Javac --release 17 -encoding UTF-8 -d $buildRoot `
    (Join-Path $projectRoot 'phone-app/src/main/java/com/e106/keyring/xiao/Wire.java') `
    (Join-Path $projectRoot 'tests/WireTest.java')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $Java -cp $buildRoot WireTest $fixture
exit $LASTEXITCODE
