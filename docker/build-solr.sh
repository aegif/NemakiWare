#!/bin/bash
set -e

SCRIPT_DIR=$(cd $(dirname $0); pwd)
NEMAKI_HOME=$(cd $SCRIPT_DIR/..; pwd)

echo "Building Solr WAR file using Docker with Java 8..."

cat > $NEMAKI_HOME/docker/Dockerfile.solr-build << EOF
FROM maven:3.8-openjdk-8

WORKDIR /app
COPY solr /app/solr

# Create custom Maven settings to allow HTTP repositories for Restlet
RUN mkdir -p /root/.m2
COPY maven-settings.xml /root/.m2/settings.xml

# Build Solr WAR with proper dependencies and custom settings
RUN cd /app/solr && \\
    echo "Building Solr WAR with Maven..." && \\
    mvn clean compile package -DskipTests -Pdevelopment -s /root/.m2/settings.xml
EOF

mkdir -p $NEMAKI_HOME/docker/solr

# Remove existing WAR file to force rebuild
if [ -f "$NEMAKI_HOME/docker/solr/solr.war" ]; then
  echo "Removing existing Solr WAR file to force rebuild..."
  rm -f "$NEMAKI_HOME/docker/solr/solr.war"
fi

if [ -d "$NEMAKI_HOME/solr" ]; then
  echo "Copying Solr source from repository..."
  cp -r $NEMAKI_HOME/solr $NEMAKI_HOME/docker/
  
  echo "Building Solr WAR file using Docker..."
  cd $NEMAKI_HOME/docker
  
  # Build Docker image
  if docker build -t nemaki-solr-build -f Dockerfile.solr-build .; then
    echo "Docker build successful, extracting WAR file..."
    # Copy the WAR file from the built Docker image
    if docker run --rm -v $NEMAKI_HOME/docker/solr:/host-solr nemaki-solr-build sh -c "cp /app/solr/target/solr.war /host-solr/ && echo 'WAR file copied successfully'"; then
      echo "Solr WAR file extracted successfully"
    else
      echo "ERROR: Failed to extract WAR file from Docker container"
      exit 1
    fi
  else
    echo "ERROR: Docker build failed"
    exit 1
  fi
else
  echo "ERROR: Solr source directory does not exist at $NEMAKI_HOME/solr"
  exit 1
fi

echo "Solr WAR file prepared successfully!"
