set EXECUTE_CD=%CD%
cd /d %~dp0

set UTIL_HOME=bjornloka

mvn -f %UTIL_HOME% package
java -cp %UTIL_HOME%/target/bjornloka.jar jp.aegif.nemaki.bjornloka.Setup '' '' '' '' '' '' "initial_import/bedroom_init.dump" "initial_import/archive_init.dump"
mvn -f $UTIL_HOME clean

cd /d %EXECUTE_CD%