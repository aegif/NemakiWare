FROM tomcat:8-jre8

# Set environment variables
ENV CATALINA_HOME /usr/local/tomcat
ENV PATH $CATALINA_HOME/bin:$PATH
ENV JAVA_OPTS="-Xms512m -Xmx1024m -XX:MaxPermSize=256m"

# Remove default Tomcat applications
RUN rm -rf $CATALINA_HOME/webapps/*

# Create necessary directories
RUN mkdir -p $CATALINA_HOME/shared/classes/
RUN mkdir -p $CATALINA_HOME/conf/Catalina/localhost/

# Copy the WAR file directly with the context path name
COPY ./core.war $CATALINA_HOME/webapps/core.war

# Copy context configuration
COPY ./context.xml $CATALINA_HOME/conf/Catalina/localhost/core.xml

# Copy configuration files
COPY ./nemakiware.properties $CATALINA_HOME/shared/classes/
COPY ./repositories.yml $CATALINA_HOME/shared/classes/
COPY ./log4j.properties $CATALINA_HOME/shared/classes/

# Set environment variables for Ektorp
ENV CATALINA_OPTS="-Xms512m -Xmx1024m -Dorg.ektorp.support.AutoUpdateViewOnChange=true -Dorg.ektorp.http.IdleConnectionMonitor.enabled=false"

# Expose the default Tomcat port
EXPOSE 8080

# Start Tomcat
CMD ["catalina.sh", "run"]
