#!/usr/bin/env bash
# RC6.4 Epic 1: SOC template validation gate.
#
# Runs whatever validators are available on this host against the 6
# templates under docs/soc-templates/. Reports PASS / SKIP / FAIL for
# each (validator, template) pair. Exits non-zero if any FAIL.
#
# Two tiers:
#
#   Phase 1 (always runs, no external deps):
#     - JSON parse        for docs/soc-templates/kibana-detection-rules.ndjson
#     - YAML parse        for *.yml
#     - TOML parse        for *.toml (Python 3.11+ tomllib)
#     - placeholder grep  enumerates remaining ${...} in every file
#     - file-type smoke   `file` reports text-ish, not binary
#
#   Phase 2 (opt-in via VALIDATE_DOCKER=1, requires docker):
#     - vector validate       via timberio/vector
#     - fluent-bit --dry-run  via fluent/fluent-bit
#     - filebeat test config  via docker.elastic.co/beats/filebeat
#     - cortextool rules check via grafana/cortex-tools
#     Splunk and Kibana Detection Engine remain manual gates
#     (they require a running cluster, not just a CLI).
#
# Usage:
#   scripts/validate-soc-templates.sh           # Phase 1 only
#   VALIDATE_DOCKER=1 scripts/validate-soc-templates.sh   # Phase 1 + 2
#
# The script writes a structured report to stdout. To capture the
# Markdown summary that ships in docs/soc-templates/VALIDATION.md,
# redirect via the WRITE_VALIDATION_MD env var:
#   WRITE_VALIDATION_MD=1 scripts/validate-soc-templates.sh
#
# Exit codes:
#   0   all attempted checks PASS, any SKIPs are honestly reported
#   1   at least one check FAILed
#   2   misconfiguration (template files missing, etc.)

set -uo pipefail

# Locate the templates dir relative to this script
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$HERE/.." && pwd)"
TEMPLATE_DIR="$REPO_ROOT/docs/soc-templates"

if [ ! -d "$TEMPLATE_DIR" ]; then
  echo "ERROR: template dir not found at $TEMPLATE_DIR" >&2
  exit 2
fi

PASS_COUNT=0
SKIP_COUNT=0
FAIL_COUNT=0

# Markdown summary buffer (only written when WRITE_VALIDATION_MD=1)
MD_BUF=""

# ── helpers ─────────────────────────────────────────────────────

ok()   { printf '  \033[32mPASS\033[0m  %s\n' "$1"; PASS_COUNT=$((PASS_COUNT+1)); MD_BUF+="| ✅ PASS | $1 |\n"; }
skip() { printf '  \033[33mSKIP\033[0m  %s — %s\n' "$1" "$2"; SKIP_COUNT=$((SKIP_COUNT+1)); MD_BUF+="| ⚠️ SKIP | $1 — $2 |\n"; }
fail() { printf '  \033[31mFAIL\033[0m  %s — %s\n' "$1" "$2"; FAIL_COUNT=$((FAIL_COUNT+1)); MD_BUF+="| ❌ FAIL | $1 — $2 |\n"; }
hdr()  { printf '\n\033[1m== %s ==\033[0m\n' "$1"; MD_BUF+="\n### $1\n\n| Status | Check |\n|---|---|\n"; }

have() { command -v "$1" >/dev/null 2>&1; }

# ── Phase 1: host-side static checks ──────────────────────────

hdr "Phase 1.1 — JSON parse (NDJSON)"
NDJSON="$TEMPLATE_DIR/kibana-detection-rules.ndjson"
if [ ! -f "$NDJSON" ]; then
  fail "kibana-detection-rules.ndjson" "file missing"
else
  if ! have python3; then
    skip "kibana-detection-rules.ndjson" "python3 unavailable"
  else
    line_no=0
    ok_lines=0
    fail_lines=0
    while IFS= read -r line; do
      line_no=$((line_no+1))
      if printf '%s\n' "$line" | python3 -c 'import sys,json; json.loads(sys.stdin.read())' 2>/dev/null; then
        ok_lines=$((ok_lines+1))
      else
        fail_lines=$((fail_lines+1))
        fail "kibana-detection-rules.ndjson:$line_no" "JSON parse failed"
      fi
    done < "$NDJSON"
    if [ "$fail_lines" = 0 ]; then
      ok "kibana-detection-rules.ndjson — $ok_lines/$line_no lines valid JSON"
    fi
  fi
fi

