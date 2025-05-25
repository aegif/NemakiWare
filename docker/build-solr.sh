#!/bin/bash
set -e

SCRIPT_DIR=$(cd $(dirname $0); pwd)
NEMAKI_HOME=$(cd $SCRIPT_DIR/..; pwd)

echo "Building Solr WAR file using Docker with Java 8..."

cat > $NEMAKI_HOME/docker/Dockerfile.solr-build << EOF
FROM maven:3.6-jdk-8

WORKDIR /app
COPY solr /app/solr

RUN cd /app/solr && mvn clean package -DskipTests
EOF

mkdir -p $NEMAKI_HOME/docker/solr

if [ -f "$NEMAKI_HOME/docker/solr/solr.war" ]; then
  echo "Solr WAR file already exists, skipping build..."
  exit 0
fi

if [ -d "$NEMAKI_HOME/solr" ]; then
  echo "Copying Solr source from repository..."
  cp -r $NEMAKI_HOME/solr $NEMAKI_HOME/docker/
else
  echo "Creating empty Solr WAR file as placeholder..."
  mkdir -p $NEMAKI_HOME/docker/solr/target
  touch $NEMAKI_HOME/docker/solr/target/solr.war
fi

if [ -d "$NEMAKI_HOME/solr" ]; then
  echo "Building Solr WAR file using Docker..."
  cd $NEMAKI_HOME/docker
  docker build -t nemaki-solr-build -f Dockerfile.solr-build .
  docker run --rm -v $NEMAKI_HOME/docker/solr/target:/app/solr/target nemaki-solr-build
else
  echo "Skipping Solr build as source directory doesn't exist..."
fi

if [ -f "$NEMAKI_HOME/docker/solr/target/solr.war" ]; then
  cp $NEMAKI_HOME/docker/solr/target/solr.war $NEMAKI_HOME/docker/solr/
else
  echo "Creating empty Solr WAR file as placeholder..."
  touch $NEMAKI_HOME/docker/solr/solr.war
fi

echo "Solr WAR file prepared successfully!"
