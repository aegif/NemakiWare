FROM tomcat:9-jre8

# Remove default Tomcat applications
RUN rm -rf /usr/local/tomcat/webapps/*

# Copy the Solr WAR file
COPY solr.war /usr/local/tomcat/webapps/solr.war

# Copy Solr configuration
COPY solr /usr/local/tomcat/solr

# Set environment variables
ENV CATALINA_OPTS="-Xms512m -Xmx1024m -Dsolr.solr.home=/usr/local/tomcat/solr"

# Expose the default Tomcat port
EXPOSE 8080

# Start Tomcat
CMD ["catalina.sh", "run"]