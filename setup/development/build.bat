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

set OUTPUT_BASE=%SOURCE_HOME%\target\
set OUTPUT_WAR_DIR=%OUTPUT_BASE%NemakiWare\
if not exist "%OUTPUT_WAR_DIR%" (
    mkdir "%OUTPUT_WAR_DIR%"
)

rem for aws beanstalk
if not exist "%OUTPUT_WAR_DIR%.ebextensions" (
    xcopy "%BAT_DIR%aws\.ebextensions" "%OUTPUT_WAR_DIR%" /Y
)


@echo on
echo NemakiCore packaging...
@echo off
call mvn -f "%SOURCE_HOME%\core" war:war
xcopy "%SOURCE_HOME%\core\target\core.war" "%OUTPUT_WAR_DIR%" /Y
@echo on
echo NemakiCore war created.
@echo off

@echo on
echo NemakiSolr packaging...
@echo off
call mvn -f "%SOURCE_HOME%\solr" war:war
xcopy "%SOURCE_HOME%\solr\target\solr.war" "%OUTPUT_WAR_DIR%" /Y
@echo on
echo NemakiSolr war created.
@echo off

@echo on
echo NemakiUI client packaging...
@echo off
cd /d "%SOURCE_HOME%\ui"
call sbt war
xcopy "%SOURCE_HOME%\ui\target\ui.war" "%OUTPUT_WAR_DIR%" /Y
@echo on
echo NemakiUI war created.
@echo off

set EB_ZIP_ARCHIVE="%OUTPUT_BASE%NemakiWare.zip"
if exist "%EB_ZIP_ARCHIVE%" (
    del "%EB_ZIP_ARCHIVE%" /Q
)

"%BAT_DIR%7za.exe" a "%EB_ZIP_ARCHIVE%" "%OUTPUT_WAR_DIR%*"

cd /d "%ORIGINAL_PWD%"


pause
