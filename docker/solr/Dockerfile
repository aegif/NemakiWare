FROM tomcat:9-jdk8

# Set environment variables
ENV CATALINA_HOME /usr/local/tomcat
ENV PATH $CATALINA_HOME/bin:$PATH
ENV JAVA_OPTS="-Xms512m -Xmx1024m -XX:MaxPermSize=256m"

# Remove default Tomcat applications
RUN rm -rf $CATALINA_HOME/webapps/*

# Copy the WAR file
COPY ./solr.war $CATALINA_HOME/webapps/solr.war

# Create configuration directory
RUN mkdir -p $CATALINA_HOME/shared/classes/

# Copy configuration files
COPY ./nemakisolr.properties $CATALINA_HOME/shared/classes/
COPY ./log4j.properties $CATALINA_HOME/shared/classes/

# Expose the default Tomcat port
EXPOSE 8080

# Start Tomcat
CMD ["catalina.sh", "run"]
