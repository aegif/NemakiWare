
set -e

SCRIPT_DIR=$(cd $(dirname $0); pwd)
NEMAKI_HOME=$(cd $SCRIPT_DIR/..; pwd)

echo "Building Solr WAR file using Docker with Java 8..."

cat > $SCRIPT_DIR/Dockerfile.solr-build << EOF
FROM maven:3.8-openjdk-8

WORKDIR /app
COPY . /app

RUN cd solr && mvn clean package -DskipTests
EOF

docker build -f $SCRIPT_DIR/Dockerfile.solr-build -t nemaki-solr-build $NEMAKI_HOME
docker create --name nemaki-solr-build-container nemaki-solr-build
docker cp nemaki-solr-build-container:/app/solr/target/solr.war $SCRIPT_DIR/solr/
docker rm nemaki-solr-build-container
docker rmi nemaki-solr-build

echo "Solr WAR file built successfully!"
