param([string]$Java = 'java')
$ErrorActionPreference = 'Stop'
$iotRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$projectRoot = Join-Path $PSScriptRoot 'phone-test'
$wrapper = (Resolve-Path (Join-Path $PSScriptRoot 'virtual-keyring/gradle/wrapper/gradle-wrapper.jar')).Path
$cache = Join-Path $iotRoot 'build/cache/phone-test'
& $Java -classpath $wrapper org.gradle.wrapper.GradleWrapperMain -p $projectRoot `
    --project-cache-dir $cache --no-daemon :phone-app:assembleDebug :phone-app:lintDebug checkstyleAll
exit $LASTEXITCODE
