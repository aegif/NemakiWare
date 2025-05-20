
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

echo "Creating configuration directories..."
mkdir -p $NEMAKI_HOME/docker/ui-war/config2
mkdir -p $NEMAKI_HOME/docker/ui-war/config3

echo "Creating configuration files..."
cat > $NEMAKI_HOME/docker/ui-war/config2/nemakiware_ui.properties << EOF
nemaki.core.uri=http://core2:8080/core
nemaki.core.uri.archive=http://core2:8080/core
nemaki.auth.superuser.id=admin
nemaki.auth.superuser.password=admin
EOF

cat > $NEMAKI_HOME/docker/ui-war/config3/nemakiware_ui.properties << EOF
nemaki.core.uri=http://core3:8080/core
nemaki.core.uri.archive=http://core3:8080/core
nemaki.auth.superuser.id=admin
nemaki.auth.superuser.password=admin
EOF

if [ -f $UI_DIR/conf/log4j.properties ]; then
  cp $UI_DIR/conf/log4j.properties $NEMAKI_HOME/docker/ui-war/
else
  echo "Warning: log4j.properties not found"
  touch $NEMAKI_HOME/docker/ui-war/log4j.properties
fi

echo "UI WAR file prepared successfully!"
