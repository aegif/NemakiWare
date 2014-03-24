@echo off
set local

rem Location
if %1 == "" (
	set BAT_PATH=%~dp0
	cd /d %BAT_PATH%
	cd /d ../../
	FOR /F %%i in ('CD') do set SOURCE_HOME=%%i
	cd /d %EXECUTE_CD%
) ELSE (
	set SOURCE_HOME=%1
)

rem Install Rails
call %SOURCE_HOME%\setup\nemakishare\railsinstaller-2.2.2.exe

rem refresh environmental variable(Ruby/Rails path)
call %SOURCE_HOME%\setup\nemakishare\resetvars.bat

rem Setting nemakishare
cd /d %SOURCE_HOME%\nemakishare\
call git init
call bundle install --path=vendor/bundle --local
call rake db:migrate:reset
