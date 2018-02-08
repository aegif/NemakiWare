#!/bin/sh

SCRIPT_HOME=$(cd $(dirname $0);pwd)
UTIL_HOME=$SCRIPT_HOME/bjornloka

mvn -f $UTIL_HOME package
java -cp $UTIL_HOME/target/bjornloka.jar jp.aegif.nemaki.bjornloka.Setup '' '' '' '' '' '' "$SCRIPT_HOME/initial_import/bedroom_init.dump" "$SCRIPT_HOME/initial_import/archive_init.dump"
mvn -f $UTIL_HOME clean