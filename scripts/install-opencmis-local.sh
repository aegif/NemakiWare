#!/usr/bin/env bash
# Install OpenCMIS 1.1.0-nemakiware JARs from lib/built-jars/ into local Maven repository.
# Run this once before building if you don't have GitHub Packages authentication configured.
#
# Usage: ./scripts/install-opencmis-local.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
JAR_DIR="$PROJECT_ROOT/lib/built-jars"

GROUP_ID="org.apache.chemistry.opencmis"
VERSION="1.1.0-nemakiware"

ARTIFACTS=(
    chemistry-opencmis-client-api
    chemistry-opencmis-client-bindings
    chemistry-opencmis-client-impl
    chemistry-opencmis-commons-api
    chemistry-opencmis-commons-impl
    chemistry-opencmis-server-bindings
    chemistry-opencmis-server-support
    chemistry-opencmis-test-tck
)

installed=0
for artifact in "${ARTIFACTS[@]}"; do
    jar="$JAR_DIR/${artifact}-${VERSION}.jar"
    sources="$JAR_DIR/${artifact}-${VERSION}-sources.jar"

    if [[ ! -f "$jar" ]]; then
        echo "WARN: $jar not found, skipping"
        continue
    fi

    cmd=(mvn install:install-file
        -DgroupId="$GROUP_ID"
        -DartifactId="$artifact"
        -Dversion="$VERSION"
        -Dpackaging=jar
        -Dfile="$jar"
    )

    if [[ -f "$sources" ]]; then
        cmd+=(-Dsources="$sources")
    fi

    echo "Installing $artifact ..."
    "${cmd[@]}" -q
    ((installed++))
done

echo ""
echo "Done: $installed artifacts installed to local Maven repository."
echo "You can now build with: mvn clean package -f core/pom.xml -Pdevelopment -DskipTests"
