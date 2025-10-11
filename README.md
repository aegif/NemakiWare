# NemakiWare

NemakiWare is an open source Enterprise Content Management system, compliant with CMIS ver1.1.

## Features
- **All-in-one package** including server, full-text search engine, and modern React client
- **Easy installation** with automated installer
- **Jakarta EE compatible** with Java 17 support
- **Modern React SPA client interface** (replaces legacy Play Framework UI)
- **SAML and OIDC authentication** support
- **Full CMIS 1.1 compliance**
- **Docker containerization** support

## Key Capabilities
* **CMIS ver1.1 compliant and even CMIS-native server**
    * Easy Integration or replacement with other existing/future CMIS-compliant client.
    * Extended feature out of CMIS specification: user/group, archive etc.
    * Highly customizable in CMIS specification because of CMIS-native development.

* **Enhanced Full-Text Search**: Apache Solr with ExtractingRequestHandler (Solr Cell)
    * PDF documents: Complete text extraction with metadata
    * Microsoft Office: Word (.docx/.doc), Excel (.xlsx/.xls), PowerPoint (.pptx/.ppt)
    * OpenDocument: Writer (.odt), Calc (.ods), Impress (.odp)
    * Web formats: HTML, XML, RTF, plain text
    * **Quick Verification**: `./quick-verify-extracting.sh`
    * **Full Verification**: `./verify-extracting-handler.sh`

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
   ./start-jetty-dev.sh
   ```
   
   Or manually:
   ```bash
   export MAVEN_OPTS="--add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED --add-opens=java.base/java.text=ALL-UNNAMED --add-opens=java.desktop/java.awt.font=ALL-UNNAMED"
   mvn jetty:run -Pjakarta -Djetty.port=8080
   ```

**Access Points:**
- CMIS Service: `http://localhost:8080/core/atom/bedroom` (admin:admin)
- Repository Info: `http://localhost:8080/core/atom/bedroom`
- Folder Operations: `http://localhost:8080/core/atom/bedroom/children?id=e02f784f8360a02cc14d1314c10038ff`

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

## Testing

### TCK (Technology Compatibility Kit) Testing

NemakiWare includes CMIS 1.1 TCK tests to verify full specification compliance.

**Quick TCK Test Execution:**
```bash
# Run all TCK tests with automatic database cleanup
./tck-test-clean.sh

# Run specific test group (e.g., QueryTestGroup)
./tck-test-clean.sh QueryTestGroup

# Run specific test method
./tck-test-clean.sh QueryTestGroup#queryLikeTest
```

**What the test script provides:**
- Automatic database cleanup before testing (prevents test data accumulation)
- Docker container health verification
- Comprehensive test execution with 90-minute timeout protection
- Detailed performance and success rate reporting

**Important Notes:**
- Always use the cleanup script for reliable test results
- Test data accumulation can cause false failures (especially querySmokeTest)
- Expected execution time: 5-40 minutes depending on test scope
- Requires 3GB Java heap (configured in docker-compose-simple.yml)

For detailed testing procedures and troubleshooting, see `CLAUDE.md` section "TCK Test Execution (Standard Procedure)".

License
----------
Copyright (c) 2013-2018 aegif.

NemakiWare is Open Source software and licensed under the `GNU Affero General Public License version 3`. You are welcome to change and redistribute it under certain conditions. For more information see the `legal/LICENSE` file.

[![Bitdeli Badge](https://d2weczhvl823v0.cloudfront.net/NemakiWare/NemakiWare/trend.png)](https://bitdeli.com/free "Bitdeli Badge")
