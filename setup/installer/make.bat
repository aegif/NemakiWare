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
set FLG_P=
for %%i in (%*) do (
	if %%i == -e (
		set FLG_E=TRUE
		shift
	) ELSE IF %%i == -p (
		set FLG_P=-P product
	) ELSE (
		set FLG_E=FALSE
	)
)

rem Location
if [%1] == [] (
	cd /d %~dp0
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
call mvn -f %SOURCE_HOME%\core package %FLG_P%
call mvn -f %SOURCE_HOME%\solr clean
call mvn -f %SOURCE_HOME%\solr package %FLG_P%
cd /d %SOURCE_HOME%\ui
call activator.bat war
cd /d %ORIGINAL_PWD%

rem Build installer
call %SCRIPT_HOME%\IzPack\bin\compile.bat %SCRIPT_HOME%\install.xml -b %SOURCE_HOME%\ -o %SCRIPT_HOME%\install.jar -k standard

rem Delete tmp file after putting them into installer
del %USER_INPUT_SPEC_MODIFIED%

rem Message
echo The install file is successfully generated.
pause

rem Execute isntaller
if "%FLG_E%" == "TRUE" (

	echo Continue to install...

	java -jar %SCRIPT_HOME%\install.jar
)

endlocal
