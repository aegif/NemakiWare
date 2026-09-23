#!/usr/bin/env bash
# 3.4.0 の SBOM (CycloneDX) を 2 つ作る — Maven 側 (WAR と verifier の依存) と npm 側 (UI)。
#
# 何を作るか
#   target/nemakiware-<version>-sbom.json / .xml   Maven の全 module を aggregate (CycloneDX 1.6)
#   target/nemakiware-<version>-ui-sbom.json       core/src/main/webapp/ui の npm 依存
#
# 前提
#   - 網が要る (plugin と cyclonedx-npm を取り寄せる)。offline (-o) では最初の 1 回は落ちる。
#     2026-09-23 に取り寄せた版を固定してある。
#   - 署名はしない。detached signature は鍵を持つリリース担当者の作業で、この script の外。
#   - リリース成果物は git worktree で切った tagged tree から作ること (CLAUDE.md)。
#     この作業コピーで作った SBOM は「今の HEAD の依存」であって「タグの依存」ではない。
#
# 使い方
#   tools/sbom/make-sbom.sh            # version は root pom から読む
#
# 2026-09-23 の実測 (HEAD f4e643f59 相当、Playwright 実行中に nice -n 19 で): Maven 401 component /
# 402 dependency、root nemakiware 3.4.0。UI 側は下記の実行で出た数を準備文書に記録した。
set -euo pipefail
cd "$(dirname "$0")/../.."

VERSION=$(grep -m1 '<version>' pom.xml | sed -E 's/.*<version>([^<]+)<.*/\1/')
PLUGIN=org.cyclonedx:cyclonedx-maven-plugin:2.9.3
NPM_TOOL=@cyclonedx/cyclonedx-npm

mkdir -p target
echo "== Maven aggregate BOM (${PLUGIN}) =="
mvn -q "${PLUGIN}:makeAggregateBom" -DoutputFormat=all -DoutputName="nemakiware-${VERSION}-sbom" -DskipTests
ls -la "target/nemakiware-${VERSION}-sbom.json" "target/nemakiware-${VERSION}-sbom.xml"

echo "== npm BOM (${NPM_TOOL}) =="
( cd core/src/main/webapp/ui && npx --yes "${NPM_TOOL}" --output-format JSON \
    --output-file "$(pwd)/../../../../../target/nemakiware-${VERSION}-ui-sbom.json" )
ls -la "target/nemakiware-${VERSION}-ui-sbom.json"

python3 - "$VERSION" <<'PY'
import json, sys
v = sys.argv[1]
for name in (f"target/nemakiware-{v}-sbom.json", f"target/nemakiware-{v}-ui-sbom.json"):
    d = json.load(open(name))
    print(f"{name}: CycloneDX {d.get('specVersion')}, {len(d.get('components', []))} components, "
          f"root {d.get('metadata', {}).get('component', {}).get('name')}")
PY
