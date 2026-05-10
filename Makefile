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

.PHONY: ui war deploy deploy-local qa tck-clean tck e2e e2e-quick unit guards verify verify-full clean health env-check

UI_DIR := core/src/main/webapp/ui
DOCKER_DIR := docker
COMPOSE := docker compose -f $(DOCKER_DIR)/docker-compose-simple.yml

# Compose requires CouchDB credentials (RC13+). The Makefile no longer
# provides defaults — that masked the security gain of compose's
# `${VAR:?...}` fail-fast and let `make deploy` re-introduce the legacy
# admin/password pair on production hosts. Set them in the calling shell
# (or .env) before running any docker target. For local-only convenience
# use `make deploy-local`, which sets harmless dev credentials inline
# and is intended only for the developer workstation.

# ---- Build ----

ui:
	cd $(UI_DIR) && npm run build

war: ui
	mvn package -f core/pom.xml -Pdevelopment -DskipTests -q

# Verify required env is set before any docker target runs.
env-check:
	@if [ -z "$$COUCHDB_USER" ] || [ -z "$$COUCHDB_PASSWORD" ]; then \
		echo "ERROR: COUCHDB_USER and COUCHDB_PASSWORD must be set in the environment."; \
		echo "       Production: provide strong values via your secret store / .env."; \
		echo "       Local dev:   use 'make deploy-local' to set dev credentials inline."; \
		exit 1; \
	fi

deploy: env-check war
	cp core/target/core.war $(DOCKER_DIR)/core/core.war
	cd $(DOCKER_DIR) && $(COMPOSE) up -d --build --force-recreate core
	@echo "Waiting for core to be healthy..."
	@# Health check uses /core/rest/all/repositories — the only truly
	@# public endpoint on the SP. Earlier revisions confused CouchDB
	@# credentials with NemakiWare app-user credentials by hitting
	@# /core/atom/bedroom with $$COUCHDB_PASSWORD; the request happened
	@# to succeed only because the dev CouchDB and dev app admin shared
	@# admin/admin. The repositories endpoint requires no auth and
	@# returns the configured repository list, which is the strongest
	@# signal we can get without app-user credentials. The for-loop
	@# explicitly fails the make target when the readiness budget is
	@# exhausted (the previous version always returned success).
	@if ! { for i in $$(seq 1 24); do \
		if curl -sf http://localhost:8080/core/rest/all/repositories > /dev/null 2>&1; then \
			echo "Ready (after $${i} attempts)"; exit 0; \
		fi; sleep 5; \
	done; exit 1; }; then \
		echo "ERROR: core did not become ready within 120s — see 'docker compose -f $(DOCKER_DIR)/docker-compose-simple.yml logs core'"; \
		exit 1; \
	fi

# Convenience target for local development on the developer workstation.
# Uses the well-known admin/password pair that the dev CouchDB container
# also defaults to. Never use this on a server that anyone else can reach.
deploy-local: war
	@echo "[deploy-local] Using DEV CouchDB credentials admin/password (workstation only)."
	COUCHDB_USER=admin COUCHDB_PASSWORD=password $(MAKE) deploy

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
