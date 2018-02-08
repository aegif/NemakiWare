#!/bin/bash

# Initialize first run
if [[ -e /.firstrun ]]; then
    /scripts/first_run.sh &
else
	sh /usr/local/tomcat/bin/startup.sh
fi

# Start CouchDB
echo "Starting CouchDB..."
/home/couchdb/bin/couchdb 




