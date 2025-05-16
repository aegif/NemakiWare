



set -e

SCRIPT_DIR=$(cd $(dirname $0); pwd)
cd $SCRIPT_DIR

if [ -z "$COUCHDB_USER" ] || [ -z "$COUCHDB_PASSWORD" ]; then
  echo "Please set COUCHDB_USER and COUCHDB_PASSWORD environment variables"
  echo "Example:"
  echo "  export COUCHDB_USER=admin"
  echo "  export COUCHDB_PASSWORD=password"
  exit 1
fi

export COUCHDB_USER
export COUCHDB_PASSWORD

echo "Starting CouchDB containers..."
docker-compose up -d

echo "Waiting for CouchDB to be ready..."
sleep 10

echo "Building the project..."
mvn clean package

echo "Running tests against CouchDB 2.x (no auth)..."
COUCHDB_URL=http://localhost:5984 mvn test

echo "Running tests against CouchDB 3.x (with auth)..."
COUCHDB_URL=http://localhost:15984 COUCHDB_USERNAME="$COUCHDB_USER" COUCHDB_PASSWORD="$COUCHDB_PASSWORD" mvn test

echo "Stopping CouchDB containers..."
docker-compose down

echo "All tests completed successfully!"
