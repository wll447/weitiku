$ErrorActionPreference = 'Continue'
$env:ANDROID_HOME     = 'D:\Android'
$env:ANDROID_SDK_ROOT = 'D:\Android'
$env:GRADLE_USER_HOME = 'D:\Android\gradle-home'
$env:JAVA_HOME        = 'C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot'
$proj  = '$PSScriptRoot'
$adb   = 'D:\Android\platform-tools\adb.exe'
$apk   = "$proj\app\build\outputs\apk\debug\app-debug.apk"

Write-Host "=== BUILD ==="
& "$env:JAVA_HOME\bin\java.exe" -version 2>&1 | Out-Null
& 'D:\Android\gradle-8.7\bin\gradle.bat' -p $proj assembleDebug 2>&1 | Select-Object -Last 4

if (-not (Test-Path $apk)) { Write-Host "NO APK"; exit 1 }

Write-Host "=== INSTALL ==="
& $adb connect 127.0.0.1:16384 2>&1 | Out-Null
& $adb -s 127.0.0.1:16384 install -r $apk 2>&1 | Select-Object -Last 1

Write-Host "=== SELFTEST ==="
& $adb -s 127.0.0.1:16384 shell "am force-stop com.weizh.tiku; logcat -c" 2>&1 | Out-Null
& $adb -s 127.0.0.1:16384 shell "am start -n com.weizh.tiku/.MainActivity --ez selftest true" 2>&1 | Out-Null
Start-Sleep -Seconds 8
& $adb -s 127.0.0.1:16384 logcat -d -s WeiTikuSelfTest 2>&1
