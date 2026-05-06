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

# Java options — CouchDB credentials are injected via environment variables
# at runtime (docker-compose env_file or -e flags), NOT baked into the image.
ENV JAVA_OPTS="-Xms512m -Xmx1024m -XX:+DisableExplicitGC -Djava.net.preferIPv4Stack=true -Djava.net.preferIPv6Addresses=false -Dnemakiware.properties=/usr/local/tomcat/conf/nemakiware.properties -Drepositories.yml=/usr/local/tomcat/conf/repositories.yml -Dlog4j.configuration=file:/usr/local/tomcat/conf/log4j.properties"
# CouchDB connection — override via docker-compose environment or env_file
ENV COUCHDB_URL="http://couchdb:5984" \
    COUCHDB_AUTH_ENABLED="true" \
    COUCHDB_USERNAME="admin" \
    COUCHDB_PASSWORD="password"
# Pass CouchDB config as system properties at startup
# Expose Tomcat port
EXPOSE 8080

# Start Tomcat with CouchDB system properties from environment variables
CMD ["sh", "-c", "catalina.sh run -Ddb.couchdb.url=${COUCHDB_URL} -Ddb.couchdb.auth.enabled=${COUCHDB_AUTH_ENABLED} -Ddb.couchdb.auth.username=${COUCHDB_USERNAME} -Ddb.couchdb.auth.password=${COUCHDB_PASSWORD}"]
