FROM tomcat:7-jre8

# Remove default Tomcat applications
RUN rm -rf /usr/local/tomcat/webapps/*

# Create necessary directories for context configuration
RUN mkdir -p /usr/local/tomcat/conf/Catalina/localhost

# Copy the UI WAR file with the correct context path name
COPY ui.war /usr/local/tomcat/webapps/ui.war

# Copy context configuration
COPY ui.xml /usr/local/tomcat/conf/Catalina/localhost/ui.xml

# Copy configuration files
COPY nemakiware_ui.properties /usr/local/tomcat/conf/
COPY log4j.properties /usr/local/tomcat/conf/

# Set environment variables
ENV CATALINA_OPTS="-Xms512m -Xmx1024m -Dlog4j.configuration=file:/usr/local/tomcat/conf/log4j.properties -Dnemakiware_ui.properties=/usr/local/tomcat/conf/nemakiware_ui.properties"

# Expose the default Tomcat port
EXPOSE 8080

# Start Tomcat
CMD ["catalina.sh", "run"]
