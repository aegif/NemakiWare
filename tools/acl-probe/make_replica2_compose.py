"""2 レプリカ構成の compose override を生成する (cross-replica 計測用)。

`rag_revocation_cross_replica.py` は「A で剥奪して B で確認する」ので、
**同一の CouchDB / Solr を見る 2 つ目の core** が要る。

override を手書きでリポジトリに置くと本体の compose と**必ず drift する**
(CATALINA_OPTS だけで 30 個近い `-D` があり、片方だけ足された瞬間に
「2 レプリカのはずが設定違いの 2 台」になって、測っているものが変わる)。
なので毎回**本体から派生させて生成する**。

    python3 tools/acl-probe/make_replica2_compose.py
    docker compose -p nb33 -f docker/docker-compose-simple.yml \\
        -f /tmp/nb33-replica2.yml --profile rag up -d core2

`core` との違いは 3 点だけ:

  - ホスト側ポート 8090 (8080 は core、8081 は TEI が使っている)
  - `nemakiware.deployment.singleReplica=false` /
    `stickySession=true` (docs/MULTI-REPLICA-DEPLOYMENT.md の R3。
    無いと single-replica 前提の WARN 挙動のままになる)
  - 相対パスを絶対パスに (override が /tmp にあるため)

片付け:

    docker compose -p nb33 -f docker/docker-compose-simple.yml \\
        -f /tmp/nb33-replica2.yml stop core2 && docker rm -f nb33-core2-1
"""
import pathlib
import re
import sys

REPO = pathlib.Path(__file__).resolve().parents[2]
DOCKER = REPO / "docker"
SOURCE = DOCKER / "docker-compose-simple.yml"
OUT = pathlib.Path("/tmp/nb33-replica2.yml")
HOST_PORT = "8090"


def main():
    text = SOURCE.read_text()
    m = re.search(r"^  core:\n(.*?)(?=^  [a-z0-9_-]+:\n|\Z)", text, re.M | re.S)
    if not m:
        raise SystemExit(f"could not find the 'core' service in {SOURCE}")
    block = m.group(0)

    if '- "8080:8080"' not in block:
        raise SystemExit("the core service no longer publishes 8080:8080 — this generator "
                         "would produce a replica that collides with it or is unreachable")
    if "-Djdk.httpclient.allowRestrictedHeaders=host" not in block:
        raise SystemExit("CATALINA_OPTS no longer ends where this generator expects; appending "
                         "the replica flags blindly could corrupt the option string")

    core2 = block.replace("  core:\n", "  core2:\n", 1)
    core2 = core2.replace('- "8080:8080"', f'- "{HOST_PORT}:8080"', 1)
    core2 = core2.replace(
        "-Djdk.httpclient.allowRestrictedHeaders=host",
        "-Djdk.httpclient.allowRestrictedHeaders=host"
        " -Dnemakiware.deployment.singleReplica=false"
        " -Dnemakiware.deployment.stickySession=true", 1)
    # The override lives outside docker/, so every relative path has to be re-anchored.
    core2 = core2.replace("      context: ./core", f"      context: {DOCKER}/core")
    core2 = core2.replace("- path: ./secrets/", f"- path: {DOCKER}/secrets/")
    core2 = core2.replace("      - ./secrets:/usr/local/tomcat/secrets:ro",
                          f"      - {DOCKER}/secrets:/usr/local/tomcat/secrets:ro")

    if "./" in re.sub(r"https?://", "", core2):
        leftovers = [l for l in core2.split("\n") if "./" in l and "http" not in l]
        print("WARNING: relative paths remain and will resolve against /tmp:", file=sys.stderr)
        for l in leftovers:
            print("  " + l.strip(), file=sys.stderr)

    OUT.write_text("services:\n" + core2)
    print(f"wrote {OUT} (core2 on host port {HOST_PORT}, derived from {SOURCE.name})")
    print("\ndocker compose -p nb33 -f docker/docker-compose-simple.yml \\\n"
          f"    -f {OUT} --profile rag up -d core2")


if __name__ == "__main__":
    main()
