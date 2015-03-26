@echo off
setlocal

rem Batch script to generate NemakiWare installer from source code
rem
rem Usage:
rem ./make.bat
rem or
rem ./make.bat PATH_TO_NEMAKIWARE_SOURCECODE
rem
rem Note: PATH_TO_NEMAKIWARE_SOURCECODE means the parent folder of nemakiware, nemakisolr etc.
rem Note: PATH_TO_NEMAKIWARE_SOURCECODE should be without the last slash.
rem Note: -e option enables to execute the generated install.jar

rem ###

set ORIGINAL_PWD=%CD%

rem Parse options

set FLG_E=FALSE
for %%i in (%*) do (
	if %%i == -e (
		set FLG_E=TRUE
		shift
	) ELSE (
		set FLG_E=FALSE
	)
)

rem Location
if [%1] == [] (
	set BAT_DIR=%~dp0
	pushd %BAT_DIR%
	cd  ../../
	FOR /F %%i in ('CD') do set SOURCE_HOME=%%i
	pushd %ORIGINAL_PWD%
) ELSE (
	set SOURCE_HOME=%1
)

rem nemakiware
@echo on
echo NemakiWare configuration...
@echo off
call mvn -f %SOURCE_HOME%\nemakiware eclipse:eclipse
@echo on
echo NemakiWare configuration done.
@echo off

pause

rem nemakisolr
@echo on
echo NemakiSolr configuration...
@echo off
call mvn -f %SOURCE_HOME%\nemakisolr eclipse:eclipse
@echo on
echo NemakiSolr configuration done.
@echo off

rem nemakishare
@echo on
echo NemakiShare configuration...
@echo off
pushd %SOURCE_HOME%\nemakishare
call bundle install --path vendor\bundle
call rake db:migrate:reset
pushd %ORIGINAL_PWD%
@echo on
echo NemakiShare configuration done.
@echo off

rem Run applications
if "%FLG_E%" == "TRUE" (
	call %BAT_DIR%\start.bat
)

endlocal