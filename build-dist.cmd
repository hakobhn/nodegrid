@echo off
rem Windows wrapper: runs build-dist.sh with Git Bash.  Usage: build-dist.cmd [windows-x64] [linux-x64] [linux-aarch64]
setlocal
set "GITBASH=%ProgramFiles%\Git\bin\bash.exe"
if not exist "%GITBASH%" (
    echo Git Bash not found at "%GITBASH%".
    echo Install Git for Windows, or run build-dist.sh from any bash shell.
    exit /b 1
)
"%GITBASH%" "%~dp0build-dist.sh" %*
