// Tests for ../firestore.rules on the Firestore emulator: npm ci && npm test (Node 20+, Java 21).
import assert from 'node:assert/strict';
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
const PAYOUT_ID = '44444444-4444-4444-8444-444444444444';

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

/** A bonus in the shape the previous app version writes: no wholeMonth. */
function monthBonus(fields = {}) {
  return bonus({ wholeMonth: true, ...fields });
}

function payout(fields = {}) {
  return {
    v: 1, updatedAt: now(), serverUpdatedAt: serverTimestamp(), deleted: false,
    jobId: JOB_ID, month: 202611, amountMinor: 450000, note: 'Przelew', receivedEpochDay: 20420,
    ...fields,
  };
}

const jobRef = (db, uid = UID, id = JOB_ID) => doc(db, 'users', uid, 'jobs', id);
const bonusRef = (db, id = BONUS_ID) => doc(db, 'users', UID, 'bonuses', id);
const payoutRef = (db, uid = UID, id = PAYOUT_ID) => doc(db, 'users', uid, 'payouts', id);

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
    await assertSucceeds(setDoc(payoutRef(db), payout()));
    await assertSucceeds(getDocs(collection(db, 'users', UID, 'shifts')));
    await assertSucceeds(getDocs(collection(db, 'users', UID, 'payouts')));
    await assertSucceeds(getDoc(payoutRef(db)));
    await assertSucceeds(deleteDoc(doc(db, 'users', UID, 'bonuses', BONUS_ID)));
    await assertSucceeds(deleteDoc(payoutRef(db)));
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
      await assertFails(setDoc(payoutRef(db), payout()), who);
      await assertFails(getDocs(collection(db, 'users', UID, 'payouts')), who);
    }
  });

  test("the allowed email can't touch another uid's data", async () => {
    await env.withSecurityRulesDisabled(async (context) => {
      await setDoc(jobRef(context.firestore(), 'other-uid'), job({ serverUpdatedAt: Timestamp.now() }));
      await setDoc(payoutRef(context.firestore(), 'other-uid'), payout({ serverUpdatedAt: Timestamp.now() }));
    });
    const db = owner();
    await assertFails(getDoc(jobRef(db, 'other-uid')));
    await assertFails(setDoc(jobRef(db, 'other-uid'), job()));
    await assertFails(deleteDoc(jobRef(db, 'other-uid')));
    await assertFails(getDoc(payoutRef(db, 'other-uid')));
    await assertFails(setDoc(payoutRef(db, 'other-uid'), payout({ updatedAt: now() + 1000 })));
    await assertFails(deleteDoc(payoutRef(db, 'other-uid')));
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

describe('bonuses accept both document shapes', () => {
  test('the shape of the previous app version (no wholeMonth) is accepted', async () => {
    const db = owner();
    await assertSucceeds(setDoc(bonusRef(db), bonus()));
    await assertSucceeds(setDoc(bonusRef(db), bonus({ updatedAt: now() + 1000, deleted: true })));
  });

  test('the current shape (wholeMonth true or false) is accepted', async () => {
    const db = owner();
    await assertSucceeds(setDoc(bonusRef(db), monthBonus()));
    await assertSucceeds(setDoc(bonusRef(db), monthBonus({ updatedAt: now() + 1000, wholeMonth: false })));
  });

  test('wholeMonth must be a boolean when it is there', async () => {
    const db = owner();
    for (const value of ['true', 1, 0, null, { a: true }, []]) {
      await assertFails(setDoc(bonusRef(db), monthBonus({ wholeMonth: value })), JSON.stringify(value));
    }
  });

  test('an unknown extra field is still refused, in either shape', async () => {
    const db = owner();
    await assertFails(setDoc(bonusRef(db), bonus({ extra: 'x' })));
    await assertFails(setDoc(bonusRef(db), monthBonus({ extra: 'x' })));
    await assertFails(setDoc(bonusRef(db), monthBonus({ month: 202611 })));
  });

  test('the other bonus checks still apply to the current shape', async () => {
    const db = owner();
    await assertFails(setDoc(bonusRef(db), monthBonus({ amountGrosze: 50.25 })));
    await assertFails(setDoc(bonusRef(db), monthBonus({ jobId: 'kawiarnia' })));
    await assertFails(setDoc(bonusRef(db), monthBonus({ note: 'x'.repeat(20001) })));
    await assertFails(setDoc(bonusRef(db), monthBonus({ dateEpochDay: '2026-11-01' })));
    await assertSucceeds(setDoc(bonusRef(db), monthBonus({ note: null })));
  });

  test('a write from the previous version merges onto a month bonus and the mark survives', async () => {
    const db = owner();
    await assertSucceeds(setDoc(bonusRef(db), monthBonus({ updatedAt: 1000, amountGrosze: 5000 })));
    // The previous version knows nothing of wholeMonth: it sends its own fields, merged (SetOptions.merge()).
    await assertSucceeds(setDoc(bonusRef(db), bonus({ updatedAt: 1001, amountGrosze: 7000 }), { merge: true }));
    const stored = (await getDoc(bonusRef(db))).data();
    assert.equal(stored.wholeMonth, true);
    assert.equal(stored.amountGrosze, 7000);
    assert.equal(stored.updatedAt, 1001);
  });

  test('the current version can merge onto a document written by the previous version', async () => {
    const db = owner();
    await assertSucceeds(setDoc(bonusRef(db), bonus({ updatedAt: 1000 })));
    await assertSucceeds(setDoc(bonusRef(db), monthBonus({ updatedAt: 1001 }), { merge: true }));
    assert.equal((await getDoc(bonusRef(db))).data().wholeMonth, true);
  });

  test('an older version of a month bonus is refused like any record', async () => {
    const db = owner();
    await assertSucceeds(setDoc(bonusRef(db), monthBonus({ updatedAt: 1000 })));
    await assertFails(setDoc(bonusRef(db), monthBonus({ updatedAt: 999, wholeMonth: false })));
    await assertFails(setDoc(bonusRef(db), bonus({ updatedAt: 1000 }), { merge: true }));
  });
});

describe('payouts', () => {
  test('a valid payout, with or without a received date and note', async () => {
    const db = owner();
    await assertSucceeds(setDoc(payoutRef(db), payout()));
    const other = '55555555-5555-4555-8555-555555555555';
    await assertSucceeds(setDoc(payoutRef(db, UID, other), payout({ note: null, receivedEpochDay: null })));
    await assertSucceeds(setDoc(payoutRef(db), payout({ updatedAt: now() + 1000, deleted: true })));
  });

  test('several payouts for the same job and month are separate records', async () => {
    const db = owner();
    const second = '66666666-6666-4666-8666-666666666666';
    await assertSucceeds(setDoc(payoutRef(db), payout({ amountMinor: 400000 })));
    await assertSucceeds(setDoc(payoutRef(db, UID, second), payout({ amountMinor: 50000 })));
  });

  test('every field must be there, with its type, and nothing else', async () => {
    const db = owner();
    const without = (name) => { const p = payout(); delete p[name]; return p; };
    const bad = {
      'a missing amount': without('amountMinor'),
      'a missing month': without('month'),
      'a missing job': without('jobId'),
      'a missing note': without('note'),
      'a missing received date': without('receivedEpochDay'),
      'no schema version': without('v'),
      'an extra field': payout({ extra: 'x' }),
      'a bonus-only field': payout({ wholeMonth: true }),
      'amount as a fraction': payout({ amountMinor: 100.5 }),
      'amount as text': payout({ amountMinor: '4500' }),
      'a negative amount': payout({ amountMinor: -1 }),
      'a job id that is not a UUID': payout({ jobId: 'kawiarnia' }),
      'a null job': payout({ jobId: null }),
      'a month as text': payout({ month: '2026-11' }),
      'a month as a fraction': payout({ month: 202611.5 }),
      'month 0 (the "from the beginning" key)': payout({ month: 0 }),
      'month 13': payout({ month: 202613 }),
      'month 00': payout({ month: 202600 }),
      'a month before year 1000': payout({ month: 99912 }),
      'a month after year 9999': payout({ month: 1000001 }),
      'a note too long': payout({ note: 'x'.repeat(20001) }),
      'a note that is not text': payout({ note: 5 }),
      'a received date as text': payout({ receivedEpochDay: '2026-12-02' }),
      'a received date as a fraction': payout({ receivedEpochDay: 20420.5 }),
      'deleted not a boolean': payout({ deleted: 'no' }),
    };
    for (const [what, data] of Object.entries(bad)) {
      await assertFails(setDoc(payoutRef(db), data), what);
    }
    await assertSucceeds(setDoc(payoutRef(db), payout({ note: 'x'.repeat(20000), month: 202612, amountMinor: 0 })));
  });

  test('the document id is a lowercase UUID', async () => {
    const db = owner();
    await assertFails(setDoc(payoutRef(db, UID, 'not-a-uuid'), payout()));
    await assertFails(setDoc(payoutRef(db, UID, 'ABCDEF12-1111-4111-8111-111111111111'), payout()));
  });

  test('a later version replaces a payout; the same or an earlier one is refused', async () => {
    const db = owner();
    await assertSucceeds(setDoc(payoutRef(db), payout({ updatedAt: 1000 })));
    await assertFails(setDoc(payoutRef(db), payout({ updatedAt: 1000, amountMinor: 1 })));
    await assertFails(setDoc(payoutRef(db), payout({ updatedAt: 999, amountMinor: 2 })));
    await assertSucceeds(setDoc(payoutRef(db), payout({ updatedAt: 1001, amountMinor: 3 })));
  });

  test('a phone clock more than 10 minutes ahead is refused', async () => {
    const db = owner();
    await assertSucceeds(setDoc(payoutRef(db), payout({ updatedAt: now() + 5 * 60 * 1000 })));
    await assertFails(setDoc(payoutRef(db, UID, '77777777-7777-4777-8777-777777777777'),
      payout({ updatedAt: now() + 11 * 60 * 1000 })));
  });

  test('serverUpdatedAt must be the server time of the write', async () => {
    const db = owner();
    await assertFails(setDoc(payoutRef(db), payout({ serverUpdatedAt: Timestamp.now() })));
  });

  test('the owner can delete a payout; others cannot', async () => {
    const db = owner();
    await assertSucceeds(setDoc(payoutRef(db), payout()));
    const stranger = env.authenticatedContext(UID, google('someone@example.com')).firestore();
    await assertFails(deleteDoc(payoutRef(stranger)));
    await assertSucceeds(deleteDoc(payoutRef(db)));
  });
});
