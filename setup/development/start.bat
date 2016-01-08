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
  SOURCE_HOME=%1
)

@echo on
echo NemakiCore server starting...
@echo off
start mvn -f %SOURCE_HOME%\core jetty:run
@echo on
echo NemakiCore server started.
@echo off

@echo on
echo NemakiSolr server starting...
@echo off
start mvn -f %SOURCE_HOME%\solr jetty:run
@echo on
echo NemakiSolr server started.
@echo off

@echo on
echo NemakiUI client starting...
@echo off
cd /d %SOURCE_HOME%\ui
start activator -jvm-debug 9999 run
cd /d %ORIGINAL_PWD%
@echo on
echo NemakiUI client started.
@echo off

end local