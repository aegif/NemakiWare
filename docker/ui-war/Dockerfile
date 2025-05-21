FROM tomcat:9-jre8

# Install unzip
RUN apt-get update && apt-get install -y unzip curl && rm -rf /var/lib/apt/lists/*

# Remove default Tomcat applications
RUN rm -rf /usr/local/tomcat/webapps/*

# Copy the UI WAR file as ROOT.war to deploy at root context
COPY ui.war /usr/local/tomcat/webapps/ROOT.war

# Copy configuration files
COPY nemakiware_ui.properties /usr/local/tomcat/conf/nemakiware_ui.properties
COPY log4j.properties /usr/local/tomcat/conf/log4j.properties

# Set environment variables for Play Framework
# Remove the context path settings to match the ROOT deployment
ENV JAVA_OPTS="-Dorg.apache.catalina.loader.WebappClassLoader.ENABLE_CLEAR_REFERENCES=true -Dorg.apache.tomcat.util.scan.StandardJarScanFilter.jarsToSkip=*.jar"
ENV CATALINA_OPTS="-Xms512m -Xmx1024m -Dlog4j.configuration=file:/usr/local/tomcat/conf/log4j.properties -Dnemakiware_ui.properties=/usr/local/tomcat/conf/nemakiware_ui.properties -Dplay.http.context=/ -Dapplication.context=/"

# Expose the default Tomcat port
EXPOSE 8080

# Start Tomcat
CMD ["catalina.sh", "run"]
