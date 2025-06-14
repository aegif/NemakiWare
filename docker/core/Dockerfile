FROM tomcat:9-jre8

# Set environment variables
ENV CATALINA_HOME /usr/local/tomcat
ENV CATALINA_BASE /usr/local/tomcat

# Install OpenJDK for compilation and curl for CouchDB connectivity testing
RUN apt-get update && apt-get install -y openjdk-8-jdk-headless curl && rm -rf /var/lib/apt/lists/*

# Remove default Tomcat applications
RUN rm -rf $CATALINA_HOME/webapps/*

# Create necessary directories
RUN mkdir -p $CATALINA_HOME/conf/Catalina/localhost/
RUN mkdir -p $CATALINA_HOME/lib/custom/

# Copy the WAR file
COPY ./core.war $CATALINA_HOME/webapps/core.war

# Extract WAR to ensure proper deployment structure - remove any existing extraction first
RUN cd $CATALINA_HOME/webapps && rm -rf core && mkdir -p core && cd core && jar -xf ../core.war

# Copy context configuration
COPY ./context.xml $CATALINA_HOME/conf/Catalina/localhost/core.xml

# Copy configuration files - using config2 as default, can be overridden at runtime
COPY ./config2/nemakiware.properties $CATALINA_HOME/conf/nemakiware.properties
# Also copy to classpath so Spring can find it (overwrite the one in WAR)
COPY ./config2/nemakiware.properties $CATALINA_HOME/webapps/core/WEB-INF/classes/nemakiware.properties
# Don't override Spring config files - they are already properly included in the WAR
COPY ./repositories.yml $CATALINA_HOME/conf/repositories.yml
COPY ./log4j.properties $CATALINA_HOME/conf/log4j.properties

# Note: No longer using wait-for-couchdb.sh as we implement lazy initialization

# Copy and compile the thread killers for Ektorp and EHCache
COPY ./EktorpThreadKiller.java /tmp/
COPY ./EHCacheThreadKiller.java /tmp/
COPY ./EHCacheShutdownListener.java /tmp/
RUN javac -cp $CATALINA_HOME/lib/*:$CATALINA_HOME/webapps/core/WEB-INF/lib/* -d $CATALINA_HOME/lib/custom/ /tmp/EktorpThreadKiller.java
RUN javac -cp $CATALINA_HOME/lib/*:$CATALINA_HOME/webapps/core/WEB-INF/lib/* -d $CATALINA_HOME/lib/custom/ /tmp/EHCacheThreadKiller.java
RUN javac -cp $CATALINA_HOME/lib/*:$CATALINA_HOME/webapps/core/WEB-INF/lib/* -d $CATALINA_HOME/lib/custom/ /tmp/EHCacheShutdownListener.java

# Create a startup script that waits for CouchDB and runs the thread killers before starting Tomcat
RUN echo '#!/bin/bash\n\
echo "Clearing Tomcat cache directories..."\n\
rm -rf $CATALINA_HOME/work/*\n\
rm -rf $CATALINA_HOME/temp/*\n\
echo "Cache cleared successfully."\n\
\n\
echo "Re-extracting WAR file for clean deployment..."\n\
cd $CATALINA_HOME/webapps && rm -rf core && mkdir -p core && cd core && jar -xf ../core.war\n\
echo "WAR re-extraction completed."\n\
\n\
echo "Copying configuration files based on environment..."\n\
if [ -f "/usr/local/tomcat/conf/nemakiware.properties" ]; then\n\
  cp /usr/local/tomcat/conf/nemakiware.properties /usr/local/tomcat/webapps/core/WEB-INF/classes/nemakiware.properties\n\
  echo "Configuration files copied."\n\
fi\n\
\n\
# Check if this is Core3 and copy Core3-specific configuration\n\
if [ -d "/usr/local/tomcat/core-config3" ]; then\n\
  echo "Detected Core3 environment - applying Core3-specific configuration..."\n\
  if [ -f "/usr/local/tomcat/core-config3/nemakiware.properties" ]; then\n\
    cp /usr/local/tomcat/core-config3/nemakiware.properties /usr/local/tomcat/conf/nemakiware.properties\n\
    cp /usr/local/tomcat/core-config3/nemakiware.properties /usr/local/tomcat/webapps/core/WEB-INF/classes/nemakiware.properties\n\
    echo "Core3 nemakiware.properties applied"\n\
  fi\n\
  if [ -f "/usr/local/tomcat/core-config3/WEB-INF/classes/ehcache.xml" ]; then\n\
    cp /usr/local/tomcat/core-config3/WEB-INF/classes/ehcache.xml /usr/local/tomcat/webapps/core/WEB-INF/classes/ehcache.xml\n\
    echo "Core3 ehcache.xml applied"\n\
  fi\n\
  if [ -f "/usr/local/tomcat/core-config3/WEB-INF/classes/businesslogicContext.xml" ]; then\n\
    cp /usr/local/tomcat/core-config3/WEB-INF/classes/businesslogicContext.xml /usr/local/tomcat/webapps/core/WEB-INF/classes/businesslogicContext.xml\n\
    echo "Core3 businesslogicContext.xml applied"\n\
  fi\n\
  echo "Core3 configuration application completed"\n\
fi\n\
\n\
echo "Waiting for CouchDB to be ready..."\n\
# Wait for CouchDB to be accessible using curl (which works)\n\
# Check appropriate CouchDB based on environment\n\
if [ -d "/usr/local/tomcat/core-config3" ]; then\n\
  COUCHDB_URL="http://couchdb3:5984"\n\
else\n\
  COUCHDB_URL="http://couchdb2:5984"\n\
fi\n\
echo "Checking CouchDB at: $COUCHDB_URL"\n\
for i in {1..60}; do\n\
  if curl -s $COUCHDB_URL > /dev/null 2>&1; then\n\
    echo "CouchDB is ready!"\n\
    break\n\
  fi\n\
  echo "Waiting for CouchDB (attempt $i/60)..."\n\
  sleep 5\n\
done\n\
\n\
# Set system properties to disable both Ektorp and EHCache thread creation BEFORE starting Tomcat\n\
export JAVA_OPTS="$JAVA_OPTS -Dorg.ektorp.support.AutoUpdateViewOnChange=false -Dorg.ektorp.http.IdleConnectionMonitor.enabled=false -Dorg.ektorp.support.DesignDocument.UPDATE_ON_DIFF=false -Dorg.ektorp.support.DesignDocument.ALLOW_AUTO_UPDATE=true -Dnet.sf.ehcache.enableShutdownHook=true -Dnet.sf.ehcache.statisticsEnabled=false -Dnet.sf.ehcache.cache.statisticsEnabled=false -Dorg.apache.catalina.loader.WebappClassLoader.ENABLE_CLEAR_REFERENCES=true -Dorg.apache.tomcat.util.scan.StandardJarScanFilter.jarsToSkip=*.jar"\n\
\n\
# Create a shutdown hook script to clean up both Ektorp and EHCache threads on Tomcat shutdown\n\
echo "#!/bin/bash" > $CATALINA_HOME/bin/cleanup-threads.sh\n\
echo "java -cp $CATALINA_HOME/lib/custom/ EktorpThreadKiller" >> $CATALINA_HOME/bin/cleanup-threads.sh\n\
echo "java -cp $CATALINA_HOME/lib/custom/ EHCacheThreadKiller" >> $CATALINA_HOME/bin/cleanup-threads.sh\n\
chmod +x $CATALINA_HOME/bin/cleanup-threads.sh\n\
\n\
# Start Tomcat in background and setup cleanup\n\
trap "$CATALINA_HOME/bin/cleanup-threads.sh; exit" SIGTERM SIGINT\n\
\n\
# Start Tomcat\n\
exec catalina.sh run\n\
' > $CATALINA_HOME/bin/startup-with-thread-killer.sh

# Make the startup script executable
RUN chmod +x $CATALINA_HOME/bin/startup-with-thread-killer.sh

# Set environment variables for Ektorp and EHCache thread management
ENV JAVA_OPTS="-Dorg.ektorp.support.AutoUpdateViewOnChange=false -Dorg.ektorp.http.IdleConnectionMonitor.enabled=false -Dorg.ektorp.support.DesignDocument.UPDATE_ON_DIFF=false -Dorg.ektorp.support.DesignDocument.ALLOW_AUTO_UPDATE=true -Dnet.sf.ehcache.enableShutdownHook=true -Dnet.sf.ehcache.statisticsEnabled=false -Dnet.sf.ehcache.cache.statisticsEnabled=false -Dorg.apache.catalina.loader.WebappClassLoader.ENABLE_CLEAR_REFERENCES=true -Dorg.apache.tomcat.util.scan.StandardJarScanFilter.jarsToSkip=*.jar"
ENV CATALINA_OPTS="-Xms512m -Xmx1024m -XX:+DisableExplicitGC -Djava.net.preferIPv4Stack=true -Djava.net.preferIPv6Addresses=false -Dhttp.proxyHost= -Dhttp.proxyPort= -Dhttps.proxyHost= -Dhttps.proxyPort= -Dhttp.nonProxyHosts=* -Djava.net.useSystemProxies=false -Dnetworkaddress.cache.ttl=60 -Dnemakiware.properties=/usr/local/tomcat/conf/nemakiware.properties -Drepositories.yml=/usr/local/tomcat/conf/repositories.yml -Dlog4j.configuration=file:/usr/local/tomcat/conf/log4j.properties"

# Expose the default Tomcat port
EXPOSE 8080

# Start directly with the startup script (lazy initialization handles CouchDB connectivity)
CMD ["/usr/local/tomcat/bin/startup-with-thread-killer.sh"]
