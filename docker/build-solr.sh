#!/bin/bash
set -e

SCRIPT_DIR=$(cd $(dirname $0); pwd)
NEMAKI_HOME=$(cd $SCRIPT_DIR/..; pwd)

echo "Building Solr JAR file using Docker with Java 11..."

cat > $NEMAKI_HOME/docker/Dockerfile.solr-build << EOF
FROM maven:3.8-openjdk-11

WORKDIR /app
COPY solr /app/solr

# Create custom Maven settings to allow HTTP repositories for Restlet
RUN mkdir -p /root/.m2
COPY maven-settings.xml /root/.m2/settings.xml

# Build Solr JAR with proper dependencies and custom settings
RUN cd /app/solr && \\
    echo "Building Solr JAR with Maven..." && \\
    mvn clean compile package -DskipTests -Pdevelopment -s /root/.m2/settings.xml
EOF

mkdir -p $NEMAKI_HOME/docker/solr

# Remove existing JAR file to force rebuild
if [ -f "$NEMAKI_HOME/docker/solr/solr.jar" ]; then
  echo "Removing existing Solr JAR file to force rebuild..."
  rm -f "$NEMAKI_HOME/docker/solr/solr.jar"
fi

if [ -d "$NEMAKI_HOME/solr" ]; then
  echo "Copying Solr source from repository..."
  cp -r $NEMAKI_HOME/solr $NEMAKI_HOME/docker/
  
  echo "Building Solr JAR file using Docker..."
  cd $NEMAKI_HOME/docker
  
  # Build Docker image
  if docker build -t nemaki-solr-build -f Dockerfile.solr-build .; then
    echo "Docker build successful, extracting JAR file..."
    # Copy the JAR file from the built Docker image
    if docker run --rm -v $NEMAKI_HOME/docker/solr:/host-solr nemaki-solr-build sh -c "cp /app/solr/target/solr.jar /host-solr/ && echo 'JAR file copied successfully'"; then
      echo "Solr JAR file extracted successfully"
    else
      echo "ERROR: Failed to extract JAR file from Docker container"
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

# Verify the JAR file was created
if [ ! -f "$NEMAKI_HOME/docker/solr/solr.jar" ]; then
  echo "ERROR: Solr JAR file was not created"
  exit 1
fi

echo "Solr JAR file built successfully at: $NEMAKI_HOME/docker/solr/solr.jar"
