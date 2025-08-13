# Development Workflow and Commands

## Essential Environment Setup

### Java 17 Environment (MANDATORY)
```bash
export JAVA_HOME=/Users/ishiiakinori/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH

# Verify version
java -version  # Must be 17.x.x
mvn -version   # Must show Java 17
```

## Standard Build Process

### 1. Complete Clean Build (Required for All Development)
```bash
# Step 1: Set Java 17 environment (MANDATORY)
export JAVA_HOME=/Users/ishiiakinori/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH

# Step 2: Navigate to project root
cd /Users/ishiiakinori/NemakiWare

# Step 3: Clean build with Java 17
mvn clean package -f core/pom.xml -Pdevelopment

# Step 4: Copy WAR file to Docker context
cp core/target/core.war docker/core/core.war

# Step 5: Stop and rebuild Docker environment with no cache
cd docker
docker compose -f docker-compose-simple.yml down
docker build --no-cache -t nemakiware/core -f core/Dockerfile.simple core/

# Step 6: Start clean environment
docker compose -f docker-compose-simple.yml up -d

# Step 7: Wait for complete initialization
sleep 60
```

### 2. Source Code Modification Workflow
```bash
# 1. Modify Java source in core/src/main/java/
# 2. Rebuild and redeploy
mvn clean package -f core/pom.xml -Pdevelopment
cp core/target/core.war docker/core/core.war

# 3. Restart container to reflect changes
docker stop docker-core-1
docker build --no-cache -t docker-core docker/core/
docker start docker-core-1

# 4. Verify changes
curl -u admin:admin http://localhost:8080/core/atom/bedroom
```

## React UI Development

### UI Development Workflow
```bash
# 1. Navigate to UI directory
cd /Users/ishiiakinori/NemakiWare/core/src/main/webapp/ui

# 2. Install dependencies (first time)
npm install

# 3. Development server with hot reload
npm run dev  # Runs on port 5173 with proxy to backend

# 4. Production build for integration
npm run build

# 5. Type checking
npm run type-check
```

### UI + Core Integration Workflow
```bash
# 1. Build UI with changes
cd core/src/main/webapp/ui && npm run build

# 2. Rebuild complete WAR file (includes UI)
cd /Users/ishiiakinori/NemakiWare
mvn clean package -f core/pom.xml -Pdevelopment

# 3. Deploy new WAR to Docker
cp core/target/core.war docker/core/core.war
cd docker && docker compose -f docker-compose-simple.yml down
docker compose -f docker-compose-simple.yml up -d --build

# 4. Verify deployment
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/core/ui/
```

## Testing and Verification

### Primary QA Testing
```bash
# Navigate to project root and run comprehensive tests
cd /Users/ishiiakinori/NemakiWare
./qa-test.sh

# Test modes available:
./qa-test.sh fast    # Essential tests only (5-10 seconds)
./qa-test.sh core    # CMIS and database tests (15-20 seconds)
./qa-test.sh qa      # Standard comprehensive testing (default)
./qa-test.sh full    # All tests including performance (30-40 seconds)
```

### Health Check Commands
```bash
# Quick verification commands
docker ps  # All containers should be running
curl -u admin:admin http://localhost:8080/core/atom/bedroom  # Should return HTTP 200
curl -u admin:admin http://localhost:8080/core/rest/all/repositories  # Should return repository list

# CouchDB verification
curl -u admin:password http://localhost:5984/_all_dbs
# Expected: ["bedroom","bedroom_closet","canopy","canopy_closet","nemaki_conf"]

# UI access verification
curl http://localhost:8080/core/ui/dist/  # Should return React UI login page
```

## Development Tools and Commands

### Jetty Development Server (Alternative to Docker)
```bash
# Manual Jetty development server (separate terminal)
cd core && mvn jetty:run -Djetty.port=8081

# Access at: http://localhost:8081/core
```

### Docker Container Management
```bash
# Check container status
docker ps
docker logs docker-core-1 --tail 20

# Restart unhealthy containers
docker compose -f docker/docker-compose-simple.yml restart core
sleep 30

# Complete environment restart
cd docker
docker compose -f docker-compose-simple.yml down
docker compose -f docker-compose-simple.yml up -d
```

### Maven Build Profiles
- **development** (default): Standard development build with tests disabled via @Ignore
- **product**: Production build configuration