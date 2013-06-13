#!/bin/sh

echo "Client stopping..."
cd ../nemakishare
kill -9 $(cat tmp/pids/server.pid)
rm -f tmp/pids/server.pid

echo "Solr stopping..."
cd ../nemakisolr
mvn jetty:stop

echo "Server stopping..."
cd ../nemakiware
mvn jetty:stop