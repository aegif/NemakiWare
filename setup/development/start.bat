@echo off
set local

set ORIGINAL_PWD=%CD%

rem Location
if "%1" == "" (
	set BAT_DIR=%~dp0
	cd /d %BAT_DIR%
	cd /d ../../
	FOR /F %%i in ('CD') do set SOURCE_HOME=%%i
	cd /d %ORIGINAL_PWD%
) ELSE (
	set SOURCE_HOME=%1
)

@echo on
echo NemakiWare server starting...
@echo off
start mvn -f %SOURCE_HOME%\nemakiware jetty:run
@echo on
echo NemakiWare server started.
@echo off

@echo on
echo NemakiSolr server starting...
@echo off
start mvn -f %SOURCE_HOME%\nemakisolr jetty:run
@echo on
echo NemakiSolr server started.
@echo off

@echo on
echo NemakiShare client starting...
@echo off
cd /d %SOURCE_HOME%\nemakishare
start rails s
cd /d %ORIGINAL_PWD%
@echo on
echo NemakiShare client started.
@echo off

end local