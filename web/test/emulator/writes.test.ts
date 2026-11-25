import { deleteApp, initializeApp, type FirebaseApp } from 'firebase/app';
import { connectFirestoreEmulator, doc, getDoc, getFirestore, serverTimestamp, setDoc, type Firestore } from 'firebase/firestore/lite';
import { afterAll, describe, expect, test } from 'vitest';
import { writeRecord, type WriteResult } from '../../src/data/writes';
import { epochDay } from '../../src/domain/dates';
import { bonusFields, shiftFields, type BonusValues, type PayoutValues, type ShiftValues } from '../../src/edit/documents';
import type { WriteOp } from '../../src/edit/plan';

/*
 * The write layer (src/data/writes.ts) on the Firestore emulator, under the real firebase/firestore.rules (a copy with
 * a test address; npm run test:emulator). Every write the web makes must be accepted; wrong shapes, another user's
 * data and a wrong account must be refused; a write from an older version must abort; a retried transaction must not
 * write twice.
 */

const host = process.env['FIRESTORE_EMULATOR_HOST'];
if (!host) throw new Error('Run with npm run test:emulator (it starts the Firestore emulator).');
const [emulatorHost, emulatorPort] = host.split(':') as [string, string];

const apps: FirebaseApp[] = [];
let appCount = 0;

/** A signed-in client as the rules require: Google sign-in, verified address of the owner. */
function client(uid: string, email = 'owner@example.com', provider = 'google.com', emailVerified = true): Firestore {
  const app = initializeApp({ projectId: 'demo-shift-tracker', apiKey: 'emulator' }, `client-${appCount++}`);
  apps.push(app);
  const db = getFirestore(app);
  connectFirestoreEmulator(db, emulatorHost, Number(emulatorPort), {
    mockUserToken: { sub: uid, user_id: uid, email, email_verified: emailVerified, firebase: { sign_in_provider: provider as never, identities: {} } },
  });
  return db;
}

afterAll(async () => {
  await Promise.all(apps.map((app) => deleteApp(app)));
});

let uidCount = 0;
const newUid = () => `user-${Date.now()}-${uidCount++}`;
const uuid = () => crypto.randomUUID();

const JOB = crypto.randomUUID();
const OTHER_JOB = crypto.randomUUID();
const LIVE = new Set([JOB, OTHER_JOB]);
const DAY = epochDay(2026, 11, 13);

const shift: ShiftValues = { jobId: JOB, date: DAY, start: 540, end: 1020, breakMinutes: 30, rate: 3150n, note: 'Rano', externalUid: null, secondRate: null };
const nightShift: ShiftValues = { jobId: OTHER_JOB, date: DAY, start: 1320, end: 360, breakMinutes: 30, rate: 3300n, note: null, externalUid: null, secondRate: { rate: 3700n, from: 1380, to: 360 } };
const dayBonus: BonusValues = { jobId: JOB, date: DAY, amount: 3800n, note: 'Napiwki', wholeMonth: false };
const monthBonus: BonusValues = { jobId: OTHER_JOB, date: epochDay(2026, 11, 1), amount: 30000n, note: null, wholeMonth: true };
const payout: PayoutValues = { jobId: OTHER_JOB, month: 202611, amount: 190000n, note: 'Przelew', receivedOn: epochDay(2026, 12, 10) };
const bareP: PayoutValues = { jobId: JOB, month: 202610, amount: 1n, note: null, receivedOn: null };

async function ok(result: Promise<WriteResult>) {
  const r = await result;
  if (!r.ok) throw new Error(`refused: ${r.refusal}`);
  return r;
}

async function stored(db: Firestore, uid: string, collection: string, id: string) {
  const snap = await getDoc(doc(db, 'users', uid, collection, id));
  return snap.exists() ? snap.data() : null;
}

