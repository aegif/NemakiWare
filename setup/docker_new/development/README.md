# NemakiWare Development On Docker

Please follow the following steps to properly set up your docker environment for development:

1. Unzip and untar `db/data.tar.gz`
   * ensure that the `db/data` folder is not empty
2. Start the docker environment with by issuying `docker-compose up` in this folder
3. Enter the terminal for `nemakidev` and build NemakiWare by 
   * Running `mvn install` in the common and action folders
   * Editing the `<base nemakiware directory>/core/core/src/main/webapp/WEB-INF/classes/nemakiware.properties` and change `db.couchdb.url` to equal `db.couchdb.url=http://admin:password@nemakidev-couchdb:5984`
4. Start the core debug: run `mvn jetty:run` in the core folder
5. Start the solr debug: run `mvn jetty:run` in the solr folder
6. Start the ui debug: run `activator run` in the ui folder
