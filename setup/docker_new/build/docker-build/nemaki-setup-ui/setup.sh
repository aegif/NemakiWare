#!/bin/bash
chmod -R 777 /app

cd /app && mvn install
# cd /app/action && mvn install
# cd /app/common && mvn install

# cd /app/ui && activator clean
# cd /app/ui && activator war

# cd /app && mvn clean package
# cd /app/core && mvn clean package
# cd /app/solr && mvn clean package