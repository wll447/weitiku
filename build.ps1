$ErrorActionPreference = 'Continue'
$env:ANDROID_HOME     = 'D:\Android'
$env:ANDROID_SDK_ROOT = 'D:\Android'
$env:GRADLE_USER_HOME = 'D:\Android\gradle-home'
$env:JAVA_HOME        = 'C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot'
$env:PATH = "$env:JAVA_HOME\bin;" + $env:PATH
$proj = '$PSScriptRoot'
& 'D:\Android\gradle-8.7\bin\gradle.bat' -p $proj assembleDebug --stacktrace 2>&1
Write-Host "EXITCODE=$LASTEXITCODE"
