FROM tomcat:9-jre8

# Install unzip and curl for diagnostics
RUN apt-get update && apt-get install -y unzip curl && rm -rf /var/lib/apt/lists/*

# Remove default Tomcat applications
RUN rm -rf /usr/local/tomcat/webapps/*

# Copy the UI WAR file with ## notation to create /ui context path
COPY ui.war /usr/local/tomcat/webapps/ui##.war

# Copy configuration files
COPY nemakiware_ui.properties /usr/local/tomcat/conf/nemakiware_ui.properties
COPY log4j.properties /usr/local/tomcat/conf/log4j.properties

# Create Catalina/localhost directory if it doesn't exist
RUN mkdir -p /usr/local/tomcat/conf/Catalina/localhost

# Copy context XML files
COPY ROOT.xml /usr/local/tomcat/conf/Catalina/localhost/
COPY ui.xml /usr/local/tomcat/conf/Catalina/localhost/

# Set environment variables for Play Framework
ENV JAVA_OPTS="-Dorg.apache.catalina.loader.WebappClassLoader.ENABLE_CLEAR_REFERENCES=true -Dorg.apache.tomcat.util.scan.StandardJarScanFilter.jarsToSkip=*.jar -Dplay.http.context=/ui/ -Dapplication.context=/ui/"
ENV CATALINA_OPTS="-Xms512m -Xmx1024m -Dlog4j.configuration=file:/usr/local/tomcat/conf/log4j.properties -Dnemakiware_ui.properties=/usr/local/tomcat/conf/nemakiware_ui.properties"

# Expose the default Tomcat port
EXPOSE 8080

# Start Tomcat
CMD ["catalina.sh", "run"]