hdr "Phase 1.2 — YAML parse"
for yml in "$TEMPLATE_DIR"/*.yml; do
  [ -f "$yml" ] || continue
  name="$(basename "$yml")"
  if ! have python3; then
    skip "$name" "python3 unavailable"
    continue
  fi
  if python3 -c 'import yaml' 2>/dev/null; then
    if python3 -c "import yaml; yaml.safe_load(open('$yml'))" 2>/dev/null; then
      ok "$name — YAML parses"
    else
      err="$(python3 -c "import yaml; yaml.safe_load(open('$yml'))" 2>&1 | tail -1)"
      fail "$name" "YAML parse: $err"
    fi
  else
    skip "$name" "python3 yaml module unavailable (pip install pyyaml)"
  fi
done

hdr "Phase 1.3 — TOML parse"
for toml in "$TEMPLATE_DIR"/*.toml; do
  [ -f "$toml" ] || continue
  name="$(basename "$toml")"
  if ! have python3; then
    skip "$name" "python3 unavailable"
    continue
  fi
  if python3 -c 'import tomllib' 2>/dev/null; then
    if python3 -c "import tomllib; tomllib.load(open('$toml','rb'))" 2>/dev/null; then
      ok "$name — TOML parses (python tomllib)"
    else
      err="$(python3 -c "import tomllib; tomllib.load(open('$toml','rb'))" 2>&1 | tail -1)"
      fail "$name" "TOML parse: $err"
    fi
  else
    skip "$name" "python tomllib unavailable (needs Python 3.11+)"
  fi
done

hdr "Phase 1.4 — NUL-byte smoke (RC6.1 P2-3 regression class)"
# Python rather than `grep -q $'\x00' …` because bash strips NUL
# from argv and grep with the empty pattern matches every file
# (false-positive every time — observed during RC6.4 script
# bring-up).
for f in "$TEMPLATE_DIR"/*.yml "$TEMPLATE_DIR"/*.conf "$TEMPLATE_DIR"/*.toml "$TEMPLATE_DIR"/*.ndjson; do
  [ -f "$f" ] || continue
  name="$(basename "$f")"
  if ! have python3; then
    skip "$name" "python3 unavailable for NUL scan"
    continue
  fi
  if nul_count="$(python3 -c "
import sys
data = open(sys.argv[1], 'rb').read()
print(data.count(b'\x00'))
" "$f")" && [ "$nul_count" = "0" ]; then
    ok "$name — 0 NUL bytes"
  else
    fail "$name" "$nul_count NUL bytes present"
  fi
done

hdr "Phase 1.4.1 — source-tree NUL-byte scan (RC6.10 — extends Phase 1.4 to .java/.ts/.tsx/.js/.jsx)"
# Two NUL-shipped regressions in this RC cycle alone:
#   - RC6.1 P2-3: ConnectorGovernanceTab.tsx had a literal 0x00 in a
#     join() separator → grep/file/rg treated the file as binary.
#   - RC6.7 P3:  HttpWebhookDispatcherTest.java had a literal 0x00 in
#     a string literal → same.
# Both got past Java/TypeScript compilation. Add a source-side gate.
#
# Honors VALIDATE_SOURCE_NUL=0 to disable for environments where this
# is run against pre-cleaned trees (default: 1 = run).
if [ "${VALIDATE_SOURCE_NUL:-1}" = "1" ]; then
  if ! have python3; then
    skip "source-tree NUL scan" "python3 unavailable"
  else
    SRC_NUL_REPORT="$(python3 - "$REPO_ROOT" <<'PYSRCNUL'
import os, sys
root = sys.argv[1]
exts = ('.java', '.ts', '.tsx', '.js', '.jsx')
exclude_parts = {'node_modules', 'target', 'dist', 'build', '.git',
                 'coverage', 'playwright-report', 'test-results'}
hits = []
total = 0
for dirpath, dirnames, filenames in os.walk(root):
    dirnames[:] = [d for d in dirnames if d not in exclude_parts]
    for fn in filenames:
        if not fn.endswith(exts):
            continue
        total += 1
        path = os.path.join(dirpath, fn)
        try:
            with open(path, 'rb') as f:
                data = f.read()
            n = data.count(b'\x00')
            if n:
                rel = os.path.relpath(path, root)
                hits.append((rel, n))
        except OSError:
            pass
print(f'TOTAL={total}')
for rel, n in hits:
    print(f'HIT={rel}:{n}')
PYSRCNUL
)"
    scanned="$(printf '%s\n' "$SRC_NUL_REPORT" | sed -n 's/^TOTAL=//p')"
    nul_hits="$(printf '%s\n' "$SRC_NUL_REPORT" | grep -c '^HIT=' || true)"
    if [ "${nul_hits:-0}" = 0 ]; then
      ok "source-tree NUL scan — 0 hits across $scanned source files"
    else
      while IFS= read -r line; do
        case "$line" in
          HIT=*)
            payload="${line#HIT=}"
            fail "source-tree NUL: $payload" "literal NUL byte in source file"
            ;;
        esac
      done <<< "$SRC_NUL_REPORT"
    fi
  fi
else
  skip "source-tree NUL scan" "disabled via VALIDATE_SOURCE_NUL=0"
fi

hdr "Phase 1.5 — file-type smoke"
# `file` reports NDJSON as "JSON data" which is text-class but
# the substring "data" alone would false-match here; the real
# binary classes are "data" *alone* (the unhelpful catch-all),
# "ELF", "executable", or anything mentioning "binary".
for f in "$TEMPLATE_DIR"/*.yml "$TEMPLATE_DIR"/*.conf "$TEMPLATE_DIR"/*.toml "$TEMPLATE_DIR"/*.ndjson; do
  [ -f "$f" ] || continue
  name="$(basename "$f")"
  if have file; then
    type="$(file -b "$f")"
    case "$type" in
      data|*executable*|ELF*|*binary*)
        # "data" alone is the unhelpful catch-all; "JSON data" /
        # "ASCII text" / "UTF-8 text" are all text-class and pass.
        fail "$name" "file reports as non-text: $type"
        ;;
      *)
        ok "$name — $type"
        ;;
    esac
  else
    skip "$name" "file utility unavailable"
  fi
done

hdr "Phase 1.6 — placeholder enumeration"
PLACEHOLDERS="$(grep -hoE '\$\{[A-Z_][A-Z_0-9:-]*\}' "$TEMPLATE_DIR"/*.yml "$TEMPLATE_DIR"/*.conf "$TEMPLATE_DIR"/*.toml "$TEMPLATE_DIR"/*.ndjson 2>/dev/null | sort -u || true)"
if [ -z "$PLACEHOLDERS" ]; then
  ok "no \${...} placeholders found"
else
  count="$(printf '%s\n' "$PLACEHOLDERS" | wc -l | tr -d ' ')"
  ok "$count distinct placeholders enumerated (informational — operator fills before deploy)"
  while IFS= read -r p; do
    MD_BUF+="| ℹ️ INFO | placeholder: \`$p\` |\n"
  done <<< "$PLACEHOLDERS"
fi

# ── Phase 2: opt-in dockerized validators ─────────────────────

if [ "${VALIDATE_DOCKER:-0}" = "1" ]; then
  if ! have docker; then
    hdr "Phase 2 — DOCKERIZED VALIDATORS (skipped, docker unavailable)"
    skip "vector validate"      "docker not installed"
    skip "fluent-bit --dry-run" "docker not installed"
    skip "filebeat test config" "docker not installed"
    skip "cortextool rules check" "docker not installed"
  else
    # Synthetic env values so validators get past placeholder
    # checks and reach the actual parse / syntax stage. Operators
    # supply real values in production; this script's job is to
    # catch syntax bugs, not env-completeness bugs.
    SYNTH_ENV=(
      -e SIEM_ENDPOINT=http://siem.example.local:8088
      -e SIEM_HOST=siem.example.local
      -e SIEM_PORT=3100
      -e SIEM_USERNAME=svc-nemakiware
      -e SIEM_PASSWORD=placeholder-password
      -e SIEM_API_KEY=placeholder-api-key
      -e SIEM_SPLUNK_INDEX=nemakiware
      -e BUSINESS_HOURS_TZ=UTC
      -e BUSINESS_HOURS_START_LOCAL=06
      -e BUSINESS_HOURS_END_LOCAL=22
      -e NEMAKIWARE_AUDIT_LOG_PATH=/tmp/audit.log
      -e HOSTNAME=validate-soc-templates
    )

    hdr "Phase 2.1 — vector validate (timberio/vector)"
    # `vector validate` runs source healthchecks too; the file source's
    # `data_dir = /var/lib/vector/nemakiware-audit` must resolve to an
    # existing writable directory inside the container, otherwise the
    # check fails ("data_dir does not exist") even though the rest of
    # the config is fine. Mount a tmpfs at the expected path; operator
    # is expected to create the real path with proper ownership.
    #
    # --skip-healthchecks skips the *sink* network probes (Loki HTTP,
    # Splunk HEC, ES bulk endpoint). Those require a live SIEM and
    # would always fail in a CI / template-validation context against
    # the synthetic ${SIEM_HOST}=siem.example.local. Config syntax,
    # VRL transform, and source healthchecks still run.
    if docker run --rm -v "$TEMPLATE_DIR:/cfg:ro" \
         --tmpfs /var/lib/vector/nemakiware-audit \
         --tmpfs /tmp \
         "${SYNTH_ENV[@]}" \
         timberio/vector:latest-alpine \
         validate --skip-healthchecks /cfg/vector-nemakiware.toml \
         >/tmp/vector.log 2>&1
    then
      ok "vector-nemakiware.toml — vector validate passed (--skip-healthchecks)"
    else
      err="$(tail -10 /tmp/vector.log | tr '\n' ' ')"
      fail "vector-nemakiware.toml" "vector validate: $err"
    fi

    hdr "Phase 2.2 — fluent-bit --dry-run (fluent/fluent-bit)"
    if docker run --rm -v "$TEMPLATE_DIR:/cfg:ro" "${SYNTH_ENV[@]}" \
         fluent/fluent-bit:latest \
         /fluent-bit/bin/fluent-bit -c /cfg/fluent-bit-nemakiware.conf --dry-run \
         >/tmp/fb.log 2>&1
    then
      ok "fluent-bit-nemakiware.conf — fluent-bit --dry-run passed"
    else
      err="$(tail -5 /tmp/fb.log | tr '\n' ' ')"
      fail "fluent-bit-nemakiware.conf" "fluent-bit --dry-run: $err"
    fi

    hdr "Phase 2.3 — filebeat test config (docker.elastic.co/beats/filebeat)"
    # Filebeat 8.x refuses configs not owned by the running user
    # ("must be owned by the user identifier (uid=0) or root").
    # The host-mounted file is owned by the host user, which the
    # container sees as a non-matching uid. Workaround: copy the
    # config into a writable tmpfs and chown to root inside the
    # container before running test config.
    if docker run --rm \
         -v "$TEMPLATE_DIR:/src:ro" \
         --tmpfs /work \
         --tmpfs /var/log/filebeat \
         --user root \
         "${SYNTH_ENV[@]}" \
         --entrypoint sh \
         docker.elastic.co/beats/filebeat:8.15.0 \
         -c 'cp /src/filebeat-nemakiware.yml /work/filebeat.yml \
             && chown root:root /work/filebeat.yml \
             && chmod 600 /work/filebeat.yml \
             && filebeat test config -c /work/filebeat.yml' \
         >/tmp/filebeat.log 2>&1
    then
      ok "filebeat-nemakiware.yml — filebeat test config passed"
    else
      err="$(tail -5 /tmp/filebeat.log | tr '\n' ' ')"
      fail "filebeat-nemakiware.yml" "filebeat test config: $err"
    fi

    hdr "Phase 2.4 — cortextool rules check (grafana/cortex-tools, backend=loki)"
    # cortextool / LogQL do NOT interpolate ${VAR} or ${VAR:-default}
    # in the rule expression body — they parse the literal string and
    # error "syntax error: unexpected IDENTIFIER" at the first `$`.
    # Operators are expected to envsubst at deploy time, so we do the
    # same here against SYNTH_ENV before handing the file to cortextool.
    #
    # GNU envsubst doesn't honour the `:-default` form, so we use a
    # Python one-liner that does — keeps the rule file deploy-portable
    # (operator can set just the vars they want overridden).
    RENDERED="$(mktemp -t loki-ruler-rendered.XXXXXX.yml)"
    if ! python3 - "$TEMPLATE_DIR/loki-ruler-rules.yml" <<'PYENVSUBST' > "$RENDERED" 2>/tmp/envsubst.log
import os, re, sys
content = open(sys.argv[1]).read()
def repl(m):
    var, default = m.group(1), m.group(2)
    return os.environ.get(var, default if default is not None else "")
sys.stdout.write(re.sub(r'\$\{([A-Z_][A-Z0-9_]*)(?::-([^}]*))?\}', repl, content))
PYENVSUBST
    then
      err="$(tail -3 /tmp/envsubst.log | tr '\n' ' ')"
      fail "loki-ruler-rules.yml" "envsubst preprocess failed: $err"
    else
      # Re-export SYNTH_ENV values into the current shell so the
      # python subst sees them (docker -e is for the *container*
      # env, not the host process running this preprocess).
      export SIEM_HOST=siem.example.local SIEM_PORT=3100 \
             SIEM_USERNAME=svc-nemakiware SIEM_PASSWORD=placeholder-password \
             BURST_THRESHOLD_PER_SEC=0.067 BURST_THRESHOLD=20 \
             LOST_COUNT_OUTLIER_THRESHOLD=50 OFF_HOURS_REGEX='^(0[0-5]|2[2-3])$' \
             NEW_ACTOR_LOOKBACK=7d
      python3 - "$TEMPLATE_DIR/loki-ruler-rules.yml" <<'PYENVSUBST' > "$RENDERED" 2>/tmp/envsubst.log
import os, re, sys
content = open(sys.argv[1]).read()
def repl(m):
    var, default = m.group(1), m.group(2)
    return os.environ.get(var, default if default is not None else "")
sys.stdout.write(re.sub(r'\$\{([A-Z_][A-Z0-9_]*)(?::-([^}]*))?\}', repl, content))
PYENVSUBST
      if docker run --rm \
           -v "$RENDERED:/cfg/loki-ruler-rules.yml:ro" \
           grafana/cortex-tools:latest \
           rules check --backend=loki --rule-files /cfg/loki-ruler-rules.yml \
           >/tmp/cortex.log 2>&1
      then
        ok "loki-ruler-rules.yml — cortextool rules check passed (backend=loki, envsubst'd)"
      else
        err="$(tail -5 /tmp/cortex.log | tr '\n' ' ')"
        fail "loki-ruler-rules.yml" "cortextool rules check (backend=loki): $err"
      fi
      rm -f "$RENDERED"
    fi
  fi

  hdr "Phase 2.5 — Manual gates (not run; operator-side)"
  skip "kibana-detection-rules.ndjson" "Elastic 8 cluster import (Security → Manage rules → Import) — operator gate"
  skip "splunk-savedsearches.conf"      "splunk btool savedsearches list — operator gate"
else
  hdr "Phase 2 — DOCKERIZED VALIDATORS (opt-in, set VALIDATE_DOCKER=1 to run)"
  skip "vector validate"        "VALIDATE_DOCKER unset"
  skip "fluent-bit --dry-run"   "VALIDATE_DOCKER unset"
  skip "filebeat test config"   "VALIDATE_DOCKER unset"
  skip "cortextool rules check" "VALIDATE_DOCKER unset"
  skip "kibana detection import" "operator-side (running Elastic cluster required)"
  skip "splunk btool"            "operator-side (Splunk installation required)"
fi

# ── Summary ───────────────────────────────────────────────────

printf '\n\033[1m========== SUMMARY ==========\033[0m\n'
printf '  PASS:  %d\n' "$PASS_COUNT"
printf '  SKIP:  %d\n' "$SKIP_COUNT"
printf '  FAIL:  %d\n' "$FAIL_COUNT"
printf '  total checks attempted: %d\n' "$((PASS_COUNT + SKIP_COUNT + FAIL_COUNT))"

# Optionally write the Markdown summary
if [ "${WRITE_VALIDATION_MD:-0}" = "1" ]; then
  VAL_MD="$TEMPLATE_DIR/VALIDATION.md"
  {
    echo "# SOC template validation — last automated run"
    echo
    echo "Generated by \`scripts/validate-soc-templates.sh\` —"
    echo "do NOT edit this file by hand; re-run the script to update."
    echo
    echo "**Run timestamp**: $(date -u '+%Y-%m-%d %H:%M:%S UTC')"
    echo "**Branch**: $(git -C "$REPO_ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)"
    echo "**Commit**: $(git -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"
    echo "**Docker phase ran**: $([ "${VALIDATE_DOCKER:-0}" = "1" ] && echo "yes" || echo "no (set VALIDATE_DOCKER=1 to enable)")"
    echo
    echo "## Summary"
    echo
    echo "- PASS: $PASS_COUNT"
    echo "- SKIP: $SKIP_COUNT"
    echo "- FAIL: $FAIL_COUNT"
    echo
    echo "## Detail"
    # Expand the \n placeholders that the bash buffer collected
    printf '%b\n' "$MD_BUF"
  } > "$VAL_MD"
  printf '\nWrote %s\n' "$VAL_MD"
fi

[ "$FAIL_COUNT" -gt 0 ] && exit 1
exit 0
