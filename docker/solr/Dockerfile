FROM tomcat:9-jre11

# Remove default Tomcat applications
RUN rm -rf /usr/local/tomcat/webapps/*

# Copy the Solr WAR file
COPY solr.war /usr/local/tomcat/webapps/solr.war

# Copy Solr configuration
COPY solr /usr/local/tomcat/solr

# Fix Solr data directory permissions for container access
RUN chmod -R 755 /usr/local/tomcat/solr && \
    chown -R root:root /usr/local/tomcat/solr && \
    mkdir -p /usr/local/tomcat/solr/nemaki/data && \
    mkdir -p /usr/local/tomcat/solr/token/data && \
    chmod -R 777 /usr/local/tomcat/solr/nemaki/data && \
    chmod -R 777 /usr/local/tomcat/solr/token/data

# Set environment variables
ENV CATALINA_OPTS="-Xms512m -Xmx1024m -Dsolr.solr.home=/usr/local/tomcat/solr"

# Expose the default Tomcat port
EXPOSE 8080

# Start Tomcat
CMD ["catalina.sh", "run"]
