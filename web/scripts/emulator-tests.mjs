// Runs the write-layer tests (test/emulator) on the Firestore emulator with the real firebase/firestore.rules.
// The committed rules have the placeholder YOUR_EMAIL_HERE and let nobody in; the tests sign in as
// owner@example.com, so a temporary copy gets that address (as firebase/run-emulator-tests.sh does).
// Needs Java 21 (the emulator) and network access for npx the first time.
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';

const web = resolve(import.meta.dirname, '..');
const rules = readFileSync(resolve(web, '../firebase/firestore.rules'), 'utf8');
if (!rules.includes('YOUR_EMAIL_HERE')) throw new Error('firebase/firestore.rules has no placeholder address: refusing to test a copy of your real rules');

const work = mkdtempSync(join(tmpdir(), 'web-emulator-'));
try {
  writeFileSync(join(work, 'firestore.rules'), rules.replaceAll('YOUR_EMAIL_HERE', 'owner@example.com'));
  writeFileSync(
    join(work, 'firebase.json'),
    JSON.stringify({ firestore: { rules: 'firestore.rules' }, emulators: { firestore: { host: '127.0.0.1', port: 8080 }, ui: { enabled: false }, singleProjectMode: true } }),
  );
  const tests = `npx vitest run --root "${web}" --project emulator`;
  const result = spawnSync(
    'npx',
    ['--yes', 'firebase-tools@15.34.0', 'emulators:exec', '--only', 'firestore', '--project', 'demo-shift-tracker', tests],
    { cwd: work, stdio: 'inherit', shell: process.platform === 'win32' },
  );
  process.exitCode = result.status ?? 1;
} finally {
  rmSync(work, { recursive: true, force: true });
}
