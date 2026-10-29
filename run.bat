@echo off
setlocal
cd /d "%~dp0"

set OUT_MAIN=out\main
set OUT_TEST=out\test

if not exist "%OUT_MAIN%" mkdir "%OUT_MAIN%"
javac --release 17 -encoding UTF-8 -sourcepath src\main\java -d "%OUT_MAIN%" src\main\java\com\solari\similarity\Main.java
if errorlevel 1 exit /b %errorlevel%

if exist "src\main\resources\static" (
  if not exist "%OUT_MAIN%\static" mkdir "%OUT_MAIN%\static"
  xcopy /Y /Q "src\main\resources\static\*" "%OUT_MAIN%\static\" >nul
)

set MODE=%~1
if "%MODE%"=="" set MODE=cli

if /I "%MODE%"=="test" (
  if not exist "%OUT_TEST%" mkdir "%OUT_TEST%"
  javac --release 17 -encoding UTF-8 -cp "%OUT_MAIN%" -sourcepath src\test\java -d "%OUT_TEST%" src\test\java\com\solari\similarity\SimilarityAssistantTest.java
  if errorlevel 1 exit /b %errorlevel%
  java -cp "%OUT_MAIN%;%OUT_TEST%" com.solari.similarity.SimilarityAssistantTest
  exit /b %errorlevel%
)

if /I "%MODE%"=="server" (
  shift
  java -cp "%OUT_MAIN%" com.solari.similarity.Main --serve %1 %2 %3 %4 %5 %6
  exit /b %errorlevel%
)

if /I "%MODE%"=="cli" (
  shift
  java -cp "%OUT_MAIN%" com.solari.similarity.Main %1 %2 %3 %4 %5 %6
  exit /b %errorlevel%
)

java -cp "%OUT_MAIN%" com.solari.similarity.Main %*
