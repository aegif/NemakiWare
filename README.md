NemakiWare
======================

What is NemakiWare?
------

NemakiWare is an open source Enterprise Content Management system.

* **CMIS ver1.1 compliant and even CMIS-native server**
    * Easy Integration or replacement with other existing/future CMIS-compliant client.
    * Extended feature out of CMIS specification: user/group, archive etc.
    * Highly customizable in CMIS specification because of CMIS-native development.


* **All-in-One package (server, full-text search engine and client)**
    * Ready to use in the real context.
    * All components are connected via CMIS interface.


* **NoSQL CouchDB backend**
    * Simple document-based NoSQL database.
    * Easy Database management and replication.


Etymology
------
"Nemaki" derives from a Japanese word "寝巻き" which means night clothes.
You can relax and enjoy happy enterprise time as if you are lying on the couch in your room!


## Jakarta EE 10 Development Environment

NemakiWare supports modern Jakarta EE 10 development with Maven Jetty plugin for streamlined debugging and development.

### Quick Start

**Prerequisites:**
- Java 17
- Maven 3.6+
- Docker (for CouchDB only)

**Setup:**

1. **Start CouchDB (Docker)**
   ```bash
   docker run -d --name couchdb-dev -p 5984:5984 \
     -e COUCHDB_USER=admin -e COUCHDB_PASSWORD=password \
     couchdb:3
   ```

2. **Configure Java 17 Module System**
   ```bash
   export MAVEN_OPTS="--add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED --add-opens=java.base/java.text=ALL-UNNAMED --add-opens=java.desktop/java.awt.font=ALL-UNNAMED"
   ```

3. **Start Development Server**
   ```bash
   cd core
   mvn jetty:run -Pjakarta -Djetty.port=8081
   ```

**Access Points:**
- CMIS Service: `http://localhost:8081/core/atom/bedroom` (admin:admin)
- Repository Info: `http://localhost:8081/core/atom/bedroom`
- Folder Operations: `http://localhost:8081/core/atom/bedroom/children?id=e02f784f8360a02cc14d1314c10038ff`

### Development Features

- **Jakarta EE 10 Compatible**: Full support for modern Jakarta namespace
- **Simplified Dependencies**: CouchDB-only Docker requirement
- **Debug-Friendly**: Direct Maven execution with Jetty
- **Fast Iteration**: Automatic code reloading
- **Reduced Packaging Complexity**: Eliminates WAR deployment issues

### Technical Details

- **Runtime**: Jetty 11 with Jakarta EE 10
- **Database**: CouchDB 3.x via Cloudant SDK
- **Authentication**: Jakarta Servlet API compatible
- **Search**: MockSolrUtil (disabled for development)
- **Spring**: Version 6 with Jakarta EE support

For complete setup instructions, see `JAKARTA-EE-QUICKSTART.md`.


License
----------
Copyright (c) 2013-2018 aegif.

NemakiWare is Open Source software and licensed under the `GNU Affero General Public License version 3`. You are welcome to change and redistribute it under certain conditions. For more information see the `legal/LICENSE` file.

[![Bitdeli Badge](https://d2weczhvl823v0.cloudfront.net/NemakiWare/NemakiWare/trend.png)](https://bitdeli.com/free "Bitdeli Badge")
