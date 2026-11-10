// Tests for ../firestore.rules on the Firestore emulator: npm ci && npm test (Node 20+, Java 21).
import { after, before, beforeEach, describe, test } from 'node:test';
import { readFileSync } from 'node:fs';
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} from '@firebase/rules-unit-testing';
import {
  deleteDoc,
  doc,
  getDoc,
  getDocs,
  collection,
  serverTimestamp,
  setDoc,
  Timestamp,
} from 'firebase/firestore';

const EMAIL = 'owner@example.com';
const UID = 'owner-uid';
const PLACEHOLDER = 'YOUR_EMAIL_HERE';
const committedRules = readFileSync(new URL('../firestore.rules', import.meta.url), 'utf8');

const JOB_ID = '11111111-1111-4111-8111-111111111111';
const SHIFT_ID = '22222222-2222-4222-8222-222222222222';
const BONUS_ID = '33333333-3333-4333-8333-333333333333';

let env;

/** Claims of a Google sign-in, as Firebase Auth puts them in the ID token. */
function google(email = EMAIL, extra = {}) {
  return { email, email_verified: true, firebase: { sign_in_provider: 'google.com' }, ...extra };
}

function owner() {
  return env.authenticatedContext(UID, google()).firestore();
}

const now = () => Date.now();

function job(fields = {}) {
  return {
    v: 1, updatedAt: now(), serverUpdatedAt: serverTimestamp(), deleted: false,
    name: 'Kawiarnia', hourlyRateGrosze: 3300, color: -14575885, secondRate: null, currency: 'PLN',
    payHistory: [{ fromMonth: 0, payType: 'HOURLY', monthlyAmountMinor: 0, deductionBp: 0 }],
    ...fields,
  };
}

function shift(fields = {}) {
  return {
    v: 1, updatedAt: now(), serverUpdatedAt: serverTimestamp(), deleted: false,
    jobId: JOB_ID, dateEpochDay: 20400, startMinute: 480, endMinute: 960, unpaidBreakMinutes: 30,
    hourlyRateGrosze: 3300, note: null, externalUid: null,
    secondRate: { rateGrosze: 4000, fromMinute: 1320, toMinute: 360 },
    ...fields,
  };
}

function bonus(fields = {}) {
  return {
    v: 1, updatedAt: now(), serverUpdatedAt: serverTimestamp(), deleted: false,
    jobId: JOB_ID, dateEpochDay: 20400, amountGrosze: 5000, note: 'Premia',
    ...fields,
  };
}

const jobRef = (db, uid = UID, id = JOB_ID) => doc(db, 'users', uid, 'jobs', id);

before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'demo-shift-tracker',
    firestore: { rules: committedRules.replaceAll(PLACEHOLDER, EMAIL) },
  });
});

after(async () => {
  await env?.cleanup();
});

beforeEach(async () => {
  await env.clearFirestore();
});

describe('as committed', () => {
  test('nobody gets in until the placeholder is replaced', async () => {
    const placeholderEnv = await initializeTestEnvironment({
      projectId: 'demo-shift-tracker-placeholder',
      firestore: { rules: committedRules },
    });
    try {
      for (const email of [EMAIL, PLACEHOLDER, PLACEHOLDER.toLowerCase()]) {
        const db = placeholderEnv.authenticatedContext(UID, google(email)).firestore();
        await assertFails(getDoc(doc(db, 'users', UID)));
        await assertFails(setDoc(jobRef(db), job()));
      }
    } finally {
      await placeholderEnv.cleanup();
    }
  });
});

