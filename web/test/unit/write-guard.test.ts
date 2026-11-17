import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import { describe, expect, test } from 'vitest';
import { EDITABLE_COLLECTIONS } from '../../src/edit/documents';

/*
 * Where and what the site may write (stage B; stage A's guard allowed no write at all). This fails the build when:
 *  - a Firestore write call appears anywhere but src/data/writes.ts;
 *  - writes.ts uses anything but a transaction's set (no hard delete, no update, no batch, no setDoc);
 *  - writes.ts could write anywhere but users/{uid}/shifts|bonuses|payouts, or names a jobs or users write;
 *  - anything but data/cloud.ts and data/writes.ts imports Firebase, or Firestore comes from anything but "lite".
 */

const SRC = resolve(__dirname, '../../src');
const WRITES = join('data', 'writes.ts');

/** Every write API of the Firestore SDKs (full and lite), and the transaction's own write methods. */
const WRITE_CALLS = [
  'setDoc', 'updateDoc', 'deleteDoc', 'addDoc', 'writeBatch', 'runTransaction', 'deleteField', 'serverTimestamp',
  'arrayUnion', 'arrayRemove', 'increment', 'executeWrite', 'WriteBatch', 'Transaction',
];
const TRANSACTION_WRITES = /\.(set|update|delete)\s*\(/g;

function files(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    return statSync(path).isDirectory() ? files(path) : /\.(ts|js|mjs)$/.test(name) ? [path] : [];
  });
}

const sources = files(SRC).map((path) => ({ path: relative(SRC, path), text: readFileSync(path, 'utf8') }));
const named = (text: string, name: string) => new RegExp(`\\b${name}\\b`).test(text);
/** Code without comments, so a comment mentioning a call doesn't count (and can't hide one). */
const code = (text: string) => text.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '');

describe('the single write path', () => {
  test('no Firestore write call outside src/data/writes.ts', () => {
    const found = sources
      .filter((s) => s.path !== WRITES)
      .flatMap(({ path, text }) => WRITE_CALLS.filter((name) => named(code(text), name)).map((name) => `${path}: ${name}`));
    expect(found).toEqual([]);
  });

  test('writes.ts writes only with a transaction’s set, merged, after reading the document in that transaction', () => {
    const text = code(sources.find((s) => s.path === WRITES)!.text);
    const used = WRITE_CALLS.filter((name) => named(text, name));
    expect(used.sort()).toEqual(['runTransaction', 'serverTimestamp']);
    expect([...text.matchAll(TRANSACTION_WRITES)].map((m) => m[1])).toEqual(['set']);
    expect(text).toMatch(/transaction\.get\(ref\)[\s\S]*transaction\.set\(ref, \{ \.\.\.result\.fields, serverUpdatedAt: serverTimestamp\(\) \}, \{ merge: true \}\)/);
    expect(text).toMatch(/if \(!EDITABLE_COLLECTIONS\.includes\(op\.collection\)\) throw/);
    expect(text).toMatch(/doc\(db, 'users', uid, op\.collection, op\.id\)/);
    expect(text).not.toMatch(/['"`]jobs['"`]/);
  });

  test('the writable collections are exactly shifts, bonuses and payouts', () => {
    expect([...EDITABLE_COLLECTIONS]).toEqual(['shifts', 'bonuses', 'payouts']);
  });

  test('only data/cloud.ts and data/writes.ts import Firebase; Firestore only as "lite"', () => {
    const importers = sources.filter(({ text }) => /from ['"]firebase\//.test(text) || /import\(['"]firebase/.test(text)).map((s) => s.path).sort();
    expect(importers).toEqual([join('data', 'cloud.ts'), WRITES].sort());
    const modules = new Set(sources.flatMap(({ text }) => [...text.matchAll(/from ['"](firebase\/[^'"]+)['"]/g)].map((m) => m[1])));
    expect(modules).toEqual(new Set(['firebase/app', 'firebase/auth', 'firebase/firestore/lite']));
  });

  test('the guard sees what it must refuse', () => {
    expect(WRITE_CALLS.some((name) => named("import { setDoc } from 'firebase/firestore/lite';", name))).toBe(true);
    expect([..."transaction.delete(ref); transaction.update(ref, {})".matchAll(TRANSACTION_WRITES)].map((m) => m[1])).toEqual(['delete', 'update']);
    expect(code('// setDoc\nconst x = 1;')).not.toMatch(/setDoc/);
  });
});
