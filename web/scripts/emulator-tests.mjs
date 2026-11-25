// Runs the write-layer tests (test/emulator) on the Firestore emulator with the real firebase/firestore.rules,
// exactly as committed (per-user access: each signed-in user only reaches users/{their uid}).
// Needs Java 21 (the emulator) and network access for npx the first time.
import { spawnSync } from 'node:child_process';
import { copyFileSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';

const web = resolve(import.meta.dirname, '..');

const work = mkdtempSync(join(tmpdir(), 'web-emulator-'));
try {
  copyFileSync(resolve(web, '../firebase/firestore.rules'), join(work, 'firestore.rules'));
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
