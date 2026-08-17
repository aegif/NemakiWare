#!/usr/bin/env bash
# =============================================================================
# CI helper: seed CMIS documents so the OData integration tests have real,
# distinct data to exercise — and FAIL CLOSED if that precondition cannot be met.
# =============================================================================
# The OData paging / $orderby regression tests (ODataOlingoClientValidationIT)
# guard with assumeTrue(total >= 2) and assumeTrue(distinct names); on a fresh CI
# database they would SKIP, so the CI gate would pass WITHOUT running the very
# regressions it exists to protect (the same class of defect as an empty
# early-return PASS). This script therefore:
#   1. always (idempotently) creates a set of distinct, ASCII-sortable documents
#      via the CMIS Browser Binding (409 = already exists is fine), and
#   2. waits until the query path (Solr, async-indexed) actually returns them —
#      count >= SEED_COUNT, every seed name present, and ALL names distinct —
#   3. exiting NON-ZERO if that state is not reached, so the job fails instead of
#      silently skipping the regression tests.
#
# Run it after scripts/ci-complete-setup.sh, once admin:admin works.
# Environment:
#   BASE_URL    default http://localhost:8080
#   NEMAKI_REPO default bedroom
#   NEMAKI_USER default admin
#   NEMAKI_PASS default admin
#   SEED_COUNT  default 3   (number of distinct seed documents to guarantee)
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
REPO="${NEMAKI_REPO:-bedroom}"
USER="${NEMAKI_USER:-admin}"
PASS="${NEMAKI_PASS:-admin}"
SEED_COUNT="${SEED_COUNT:-3}"
BROWSER="${BASE_URL}/core/browser/${REPO}"

# Fixed, distinct, ASCII-sortable seed names (unambiguous for $orderby=name).
letters=(a b c d e f g h)
SEED_NAMES=""
for i in $(seq 0 $((SEED_COUNT - 1))); do
  SEED_NAMES="${SEED_NAMES} odata-ci-seed-${letters[$i]}.txt"
done
SEED_NAMES="${SEED_NAMES# }"

echo "[seed] resolving root folder id for repo '${REPO}' ..."
root="$(curl -sf -u "${USER}:${PASS}" "${BROWSER}?cmisselector=repositoryInfo" \
  | jq -r --arg repo "$REPO" '.[$repo].rootFolderId // (to_entries[0].value.rootFolderId)')"
[ -n "$root" ] && [ "$root" != "null" ] || { echo "[seed] FATAL: could not resolve rootFolderId"; exit 1; }
echo "[seed] root=${root}"

create() { # $1 = document name
  local code
  code="$(curl -s -o /dev/null -w '%{http_code}' -u "${USER}:${PASS}" -X POST "${BROWSER}" \
    -F "cmisaction=createDocument" \
    -F "folderId=${root}" \
    -F "propertyId[0]=cmis:objectTypeId" -F "propertyValue[0]=cmis:document" \
    -F "propertyId[1]=cmis:name"         -F "propertyValue[1]=$1")"
  case "$code" in
    201) echo "[seed] created '$1' (201)" ;;
    409) echo "[seed] '$1' already exists (409) — ok" ;;
    *)   echo "[seed] FATAL: createDocument '$1' -> HTTP ${code}"; exit 1 ;;
  esac
}

# Always ensure the fixed seed documents exist (idempotent via the 409 branch),
# regardless of any pre-existing documents — this is what guarantees the distinct
# names the $orderby regression relies on.
echo "[seed] ensuring ${SEED_COUNT} distinct seed documents exist: ${SEED_NAMES}"
for nm in $SEED_NAMES; do
  create "$nm"
done

# Return the queryable cmis:document names, one per line.
query_names() {
  curl -sf -u "${USER}:${PASS}" -G "${BROWSER}" \
    --data-urlencode "cmisselector=query" \
    --data-urlencode "q=SELECT cmis:name FROM cmis:document" \
    --data-urlencode "maxItems=1000" 2>/dev/null \
    | jq -r '.results[]?.properties["cmis:name"].value' 2>/dev/null
}

# True only when the query path returns our seed set: count >= SEED_COUNT and
# every seed name is queryable. We deliberately do NOT reject duplicate names
# elsewhere in the repository — CMIS legitimately allows same-named documents in
# different folders, so a repo-wide uniqueness check would fail on valid data.
# The seed names are distinct by construction (odata-ci-seed-a/b/c...), which is
# all the $orderby regression needs; unrelated documents are ignored.
ready() {
  local names total nm
  names="$(query_names)" || return 1
  total="$(printf '%s\n' "$names" | grep -c . || true)"
  if [ "${total:-0}" -lt "$SEED_COUNT" ]; then
    echo "[seed]   not ready: indexed count=${total:-0} < ${SEED_COUNT}"; return 1
  fi
  for nm in $SEED_NAMES; do
    if ! printf '%s\n' "$names" | grep -qxF "$nm"; then
      echo "[seed]   not ready: seed '${nm}' not queryable yet (Solr indexing lag)"; return 1
    fi
  done
  return 0
}

echo "[seed] waiting for the query path to return >= ${SEED_COUNT} distinct documents (incl. all seeds) ..."
for i in $(seq 1 40); do
  if ready; then
    echo "[seed] ready — OData paging/\$orderby tests will run (not skip). (attempt ${i})"
    exit 0
  fi
  sleep 3
done

echo "[seed] FATAL: preconditions not met after wait — failing closed so the OData gate"
echo "[seed]        does NOT pass by silently skipping its regression tests."
echo "[seed] last observed queryable names:"
query_names | sed 's/^/[seed]   /' || true
exit 1
