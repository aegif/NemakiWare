#!/bin/sh

echo "Server starting..."
cd ../nemakiware
nohup mvn jetty:run &

echo "Solr starting..."
cd ../nemakisolr
nohup mvn jetty:run & 

echo "Client starting..."
cd ../nemakishare
nohup rails s & 