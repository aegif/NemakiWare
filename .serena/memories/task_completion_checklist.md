# Task Completion Checklist

## Essential Steps After Code Changes

### 1. Build Verification
```bash
# Set Java 17 environment (MANDATORY)
export JAVA_HOME=/Users/ishiiakinori/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH

# Clean build to ensure all changes compile
mvn clean package -f core/pom.xml -Pdevelopment

# Verify WAR file was created successfully
ls -la core/target/core.war
```

### 2. Docker Deployment and Testing
```bash
# Copy WAR to Docker context
cp core/target/core.war docker/core/core.war

# Rebuild and restart Docker environment
cd docker
docker compose -f docker-compose-simple.yml down
docker build --no-cache -t nemakiware/core -f core/Dockerfile.simple core/
docker compose -f docker-compose-simple.yml up -d

# Wait for initialization
sleep 60
```

### 3. Comprehensive QA Testing (MANDATORY)
```bash
# Navigate to project root
cd /Users/ishiiakinori/NemakiWare

# Run comprehensive test suite
./qa-test.sh

# Expected result: All tests should pass (currently 46/46 = 100% success rate)
# If any tests fail, investigate and fix before considering task complete
```

### 4. Health Check Verification
```bash
# Core CMIS endpoints
curl -u admin:admin http://localhost:8080/core/atom/bedroom
# Expected: HTTP 200 with XML repository info

# Repository listing
curl -u admin:admin http://localhost:8080/core/rest/all/repositories
# Expected: HTTP 200 with JSON repository list

# Database connectivity
curl -u admin:password http://localhost:5984/_all_dbs
# Expected: ["bedroom","bedroom_closet","canopy","canopy_closet","nemaki_conf"]

# UI accessibility (if UI changes were made)
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/core/ui/
# Expected: HTTP 200
```

## Special Considerations for UI Changes

### UI-Specific Task Completion
```bash
# 1. Build UI with changes
cd core/src/main/webapp/ui && npm run build

# 2. Run type checking
npm run type-check

# 3. Rebuild WAR with UI assets
cd /Users/ishiiakinori/NemakiWare
mvn clean package -f core/pom.xml -Pdevelopment

# 4. Deploy and verify UI accessibility
cp core/target/core.war docker/core/core.war
cd docker && docker compose -f docker-compose-simple.yml down
docker compose -f docker-compose-simple.yml up -d --build

# 5. Test UI functionality
# - Login screen loads correctly
# - Authentication works
# - Document list displays
# - No JavaScript errors in browser console
```

## Linting and Code Quality

### Current Status
- **Java Linting**: Built into Maven compilation process
- **TypeScript Linting**: Built into Vite build process
- **No separate linting commands**: Quality checks integrated into build

### Code Quality Verification
```bash
# Maven compilation includes style checks
mvn clean compile -f core/pom.xml

# TypeScript type checking
cd core/src/main/webapp/ui && npm run type-check
```

## Performance and System Verification

### Container Health Check
```bash
# Check all containers are running
docker ps
# Expected: All containers (core, couchdb, solr) in "healthy" or "running" state

# Check logs for errors
docker logs docker-core-1 --tail 20
# Should not show ERROR level messages (INFO/DEBUG acceptable)
```

### Performance Baseline
```bash
# Run performance tests if available
./qa-test.sh full

# Monitor resource usage during tests
docker stats --no-stream
```

## Documentation Updates

### When Documentation Updates Are Required
- New features or endpoints added
- Configuration changes
- New environment variables or settings
- Changes to build process or dependencies

### Documentation Files to Consider
- `CLAUDE.md` - Primary development documentation
- `README.md` - User-facing documentation
- Inline code comments for complex logic

## Version Control Best Practices

### Before Committing
```bash
# Verify git status
git status

# Stage appropriate files
git add <modified-files>

# Commit with descriptive message
git commit -m "feat: Add [feature description]" or "fix: Resolve [issue description]"
```

### Post-Commit Verification
```bash
# Ensure clean working directory
git status
# Expected: "working tree clean"

# Verify branch status
git branch
# Should show current feature branch
```

## Critical Success Criteria

### ✅ Task is Complete When:
1. All code compiles without errors (`mvn clean package` succeeds)
2. Docker environment starts successfully
3. QA test suite passes 100% (46/46 tests)
4. All health check commands return expected results
5. No ERROR level logs in container output
6. UI loads and functions correctly (if UI changes made)
7. Git working directory is clean

### ❌ Task is NOT Complete If:
- Any compilation errors exist
- QA tests fail or show reduced success rate
- Health check commands return HTTP 4xx/5xx errors
- Container logs show ERROR level messages
- UI shows JavaScript errors or fails to load
- Docker containers fail to start or become unhealthy