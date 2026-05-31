FROM tomcat:11.0-jdk21-openjdk-slim

# Install curl for health checks and LibreOffice for Office document preview
RUN apt-get update && apt-get install -y \
    curl \
    libreoffice-writer \
    libreoffice-calc \
    libreoffice-impress \
    libreoffice-common \
    fonts-dejavu \
    fonts-liberation \
    && rm -rf /var/lib/apt/lists/*

# Remove default Tomcat applications
RUN rm -rf /usr/local/tomcat/webapps/*

# Copy the Jakarta-ready WAR file
COPY core.war /usr/local/tomcat/webapps/core.war

# Copy configuration files
COPY nemakiware.properties /usr/local/tomcat/conf/nemakiware.properties
COPY repositories.yml /usr/local/tomcat/conf/repositories.yml
COPY log4j.properties /usr/local/tomcat/conf/log4j.properties
COPY server.xml /usr/local/tomcat/conf/server.xml

# Java heap and general options (no credentials here)
ENV JAVA_OPTS="-Xms512m -Xmx1024m -XX:+DisableExplicitGC -Djava.net.preferIPv4Stack=true -Djava.net.preferIPv6Addresses=false -Dnemakiware.properties=/usr/local/tomcat/conf/nemakiware.properties -Drepositories.yml=/usr/local/tomcat/conf/repositories.yml -Dlog4j.configuration=file:/usr/local/tomcat/conf/log4j.properties -Djdk.httpclient.allowRestrictedHeaders=host"

# NO credentials in image layers. Must be provided at runtime:
#   docker run -e COUCHDB_URL=... -e COUCHDB_USERNAME=... -e COUCHDB_PASSWORD=... ...
# Or via docker-compose environment / env_file.

EXPOSE 8080

# Validate required credentials at startup, then inject via CATALINA_OPTS.
# Supports both COUCHDB_USER (compose convention) and COUCHDB_USERNAME.
CMD ["sh", "-c", "\
  DB_USER=${COUCHDB_USER:-$COUCHDB_USERNAME}; \
  DB_PASS=$COUCHDB_PASSWORD; \
  if [ -z \"$DB_USER\" ] || [ -z \"$DB_PASS\" ]; then \
    echo 'ERROR: COUCHDB_USER and COUCHDB_PASSWORD must be set via environment variables.'; \
    echo 'Example: docker run -e COUCHDB_USER=admin -e COUCHDB_PASSWORD=secret ...'; \
    exit 1; \
  fi; \
  export CATALINA_OPTS=\"$CATALINA_OPTS \
    -Ddb.couchdb.url=${COUCHDB_URL:-http://couchdb:5984} \
    -Ddb.couchdb.auth.enabled=${COUCHDB_AUTH_ENABLED:-true} \
    -Ddb.couchdb.auth.username=$DB_USER \
    -Ddb.couchdb.auth.password=$DB_PASS\"; \
  exec catalina.sh run"]
