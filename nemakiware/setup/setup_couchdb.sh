#!/bin/sh

#Setting
COUCHDB_PYTHON="./CouchDB-0.8"
DUMP_DIR="./CouchDB_dump"
COUCHDB_HOST="http://127.0.0.1:5984"
DB_REPOSITORY="bedroom"
DB_ARCHIVE="archive"
DEFAULT_DUMP_NAME_REPOSITORY="bedroom_init.dump"
DEFAULT_DUMP_NAME_ARCHIVE="archive_init.dump"

#gem install rest-client

#Create CouchDB databases
ruby create_db.rb ${COUCHDB_HOST}/${DB_REPOSITORY}
ruby create_db.rb ${COUCHDB_HOST}/${DB_ARCHIVE}

#Load dump files
#sudo python ./CouchDB-0.8/setup.py install
if [ -n argv[0] ]; then
	DUMP_NAME_REPOSITORY=${DEFAULT_DUMP_NAME_REPOSITORY}
else
	DUMP_NAME_REPOSITORY=argv[0]
fi
echo "Importing ${DUMP_NAME_REPOSITORY}"
python ${COUCHDB_PYTHON}/couchdb/tools/load.py ${COUCHDB_HOST}/${DB_REPOSITORY} < ${DUMP_DIR}/${DUMP_NAME_REPOSITORY}
if [ -n argv[1] ]; then
	DUMP_NAME_ARCHIVE=${DEFAULT_DUMP_NAME_ARCHIVE}
else
	DUMP_NAME_ARCHIVE=argv[1]
fi
echo "Importing ${DUMP_NAME_ARCHIVE}"
python ${COUCHDB_PYTHON}/couchdb/tools/load.py ${COUCHDB_HOST}/${DB_ARCHIVE} < ${DUMP_DIR}/${DUMP_NAME_ARCHIVE}