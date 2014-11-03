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

rem Parse options
set ORIGINAL_PWD=%CD%

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
	cd /d %BAT_DIR%
	cd /d ../../
	FOR /F %%i in ('CD') do set SOURCE_HOME=%%i
	cd /d %ORIGINAL_PWD%
) ELSE (
	set SOURCE_HOME=%1
)

set SCRIPT_HOME=%SOURCE_HOME%\setup\installer
set DEFAULT_PROP_PATH=%SOURCE_HOME%\core\src\main\webapp\WEB-INF\classes
set CUSTOM_PROP_PATH=%SOURCE_HOME%\core\src\main\resources

rem Build install utilities
call mvn -f %SCRIPT_HOME%\install-util package
call mvn -f %SOURCE_HOME%\setup\couchdb\bjornloka package

rem Setting installer default values from source code
rem Override by custom properties
set PROPERTIES=%DEFAULT_PROP_PATH%\nemakiware.properties
set PROPERTIES_CUSTOM=%CUSTOM_PROP_PATH%\custom-nemakiware.properties

set USER_INPUT_SPEC=%SCRIPT_HOME%\user-input-spec.xml
set USER_INPUT_SPEC_MODIFIED=%SCRIPT_HOME%\user-input-spec_modified.xml
java -cp %SCRIPT_HOME%\install-util\target\install-util.jar jp.aegif.nemaki.installer.ProcessTemplate %USER_INPUT_SPEC% %PROPERTIES% %PROPERTIES_CUSTOM%

rem Prepare WAR
call mvn -f %SOURCE_HOME%\core clean
call mvn -f %SOURCE_HOME%\core -Dmaven.test.skip=true package
call mvn -f %SOURCE_HOME%\solr clean
call mvn -f %SOURCE_HOME%\solr -Dmaven.test.skip=true package
cd /d %SOURCE_HOME%\ui
call activator.bat war
cd /d %ORIGINAL_PWD%

rem Build installer
call %SCRIPT_HOME%\IzPack\bin\compile.bat %SCRIPT_HOME%\install.xml -b %SOURCE_HOME%\ -o %SCRIPT_HOME%\install.jar -k standard

rem Delete tmp file after putting them into installer
del %USER_INPUT_SPEC_MODIFIED%

rem Execute isntaller
if "%FLG_E%" == "TRUE" (
	java -jar %SCRIPT_HOME%\install.jar
)

endlocal