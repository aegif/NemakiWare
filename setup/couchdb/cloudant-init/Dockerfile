FROM maven:3.8-openjdk-8 AS builder

WORKDIR /app
COPY . /app/
RUN mvn clean package

FROM openjdk:8-jre-slim

WORKDIR /app
COPY --from=builder /app/target/cloudant-init.jar /app/cloudant-init.jar

ENTRYPOINT ["java", "-cp", "/app/cloudant-init.jar", "jp.aegif.nemaki.cloudantinit.CouchDBInitializer"]
