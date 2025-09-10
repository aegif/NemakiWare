FROM tomcat:10-jdk17-openjdk-slim

# Install curl for health checks and connectivity testing
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*

# Remove default Tomcat applications
RUN rm -rf /usr/local/tomcat/webapps/*

# Copy the Jakarta-ready WAR file
COPY core.war /usr/local/tomcat/webapps/core.war

# Copy configuration files
COPY nemakiware.properties /usr/local/tomcat/conf/nemakiware.properties
COPY repositories.yml /usr/local/tomcat/conf/repositories.yml
COPY log4j.properties /usr/local/tomcat/conf/log4j.properties

# Set environment variables for CouchDB connection and Java configuration
ENV JAVA_OPTS="-Xms512m -Xmx1024m -XX:+DisableExplicitGC -Djava.net.preferIPv4Stack=true -Djava.net.preferIPv6Addresses=false -Dnemakiware.properties=/usr/local/tomcat/conf/nemakiware.properties -Drepositories.yml=/usr/local/tomcat/conf/repositories.yml -Dlog4j.configuration=file:/usr/local/tomcat/conf/log4j.properties -Ddb.couchdb.url=http://couchdb:5984 -Ddb.couchdb.auth.enabled=true -Ddb.couchdb.auth.username=admin -Ddb.couchdb.auth.password=password"

# Expose Tomcat port
EXPOSE 8080

# Start Tomcat
CMD ["catalina.sh", "run"]
