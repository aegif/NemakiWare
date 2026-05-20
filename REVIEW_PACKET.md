# NemakiWare v3.1.1-RC5.4 — External Review Packet

Single entry point for the external review of the RC5 cycle. This
file lives on `release/3.1.1-RC5.4` branch HEAD and is **NOT
included in the `v3.1.1-RC5.4` tag**. It exists to disambiguate:

- **Code artifact under review** = the annotated tag
  `v3.1.1-RC5.4` (peeled commit `014939eeb`).
- **Review supplementary documentation** = files on
  `release/3.1.1-RC5.4` **branch HEAD** that landed after the tag
  was cut. None of these change the reviewed code; they only
  clarify framing for reviewers.

If you are reviewing **only what will ship**, check out the tag and
ignore anything in this file. If you want the up-to-date review
narrative, read this file at branch HEAD.

---

## 1. Quick reference

| Item | Value |
|---|---|
| Tag | `v3.1.1-RC5.4` |
| Tag annotated object SHA | `d0a4a4f3d0f40482b0ca45cae47f75305235588b` |
| Tag peeled commit | `014939eeb0a653ef18a9a5719ca8258a0f38c305` |
| Branch | `release/3.1.1-RC5.4` |
| Branch HEAD | latest commit on this branch (see `git log`) |
| Base of RC5 cycle | `v3.1.1-RC4.1` (`572aad18b`) |
| Cumulative diff cmd | `git diff v3.1.1-RC4.1..v3.1.1-RC5.4` |

The tag and the branch HEAD will diverge as post-tag review
corrections land. The divergence is intentional and limited to
files in section 3 below.

---

## 2. What's in the tag (reviewed code artifact)

The `v3.1.1-RC5.4` tag captures the state at commit `014939eeb`,
which itself wraps the feature commit `6283afc96`
(`feat(rc5.4): R3 + R4 closure review code corrections`) plus a
pre-tag doc closure (status flip from 進行中 → shipped).

Cumulative from `v3.1.1-RC4.1`:

- **Scheduled delegated profiles** (RC5 §12.1) — opt-in per-tick
  governance for non-admin profile creators. Default-off.
- **Connector governance view** (RC5 §12.3) +
  **simulate-remove endpoint** (RC5.3 W2) — admin-only audit and
  what-if tooling.
- **Auto-disabled triage UI** (RC5 V1-V4) — markers + filter +
  banner + custom-N-days window.
- **Server-side auto-disabled profile filtering** (RC5.3 W1) —
  `autoDisabledSince` query param on `GET /v1/admin/import-profiles`
  pushes V6's client-side "last N days" window to the server for
  large profile lists.
- **RC5.4 closure code corrections** — R3 explicit audit button +
  R4 strict 400 on malformed `autoDisabledSince`.

For the full narrative see `RELEASE_NOTES.md` (5 sections RC5 →
RC5.4) and `docs/design/connector-delegation.md` §12.1 - §12.13.

---

## 3. What's on the branch HEAD but NOT in the tag

Files allowed to differ between tag and branch HEAD during external
review. Code reviewers can confirm there is no functional drift by
restricting their diff to these files when comparing tag vs branch:

- `REVIEW_PACKET.md` (this file — does not exist in the tag)
- `RELEASE_NOTES.md` — post-tag clarifying additions (no shipped
  behaviour changes, only review-time exposition)
- `CLAUDE.md` — post-tag clarifying additions (project-internal
  navigation parity with `RELEASE_NOTES.md`)
- `core/src/main/webapp/ui/src/services/externalIngest.ts` —
  JSDoc comment correction for the RC5.4 `autoDisabledSince` 400
  behaviour (no runtime change)

All other files MUST be byte-equal between the tag and the branch
HEAD. If you observe drift in any file outside this list, that is
a bug — please flag it.

---

## 4. Acceptance status summary

- **Blocking findings**: 0
- **Unit tests**: 155 / 155 ingest delegation tests PASS
- **Live verification**: scheduled delegated default-off, governance
  V3 admin/non-admin/anonymous gating, W2 audit pipeline, W1 since
  filter (with R4 strict 400), R3 explicit audit button all PASS
- **API contract**: additive only, no breaking changes for callers
  using valid input (a single optional query param became strict on
  malformed non-empty values in R4)
- **Patch / view / Mango index / migration / DB bootstrap**:
  unchanged since RC4.1 (cumulative zero diff in those areas)

---

## 5. Remaining follow-ups (post-RC5.4, not blocking review)

These are explicitly NOT blockers. Listed so the external reviewer
knows we know about them.

- **R1**: SOC tooling integration for the new
  `EXTERNAL_GOVERNANCE_SIMULATE` audit event — ops scope, lives
  outside this repository.
- **R5**: `IngestSchedulerService.pollScheduledProfiles` re-runs
  `resolveFolderId(...)` when re-checking connector delegation; if
  the second call returns null the resulting audit `denialReason` is
  `CONNECTOR_NOT_DELEGATED` instead of the more accurate
  `TARGET_FOLDER_UNRESOLVABLE`. Microsecond-window race, safety
  property preserved, denial reason is mislabeled in that edge case.

R2, R3, R4 are shipped (see `RELEASE_NOTES.md` follow-up table).

---

## 6. Promotion path (operational)

`v3.1.1-RC5.4` is and remains a release candidate. The GA path
is:

1. External review concludes with approval.
2. Merge `release/3.1.1-RC5.4` into `master`.
3. Cut a **new** annotated tag `v3.1.1` against the merge commit
   on `master` — never relabel an RC tag.
4. Optionally create a single GitHub Release attached to `v3.1.1`.
5. The RC tags (`v3.1.1-RC5`, `.1`, `.2`, `.3`, `.4`) stay as
   internal milestones; they keep the cycle's audit trail intact.

---

## 7. Files external reviewers usually start with

| Purpose | File |
|---|---|
| What changed and why (per RC) | `RELEASE_NOTES.md` |
| Design rationale | `docs/design/connector-delegation.md` |
| Multi-replica operational notes | `docs/MULTI-REPLICA-DEPLOYMENT.md` |
| Project-internal navigation | `CLAUDE.md` (Japanese) |
| API entry points | `ConnectorDefinitionController.java`, `ImportProfileDefinitionController.java`, `IngestSchedulerService.java` |
| Test coverage proof | `core/src/test/java/jp/aegif/nemaki/rest/ingest/*Test.java` (155 tests) |
