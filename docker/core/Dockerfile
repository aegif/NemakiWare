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
ENV JAVA_OPTS="-Xms512m -Xmx1024m -XX:+DisableExplicitGC -Djava.net.preferIPv4Stack=true -Djava.net.preferIPv6Addresses=false -Dnemakiware.properties=/usr/local/tomcat/conf/nemakiware.properties -Drepositories.yml=/usr/local/tomcat/conf/repositories.yml -Dlog4j.configuration=file:/usr/local/tomcat/conf/log4j.properties"

# CouchDB connection — NO default credentials in the image.
# Must be provided at runtime via docker-compose environment, env_file, or -e flags.
# Example: docker run -e COUCHDB_URL=http://couchdb:5984 -e COUCHDB_USERNAME=admin -e COUCHDB_PASSWORD=secret ...

EXPOSE 8080

# Build CATALINA_OPTS from environment variables at container start, then exec Tomcat.
# This ensures -D properties are passed to the JVM, not to catalina.sh as arguments.
CMD ["sh", "-c", "export CATALINA_OPTS=\"$CATALINA_OPTS -Ddb.couchdb.url=${COUCHDB_URL:-http://couchdb:5984} -Ddb.couchdb.auth.enabled=${COUCHDB_AUTH_ENABLED:-true} -Ddb.couchdb.auth.username=${COUCHDB_USERNAME:-admin} -Ddb.couchdb.auth.password=${COUCHDB_PASSWORD:-password}\"; exec catalina.sh run"]
