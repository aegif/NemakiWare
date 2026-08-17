# Solr 10.0.0 (Lucene 10.3.2). Carries forward the CVE-2025-66516 fix
# (PDF/XFA XXE via /update/extract) and ships Jetty 12 / refreshed ZooKeeper /
# Netty / Jackson vs the 9.10.1-slim base's older bundled libraries. Keep in
# lockstep with the Maven solr-solrj 10.0.0 / lucene 10.3.2 pins. This
# Dockerfile is the source for both the dev docker-compose-simple build and the
# published nemakiware-solr image.
FROM solr:10.0.0-slim

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

# Use Solr's official standalone (user-managed) server.
# Solr 10 changed the default startup mode to SolrCloud (the docker script
# hardcodes SOLR_MODE=solrcloud and ignores the env var), which fails with
# "no cloud config provided" when no ZooKeeper is configured. NemakiWare runs a
# single standalone node with cores discovered via core.properties, so pass
# --user-managed to start in the classic standalone mode.
CMD ["solr-foreground", "--user-managed"]
