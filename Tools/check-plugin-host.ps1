$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$env:JAVA_HOME = Join-Path $env:ProgramFiles 'Android/Android Studio/jbr'
$env:ANDROID_HOME = Join-Path $env:USERPROFILE 'sdk'
if (Test-Path T:/) { throw 'T: is busy; wait for the active build.' }
& subst.exe T: $projectRoot
if ($LASTEXITCODE -ne 0) { throw 'Cannot map workspace' }
Push-Location T:/
try {
    $ErrorActionPreference = 'Continue'
    & .\gradlew.bat :TMessagesProj_Ghost:compileDebugJavaWithJavac -PghostOnly=true --max-workers=2 --console=plain -I "$projectRoot/Tools/ghost-local-build.init.gradle" "-PghostOfflineLsplant=$projectRoot/../Reports/lsplant-6.4-aliucord.4-arm64.aar"
    $taskExitCode = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($taskExitCode -ne 0) { throw 'Plugin host compilation failed' }
} finally {
    Pop-Location
    & subst.exe T: /D
}
