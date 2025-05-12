
SCRIPT_HOME=$(cd $(dirname $0);pwd)
UTIL_HOME=$SCRIPT_HOME/cloudant-init

mvn -f $UTIL_HOME package

java -cp $UTIL_HOME/target/cloudant-init.jar jp.aegif.nemaki.cloudantinit.Setup "$1" "$2" "$3" "$4" "$5" "$6" "$7" "bedroom_init.dump" "archive_init.dump"

mvn -f $UTIL_HOME clean
