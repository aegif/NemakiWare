# Essential Commands for NemakiWare Development

## Environment Setup Commands

### Java 17 Environment (Always Required)
```bash
export JAVA_HOME=/Users/ishiiakinori/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
java -version  # Verify shows 17.x.x
mvn -version   # Verify shows Java 17
```

## Core Development Commands

### Standard Build and Deploy Workflow
```bash
# Full clean build and deployment
cd /Users/ishiiakinori/NemakiWare
mvn clean package -f core/pom.xml -Pdevelopment
cp core/target/core.war docker/core/core.war
cd docker && docker compose -f docker-compose-simple.yml down
docker build --no-cache -t nemakiware/core -f core/Dockerfile.simple core/
docker compose -f docker-compose-simple.yml up -d
sleep 60
```

### Quick Development Iteration
```bash
# For Java source changes only
mvn clean package -f core/pom.xml -Pdevelopment
cp core/target/core.war docker/core/core.war
docker stop docker-core-1 && docker start docker-core-1
```

## Testing Commands

### Primary QA Testing (Most Important)
```bash
cd /Users/ishiiakinori/NemakiWare
./qa-test.sh                 # Standard comprehensive testing
./qa-test.sh fast           # Quick essential tests (5-10 seconds)
./qa-test.sh full           # Full tests including performance
```

### Health Check Commands
```bash
# Core application health
curl -u admin:admin http://localhost:8080/core/atom/bedroom

# Repository list
curl -u admin:admin http://localhost:8080/core/rest/all/repositories

# Database health
curl -u admin:password http://localhost:5984/_all_dbs

# UI accessibility
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/core/ui/
```

## UI Development Commands

### React UI Development
```bash
# Navigate to UI directory
cd /Users/ishiiakinori/NemakiWare/core/src/main/webapp/ui

# Development server (hot reload on port 5173)
npm run dev

# Production build
npm run build

# Type checking
npm run type-check

# Full UI integration workflow
npm run build && cd /Users/ishiiakinori/NemakiWare && mvn clean package -f core/pom.xml -Pdevelopment
```

## Docker Management Commands

### Container Operations
```bash
# Check container status
docker ps
docker logs docker-core-1 --tail 20

# Restart containers
docker compose -f docker/docker-compose-simple.yml restart core
docker compose -f docker/docker-compose-simple.yml restart couchdb
docker compose -f docker/docker-compose-simple.yml restart solr

# Complete environment reset
cd docker
docker compose -f docker-compose-simple.yml down
docker compose -f docker-compose-simple.yml up -d
```

### Container Health Monitoring
```bash
# Resource usage
docker stats --no-stream

# Service health
docker compose -f docker/docker-compose-simple.yml ps
```

## Debugging and Troubleshooting Commands

### Log Investigation
```bash
# Core application logs
docker logs docker-core-1 --tail 50

# CouchDB logs
docker logs docker-couchdb-1 --tail 20

# Solr logs
docker logs docker-solr-1 --tail 20
```

### Network and Port Verification
```bash
# Check port usage (macOS)
lsof -i :8080  # Core application
lsof -i :5984  # CouchDB
lsof -i :8983  # Solr
lsof -i :5173  # UI development server
```

### Database Investigation
```bash
# CouchDB database list
curl -u admin:password http://localhost:5984/_all_dbs

# Repository document count
curl -u admin:password http://localhost:5984/bedroom | jq '{db_name, doc_count}'

# Design document verification
curl -u admin:password http://localhost:5984/bedroom/_design/_repo | jq '.views | keys'
```

## Alternative Development Commands

### Jetty Development Server (Alternative to Docker)
```bash
# Start Jetty for development (separate terminal)
cd core && mvn jetty:run -Djetty.port=8081

# Access at: http://localhost:8081/core
```

### Maven Specific Commands
```bash
# Clean compile only
mvn clean compile -f core/pom.xml

# Run specific test (when tests are enabled)
mvn test -Dtest=SpecificTestClass -f core/pom.xml

# Dependency tree analysis
mvn dependency:tree -f core/pom.xml
```

## Git and Version Control Commands

### Standard Git Workflow
```bash
# Check current status
git status
git branch

# Stage and commit changes
git add .
git commit -m "feat: Description of changes"

# Check recent commits
git log --oneline -10
```

## System Utilities (Darwin/macOS Specific)

### File System Commands
```bash
# Find files by pattern
find . -name "*.java" -type f
find . -name "*.tsx" -type f

# Search in files (use ripgrep if available)
rg "pattern" --type java
grep -r "pattern" core/src/main/java/

# Directory listing with details
ls -la
ls -la core/target/  # Check build artifacts
```

### Process Management
```bash
# Kill processes on specific ports
sudo lsof -ti:8080 | xargs kill -9
sudo lsof -ti:5984 | xargs kill -9

# Check Java processes
jps -v  # If available
ps aux | grep java
```

## Emergency Recovery Commands

### Complete Environment Reset
```bash
# Nuclear option - complete reset
cd /Users/ishiiakinori/NemakiWare
docker compose -f docker/docker-compose-simple.yml down
docker system prune -f
mvn clean -f core/pom.xml
mvn clean package -f core/pom.xml -Pdevelopment
cp core/target/core.war docker/core/core.war
cd docker && docker compose -f docker-compose-simple.yml up -d --build
```

### Quick Verification After Problems
```bash
# Verify everything is working
./qa-test.sh fast
curl -u admin:admin http://localhost:8080/core/atom/bedroom
docker ps
```