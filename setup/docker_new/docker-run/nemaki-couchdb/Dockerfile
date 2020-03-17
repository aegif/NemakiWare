FROM couchdb

COPY ./local.ini /opt/couchdb/etc/

# startup scripts
ADD scripts /scripts

RUN touch /.firstrun