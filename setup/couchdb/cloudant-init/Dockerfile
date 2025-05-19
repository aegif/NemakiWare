FROM openjdk:8-jre-slim

WORKDIR /app
COPY target/cloudant-init.jar /app/cloudant-init.jar

ENTRYPOINT ["java", "-cp", "/app/cloudant-init.jar", "jp.aegif.nemaki.cloudantinit.CouchDBInitializer"]
