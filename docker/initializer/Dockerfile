FROM openjdk:8-jre-slim

RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY cloudant-init.jar /app/cloudant-init.jar
COPY bedroom_init.dump /app/bedroom_init.dump
COPY entrypoint.sh /app/entrypoint.sh

RUN chmod +x /app/entrypoint.sh

# Use environment variables instead of command-line arguments
ENV COUCHDB_URL="http://localhost:5984" \
    COUCHDB_USERNAME="" \
    COUCHDB_PASSWORD="" \
    REPOSITORY_ID="bedroom" \
    DUMP_FILE="/app/bedroom_init.dump" \
    FORCE="true"

ENTRYPOINT ["/app/entrypoint.sh"]
