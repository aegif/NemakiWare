FROM tomcat:9-jre8

# Set environment variables
ENV CATALINA_HOME /usr/local/tomcat
ENV CATALINA_BASE /usr/local/tomcat

# Install OpenJDK for compilation
RUN apt-get update && apt-get install -y openjdk-8-jdk-headless && rm -rf /var/lib/apt/lists/*

# Remove default Tomcat applications
RUN rm -rf $CATALINA_HOME/webapps/*

# Create necessary directories
RUN mkdir -p $CATALINA_HOME/conf/Catalina/localhost/
RUN mkdir -p $CATALINA_HOME/lib/custom/

# Copy the WAR file
COPY ./core.war $CATALINA_HOME/webapps/core.war

# Copy context configuration
COPY ./context.xml $CATALINA_HOME/conf/Catalina/localhost/core.xml

# Copy configuration files
COPY ./nemakiware.properties $CATALINA_HOME/conf/nemakiware.properties
COPY ./repositories.yml $CATALINA_HOME/conf/repositories.yml
COPY ./log4j.properties $CATALINA_HOME/conf/log4j.properties

# Copy and compile the EktorpThreadKiller
COPY ./EktorpThreadKiller.java /tmp/
RUN javac -d $CATALINA_HOME/lib/custom/ /tmp/EktorpThreadKiller.java

# Create a startup script that runs the EktorpThreadKiller before starting Tomcat
RUN echo '#!/bin/bash\n\
# Run the EktorpThreadKiller\n\
java -cp $CATALINA_HOME/lib/custom/ EktorpThreadKiller\n\
\n\
# Set system properties to disable Ektorp thread creation\n\
export JAVA_OPTS="$JAVA_OPTS -Dorg.ektorp.support.AutoUpdateViewOnChange=false -Dorg.ektorp.http.IdleConnectionMonitor.enabled=false -Dorg.ektorp.support.DesignDocument.UPDATE_ON_DIFF=false -Dorg.ektorp.support.DesignDocument.ALLOW_AUTO_UPDATE=true -Dorg.apache.catalina.loader.WebappClassLoader.ENABLE_CLEAR_REFERENCES=true -Dorg.apache.tomcat.util.scan.StandardJarScanFilter.jarsToSkip=*.jar"\n\
\n\
# Start Tomcat\n\
exec catalina.sh run\n\
' > $CATALINA_HOME/bin/startup-with-thread-killer.sh

# Make the startup script executable
RUN chmod +x $CATALINA_HOME/bin/startup-with-thread-killer.sh

# Set environment variables for Ektorp and thread management
ENV JAVA_OPTS="-Dorg.ektorp.support.AutoUpdateViewOnChange=false -Dorg.ektorp.http.IdleConnectionMonitor.enabled=false -Dorg.ektorp.support.DesignDocument.UPDATE_ON_DIFF=false -Dorg.ektorp.support.DesignDocument.ALLOW_AUTO_UPDATE=true -Dorg.apache.catalina.loader.WebappClassLoader.ENABLE_CLEAR_REFERENCES=true -Dorg.apache.tomcat.util.scan.StandardJarScanFilter.jarsToSkip=*.jar"
ENV CATALINA_OPTS="-Xms512m -Xmx1024m -XX:+DisableExplicitGC -Dnemakiware.properties=/usr/local/tomcat/conf/nemakiware.properties -Drepositories.yml=/usr/local/tomcat/conf/repositories.yml -Dlog4j.configuration=file:/usr/local/tomcat/conf/log4j.properties"

# Expose the default Tomcat port
EXPOSE 8080

# Start Tomcat with the thread killer
CMD ["/usr/local/tomcat/bin/startup-with-thread-killer.sh"]
