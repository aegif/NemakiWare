

mvn -f bjornloka package
java -cp bjornloka/target/bjornloka.jar jp.aegif.nemaki.bjornloka.Setup '' '' '' '' '' '' "initial_import/bedroom_init.dump" "initial_import/archive_init.dump"
mvn -f $UTIL_HOME clean
