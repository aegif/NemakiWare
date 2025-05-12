

set -e

SCRIPT_DIR=$(cd $(dirname $0); pwd)
cd $SCRIPT_DIR

export COUCHDB_USER=${COUCHDB_USER:-admin}
export COUCHDB_PASSWORD=${COUCHDB_PASSWORD:-password}

echo "Starting NemakiWare Docker environment..."
docker-compose up -d

echo "Waiting for services to start..."
sleep 30

echo "Testing CouchDB 2.x..."
curl -s http://localhost:5984 > /dev/null
if [ $? -eq 0 ]; then
  echo "  CouchDB 2.x is running"
else
  echo "  Error: CouchDB 2.x is not running"
  exit 1
fi

echo "Testing CouchDB 3.x..."
curl -s -u $COUCHDB_USER:$COUCHDB_PASSWORD http://localhost:15984 > /dev/null
if [ $? -eq 0 ]; then
  echo "  CouchDB 3.x is running"
else
  echo "  Error: CouchDB 3.x is not running"
  exit 1
fi

echo "Testing Core server with CouchDB 2.x..."
curl -s http://localhost:8080/core > /dev/null
if [ $? -eq 0 ]; then
  echo "  Core server with CouchDB 2.x is running"
else
  echo "  Error: Core server with CouchDB 2.x is not running"
  exit 1
fi

echo "Testing Core server with CouchDB 3.x..."
curl -s http://localhost:18080/core > /dev/null
if [ $? -eq 0 ]; then
  echo "  Core server with CouchDB 3.x is running"
else
  echo "  Error: Core server with CouchDB 3.x is not running"
  exit 1
fi

echo "Testing Solr server with CouchDB 2.x..."
curl -s http://localhost:8081/solr > /dev/null
if [ $? -eq 0 ]; then
  echo "  Solr server with CouchDB 2.x is running"
else
  echo "  Error: Solr server with CouchDB 2.x is not running"
  exit 1
fi

echo "Testing Solr server with CouchDB 3.x..."
curl -s http://localhost:18081/solr > /dev/null
if [ $? -eq 0 ]; then
  echo "  Solr server with CouchDB 3.x is running"
else
  echo "  Error: Solr server with CouchDB 3.x is not running"
  exit 1
fi

echo "Testing UI server with CouchDB 2.x..."
curl -s http://localhost:9000 > /dev/null
if [ $? -eq 0 ]; then
  echo "  UI server with CouchDB 2.x is running"
else
  echo "  Error: UI server with CouchDB 2.x is not running"
  exit 1
fi

echo "Testing UI server with CouchDB 3.x..."
curl -s http://localhost:19000 > /dev/null
if [ $? -eq 0 ]; then
  echo "  UI server with CouchDB 3.x is running"
else
  echo "  Error: UI server with CouchDB 3.x is not running"
  exit 1
fi

echo "Testing CouchDB initializer for CouchDB 2.x..."
curl -s http://localhost:5984/bedroom > /dev/null
if [ $? -eq 0 ]; then
  echo "  CouchDB initializer for CouchDB 2.x has created the database"
else
  echo "  Error: CouchDB initializer for CouchDB 2.x has not created the database"
  exit 1
fi

echo "Testing CouchDB initializer for CouchDB 3.x..."
curl -s -u $COUCHDB_USER:$COUCHDB_PASSWORD http://localhost:15984/bedroom > /dev/null
if [ $? -eq 0 ]; then
  echo "  CouchDB initializer for CouchDB 3.x has created the database"
else
  echo "  Error: CouchDB initializer for CouchDB 3.x has not created the database"
  exit 1
fi

echo "All tests passed!"
echo "You can access the NemakiWare UI at:"
echo "  CouchDB 2.x: http://localhost:9000"
echo "  CouchDB 3.x: http://localhost:19000"

echo "To stop the Docker environment, run:"
echo "  docker-compose down"
