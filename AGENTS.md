# Repository Guidelines

## Project Structure & Modules
- `common/` — Shared utilities (JAR).
- `core/` — CMIS REST/Web Services server (WAR, Jakarta EE 10, Spring 6).
- `solr/` — Search integration helpers.
- `cloudant-init/` — CouchDB/Cloudant bootstrap tools.
- `docker/` — Compose files, images, and runtime config.
- `setup/`, `war_content/`, `WEB-INF/` — Installer and packaging assets.
- Tests live under `*/src/test/java`; reports under `*/test-reports/`.

## Build, Test, and Run
- Build all modules: `mvn -T 1C -DskipTests install` (root `pom.xml`).
- Build server only: `mvn -pl core -am package` (produces `core/target/core.war`).
- Run dev server (Jetty 11): `cd core && ./start-jetty-dev.sh`.
  - Access CMIS: `http://localhost:8080/core/atom/bedroom` (admin:admin).
- Run tests (JUnit 4): `mvn test` or `mvn -pl core test`.
- Full stack via Docker: `docker compose -f docker/docker-compose-simple.yml up -d`.

## Coding Style & Naming
- Java 17; use Jakarta APIs (`jakarta.*`), avoid `javax.*`.
- Indentation: 4 spaces, UTF-8, 120-col soft wrap.
- Packages: `jp.aegif.nemaki...`; Classes `PascalCase`, methods/fields `camelCase`, constants `UPPER_SNAKE_CASE`.
- Prefer SLF4J (`org.slf4j.Logger`) over `System.out`.
- Module boundaries: put shared code in `common/`; CMIS/server code in `core/`.

## Testing Guidelines
- Framework: JUnit 4 (Surefire configured with Java 17 module opens).
- Place tests in `src/test/java`; name files `*Test.java`.
- Keep unit tests fast and isolated (mock Solr when applicable). Useful scripts: `qa-test.sh`, `test-rest-api-comprehensive.sh`.

## Commit & Pull Request Guidelines
- Use concise, imperative subjects. Conventional prefixes are common: `feat:`, `fix:`, `refactor:`, `chore:` (optionally add module tag, e.g., `[core] fix: ...`).
- Include context and rationale in the body; reference issues (`Fixes #123`).
- PRs should include: clear description, reproduction steps, test evidence (logs or report paths), config notes (e.g., CouchDB, ports), and any Docker compose variant used.

## Security & Configuration Tips
- Do not commit secrets. Local defaults: CouchDB `admin/password` (dev only).
- Primary config: `core/nemakiware.properties`, `docker/repositories.yml`.
- For Java 17, ensure `MAVEN_OPTS` includes required `--add-opens` (see `core/start-jetty-dev.sh`).
