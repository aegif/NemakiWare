FROM tomcat:7-jre8

# Set environment variables
ENV CATALINA_HOME /usr/local/tomcat
ENV CATALINA_BASE /usr/local/tomcat

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
COPY ./nemakiware.properties $CATALINA_HOME/conf/nemakiware.properties
COPY ./repositories.yml $CATALINA_HOME/conf/repositories.yml
COPY ./log4j.properties $CATALINA_HOME/conf/log4j.properties

# Set environment variables for Ektorp and thread management
ENV JAVA_OPTS="-Dorg.ektorp.support.AutoUpdateViewOnChange=true -Dorg.ektorp.http.IdleConnectionMonitor.enabled=false -Dorg.apache.catalina.loader.WebappClassLoader.ENABLE_CLEAR_REFERENCES=true"
ENV CATALINA_OPTS="-Xms512m -Xmx1024m -XX:+DisableExplicitGC -Dnemakiware.properties=/usr/local/tomcat/conf/nemakiware.properties -Drepositories.yml=/usr/local/tomcat/conf/repositories.yml -Dlog4j.configuration=file:/usr/local/tomcat/conf/log4j.properties"

# Expose the default Tomcat port
EXPOSE 8080

# Start Tomcat
CMD ["catalina.sh", "run"]
