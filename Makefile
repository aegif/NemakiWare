# NemakiWare Makefile
# Provides convenient targets for building, testing, and managing the development environment

.PHONY: help
help:
	@echo "NemakiWare Development Makefile"
	@echo ""
	@echo "Available targets:"
	@echo "  make build              - Build WAR file (mvn clean package -DskipTests)"
	@echo "  make docker-build       - Build Docker images"
	@echo "  make reset-test-env     - Reset test environment (clean build + volume wipe + restart)"
	@echo "  make test-e2e           - Reset environment and run E2E tests"
	@echo "  make test-e2e-quick     - Run E2E tests without environment reset"
	@echo "  make validate-env       - Validate test environment is ready"
	@echo "  make docker-up          - Start Docker containers"
	@echo "  make docker-down        - Stop Docker containers"
	@echo "  make docker-down-v      - Stop Docker containers and wipe volumes"
	@echo "  make docker-logs        - Show Docker container logs"
	@echo ""

.PHONY: build
build:
	@echo "Building WAR file..."
	mvn clean package -DskipTests

.PHONY: docker-build
docker-build:
	@echo "Building Docker images..."
	cd docker && docker-compose -f docker-compose-simple.yml build

.PHONY: docker-up
docker-up:
	@echo "Starting Docker containers..."
	cd docker && docker-compose -f docker-compose-simple.yml up -d

.PHONY: docker-down
docker-down:
	@echo "Stopping Docker containers..."
	cd docker && docker-compose -f docker-compose-simple.yml down

.PHONY: docker-down-v
docker-down-v:
	@echo "Stopping Docker containers and wiping volumes..."
	cd docker && docker-compose -f docker-compose-simple.yml down -v

.PHONY: docker-logs
docker-logs:
	@echo "Showing Docker container logs..."
	cd docker && docker-compose -f docker-compose-simple.yml logs -f

.PHONY: reset-test-env
reset-test-env:
	@echo "=========================================="
	@echo "Resetting test environment..."
	@echo "=========================================="
	@echo ""
	@echo "Step 1/5: Building WAR file..."
	mvn clean package -DskipTests
	@echo ""
	@echo "Step 2/5: Building Docker images..."
	cd docker && docker-compose -f docker-compose-simple.yml build core
	@echo ""
	@echo "Step 3/5: Stopping containers and wiping volumes..."
	cd docker && docker-compose -f docker-compose-simple.yml down -v
	@echo ""
	@echo "Step 4/5: Starting containers with healthchecks..."
	cd docker && docker-compose -f docker-compose-simple.yml up -d
	@echo ""
	@echo "Step 5/5: Waiting for containers to be healthy..."
	@echo "Waiting for CouchDB..."
	@timeout 60 bash -c 'until docker exec docker-couchdb-1 curl -s http://localhost:5984/_up 2>/dev/null | grep -q "ok"; do sleep 2; done' || (echo "ERROR: CouchDB failed to start" && exit 1)
	@echo "✓ CouchDB is ready"
	@echo "Waiting for Solr..."
	@timeout 60 bash -c 'until docker exec docker-solr-1 curl -s http://localhost:8983/solr/admin/cores?action=STATUS 2>/dev/null | grep -q "status"; do sleep 2; done' || (echo "ERROR: Solr failed to start" && exit 1)
	@echo "✓ Solr is ready"
	@echo "Waiting for Core..."
	@timeout 120 bash -c 'until curl -s http://localhost:8080/core/browser/bedroom/root 2>/dev/null | grep -q "cmis:objectId"; do sleep 3; done' || (echo "ERROR: Core failed to start" && exit 1)
	@echo "✓ Core is ready"
	@echo ""
	@echo "=========================================="
	@echo "✓ Test environment ready!"
	@echo "=========================================="

.PHONY: validate-env
validate-env:
	@echo "Validating test environment..."
	@echo ""
	@echo "Checking initial content setup..."
	@cd core/src/main/webapp/ui && PLAYWRIGHT_SKIP_VALIDATE_HOST_REQUIREMENTS=1 npx playwright test tests/admin/initial-content-setup.spec.ts --project=chromium --reporter=list
	@echo ""
	@echo "✓ Environment validation complete"

.PHONY: test-e2e-quick
test-e2e-quick:
	@echo "Running E2E tests (without environment reset)..."
	cd core/src/main/webapp/ui && PLAYWRIGHT_SKIP_VALIDATE_HOST_REQUIREMENTS=1 npx playwright test

.PHONY: test-e2e
test-e2e: reset-test-env validate-env
	@echo ""
	@echo "=========================================="
	@echo "Running full E2E test suite..."
	@echo "=========================================="
	cd core/src/main/webapp/ui && PLAYWRIGHT_SKIP_VALIDATE_HOST_REQUIREMENTS=1 npx playwright test
