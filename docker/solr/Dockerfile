# 9.10.1 mitigates CVE-2025-66516 (PDF/XFA XXE via /update/extract, which
# NemakiWare enables and posts uploaded documents to) — keep this in lockstep
# with the Maven solr-core 9.10.1 pin. This Dockerfile is the source for both
# the dev docker-compose-simple build and the published nemakiware-solr image.
FROM solr:9.10.1-slim

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
