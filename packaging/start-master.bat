@echo off
rem Starts this machine as the NodeGrid master (UI: http://localhost:8080)
call "%~dp0nodegrid.bat" master %*
if errorlevel 1 pause
