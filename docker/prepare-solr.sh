
set -e

SCRIPT_DIR=$(cd $(dirname $0); pwd)
NEMAKI_HOME=$(cd $SCRIPT_DIR/..; pwd)
SOLR_DIR=$NEMAKI_HOME/solr

echo "Building Solr WAR file..."
cd $SOLR_DIR
mvn clean package -DskipTests

echo "Creating solr directory..."
mkdir -p $NEMAKI_HOME/docker/solr

echo "Copying WAR file..."
cp $SOLR_DIR/target/solr.war $NEMAKI_HOME/docker/solr/

echo "Copying configuration files..."
if [ -f $SOLR_DIR/src/main/webapp/WEB-INF/classes/nemakisolr.properties ]; then
  cp $SOLR_DIR/src/main/webapp/WEB-INF/classes/nemakisolr.properties $NEMAKI_HOME/docker/solr/
else
  echo "Warning: nemakisolr.properties not found"
  touch $NEMAKI_HOME/docker/solr/nemakisolr.properties
fi

if [ -f $SOLR_DIR/src/main/webapp/WEB-INF/classes/log4j.properties ]; then
  cp $SOLR_DIR/src/main/webapp/WEB-INF/classes/log4j.properties $NEMAKI_HOME/docker/solr/
else
  echo "Warning: log4j.properties not found"
  touch $NEMAKI_HOME/docker/solr/log4j.properties
fi

echo "Solr WAR file prepared successfully!"
