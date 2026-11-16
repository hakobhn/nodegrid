@echo off
rem Starts this machine as a NodeGrid worker (UI: http://localhost:8081).
rem Set the master address in config\application-worker.yml (nodegrid.master-url) or pass --nodegrid.master-url=http://host:8080
call "%~dp0nodegrid.bat" worker %*
if errorlevel 1 pause
