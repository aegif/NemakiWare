@echo off
setlocal

rem ###

set ORIGINAL_PWD=%CD%

rem Parse options

rem Location
if "%1" == "" (
	set BAT_DIR=%~dp0
	cd %BAT_DIR%
	cd ../../
	FOR /F %%i in ('CD') do set SOURCE_HOME=%%i
	cd %ORIGINAL_PWD%
) ELSE (
	set SOURCE_HOME=%1
)

rem nemakishare
@echo on
echo NemakiShare stopping...
@echo off
cd %SOURCE_HOME%\nemakishare
set PID=`type tmp¥pids¥server.pid`
taskkill /pid %PID% /F
rm -f tmp¥pids¥server.pid
cd %ORIGINAL_PWD%
@echo on
echo NemakiShare stopped.
@echo off

rem nemakishare
@echo on
echo NemakiShare stopping...
@echo off
call mvn -f %SOURCE_HOME%\nemakisolr jetty:stop
@echo on
echo NemakiShare stopped.
@echo off

rem nemakiware
@echo on
echo NemakiWare stopping...
@echo off
call mvn -f %SOURCE_HOME%\nemakiware jetty:stop
@echo on
echo NemakiWare stopped.
@echo off

endlocal