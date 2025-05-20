FROM openjdk:8-jre-slim

RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY cloudant-init.jar /app/cloudant-init.jar
COPY data /app/data
COPY entrypoint.sh /app/entrypoint.sh

RUN chmod +x /app/entrypoint.sh

# Remove healthcheck as this is a one-time initialization container
# that doesn't expose any HTTP endpoints

ENTRYPOINT ["/app/entrypoint.sh"]

# Default arguments that will be overridden by docker-compose command
CMD ["http://localhost:5984", "", "", "bedroom", "/app/data/bedroom_init.dump", "true"]
