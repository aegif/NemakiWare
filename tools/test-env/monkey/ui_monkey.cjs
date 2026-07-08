/**
 * UI モンキーテスター (探索的バグあぶり出し用)。
 *
 * ランダムなユーザでログインし、ナビゲーション/検索/タブ切替/ツリー展開/
 * モーダル開閉/ページングを乱択で叩き、以下を捕捉する:
 *   - console.error / pageerror (React 再構成クラッシュ等)
 *   - ErrorBoundary の「エラーが発生しました」表示
 *   - 想定外の HTTP 応答 (5xx は常に、4xx は 401/403/404 以外)
 *
 * 破壊的操作 (削除確定・危険赤ボタン・ACL変更の実行) は避ける read-heavy 方針。
 *
 * 使い方:
 *   node ui_monkey.cjs <baseUrl> [sessions] [actionsPerSession] [seed]
 *   例: node ui_monkey.cjs http://localhost:8080 12 40 1
 */
"use strict";
const path = require("path");
const fs = require("fs");
const { chromium } = require(path.resolve(__dirname, "../../../core/src/main/webapp/ui/node_modules/playwright"));

const BASE = process.argv[2] || "http://localhost:8080";
const SESSIONS = parseInt(process.argv[3] || "12", 10);
const ACTIONS = parseInt(process.argv[4] || "40", 10);
let SEED = parseInt(process.argv[5] || "1", 10);

// 決定論的乱数 (mulberry32)
function rng() { SEED |= 0; SEED = (SEED + 0x6D2B79F5) | 0; let t = Math.imul(SEED ^ (SEED >>> 15), 1 | SEED);
  t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t; return ((t ^ (t >>> 14)) >>> 0) / 4294967296; }
const pick = arr => arr[Math.floor(rng() * arr.length)];
const chance = p => rng() < p;

const USERS = ["admin", "shimizu", "miyata", "otsuka", "asada", "kudo", "okamoto", "baba", "hirata", "nagai"];
const PW = u => (u === "admin" ? "admin" : "Pass1234");

