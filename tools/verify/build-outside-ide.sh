#!/usr/bin/env bash
# Build and test NemakiWare where the IDE's Java language server cannot reach.
#
# The Red Hat Java extension (JDT LS) compiles into the SAME core/target/classes that Maven uses.
# Where it disagrees with javac it writes class files whose methods throw
# "java.lang.Error: Unresolved compilation problem", and it does so WHILE a Maven build is running
# — so `mvn clean` is not enough. A WAR packaged from a poisoned tree fails at runtime with a bare
# CmisRuntimeException, logs nothing, and does not go away when you revert the commit.
#
# This mirrors the working tree to a directory the IDE does not watch and builds there.
#
#   tools/verify/build-outside-ide.sh test     # full suite
#   tools/verify/build-outside-ide.sh war      # package and copy to docker/core/core.war
#
# CompiledClassesAreUsableTest fails the build if a poisoned class is present, so a normal
# in-place run still tells you when this script is needed.
set -euo pipefail

SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DST="${NEMAKI_VERIFY_DIR:-/tmp/nemaki-verify}"
ACTION="${1:-test}"

echo "Mirroring $SRC -> $DST (excluding build output and git metadata)"
rsync -a --delete \
  --exclude 'target/' --exclude '*/target/' \
  --exclude '.git/' \
  --exclude 'node_modules/' \
  "$SRC/" "$DST/"

cd "$DST"

case "$ACTION" in
  # Explicit Java-only goals rather than `test`: the lifecycle also runs the frontend plugin,
  # which installs its own node under the UI directory with absolute paths baked in, and that
  # does not survive being mirrored. The UI is not what this build exists to verify.
  test)
    mvn -o -pl core resources:resources compiler:compile \
                    resources:testResources compiler:testCompile surefire:test
    ;;
  war)
    # The WAR needs the UI, so this one DOES run the full lifecycle — and therefore needs the
    # frontend toolchain, which the mirror cannot provide. Build the WAR in the real tree, then
    # verify the packaged classes are not poisoned before shipping it.
    (cd "$SRC" && mvn -o clean -q && mvn -o -pl core -am package -DskipTests)
    if unzip -p "$SRC/core/target/core.war" 'WEB-INF/classes/*.class' 2>/dev/null \
        | grep -qa "Unresolved compilation problem"; then
      echo "REFUSING to ship: the WAR contains classes the IDE's language server broke." >&2
      echo "Close the Java extension (or the editor) and rebuild." >&2
      exit 1
    fi
    cp "$SRC/core/target/core.war" "$SRC/docker/core/core.war"
    echo "Copied a verified WAR to $SRC/docker/core/core.war"
    ;;
  *) echo "usage: $0 [test|war]" >&2; exit 2 ;;
esac
