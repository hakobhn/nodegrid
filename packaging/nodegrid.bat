@echo off
rem NodeGrid launcher (Windows).  Usage: nodegrid.bat [master^|worker] [extra Spring Boot args, e.g. --server.port=9090]
setlocal

set "APP_HOME=%~dp0"
cd /d "%APP_HOME%"

set "ROLE=%~1"
if "%ROLE%"=="" set "ROLE=master"
if /i not "%ROLE%"=="master" if /i not "%ROLE%"=="worker" (
    echo Usage: nodegrid.bat [master^|worker] [extra Spring Boot args]
    exit /b 1
)

rem Prefer the bundled runtime, then JAVA_HOME, then java on PATH.
set "JAVA_EXE=%APP_HOME%jre\bin\java.exe"
if not exist "%JAVA_EXE%" (
    if defined JAVA_HOME (
        set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
    ) else (
        set "JAVA_EXE=java"
    )
)

rem Collect the remaining arguments (everything after the role).
set "EXTRA_ARGS="
shift
:collect
if "%~1"=="" goto run
set EXTRA_ARGS=%EXTRA_ARGS% %1
shift
goto collect

:run
echo Starting NodeGrid as %ROLE% (logs: %APP_HOME%logs)
"%JAVA_EXE%" %JAVA_OPTS% -jar "%APP_HOME%nodegrid.jar" --spring.profiles.active=%ROLE% %EXTRA_ARGS%
exit /b %ERRORLEVEL%
