-- Fluent Bit Lua filter — operator-local hour / day enrichment
-- for NemakiWare audit events.
--
-- Wired from fluent-bit-nemakiware.conf via:
--   [FILTER]
--     Name   lua
--     Match  nemakiware.audit
--     Script fluent-bit-nemakiware-time-enrichment.lua
--     Call   add_time_enrichment
--
-- Added in RC6.4 — the previous inline `Code |` heredoc form
-- failed `fluent-bit --dry-run` with an "extra indentation level
-- found" parse error. Externalising the Lua to its own file is
-- the documented multi-line approach for Fluent Bit filters and
-- avoids the classic INI parser's whitespace-sensitivity.
--
-- For non-UTC ops, set TZ env on the Fluent Bit process
-- (e.g. systemd `Environment=TZ=Asia/Tokyo`) — Lua's
-- os.date(format, epoch) honours TZ env.

function add_time_enrichment(tag, ts, record)
  local audit_ts = record["timestamp"] or record["@timestamp"]
  if audit_ts == nil then return 0, ts, record end

  -- audit_ts is ISO-8601 like 2026-05-22T08:01:30Z (UTC).
  local y, mo, d, h, mi, s = string.match(audit_ts,
    "(%d+)-(%d+)-(%d+)T(%d+):(%d+):(%d+)")
  if y == nil then return 0, ts, record end

  -- Lua's os.time(t) interprets the table as LOCAL time, not
  -- UTC. We want the UTC epoch of the audit moment. Approach:
  --   1. naive = os.time(UTC components treated as local)
  --   2. The TZ offset AT THAT MOMENT may differ from now
  --      (DST). Compute it per-record by checking what naive's
  --      local breakdown vs UTC breakdown says, then ADD to get
  --      the true UTC epoch.
  --   3. Re-validate after the shift (a DST-boundary audit may
  --      need a one-step correction).
  --
  -- For TZs WITHOUT DST (UTC, JST, KST, AEST, ...) the
  -- "now-based" shortcut would suffice; the per-record compute
  -- below is safe for either.
  local function utc_offset_at(epoch)
    return os.difftime(epoch, os.time(os.date("!*t", epoch)))
  end
  local naive = os.time({
    year=tonumber(y), month=tonumber(mo), day=tonumber(d),
    hour=tonumber(h), min=tonumber(mi), sec=tonumber(s)})
  local offset = utc_offset_at(naive)
  local true_utc_epoch = naive + offset
  -- Re-check: a DST-spring-forward audit timestamp may need
  -- one iteration to settle. If the offset at the new epoch
  -- differs, snap to that.
  local offset2 = utc_offset_at(true_utc_epoch)
  if offset2 ~= offset then
    true_utc_epoch = naive + offset2
  end
  record["hour_of_day_local"] = tonumber(os.date("%H", true_utc_epoch))
  record["day_of_week_local"] = os.date("%A", true_utc_epoch)
  return 1, ts, record
end
