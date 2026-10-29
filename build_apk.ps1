$env:JAVA_HOME = "D:\AndroidEnv\jdk-17"
$env:ANDROID_HOME = "D:\AndroidEnv\sdk"
$env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\cmdline-tools\latest\bin;$env:ANDROID_HOME\platform-tools;$env:PATH"

Write-Host "Using JAVA_HOME: $env:JAVA_HOME"
Write-Host "Using ANDROID_HOME: $env:ANDROID_HOME"
& "$env:JAVA_HOME\bin\java.exe" -version

Set-Location -Path $PSScriptRoot
& cmd.exe /c "gradlew.bat assembleDebug --no-daemon"
if ($LASTEXITCODE -eq 0) {
    Write-Host "BUILD SUCCESSFUL! Copying APK..."
    $targetDir = "D:\gemini workplace"
    Copy-Item "app\build\outputs\apk\debug\app-debug.apk" "$targetDir\AnswerSheetScanner_v1.0.apk" -Force
    Write-Host "APK copied to $targetDir\AnswerSheetScanner_v1.0.apk"
} else {
    Write-Error "Build failed with exit code $LASTEXITCODE"
}
