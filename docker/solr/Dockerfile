FROM solr:9.8.0-slim

USER root

# Copy Solr configuration
COPY solr /var/solr/data

# Fix permissions for Solr data directories
RUN chown -R solr:solr /var/solr/data && \
    mkdir -p /var/solr/data/nemaki/data && \
    mkdir -p /var/solr/data/token/data && \
    chown -R solr:solr /var/solr/data/nemaki && \
    chown -R solr:solr /var/solr/data/token

USER solr

# Set environment variables
ENV SOLR_HOME=/var/solr/data

EXPOSE 8983

# Use Solr's official standalone server
CMD ["solr-foreground"]