describe('who', () => {
  test('the owner, signed in with Google with the allowed verified email, reads and writes their data', async () => {
    const db = owner();
    await assertSucceeds(setDoc(doc(db, 'users', UID), { epoch: 'e1', createdAt: serverTimestamp() }));
    await assertSucceeds(getDoc(doc(db, 'users', UID)));
    await assertSucceeds(setDoc(jobRef(db), job()));
    await assertSucceeds(setDoc(doc(db, 'users', UID, 'shifts', SHIFT_ID), shift()));
    await assertSucceeds(setDoc(doc(db, 'users', UID, 'bonuses', BONUS_ID), bonus()));
    await assertSucceeds(getDocs(collection(db, 'users', UID, 'shifts')));
    await assertSucceeds(deleteDoc(doc(db, 'users', UID, 'bonuses', BONUS_ID)));
    await assertSucceeds(deleteDoc(doc(db, 'users', UID)));
  });

  test('the email check ignores letter case', async () => {
    const db = env.authenticatedContext(UID, google('Owner@Example.COM')).firestore();
    await assertSucceeds(setDoc(jobRef(db), job()));
  });

  test('nobody else gets in', async () => {
    const strangers = {
      'signed out': env.unauthenticatedContext(),
      'another email': env.authenticatedContext(UID, google('someone@example.com')),
      'unverified email': env.authenticatedContext(UID, google(EMAIL, { email_verified: false })),
      'no email': env.authenticatedContext(UID, { firebase: { sign_in_provider: 'google.com' } }),
      'not signed in with Google': env.authenticatedContext(UID, google(EMAIL, { firebase: { sign_in_provider: 'password' } })),
      'anonymous': env.authenticatedContext(UID, google(EMAIL, { firebase: { sign_in_provider: 'anonymous' } })),
    };
    for (const [who, context] of Object.entries(strangers)) {
      const db = context.firestore();
      await assertFails(getDoc(doc(db, 'users', UID)), who);
      await assertFails(setDoc(doc(db, 'users', UID), { epoch: 'e1', createdAt: serverTimestamp() }), who);
      await assertFails(setDoc(jobRef(db), job()), who);
      await assertFails(getDocs(collection(db, 'users', UID, 'jobs')), who);
    }
  });

  test("the allowed email can't touch another uid's data", async () => {
    await env.withSecurityRulesDisabled(async (context) => {
      await setDoc(jobRef(context.firestore(), 'other-uid'), job({ serverUpdatedAt: Timestamp.now() }));
    });
    const db = owner();
    await assertFails(getDoc(jobRef(db, 'other-uid')));
    await assertFails(setDoc(jobRef(db, 'other-uid'), job()));
    await assertFails(deleteDoc(jobRef(db, 'other-uid')));
  });

  test('nothing outside users/{uid} is reachable', async () => {
    const db = owner();
    await assertFails(setDoc(doc(db, 'config', 'x'), { a: 1 }));
    await assertFails(setDoc(doc(db, 'users', UID, 'notes', JOB_ID), { a: 1 }));
    await assertFails(getDocs(collection(db, 'users')));
  });
});

describe('the account marker', () => {
  test('is created once with the server time and never changed', async () => {
    const db = owner();
    const ref = doc(db, 'users', UID);
    await assertFails(setDoc(ref, { epoch: 'e1', createdAt: Timestamp.fromMillis(0) }));
    await assertFails(setDoc(ref, { epoch: 'e1', createdAt: serverTimestamp(), extra: true }));
    await assertFails(setDoc(ref, { epoch: '', createdAt: serverTimestamp() }));
    await assertFails(setDoc(ref, { epoch: 42, createdAt: serverTimestamp() }));
    await assertSucceeds(setDoc(ref, { epoch: 'e1', createdAt: serverTimestamp() }));
    await assertFails(setDoc(ref, { epoch: 'e2', createdAt: serverTimestamp() }));
  });
});

