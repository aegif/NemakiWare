FROM ubuntu

RUN apt-get update
RUN apt-get install curl -y
RUN apt-get update && apt-get install -y gnupg2
RUN curl -L https://couchdb.apache.org/repo/bintray-pubkey.asc | apt-key add -
RUN apt-get update #&& apt-get install couchdb -y

RUN apt-get --no-install-recommends -y install \
    build-essential pkg-config erlang \
    libicu-dev libcurl4-openssl-dev \
	make maven openjdk-8-jdk -y

RUN update-java-alternatives --set /usr/lib/jvm/java-1.8.0-openjdk-amd64

# Transfer files
#couchdb installation files
ADD install-couchdb /usr/local/install-couchdb
#startup scripts
ADD scripts /scripts
#couchdb init java project
COPY ./couchdb /usr/local
#tomcat server
COPY 		./tomcat /usr/local/tomcat

# Install couchdb
RUN apt-get --no-install-recommends -y install libnspr4-dev libffi-dev
RUN dpkg -i /usr/local/install-couchdb/libmozjs185-1.0_1.8.5-1.0.0+dfsg-7_amd64.deb
RUN dpkg -i /usr/local/install-couchdb/libmozjs185-dev_1.8.5-1.0.0+dfsg-7_amd64.deb
RUN tar -xvzf /usr/local/install-couchdb/apache-couchdb-2.1.1.tar.gz -C /usr/local/install-couchdb/
RUN cd /usr/local/install-couchdb/apache-couchdb-2.1.1 && ./configure && make release 

RUN chmod +x /scripts/*.sh

RUN touch /.firstrun

RUN adduser --system \
        --shell /bin/bash \
        --group --gecos \
        "CouchDB Administrator" couchdb

RUN cp -R /usr/local/install-couchdb/apache-couchdb-2.1.1/rel/couchdb/ /home && chown -R couchdb:couchdb /home/couchdb && find /home/couchdb -type d -exec chmod 0770 {} \; 

RUN chmod 0644 /home/couchdb/etc/*

RUN sed -e 's/^bind_address = .*$/bind_address = 0.0.0.0/' -i /home/couchdb/etc/default.ini && \
    sed -e 's/^database_dir = .*$/database_dir = \/data/' -i /home/couchdb/etc/default.ini && \
    sed -e 's/^view_index_dir = .*$/view_index_dir = \/data/' -i /home/couchdb/etc/default.ini 


# Command to run
ENTRYPOINT ["/scripts/run.sh"]
CMD [""]

EXPOSE 5984

# Expose our data, logs and configuration volumes
VOLUME ["/data", "c:\couchdb_vol", "/usr/local/etc/couchdb"]
