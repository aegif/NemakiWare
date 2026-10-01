import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import * as ts from 'typescript';

/**
 * The locale files and the code agree (UI i18n batch 3). The test reads files only — no browser —
 * though the Playwright run around it still needs the backend (the global setup checks it).
 *
 * - Every key the code names is a text in both ja.json and en.json: the literal first argument
 *   of t() / i18n.t(), and a dotted string that starts with a locale namespace anywhere else
 *   (labelKey, a table of label keys, a message key handed to a helper) — except the context
 *   label given to parseJsonResponseBody, which is not a key.
 * - No key is built from a template (`prefix.${x}`), in t() or anywhere a namespaced template
 *   starts: the values such a key takes cannot be checked, so the code names each key literally
 *   (a table per value) and the first rule covers it.
 * - ja.json and en.json have the same keys, and each key has the same {{…}} variables in both.
 *
 * A missing key renders as the key itself (i18next's fallback), so this measures what a screen
 * would show without opening it.
 */
const UI = path.resolve(__dirname, '..', '..');
const locales = {
  ja: JSON.parse(fs.readFileSync(path.join(UI, 'src/i18n/locales/ja.json'), 'utf8')),
  en: JSON.parse(fs.readFileSync(path.join(UI, 'src/i18n/locales/en.json'), 'utf8')),
};

type Flat = Map<string, string | null>; // null marks an object node
function flatten(node: Record<string, unknown>, prefix = '', out: Flat = new Map()): Flat {
  for (const [k, v] of Object.entries(node)) {
    const key = prefix ? `${prefix}.${k}` : k;
    if (v && typeof v === 'object') {
      out.set(key, null);
      flatten(v as Record<string, unknown>, key, out);
    } else {
      out.set(key, String(v));
    }
  }
  return out;
}

function sourceFiles(dir: string, out: string[] = []): string[] {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) sourceFiles(p, out);
    else if (/\.tsx?$/.test(e.name) && !/\.d\.ts$/.test(e.name) && !/\.(test|spec)\.tsx?$/.test(e.name)) out.push(p);
  }
  return out;
}

const isTCall = (callee: string) => /(^|\.)t$/.test(callee);
const variables = (s: string) => [...s.matchAll(/{{\s*([^}\s,]+)[^}]*}}/g)].map((m) => m[1]).sort().join(',');

test('every key the code names is in ja.json and en.json, and the two files agree', () => {
  const flat = { ja: flatten(locales.ja), en: flatten(locales.en) };
  const namespaces = new Set(Object.keys(locales.ja));
  const problems: string[] = [];
  let named = 0;

  for (const file of sourceFiles(path.join(UI, 'src'))) {
    const rel = path.relative(UI, file);
    const sf = ts.createSourceFile(file, fs.readFileSync(file, 'utf8'), ts.ScriptTarget.Latest, true,
      file.endsWith('.tsx') ? ts.ScriptKind.TSX : ts.ScriptKind.TS);
    const where = (n: ts.Node) => `${rel}:${sf.getLineAndCharacterOfPosition(n.getStart(sf)).line + 1}`;
    const requireKey = (key: string, n: ts.Node) => {
      named++;
      for (const lang of ['ja', 'en'] as const) {
        if (!flat[lang].has(key)) problems.push(`${where(n)} ${key} is not in ${lang}.json`);
        else if (flat[lang].get(key) === null) problems.push(`${where(n)} ${key} is an object in ${lang}.json, not a text`);
      }
    };
    const startsWithNamespace = (text: string) => /^[a-zA-Z][a-zA-Z0-9]*\./.test(text) && namespaces.has(text.split('.')[0]);
    const visit = (n: ts.Node): void => {
      if (ts.isCallExpression(n) && isTCall(n.expression.getText(sf)) && n.arguments[0]) {
        const a0 = n.arguments[0];
        if (ts.isStringLiteral(a0) || ts.isNoSubstitutionTemplateLiteral(a0)) {
          requireKey(a0.text, a0);
        } else if (ts.isTemplateExpression(a0)) {
          problems.push(`${where(a0)} t() key built from a template — name each key literally`);
        }
      } else if (ts.isTemplateExpression(n) && startsWithNamespace(n.head.text)) {
        problems.push(`${where(n)} key built from a template (${n.head.text}…) — name each key literally`);
      } else if ((ts.isStringLiteral(n) || ts.isNoSubstitutionTemplateLiteral(n))
          && /^[a-zA-Z][a-zA-Z0-9]*(\.[a-zA-Z0-9_]+)+$/.test(n.text) && namespaces.has(n.text.split('.')[0])) {
        const parent = n.parent;
        const isTArgument = ts.isCallExpression(parent) && isTCall(parent.expression.getText(sf));
        const isContextLabel = ts.isCallExpression(parent) && parent.expression.getText(sf) === 'parseJsonResponseBody';
        if (!isTArgument && !isContextLabel) requireKey(n.text, n);
      }
      ts.forEachChild(n, visit);
    };
    visit(sf);
  }

  for (const [a, b] of [['ja', 'en'], ['en', 'ja']] as const) {
    for (const key of flat[a].keys()) {
      if (!flat[b].has(key)) problems.push(`${key} is in ${a}.json but not in ${b}.json`);
    }
  }
  for (const [key, ja] of flat.ja) {
    const en = flat.en.get(key);
    if (ja !== null && en != null && variables(ja) !== variables(en)) {
      problems.push(`${key} has {{${variables(ja)}}} in ja.json but {{${variables(en)}}} in en.json`);
    }
  }

  // The walk found the keys at all (a broken walk would pass with no problems).
  expect(named).toBeGreaterThan(2000);
  expect(problems).toEqual([]);
});
