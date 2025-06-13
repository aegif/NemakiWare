#!/bin/bash
set -e

host="$1"
port="$2"
shift 2
cmd="$@"

echo "Waiting for $host:$port to be ready..."

for i in {1..60}; do
    if curl -s --fail -u "admin:password" "http://$host:$port/" > /dev/null 2>&1; then
        echo "CouchDB is ready!"
        break
    fi
    echo "Attempt $i/60: CouchDB not ready yet, waiting 5 seconds..."
    sleep 5
done

# Final check
if ! curl -s --fail -u "admin:password" "http://$host:$port/" > /dev/null 2>&1; then
    echo "CouchDB is still not ready after 5 minutes, but starting anyway..."
fi

echo "Starting Core application..."
exec $cmd