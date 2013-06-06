#!/bin/sh

echo "Server starting..."
cd ../nemakiware
mvn jetty:run &

echo "Solr starting..."
cd ../nemakisolr
mvn jetty:run & 

echo "Client starting..."
cd ../nemakishare
rails s & 