param([string]$Java = 'java')
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$wrapper = (Resolve-Path (Join-Path $projectRoot '../virtual-keyring/gradle/wrapper/gradle-wrapper.jar')).Path
& $Java -classpath $wrapper org.gradle.wrapper.GradleWrapperMain -p $projectRoot `
    --no-daemon :phone-app:assembleDebug :phone-app:lintDebug checkstyleAll
exit $LASTEXITCODE
