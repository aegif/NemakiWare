import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import * as ts from 'typescript';

/**
 * The locale files and the code agree (UI i18n batch 3). The test reads files only — no browser.
 * Under the main Playwright config the global setup still needs the backend; playwright.lint.config.ts
 * runs this file alone without one, which is how the CI job ui-unit runs it beside vitest.
 *
 * - Every key the code names is a text in both ja.json and en.json: the literal first argument
 *   of t() / i18n.t(), and a dotted string that starts with a locale namespace anywhere else
 *   (labelKey, a table of label keys, a message key handed to a helper) — except the context
 *   label given to parseJsonResponseBody, which is not a key.
 * - No key is built from a template (`prefix.${x}`), in t() or anywhere a namespaced template
 *   starts, nor from a namespaced prefix ending in a dot (`'prefix.' + x`): the values such a key
 *   takes cannot be checked, so the code names each key literally (a table per value) and the
 *   first rule covers it. Other ways to assemble a key (`[ns, x].join('.')`, an alias of t) are
 *   not looked for; the code has none.
 * - ja.json and en.json have the same keys, and each key has the same {{…}} variables in both.
 * - A count-dependent text is a family of i18next plural forms (`key_one`, `key_other`, …) in place
 *   of the plain key. Each locale has exactly the forms its language's plural rules use
 *   (Intl.PluralRules: Japanese `other`, English `one` and `other`), every form has the same
 *   {{…}} variables, and the code names the family by its plain key in a t() call that passes
 *   `count` as a number — not in a label table, where no count is given. i18next selects no form
 *   for a missing count or a string one (`n.toLocaleString()`) and shows the key, and gives null or
 *   false the form of 0 and true the form of 1, a wrong sentence without notice. So the count must
 *   be a number to the TypeScript checker, read over tsconfig.json's program.
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
const PLURAL_SUFFIX = /_(zero|one|two|few|many|other)$/;
const variables = (s: string) => [...s.matchAll(/{{\s*([^}\s,]+)[^}]*}}/g)].map((m) => m[1]).sort().join(',');

// The program tsconfig.json describes (src), for the types the checker gives each count.
function typedProgram(): ts.Program {
  const config = ts.getParsedCommandLineOfConfigFile(path.join(UI, 'tsconfig.json'), {}, {
    ...ts.sys,
    onUnRecoverableConfigFileDiagnostic: (d) => {
      throw new Error(ts.flattenDiagnosticMessageText(d.messageText, '\n'));
    },
  });
  if (!config) throw new Error('tsconfig.json could not be read');
  return ts.createProgram(config.fileNames, config.options);
}
// A number to the checker: assignable to number — so 0 | 1, a numeric enum, number & Brand and a
// T extends number pass — and not any or never, which are assignable to it without being one.
// Only the type the checker gives the count at the call is read: an `as number` passes, and so does
// an any or a never that a generic type reaches where the checker leaves it unresolved at the call
// (a deferred conditional type with such a branch, a T extends Record<string, any> indexed by a key
// typed from a type parameter, a T extends never). Recorded as R142, not chased.
const isNumber = (checker: ts.TypeChecker, type: ts.Type): boolean =>
  (type.flags & (ts.TypeFlags.Any | ts.TypeFlags.Never)) === 0
  && checker.isTypeAssignableTo(type, checker.getNumberType());

test('every key the code names is in ja.json and en.json, and the two files agree', () => {
  test.setTimeout(120 * 1000); // the checker reads the whole program
  const program = typedProgram();
  const checker = program.getTypeChecker();
  const flat = { ja: flatten(locales.ja), en: flatten(locales.en) };
  const namespaces = new Set(Object.keys(locales.ja));
  const problems: string[] = [];
  let named = 0;

  for (const file of sourceFiles(path.join(UI, 'src'))) {
    const rel = path.relative(UI, file);
    const sf = program.getSourceFile(file);
    if (!sf) {
      problems.push(`${rel} is not in tsconfig.json's program`);
      continue;
    }
    const where = (n: ts.Node) => `${rel}:${sf.getLineAndCharacterOfPosition(n.getStart(sf)).line + 1}`;
    // What is wrong with the count a t() call passes for a plural family, if anything. Only a
    // number selects the form it means (the top of this file says what a missing count, a string,
    // null and a boolean do instead).
    const countProblem = (call: ts.CallExpression): string | undefined => {
      const options = call.arguments[1];
      const count = options && ts.isObjectLiteralExpression(options)
        ? options.properties.find((p): p is ts.PropertyAssignment | ts.ShorthandPropertyAssignment =>
          (ts.isPropertyAssignment(p) || ts.isShorthandPropertyAssignment(p)) && p.name.getText(sf) === 'count')
        : undefined;
      if (!count) return 'the t() call must pass count';
      const type = checker.getTypeAtLocation(ts.isPropertyAssignment(count) ? count.initializer : count.name);
      return isNumber(checker, type) ? undefined : `count must be a number, not ${checker.typeToString(type)}`;
    };
    const requireKey = (key: string, n: ts.Node, call?: ts.CallExpression) => {
      named++;
      for (const lang of ['ja', 'en'] as const) {
        if (!flat[lang].has(key) && flat[lang].get(`${key}_other`) != null) {
          const problem = call ? countProblem(call) : 'name it in a t() call that passes count';
          if (problem) problems.push(`${where(n)} ${key} has plural forms in ${lang}.json; ${problem}`);
        } else if (!flat[lang].has(key)) problems.push(`${where(n)} ${key} is not in ${lang}.json`);
        else if (flat[lang].get(key) === null) problems.push(`${where(n)} ${key} is an object in ${lang}.json, not a text`);
      }
    };
    const startsWithNamespace = (text: string) => /^[a-zA-Z][a-zA-Z0-9]*\./.test(text) && namespaces.has(text.split('.')[0]);
    const visit = (n: ts.Node): void => {
      if (ts.isCallExpression(n) && isTCall(n.expression.getText(sf)) && n.arguments[0]) {
        const a0 = n.arguments[0];
        if (ts.isStringLiteral(a0) || ts.isNoSubstitutionTemplateLiteral(a0)) {
          requireKey(a0.text, a0, n);
        } else if (ts.isTemplateExpression(a0)) {
          problems.push(`${where(a0)} t() key built from a template — name each key literally`);
        }
      } else if (ts.isTemplateExpression(n) && startsWithNamespace(n.head.text)) {
        problems.push(`${where(n)} key built from a template (${n.head.text}…) — name each key literally`);
      } else if ((ts.isStringLiteral(n) || ts.isNoSubstitutionTemplateLiteral(n))
          && /^[a-zA-Z][a-zA-Z0-9]*(\.[a-zA-Z0-9_]+)*\.$/.test(n.text) && startsWithNamespace(n.text)) {
        problems.push(`${where(n)} key prefix ${n.text} — a key built by concatenation; name each key literally`);
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

  // Plural families: the plain key with its forms. A form one language has and the other does
  // not is the plural rules differing, not a missing key — the forms are checked here instead.
  const families = new Set<string>();
  for (const lang of ['ja', 'en'] as const) {
    for (const key of flat[lang].keys()) {
      if (PLURAL_SUFFIX.test(key) && flat[lang].get(key) !== null) families.add(key.replace(PLURAL_SUFFIX, ''));
    }
  }
  for (const base of families) {
    const reference = flat.en.get(`${base}_other`) ?? flat.ja.get(`${base}_other`) ?? '';
    for (const lang of ['ja', 'en'] as const) {
      const needs = [...new Intl.PluralRules(lang).resolvedOptions().pluralCategories].sort();
      const has = [...flat[lang].keys()].filter((k) => k.startsWith(`${base}_`) && k.slice(base.length + 1).match(/^(zero|one|two|few|many|other)$/))
        .map((k) => k.slice(base.length + 1)).sort();
      if (has.join(',') !== needs.join(',')) {
        problems.push(`${base} has the forms [${has}] in ${lang}.json but ${lang} uses [${needs}]`);
      }
      if (flat[lang].has(base)) problems.push(`${base} is both a plain key and a plural family in ${lang}.json`);
      for (const form of has) {
        const text = flat[lang].get(`${base}_${form}`) ?? '';
        if (variables(text) !== variables(reference)) {
          problems.push(`${base}_${form} has {{${variables(text)}}} in ${lang}.json but the family has {{${variables(reference)}}}`);
        }
      }
    }
  }
  const isForm = (key: string) => PLURAL_SUFFIX.test(key) && families.has(key.replace(PLURAL_SUFFIX, ''));
  for (const [a, b] of [['ja', 'en'], ['en', 'ja']] as const) {
    for (const key of flat[a].keys()) {
      if (!flat[b].has(key) && !isForm(key)) problems.push(`${key} is in ${a}.json but not in ${b}.json`);
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
