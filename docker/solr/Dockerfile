FROM tomcat:8.5-jdk8

# Copy the Solr WAR file
COPY solr.war /usr/local/tomcat/webapps/

# Copy Solr configuration
COPY solr /usr/local/tomcat/solr

# Set environment variables
ENV CATALINA_OPTS="-Xms512m -Xmx1024m -Dsolr.solr.home=/usr/local/tomcat/solr"

# Expose port
EXPOSE 8080
