#!/bin/bash

SCRIPT_HOME=/usr/local/couchdb
UTIL_HOME=$SCRIPT_HOME/bjornloka

mvn -f $UTIL_HOME package
java -cp $UTIL_HOME/target/bjornloka.jar jp.aegif.nemaki.bjornloka.Setup 'http://127.0.0.1:5984' 'bedroom' '' '' '' '' "$SCRIPT_HOME/initial_import/bedroom_init.dump" "$SCRIPT_HOME/initial_import/archive_init.dump"
java -cp $UTIL_HOME/target/bjornloka.jar jp.aegif.nemaki.bjornloka.Setup 'http://127.0.0.1:5984' 'canopy' '' '' '' '' "$SCRIPT_HOME/initial_import/bedroom_init.dump" "$SCRIPT_HOME/initial_import/archive_init.dump"
mvn -f $UTIL_HOME clean

# delete sentinel file
rm -f /.firstrun


sh /usr/local/tomcat/bin/startup.sh