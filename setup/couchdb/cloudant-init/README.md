# CouchDB Initializer for NemakiWare

This module provides a modern implementation for initializing CouchDB databases for NemakiWare. It replaces the older bjornloka tool with a simpler, more maintainable approach that works with both CouchDB 2.x and 3.x.

## Features

- Support for CouchDB 2.x and 3.x
- Authentication support for CouchDB 3.x
- Non-authenticated mode for CouchDB 2.x
- Database creation and initialization
- Bulk document import
- Attachment handling
- Progress tracking

## Usage

The initializer can be used directly through the `Setup` class or by calling the `CouchDBInitializer` class:

```bash
# Using Setup (interactive mode)
java -cp target/cloudant-init.jar jp.aegif.nemaki.cloudantinit.Setup

# Using CouchDBInitializer directly
java -cp target/cloudant-init.jar jp.aegif.nemaki.cloudantinit.CouchDBInitializer \
  http://localhost:5984 \
  admin \
  password \
  bedroom \
  bedroom_init.dump \
  true
```

## Testing

To test the initializer with both CouchDB 2.x and 3.x, you can use Docker:

1. Create a Docker Compose file (`docker-compose.yml`):

```yaml
version: '3'

services:
  # CouchDB 2.x instance
  couchdb2:
    image: couchdb:2.3.1
    ports:
      - "5984:5984"
    environment:
      - COUCHDB_USER=${COUCHDB2_USER}
      - COUCHDB_PASSWORD=${COUCHDB2_PASSWORD}
    volumes:
      - couchdb2_data:/opt/couchdb/data

  # CouchDB 3.x instance
  couchdb3:
    image: couchdb:3.3.2
    ports:
      - "15984:5984"
    environment:
      - COUCHDB_USER=${COUCHDB3_USER}
      - COUCHDB_PASSWORD=${COUCHDB3_PASSWORD}
    volumes:
      - couchdb3_data:/opt/couchdb/data

volumes:
  couchdb2_data:
  couchdb3_data:
```

2. Create a test script:

```bash
#!/bin/bash

# Set environment variables for Docker Compose
export COUCHDB2_USER="admin"
export COUCHDB2_PASSWORD="your_password_here"
export COUCHDB3_USER="admin"
export COUCHDB3_PASSWORD="your_password_here"

# Start Docker containers
echo "Starting CouchDB containers..."
docker-compose up -d

# Wait for CouchDB to be ready
echo "Waiting for CouchDB to be ready..."
sleep 10

# Build the project
echo "Building the project..."
mvn clean package

# Run tests against CouchDB 2.x (no auth)
echo "Running tests against CouchDB 2.x (no auth)..."
COUCHDB_URL=http://localhost:5984 mvn test

# Run tests against CouchDB 3.x (with auth)
echo "Running tests against CouchDB 3.x (with auth)..."
COUCHDB_URL=http://localhost:15984 COUCHDB_USERNAME="$COUCHDB3_USER" COUCHDB_PASSWORD="$COUCHDB3_PASSWORD" mvn test

# Stop Docker containers
echo "Stopping CouchDB containers..."
docker-compose down

echo "All tests completed successfully!"
```

3. Make the script executable and run it:

```bash
chmod +x test.sh
./test.sh
```

## Security Considerations

- Never commit credentials to the repository
- Use environment variables for sensitive information
- For CouchDB 3.x, always use authentication
- For CouchDB 2.x, authentication is optional but recommended