// 想定内で無視する 4xx (認可・不存在・CSRF・レート)
const IGNORE_STATUS = new Set([401, 403, 404, 429]);
// 無視するコンソールノイズ (本質でないもの)
const IGNORE_CONSOLE = [
  /favicon/i, /Download the React DevTools/i, /React Router Future Flag/i,
  /\[antd:/i, /findDOMNode is deprecated/i, /Support for defaultProps/i,
  /ResizeObserver loop/i,
];

const findings = [];
const LIVE_OUT = path.join(__dirname, "monkey-live-" + BASE.replace(/[^a-z0-9]/gi, "_") + ".jsonl");
try { fs.writeFileSync(LIVE_OUT, ""); } catch (_) {}
function record(kind, detail, ctx) {
  const f = { kind, detail: String(detail).slice(0, 400), ctx };
  findings.push(f);
  // 逐次保存 + 即時表示 (途中で落ちても失わない)
  try { fs.appendFileSync(LIVE_OUT, JSON.stringify(f) + "\n"); } catch (_) {}
  console.log(`    ! [${kind}] (${ctx.user}) ${f.detail.slice(0, 160)}`);
}
process.on("unhandledRejection", e => record("unhandledRejection", (e && e.message) || String(e), { user: "?", label: "?" }));

async function safeLogin(page, ctx, user) {
  await page.goto(BASE + "/core/ui/index.html", { waitUntil: "domcontentloaded" });
  const u = page.locator('input[placeholder*="ユーザー"], input[placeholder*="User"], input[name="username"]').first();
  await u.waitFor({ timeout: 20000 });
  await u.fill(user);
  await page.locator('input[type="password"]').first().fill(PW(user));
  await page.getByRole("button", { name: /ログイン|Login/ }).first().click();
  await Promise.race([
    page.waitForSelector(".ant-layout, .ant-table, .ant-menu", { timeout: 25000 }).catch(() => {}),
    page.waitForSelector("text=エラーが発生しました", { timeout: 25000 }).catch(() => {}),
  ]);
  await page.waitForTimeout(800);
}

async function errorBoundaryVisible(page) {
  return (await page.locator("text=エラーが発生しました").count().catch(() => 0)) > 0;
}

// 破壊的なコントロールを除外して安全にクリックできる要素を集める
async function clickableTargets(page) {
  // 左メニュー項目 / タブ / ツリーノード / ページャ / 一般ボタン(危険色以外)
  return {
    menu: page.locator(".ant-menu-item"),
    tabs: page.locator(".ant-tabs-tab"),
    tree: page.locator(".ant-tree-node-content-wrapper"),
    treeSwitch: page.locator(".ant-tree-switcher:not(.ant-tree-switcher-noop)"),
    pager: page.locator(".ant-pagination-item, .ant-pagination-next, .ant-pagination-prev"),
    rowLinks: page.locator(".ant-table a, .ant-table .ant-btn-link"),
    // 「開く」系の非危険ボタン (作成/インポート/エクスポート/検索/表示切替/権限管理を開く)
    safeButtons: page.locator('.ant-btn:not(.ant-btn-dangerous):not([disabled])'),
  };
}

async function clickRandom(loc, page) {
  const n = await loc.count().catch(() => 0);
  if (!n) return false;
  const i = Math.floor(rng() * n);
  const el = loc.nth(i);
  // ラベルで危険操作を除外
  const txt = (await el.innerText().catch(() => "")) || "";
  if (/削除|delete|破棄|完全|リセット|reset|ログアウト|logout/i.test(txt)) return false;
  await el.click({ timeout: 2500 }).catch(() => {});
  return true;
}

async function dismissModals(page) {
  // モーダルが開いていたら Escape + キャンセルで閉じる (submit しない)
  const modal = await page.locator(".ant-modal-wrap:visible, .ant-modal:visible").count().catch(() => 0);
  if (modal) {
    await page.keyboard.press("Escape").catch(() => {});
    await page.waitForTimeout(150);
    const cancel = page.getByRole("button", { name: /キャンセル|Cancel|閉じる|Close/ }).first();
    if (await cancel.count().catch(() => 0)) await cancel.click({ timeout: 1500 }).catch(() => {});
    // Popconfirm が出ていたら「いいえ」相当 or Escape
    await page.keyboard.press("Escape").catch(() => {});
  }
}

async function doSearch(page) {
  const box = page.locator('input[placeholder*="検索"], input[placeholder*="Search"]').first();
  if (await box.count().catch(() => 0)) {
    const terms = ["賞与", "障害", "Aurora", "契約", "'", "\"", "* OR *", "設計", "  ", "𠮷野家"];
    await box.fill(pick(terms)).catch(() => {});
    const go = page.getByRole("button", { name: /検索|Search/ }).first();
    if (await go.count().catch(() => 0)) await go.click({ timeout: 2000 }).catch(() => {});
    await page.waitForTimeout(600);
  }
}

async function session(browser, user, label) {
  const ctx = await browser.newContext({ viewport: { width: 1360, height: 820 }, locale: "ja-JP", ignoreHTTPSErrors: true });
  const page = await ctx.newPage();
  const seen = new Set();
  page.on("console", m => {
    if (m.type() !== "error") return;
    const t = m.text();
    if (IGNORE_CONSOLE.some(re => re.test(t))) return;
    const key = t.slice(0, 120);
    if (seen.has("c:" + key)) return; seen.add("c:" + key);
    record("console.error", t, { user, label });
  });
  page.on("pageerror", e => {
    const key = String(e.message).slice(0, 120);
    if (seen.has("p:" + key)) return; seen.add("p:" + key);
    record("pageerror", (e.message || "") + " :: " + String(e.stack || "").split("\n")[1], { user, label });
  });
  page.on("response", r => {
    const s = r.status();
    if (s < 400 || IGNORE_STATUS.has(s)) return;
    const url = r.url();
    if (!url.includes("/core/")) return;
    const key = s + ":" + url.replace(/\?.*/, "").slice(-80);
    if (seen.has("h:" + key)) return; seen.add("h:" + key);
    record("http" + s, r.request().method() + " " + url.replace(/\?.*/, ""), { user, label });
  });

  try {
    await safeLogin(page, ctx, user);
    if (await errorBoundaryVisible(page)) record("error-boundary", "on login", { user, label });

    for (let a = 0; a < ACTIONS; a++) {
      const t = await clickableTargets(page);
      const action = pick([
        "menu", "tab", "tree", "treeSwitch", "pager", "rowLink", "safeBtn",
        "search", "back", "reloadFolder",
      ]);
      try {
        if (action === "menu") await clickRandom(t.menu, page);
        else if (action === "tab") await clickRandom(t.tabs, page);
        else if (action === "tree") await clickRandom(t.tree, page);
        else if (action === "treeSwitch") await clickRandom(t.treeSwitch, page);
        else if (action === "pager") await clickRandom(t.pager, page);
        else if (action === "rowLink") await clickRandom(t.rowLinks, page);
        else if (action === "safeBtn") { await clickRandom(t.safeButtons, page); await dismissModals(page); }
        else if (action === "search") await doSearch(page);
        else if (action === "back") { if (chance(0.5)) await page.goBack({ timeout: 3000 }).catch(() => {}); }
        else if (action === "reloadFolder") { if (chance(0.15)) await page.reload({ timeout: 8000 }).catch(() => {}); }
      } catch (_) { /* click flake ignored */ }
      await page.waitForTimeout(120 + Math.floor(rng() * 260));

      if (await errorBoundaryVisible(page)) {
        record("error-boundary", `after action#${a}=${action}`, { user, label });
        // 再読み込みして続行
        await page.goto(BASE + "/core/ui/index.html", { waitUntil: "domcontentloaded" }).catch(() => {});
        await page.waitForTimeout(800);
      }
    }
  } catch (e) {
    record("harness", e.message, { user, label });
  } finally {
    await ctx.close();
  }
}

(async () => {
  const browser = await chromium.launch();
  console.log(`monkey: ${BASE}  sessions=${SESSIONS} actions=${ACTIONS} seed=${SEED}`);
  for (let s = 0; s < SESSIONS; s++) {
    const user = s === 0 ? "admin" : pick(USERS);
    process.stdout.write(`  session ${s + 1}/${SESSIONS} as ${user} ... `);
    const before = findings.length;
    await session(browser, user, `s${s + 1}`);
    console.log(`${findings.length - before} new finding(s)`);
  }
  await browser.close();

  // 集計
  const byKind = {};
  for (const f of findings) byKind[f.kind] = (byKind[f.kind] || 0) + 1;
  console.log("\n=== SUMMARY ===");
  console.log("total findings:", findings.length, JSON.stringify(byKind));
  // ユニーク detail でまとめて表示
  const uniq = new Map();
  for (const f of findings) {
    const k = f.kind + " | " + f.detail;
    if (!uniq.has(k)) uniq.set(k, { ...f, count: 0, users: new Set() });
    const u = uniq.get(k); u.count++; u.users.add(f.ctx.user);
  }
  console.log("\n=== UNIQUE (" + uniq.size + ") ===");
  for (const [, u] of uniq) {
    console.log(`\n[${u.kind}] x${u.count} users={${[...u.users].join(",")}}`);
    console.log("  " + u.detail);
  }
  const out = path.join(__dirname, "monkey-findings-" + BASE.replace(/[^a-z0-9]/gi, "_") + ".json");
  fs.writeFileSync(out, JSON.stringify([...uniq.values()].map(u => ({ ...u, users: [...u.users] })), null, 1));
  console.log("\nwrote", out);
})().catch(e => { console.error(e); process.exit(1); });
