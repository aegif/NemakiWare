
set -e

SCRIPT_DIR=$(cd $(dirname $0); pwd)
NEMAKI_HOME=$(cd $SCRIPT_DIR/..; pwd)
CLOUDANT_INIT_DIR=$NEMAKI_HOME/setup/couchdb/cloudant-init

echo "Building cloudant-init JAR..."
cd $CLOUDANT_INIT_DIR
mvn clean package

echo "Creating initializer directory..."
mkdir -p $NEMAKI_HOME/docker/initializer

echo "Copying JAR file..."
cp $CLOUDANT_INIT_DIR/target/cloudant-init.jar $NEMAKI_HOME/docker/initializer/

echo "cloudant-init.jar prepared successfully!"
