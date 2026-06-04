@echo off
setlocal enabledelayedexpansion

:: ============================================================
:: Ring Reminder — Build Script
:: Run from: S:\Coding\Ringreminder\
:: ============================================================

set "PROJDIR=S:\Coding\Ringreminder"
set "GRADLE=%PROJDIR%\gradlew.bat"
set PS=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe

:: ── Compute version code (yyDDDHHmm) and version name (yyyyMMdd-HHmm) ──
:: Uses (Year - 2000) instead of Year %% 100 to avoid cmd.exe percent expansion
"%PS%" -Command "$d=Get-Date; $vc=[string]([int]('{0:D2}{1:D3}{2:D2}{3:D2}' -f ($d.Year - 2000), $d.DayOfYear, $d.Hour, $d.Minute)); $vn=$d.ToString('yyyyMMdd-HHmm'); [System.IO.File]::WriteAllText('%PROJDIR%\ver.tmp', \"$vc|$vn\")"
set /p VERSTR=<"%PROJDIR%\ver.tmp"
del "%PROJDIR%\ver.tmp"
for /f "tokens=1,2 delims=|" %%A in ("!VERSTR!") do ( set VC=%%A & set VN=%%B )

echo.
echo =========================================
echo      Ring Reminder Build Script
echo          v!VN! ^(!VC!^)
echo =========================================
echo.
echo Select build type:
echo   1. Debug APK   ^(isPro=true, debug signing, install via ADB^)
echo   2. Release APK ^(signed, for sideloading your device^)
echo   3. Release AAB ^(signed, for Play Store upload^)
echo.
set /p CHOICE=Choose (1-3):

if "!CHOICE!"=="1" goto debug_build
if "!CHOICE!"=="2" goto release_apk
if "!CHOICE!"=="3" goto release_aab

echo Invalid selection.
pause
exit /b 1

:: ============================================================
:: 1. DEBUG APK
:: ============================================================
:debug_build
echo.
echo === Debug APK Build ===
echo.
cd /d "%PROJDIR%"
call "%GRADLE%" assembleDebug -PappVersionCode=!VC! -PappVersionName=!VN!
if errorlevel 1 (
    echo.
    echo ERROR: Build failed. Check output above.
    pause & exit /b 1
)

set "APK_OUT=%PROJDIR%\app\build\outputs\apk\debug\app-debug.apk"
set "APK_NAMED=%PROJDIR%\app\build\outputs\apk\debug\app-debug-!VN!.apk"
if exist "!APK_OUT!" copy /Y "!APK_OUT!" "!APK_NAMED!" >nul

echo.
echo Build successful: app-debug-!VN!.apk
echo.

set /p INSTALL=Install to connected device via ADB? (y/n):
if /i not "!INSTALL!"=="y" goto open_debug

where adb >nul 2>&1
if errorlevel 1 (
    echo WARNING: adb not found on PATH. Skipping install.
    goto open_debug
)

adb devices | findstr /r "device$" >nul 2>&1
if errorlevel 1 (
    echo WARNING: No device detected. Enable USB debugging and reconnect.
    goto open_debug
)

echo Installing...
adb install -r "!APK_OUT!"
if errorlevel 1 ( echo ERROR: Install failed. ) else ( echo Installed successfully. )

:open_debug
%SystemRoot%\explorer.exe "%PROJDIR%\app\build\outputs\apk\debug\"
pause
exit /b 0

:: ============================================================
:: 2. RELEASE APK
:: ============================================================
:release_apk
if not exist "%PROJDIR%\keystore.gradle" (
    echo ERROR: keystore.gradle not found. Cannot sign release build.
    pause & exit /b 1
)
echo.
echo === Release APK Build ===
echo.
cd /d "%PROJDIR%"
call "%GRADLE%" assembleRelease -PappVersionCode=!VC! -PappVersionName=!VN!
if errorlevel 1 (
    echo.
    echo ERROR: Build failed. Check output above.
    pause & exit /b 1
)

set "APK_OUT=%PROJDIR%\app\build\outputs\apk\release\app-release.apk"
set "APK_NAMED=%PROJDIR%\app\build\outputs\apk\release\app-release-!VN!.apk"
if exist "!APK_OUT!" copy /Y "!APK_OUT!" "!APK_NAMED!" >nul

echo.
echo Build successful: app-release-!VN!.apk
%SystemRoot%\explorer.exe "%PROJDIR%\app\build\outputs\apk\release\"
pause
exit /b 0

:: ============================================================
:: 3. RELEASE AAB (Play Store)
:: ============================================================
:release_aab
if not exist "%PROJDIR%\keystore.gradle" (
    echo ERROR: keystore.gradle not found. Cannot sign release build.
    pause & exit /b 1
)
echo.
echo === Release AAB Build (Play Store) ===
echo NOTE: isPro is currently TRUE - flip to false before submitting to Play Store.
echo.
set /p CONFIRM=Continue? (y/n):
if /i not "!CONFIRM!"=="y" exit /b 0

cd /d "%PROJDIR%"
call "%GRADLE%" bundleRelease -PappVersionCode=!VC! -PappVersionName=!VN!
if errorlevel 1 (
    echo.
    echo ERROR: Build failed. Check output above.
    pause & exit /b 1
)

set "AAB_DIR=%PROJDIR%\app\build\outputs\bundle\release"
set "AAB_OUT=!AAB_DIR!\app-release.aab"
set "AAB_NAMED=!AAB_DIR!\app-release-!VN!.aab"
if exist "!AAB_OUT!" copy /Y "!AAB_OUT!" "!AAB_NAMED!" >nul

echo.
echo Build successful: app-release-!VN!.aab
%SystemRoot%\explorer.exe "!AAB_DIR!\"
pause
exit /b 0

endlocal
