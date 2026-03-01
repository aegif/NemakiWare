#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

echo "Fetching OpenCMIS jars from GitHub Packages into lib/built-jars ..."
echo "Repository URL: ${OPENCMIS_GITHUB_PACKAGES_URL:-https://maven.pkg.github.com/aegif/chemistry-opencmis-nemakiware}"

# Requires ~/.m2/settings.xml with server id "github-opencmis":
# <server>
#   <id>github-opencmis</id>
#   <username>YOUR_GITHUB_USERNAME</username>
#   <password>YOUR_GITHUB_TOKEN</password>
# </server>
mvn -f core/pom.xml \
  -Popencmis-github-packages \
  -DskipTests \
  -Dopencmis.github.packages.url="${OPENCMIS_GITHUB_PACKAGES_URL:-https://maven.pkg.github.com/aegif/chemistry-opencmis-nemakiware}" \
  validate "$@"

echo "Done. OpenCMIS jars are synchronized in lib/built-jars."
