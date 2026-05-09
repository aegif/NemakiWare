# NemakiWare Build & Test Targets
#
# See docs/TESTING-ENVIRONMENTS.md for the testing strategy.
# Quick reference:
#   make ui            # build UI only
#   make war           # build WAR (includes UI)
#   make deploy        # build + deploy to Docker
#   make qa            # run QA integration tests (94 tests, ~2min)
#   make tck-clean     # clean DB and run TCK (38 tests, ~37min)
#   make e2e           # run full Playwright E2E (~1.7h)
#   make e2e-quick     # run only ingest/cloud E2E (~1min)
#   make unit          # run JVM unit tests
#   make guards        # run CI guard scripts (CSRF + Dockerfile parity)
#   make verify        # quick: unit + guards + qa
#   make verify-full   # verify + tck-clean + e2e

.PHONY: ui war deploy qa tck-clean tck e2e e2e-quick unit guards verify verify-full clean health

UI_DIR := core/src/main/webapp/ui
DOCKER_DIR := docker
COMPOSE := docker compose -f $(DOCKER_DIR)/docker-compose-simple.yml

# Compose requires CouchDB credentials (RC13+). The Makefile defaults
# them to admin/password for local development convenience; production
# deployments must NOT use these — see docs/AWS-DEPLOYMENT-GUIDE.md.
# Override on the command line: `make deploy COUCHDB_PASSWORD=secret`.
export COUCHDB_USER ?= admin
export COUCHDB_PASSWORD ?= password

# ---- Build ----

ui:
	cd $(UI_DIR) && npm run build

war: ui
	mvn package -f core/pom.xml -Pdevelopment -DskipTests -q

deploy: war
	cp core/target/core.war $(DOCKER_DIR)/core/core.war
	cd $(DOCKER_DIR) && $(COMPOSE) up -d --build --force-recreate core
	@echo "Waiting for core to be healthy..."
	@for i in $$(seq 1 24); do \
		if curl -sf -u admin:admin http://localhost:8080/core/atom/bedroom > /dev/null 2>&1; then \
			echo "Ready"; break; \
		fi; sleep 5; \
	done

# ---- Test (assume already deployed) ----

unit:
	mvn test -Dtest='ResourceBaseCsrfProtectionTest,CanonicalImportServiceTest' -f core/pom.xml -Pdevelopment

guards:
	./scripts/check-csrf-headers.sh
	./scripts/check-dockerfile-parity.sh

qa:
	./qa-test.sh qa

tck-clean:
	./tck-test-clean.sh

tck:
	mvn test -Dtest='ConnectionTestGroup,BasicsTestGroup,TypesTestGroup,ControlTestGroup,VersioningTestGroup,CrudTestGroup1,CrudTestGroup2,QueryTestGroup' \
		-f core/pom.xml -Pdevelopment

e2e:
	cd $(UI_DIR) && npx playwright test --project=chromium

e2e-quick:
	cd $(UI_DIR) && npx playwright test --project=chromium \
		tests/api/ingest-pipeline-e2e.spec.ts \
		tests/api/external-ingest-api.spec.ts \
		tests/documents/cloud-import.spec.ts

# ---- Composite ----

verify: unit guards qa
	@echo "✓ verify (unit + guards + qa) complete"

verify-full: verify tck-clean e2e
	@echo "✓ verify-full (unit + guards + qa + tck + e2e) complete"

# ---- Utilities ----

health:
	@curl -sf -u admin:admin http://localhost:8080/core/atom/bedroom -o /dev/null -w "Core: %{http_code}\n"
	@curl -sf -u admin:password http://localhost:5984/ -o /dev/null -w "CouchDB: %{http_code}\n"
	@curl -sf http://localhost:8983/solr/admin/cores -o /dev/null -w "Solr: %{http_code}\n"

clean:
	mvn clean -f core/pom.xml -q
	rm -rf $(UI_DIR)/dist
	rm -rf $(UI_DIR)/test-results
	rm -rf $(UI_DIR)/playwright-report
	@echo "✓ clean complete"
