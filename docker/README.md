# NemakiWare Docker Environment

This directory contains Docker configurations for running the complete NemakiWare stack, including:

- CouchDB (both 2.x and 3.x versions)
- Core server (CMIS server)
- Solr server (search engine)
- UI server (web interface)
- CouchDB initializer

## Prerequisites

- Docker and Docker Compose installed
- Git repository cloned

## Directory Structure

```
docker/
├── core/                  # Core server Docker configuration
│   ├── Dockerfile         # Core server Dockerfile
│   ├── core.war           # Core server WAR file (to be built)
│   └── *.properties       # Configuration files
├── solr/                  # Solr server Docker configuration
│   ├── Dockerfile         # Solr server Dockerfile
│   ├── solr.war           # Solr server WAR file (to be built)
│   └── *.properties       # Configuration files
├── ui/                    # UI server Docker configuration
│   ├── Dockerfile         # UI server Dockerfile
│   └── *.properties       # Configuration files
├── docker-compose.yml     # Docker Compose configuration
└── README.md              # This file
```

## Building the Docker Environment

Before running the Docker environment, you need to build the NemakiWare components:

1. Build the core server:
   ```bash
   cd /path/to/NemakiWare
   mvn clean package -pl core -am
   cp core/target/core.war docker/core/
   ```

2. Build the Solr server:
   ```bash
   cd /path/to/NemakiWare
   mvn clean package -pl solr -am
   cp solr/target/solr.war docker/solr/
   ```

3. Copy configuration files:
   ```bash
   cp core/src/main/webapp/WEB-INF/classes/nemakiware.properties docker/core/
   cp core/src/main/webapp/WEB-INF/classes/repositories.yml docker/core/
   cp core/src/main/webapp/WEB-INF/classes/log4j.properties docker/core/
   cp solr/src/main/webapp/WEB-INF/classes/nemakisolr.properties docker/solr/
   cp solr/src/main/webapp/WEB-INF/classes/log4j.properties docker/solr/
   cp ui/conf/application.conf docker/ui/
   cp ui/conf/nemakiware_ui.properties docker/ui/
   ```

4. Build the CouchDB initializer:
   ```bash
   cd /path/to/NemakiWare/setup/couchdb/cloudant-init
   mvn clean package
   ```

## Running the Docker Environment

1. Start the Docker environment:
   ```bash
   cd /path/to/NemakiWare/docker
   docker-compose up -d
   ```

2. Wait for all services to start (this may take a few minutes):
   ```bash
   docker-compose logs -f
   ```

3. Access the NemakiWare UI:
   - CouchDB 2.x: http://localhost:9000
   - CouchDB 3.x: http://localhost:19000

## Testing with Different CouchDB Versions

The Docker environment includes both CouchDB 2.x and 3.x instances, each with its own set of NemakiWare components. You can test both versions simultaneously:

### CouchDB 2.x Environment

- CouchDB: http://localhost:5984
- Core server: http://localhost:8080/core
- Solr server: http://localhost:8081/solr
- UI server: http://localhost:9000

### CouchDB 3.x Environment

- CouchDB: http://localhost:15984
- Core server: http://localhost:18080/core
- Solr server: http://localhost:18081/solr
- UI server: http://localhost:19000

## Testing the CouchDB Initializer

The CouchDB initializer is automatically run when the Docker environment starts. You can verify the initialization by:

1. Checking the initializer logs:
   ```bash
   docker-compose logs initializer2  # For CouchDB 2.x
   docker-compose logs initializer3  # For CouchDB 3.x
   ```

2. Accessing the CouchDB admin interface:
   - CouchDB 2.x: http://localhost:5984/_utils
   - CouchDB 3.x: http://localhost:15984/_utils

3. Verifying the database creation:
   - Check if the "bedroom" database exists
   - Check if documents have been imported

## Troubleshooting

If you encounter issues with the Docker environment:

1. Check the logs for each service:
   ```bash
   docker-compose logs core2
   docker-compose logs solr2
   docker-compose logs ui2
   docker-compose logs couchdb2
   # Or for CouchDB 3.x
   docker-compose logs core3
   docker-compose logs solr3
   docker-compose logs ui3
   docker-compose logs couchdb3
   ```

2. Restart a specific service:
   ```bash
   docker-compose restart core2
   ```

3. Rebuild a specific service:
   ```bash
   docker-compose build core2
   docker-compose up -d core2
   ```

4. Reset the entire environment:
   ```bash
   docker-compose down -v
   docker-compose up -d
   ```

## Security Considerations

- The default credentials for CouchDB are set to admin/password
- For production use, set secure passwords using environment variables:
  ```bash
  export COUCHDB_USER=admin
  export COUCHDB_PASSWORD=secure_password
  docker-compose up -d
  ```