describe('records', () => {
  test('a later version replaces a record; the same or an earlier one is refused', async () => {
    const db = owner();
    await assertSucceeds(setDoc(jobRef(db), job({ updatedAt: 1000 })));
    await assertFails(setDoc(jobRef(db), job({ updatedAt: 1000, name: 'Same time' })));
    await assertFails(setDoc(jobRef(db), job({ updatedAt: 999, name: 'Older' })));
    await assertSucceeds(setDoc(jobRef(db), job({ updatedAt: 1001, deleted: true })));
  });

  test('a phone clock more than 10 minutes ahead is refused', async () => {
    const db = owner();
    await assertSucceeds(setDoc(jobRef(db), job({ updatedAt: now() + 5 * 60 * 1000 })));
    await assertFails(setDoc(doc(db, 'users', UID, 'bonuses', BONUS_ID), bonus({ updatedAt: now() + 11 * 60 * 1000 })));
  });

  test('serverUpdatedAt must be the server time of the write', async () => {
    const db = owner();
    await assertFails(setDoc(jobRef(db), job({ serverUpdatedAt: Timestamp.now() })));
    await assertFails(setDoc(jobRef(db), job({ serverUpdatedAt: null })));
  });

  test('document ids are lowercase UUIDs', async () => {
    const db = owner();
    await assertFails(setDoc(jobRef(db, UID, 'not-a-uuid'), job()));
    await assertFails(setDoc(jobRef(db, UID, 'ABCDEF12-1111-4111-8111-111111111111'), job()));
    await assertSucceeds(setDoc(jobRef(db, UID, 'abcdef12-1111-4111-8111-111111111111'), job()));
    await assertFails(setDoc(doc(db, 'users', UID, 'shifts', SHIFT_ID), shift({ jobId: 'kawiarnia' })));
  });

  test('every field must be there, with its type, and nothing else', async () => {
    const db = owner();
    const bad = {
      'a missing field': (() => { const j = job(); delete j.currency; return j; })(),
      'an extra field': job({ extra: 'x' }),
      'money as a fraction': job({ hourlyRateGrosze: 33.5 }),
      'money as text': job({ hourlyRateGrosze: '3300' }),
      'negative money': job({ hourlyRateGrosze: -1 }),
      'deleted not a boolean': job({ deleted: 'no' }),
      'no schema version': (() => { const j = job(); delete j.v; return j; })(),
      'a second rate with an extra field': job({ secondRate: { rateGrosze: 1, fromMinute: 0, toMinute: 60, x: 1 } }),
      'a second rate outside the day': job({ secondRate: { rateGrosze: 1, fromMinute: 0, toMinute: 1440 } }),
      'a name too long': job({ name: 'x'.repeat(20001) }),
      'pay history not a list': job({ payHistory: {} }),
    };
    for (const [what, data] of Object.entries(bad)) {
      await assertFails(setDoc(jobRef(db), data), what);
    }
    await assertSucceeds(setDoc(jobRef(db), job({ name: 'x'.repeat(20000), secondRate: { rateGrosze: 4000, fromMinute: 1320, toMinute: 360 } })));
  });

  test('text length is counted as the app counts it (UTF-16 units), not in bytes', async () => {
    const db = owner();
    const ref = doc(db, 'users', UID, 'shifts', SHIFT_ID);
    const emoji = '\u{1F600}'; // 2 UTF-16 units, 4 bytes
    await assertSucceeds(setDoc(ref, shift({ note: 'ї'.repeat(20000) }))); // 40000 bytes
    await assertFails(setDoc(ref, shift({ note: 'ї'.repeat(20001) })));
    await assertSucceeds(setDoc(ref, shift({ note: emoji.repeat(10000) })));
    await assertFails(setDoc(ref, shift({ note: emoji.repeat(10000) + 'a' })));
  });

  test('shifts', async () => {
    const db = owner();
    const ref = doc(db, 'users', UID, 'shifts', SHIFT_ID);
    await assertFails(setDoc(ref, shift({ startMinute: 1440 })));
    await assertFails(setDoc(ref, shift({ unpaidBreakMinutes: -5 })));
    await assertFails(setDoc(ref, shift({ note: 5 })));
    await assertFails(setDoc(ref, shift({ dateEpochDay: '2025-11-08' })));
    await assertSucceeds(setDoc(ref, shift({ note: 'Zamiana', externalUid: 'abc@calendar', secondRate: null })));
  });

  test('bonuses', async () => {
    const db = owner();
    const ref = doc(db, 'users', UID, 'bonuses', BONUS_ID);
    await assertFails(setDoc(ref, bonus({ amountGrosze: 50.25 })));
    await assertFails(setDoc(ref, bonus({ jobId: null })));
    await assertSucceeds(setDoc(ref, bonus({ note: null })));
  });
});
