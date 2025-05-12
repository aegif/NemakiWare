

set -e

SCRIPT_DIR=$(cd $(dirname $0); pwd)
NEMAKI_HOME=$(cd $SCRIPT_DIR/..; pwd)

echo "Building NemakiWare components..."

echo "Building core server..."
cd $NEMAKI_HOME
mvn clean package -pl core -am
cp core/target/core.war docker/core/

echo "Building Solr server..."
mvn clean package -pl solr -am
cp solr/target/solr.war docker/solr/

echo "Copying configuration files..."
cp core/src/main/webapp/WEB-INF/classes/nemakiware.properties docker/core/
cp core/src/main/webapp/WEB-INF/classes/repositories.yml docker/core/
cp core/src/main/webapp/WEB-INF/classes/log4j.properties docker/core/
cp solr/src/main/webapp/WEB-INF/classes/nemakisolr.properties docker/solr/
cp solr/src/main/webapp/WEB-INF/classes/log4j.properties docker/solr/
cp ui/conf/application.conf docker/ui/
cp ui/conf/nemakiware_ui.properties docker/ui/

echo "Building CouchDB initializer..."
cd $NEMAKI_HOME/setup/couchdb/cloudant-init
mvn clean package

echo "Build completed successfully!"
echo "You can now run the Docker environment with:"
echo "  cd $NEMAKI_HOME/docker"
echo "  docker-compose up -d"
