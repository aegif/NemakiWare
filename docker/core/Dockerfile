FROM tomcat:9-jre8

# Set environment variables
ENV CATALINA_HOME /usr/local/tomcat
ENV CATALINA_BASE /usr/local/tomcat

# Remove default Tomcat applications
RUN rm -rf $CATALINA_HOME/webapps/*

# Create necessary directories
RUN mkdir -p $CATALINA_HOME/conf/Catalina/localhost/
RUN mkdir -p /opt/nemaki/

# Copy the WAR file to a location outside of webapps
COPY ./core.war /opt/nemaki/core.war

# Copy context configuration
COPY ./context.xml $CATALINA_HOME/conf/Catalina/localhost/core.xml

# Copy configuration files
COPY ./nemakiware.properties $CATALINA_HOME/conf/nemakiware.properties
COPY ./repositories.yml $CATALINA_HOME/conf/repositories.yml
COPY ./log4j.properties $CATALINA_HOME/conf/log4j.properties

# Set environment variables for Ektorp and thread management
ENV JAVA_OPTS="-Dorg.ektorp.support.AutoUpdateViewOnChange=false -Dorg.ektorp.http.IdleConnectionMonitor.enabled=false -Dorg.ektorp.support.DesignDocument.UPDATE_ON_DIFF=false -Dorg.ektorp.support.DesignDocument.ALLOW_AUTO_UPDATE=false -Dorg.apache.catalina.loader.WebappClassLoader.ENABLE_CLEAR_REFERENCES=true -Dorg.apache.tomcat.util.scan.StandardJarScanFilter.jarsToSkip=*.jar"
ENV CATALINA_OPTS="-Xms512m -Xmx1024m -XX:+DisableExplicitGC -Dnemakiware.properties=/usr/local/tomcat/conf/nemakiware.properties -Drepositories.yml=/usr/local/tomcat/conf/repositories.yml -Dlog4j.configuration=file:/usr/local/tomcat/conf/log4j.properties"

# Expose the default Tomcat port
EXPOSE 8080

# Start Tomcat
CMD ["catalina.sh", "run"]