describe('every write the web makes passes the rules', () => {
  test('create, edit, delete (tombstone) and undo, for shifts, bonuses and payouts', async () => {
    const uid = newUid();
    const db = client(uid);
    const cases: [WriteOp['collection'], ShiftValues | BonusValues | PayoutValues][] = [
      ['shifts', shift], ['shifts', nightShift], ['bonuses', dayBonus], ['bonuses', monthBonus], ['payouts', payout], ['payouts', bareP],
    ];
    for (const [collection, values] of cases) {
      const id = uuid();
      const created = await ok(writeRecord(db, uid, { kind: 'create', collection, id, values }, LIVE));
      const edited = await ok(writeRecord(db, uid, { kind: 'update', collection, id, values: { ...values, note: 'Zmiana' }, base: created.updatedAt }, LIVE));
      const deleted = await ok(writeRecord(db, uid, { kind: 'delete', collection, id, base: edited.updatedAt }, LIVE));
      const tombstone = await stored(db, uid, collection, id);
      expect(tombstone, `${collection}: still there, as a tombstone`).toMatchObject({ deleted: true, note: 'Zmiana', updatedAt: deleted.updatedAt });
      const restored = await ok(writeRecord(db, uid, { kind: 'restore', collection, id, values: { ...values, note: 'Zmiana' }, base: deleted.updatedAt }, LIVE));
      const data = await stored(db, uid, collection, id);
      expect(data).toMatchObject({ ...restored.fields, deleted: false });
      expect(Object.keys(data!).sort()).toContain('serverUpdatedAt');
      expect(restored.updatedAt).toBeGreaterThan(deleted.updatedAt);
      expect(deleted.updatedAt).toBeGreaterThan(edited.updatedAt);
    }
  });

  test('a bonus the previous app version wrote (no wholeMonth) is edited and gets the field', async () => {
    const uid = newUid();
    const db = client(uid);
    const id = uuid();
    const old = bonusFields(dayBonus, { updatedAt: Date.now() - 60_000, deleted: false });
    delete (old as Record<string, unknown>)['wholeMonth'];
    await setDoc(doc(db, 'users', uid, 'bonuses', id), { ...old, serverUpdatedAt: serverTimestamp() });
    await ok(writeRecord(db, uid, { kind: 'update', collection: 'bonuses', id, values: { ...dayBonus, amount: 4000n }, base: old.updatedAt as number }, LIVE));
    expect(await stored(db, uid, 'bonuses', id)).toMatchObject({ wholeMonth: false, amountGrosze: 4000 });
  });

  test('an imported shift keeps its externalUid', async () => {
    const uid = newUid();
    const db = client(uid);
    const id = uuid();
    const app = shiftFields({ ...shift, externalUid: 'ics-1' }, { updatedAt: Date.now() - 60_000, deleted: false });
    await setDoc(doc(db, 'users', uid, 'shifts', id), { ...app, serverUpdatedAt: serverTimestamp() });
    await ok(writeRecord(db, uid, { kind: 'update', collection: 'shifts', id, values: { ...shift, externalUid: 'ics-1', end: 1080 }, base: app.updatedAt as number }, LIVE));
    expect(await stored(db, uid, 'shifts', id)).toMatchObject({ externalUid: 'ics-1', endMinute: 1080 });
  });
});

describe('the rules refuse what isn’t the owner’s or isn’t the app’s shape', () => {
  test('another user’s data, an unverified email, a non-Google sign-in', async () => {
    const owner = newUid();
    const op: WriteOp = { kind: 'create', collection: 'shifts', id: uuid(), values: shift };
    await expect(writeRecord(client(newUid(), 'someone@example.org'), owner, op, LIVE)).rejects.toMatchObject({ code: 'permission-denied' });
    await expect(writeRecord(client(owner, 'owner@example.com', 'google.com', false), owner, op, LIVE)).rejects.toMatchObject({ code: 'permission-denied' });
    await expect(writeRecord(client(owner, 'owner@example.com', 'password'), owner, op, LIVE)).rejects.toMatchObject({ code: 'permission-denied' });
    expect(await stored(client(owner), owner, 'shifts', op.id)).toBeNull();
  });

  test('a field too many, a field missing, a wrong type: refused (the builders never make them)', async () => {
    const uid = newUid();
    const db = client(uid);
    const fields = shiftFields(shift, { updatedAt: Date.now(), deleted: false });
    const put = (data: Record<string, unknown>) => setDoc(doc(db, 'users', uid, 'shifts', uuid()), { ...data, serverUpdatedAt: serverTimestamp() });
    await expect(put({ ...fields, extra: true })).rejects.toMatchObject({ code: 'permission-denied' });
    const { note: _note, ...missing } = fields;
    await expect(put(missing)).rejects.toMatchObject({ code: 'permission-denied' });
    await expect(put({ ...fields, hourlyRateGrosze: 31.5 })).rejects.toMatchObject({ code: 'permission-denied' });
    await expect(put(fields)).resolves.toBeUndefined();
  });

  test('a clock more than 10 minutes ahead is refused by the rules', async () => {
    const uid = newUid();
    const op: WriteOp = { kind: 'create', collection: 'shifts', id: uuid(), values: shift };
    await expect(writeRecord(client(uid), uid, op, LIVE, { now: () => Date.now() + 20 * 60_000 })).rejects.toMatchObject({ code: 'permission-denied' });
  });
});

