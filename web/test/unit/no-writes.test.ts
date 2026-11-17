import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import { describe, expect, test } from 'vitest';

/*
 * Stage A is read-only: the site must never write to Firestore. This fails the build if any write API of the
 * Firestore SDK (full or lite) is named anywhere in web/src, or if anything but the read-only data module imports
 * Firebase at all.
 */

const SRC = resolve(__dirname, '../../src');
const WRITE_CALLS = [
  'setDoc', 'updateDoc', 'deleteDoc', 'addDoc', 'writeBatch', 'runTransaction', 'deleteField', 'serverTimestamp',
  'arrayUnion', 'arrayRemove', 'increment', 'executeWrite', 'WriteBatch', 'Transaction',
];

function files(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    return statSync(path).isDirectory() ? files(path) : /\.(ts|js|mjs)$/.test(name) ? [path] : [];
  });
}

describe('no write path', () => {
  const sources = files(SRC).map((path) => ({ path: relative(SRC, path), text: readFileSync(path, 'utf8') }));

  test('no Firestore write call is named in the site’s code', () => {
    const found = sources.flatMap(({ path, text }) =>
      WRITE_CALLS.filter((name) => new RegExp(`\\b${name}\\b`).test(text)).map((name) => `${path}: ${name}`),
    );
    expect(found).toEqual([]);
  });

  test('only data/cloud.ts imports Firebase, and from Firestore only its read-only "lite" build', () => {
    const importers = sources.filter(({ text }) => /from ['"]firebase\//.test(text) || /import\(['"]firebase/.test(text)).map((s) => s.path);
    expect(importers).toEqual([join('data', 'cloud.ts')]);
    const cloud = sources.find((s) => s.path === join('data', 'cloud.ts'))!.text;
    const modules = [...cloud.matchAll(/from ['"](firebase\/[^'"]+)['"]/g)].map((m) => m[1]);
    expect(new Set(modules)).toEqual(new Set(['firebase/app', 'firebase/auth', 'firebase/firestore/lite']));
  });

  test('the guard sees a write when there is one', () => {
    const text = "import { setDoc } from 'firebase/firestore/lite';";
    expect(WRITE_CALLS.some((name) => new RegExp(`\\b${name}\\b`).test(text))).toBe(true);
  });
});
