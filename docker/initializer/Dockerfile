FROM openjdk:8-jre-alpine

# Install curl for health checks
RUN apk add --no-cache curl

WORKDIR /app

COPY cloudant-init.jar /app/
COPY bjornloka.jar /app/
COPY initial_import /app/initial_import

# Also copy dump files to root for backward compatibility
COPY initial_import/bedroom_init.dump /app/
COPY initial_import/archive_init.dump /app/
COPY initial_import/canopy_init.dump /app/

COPY entrypoint.sh /app/
COPY setup-design-documents.sh /app/
RUN chmod +x /app/entrypoint.sh /app/setup-design-documents.sh

ENTRYPOINT ["/app/entrypoint.sh"]
