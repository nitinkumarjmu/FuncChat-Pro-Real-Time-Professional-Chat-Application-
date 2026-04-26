@echo off
echo =========================================
echo       FuncChat Pro - Startup Script
echo =========================================
echo.

echo [1/3] Building the project (this may take a moment)...
call mvn clean package -DskipTests
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo ERROR: Build failed! Please check the output above.
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo [2/3] Starting the Server...
start "FuncChat Server" cmd /k "java -jar target/funcchat-pro-server.jar"
timeout /t 3 /nobreak > nul

echo [3/3] Starting two Chat Clients...
start "FuncChat Client 1" cmd /k "mvn exec:java -Dexec.mainClass=""com.chatapp.ui.ChatClientUI"""
timeout /t 1 /nobreak > nul
start "FuncChat Client 2" cmd /k "mvn exec:java -Dexec.mainClass=""com.chatapp.ui.ChatClientUI"""

echo.
echo =========================================
echo All components started successfully!
echo You can now use the chat applications.
echo =========================================
pause
