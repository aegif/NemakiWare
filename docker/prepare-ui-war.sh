
set -e

SCRIPT_DIR=$(cd $(dirname $0); pwd)
NEMAKI_HOME=$(cd $SCRIPT_DIR/..; pwd)
UI_DIR=$NEMAKI_HOME/ui

echo "Building UI WAR file..."
cd $UI_DIR
mvn clean package -DskipTests

echo "Creating ui-war directory..."
mkdir -p $NEMAKI_HOME/docker/ui-war

echo "Copying WAR file..."
cp $UI_DIR/target/ui.war $NEMAKI_HOME/docker/ui-war/

echo "Copying configuration files..."
if [ -f $UI_DIR/conf/nemakiware_ui.properties ]; then
  cp $UI_DIR/conf/nemakiware_ui.properties $NEMAKI_HOME/docker/ui-war/
else
  echo "Warning: nemakiware_ui.properties not found"
  touch $NEMAKI_HOME/docker/ui-war/nemakiware_ui.properties
fi

if [ -f $UI_DIR/conf/log4j.properties ]; then
  cp $UI_DIR/conf/log4j.properties $NEMAKI_HOME/docker/ui-war/
else
  echo "Warning: log4j.properties not found"
  touch $NEMAKI_HOME/docker/ui-war/log4j.properties
fi

echo "UI WAR file prepared successfully!"