describe('no blind overwrites', () => {
  test('an edit from an older version is refused and changes nothing', async () => {
    const uid = newUid();
    const tabA = client(uid);
    const tabB = client(uid);
    const id = uuid();
    const created = await ok(writeRecord(tabA, uid, { kind: 'create', collection: 'shifts', id, values: shift }, LIVE));
    const fromB = await ok(writeRecord(tabB, uid, { kind: 'update', collection: 'shifts', id, values: { ...shift, note: 'B' }, base: created.updatedAt }, LIVE));
    const fromA = await writeRecord(tabA, uid, { kind: 'update', collection: 'shifts', id, values: { ...shift, note: 'A' }, base: created.updatedAt }, LIVE);
    expect(fromA).toMatchObject({ ok: false, refusal: 'changed' });
    const deleteA = await writeRecord(tabA, uid, { kind: 'delete', collection: 'shifts', id, base: created.updatedAt }, LIVE);
    expect(deleteA).toMatchObject({ ok: false, refusal: 'changed' });
    expect(await stored(tabA, uid, 'shifts', id)).toMatchObject({ note: 'B', updatedAt: fromB.updatedAt, deleted: false });
  });

  test('a change that lands between the read and the commit: the retry reads it and refuses, writing nothing', async () => {
    const uid = newUid();
    const tabA = client(uid);
    const tabB = client(uid);
    const id = uuid();
    const created = await ok(writeRecord(tabA, uid, { kind: 'create', collection: 'shifts', id, values: shift }, LIVE));
    const attempts: number[] = [];
    let fromB: WriteResult | null = null as WriteResult | null;
    const result = await writeRecord(tabA, uid, { kind: 'update', collection: 'shifts', id, values: { ...shift, note: 'A' }, base: created.updatedAt }, LIVE, {
      onRead: async (attempt) => {
        attempts.push(attempt);
        if (attempt === 1) fromB = await writeRecord(tabB, uid, { kind: 'update', collection: 'shifts', id, values: { ...shift, note: 'B' }, base: created.updatedAt }, LIVE);
      },
    });
    if (!fromB?.ok) throw new Error('the other tab’s write failed');
    expect(attempts).toEqual([1, 2]);
    expect(result).toMatchObject({ ok: false, refusal: 'changed' });
    expect(await stored(tabA, uid, 'shifts', id)).toMatchObject({ note: 'B', updatedAt: fromB.updatedAt });
  });

  test('a create retried after another write of the same id doesn’t write over it', async () => {
    const uid = newUid();
    const tabA = client(uid);
    const tabB = client(uid);
    const id = uuid();
    const attempts: number[] = [];
    const result = await writeRecord(tabA, uid, { kind: 'create', collection: 'bonuses', id, values: dayBonus }, LIVE, {
      onRead: async (attempt) => {
        attempts.push(attempt);
        if (attempt === 1) await ok(writeRecord(tabB, uid, { kind: 'create', collection: 'bonuses', id, values: monthBonus }, LIVE));
      },
    });
    expect(attempts).toEqual([1, 2]);
    expect(result).toMatchObject({ ok: false, refusal: 'exists' });
    expect(await stored(tabA, uid, 'bonuses', id)).toMatchObject({ wholeMonth: true, amountGrosze: 30000 });
  });

  test('two tabs saving the same version at once: exactly one wins, the other is told', async () => {
    const uid = newUid();
    const id = uuid();
    const created = await ok(writeRecord(client(uid), uid, { kind: 'create', collection: 'payouts', id, values: payout }, LIVE));
    const results = await Promise.all(
      ['A', 'B'].map((note) =>
        writeRecord(client(uid), uid, { kind: 'update', collection: 'payouts', id, values: { ...payout, note }, base: created.updatedAt }, LIVE),
      ),
    );
    expect(results.filter((r) => r.ok)).toHaveLength(1);
    expect(results.filter((r) => !r.ok)).toEqual([expect.objectContaining({ refusal: 'changed' })]);
    const winner = results.find((r) => r.ok);
    if (!winner?.ok) throw new Error('no winner');
    expect(await stored(client(uid), uid, 'payouts', id)).toMatchObject({ note: winner.fields['note'], updatedAt: winner.updatedAt });
  });

  test('a record whose job isn’t loaded and live can’t be created or moved there', async () => {
    const uid = newUid();
    const db = client(uid);
    const result = await writeRecord(db, uid, { kind: 'create', collection: 'shifts', id: uuid(), values: { ...shift, jobId: crypto.randomUUID() } }, LIVE);
    expect(result).toMatchObject({ ok: false, refusal: 'no-job' });
  });
});
