import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { expect, test } from 'vitest';
import { epochDay } from '../../src/domain/dates';
import { planWrite, type ServerDocument, type WriteOp } from '../../src/edit/plan';

/*
 * The documents the web writes, for the app's side to read (app/src/test/.../web/WebWritesCompatTest.kt runs them
 * through the app's real mapping and sync engine). Built here by the web's own code, in order, each step over the
 * document the previous one left; committed so both sides test the same bytes. This test fails when the file is out
 * of date; UPDATE_WEB_WRITES=1 writes it again.
 */

const FILE = resolve(__dirname, '../fixtures/writes/web-writes.json');
const NOW = 1_795_000_000_000; // 2026-11-17

const CAFE = 'c49f2c0c-b280-4492-8476-1ad7e501e5da';
const NIGHT = 'fe49c731-2814-47c8-8c10-5f9ad3994e8e';
const EUR = '0b6f0c0e-7a5d-4c43-9f0d-2d5ad1e0b7a1';

const jobs = {
  [CAFE]: { v: 1, updatedAt: 1_790_000_000_000, deleted: false, name: 'Kawiarnia „Bajka”', hourlyRateGrosze: 3150, color: -12345273, secondRate: null, currency: 'PLN', payHistory: [] },
  [NIGHT]: {
    v: 1, updatedAt: 1_790_000_000_000, deleted: false, name: 'Magazyn nocny', hourlyRateGrosze: 3300, color: -14575885,
    secondRate: { rateGrosze: 3700, fromMinute: 1380, toMinute: 360 }, currency: 'PLN',
    payHistory: [{ fromMonth: 202609, payType: 'HOURLY', monthlyAmountMinor: 0, deductionBp: 1371 }],
  },
  [EUR]: { v: 1, updatedAt: 1_790_000_000_000, deleted: false, name: 'Переклади', hourlyRateGrosze: 1800, color: -26624, secondRate: null, currency: 'EUR', payHistory: [] },
};
const LIVE = new Set(Object.keys(jobs));

const SHIFT = 'a1000000-0000-4000-8000-000000000001';
const NIGHT_SHIFT = 'a1000000-0000-4000-8000-000000000002';
const APP_SHIFT = 'a1000000-0000-4000-8000-000000000003';
const DAY_BONUS = 'b2000000-0000-4000-8000-000000000001';
const MONTH_BONUS = 'b2000000-0000-4000-8000-000000000002';
const OLD_BONUS = 'b2000000-0000-4000-8000-000000000003';
const PAYOUT = 'c3000000-0000-4000-8000-000000000001';
const EUR_PAYOUT = 'c3000000-0000-4000-8000-000000000002';

const d = (y: number, m: number, day: number) => epochDay(y, m, day);
const shift = { jobId: CAFE, date: d(2026, 11, 13), start: 540, end: 1020, breakMinutes: 30, rate: 3150n, note: 'Rano', externalUid: null, secondRate: null };

/** Documents the app wrote before the web touched them (an imported shift, a bonus in the previous app's shape). */
const appDocuments: Record<string, Record<string, unknown>> = {
  [`shifts/${APP_SHIFT}`]: {
    v: 1, updatedAt: 1_794_000_000_000, deleted: false, jobId: CAFE, dateEpochDay: d(2026, 11, 2), startMinute: 600, endMinute: 900,
    unpaidBreakMinutes: 0, hourlyRateGrosze: 3000, note: null, externalUid: 'ics-20261102-1', secondRate: null,
  },
  [`bonuses/${OLD_BONUS}`]: { v: 1, updatedAt: 1_794_000_000_000, deleted: false, jobId: CAFE, dateEpochDay: d(2026, 11, 3), amountGrosze: 2500, note: 'Stara' },
};

interface Step {
  name: string;
  op: WriteOp;
}

const steps: Step[] = [
  { name: 'shift: created on the web', op: { kind: 'create', collection: 'shifts', id: SHIFT, values: shift } },
  { name: 'shift: edited on the web', op: { kind: 'update', collection: 'shifts', id: SHIFT, values: { ...shift, end: 1080, note: null }, base: NOW } },
  { name: 'shift: deleted on the web (tombstone)', op: { kind: 'delete', collection: 'shifts', id: SHIFT, base: NOW + 1 } },
  { name: 'shift: undo of the delete', op: { kind: 'restore', collection: 'shifts', id: SHIFT, values: { ...shift, end: 1080, note: null }, base: NOW + 2 } },
  {
    name: 'night shift with a second rate, ends the next day',
    op: { kind: 'create', collection: 'shifts', id: NIGHT_SHIFT, values: { jobId: NIGHT, date: d(2026, 11, 13), start: 1320, end: 360, breakMinutes: 30, rate: 3300n, note: null, externalUid: null, secondRate: { rate: 3700n, from: 1380, to: 360 } } },
  },
  {
    name: 'an imported shift edited on the web keeps its externalUid',
    op: { kind: 'update', collection: 'shifts', id: APP_SHIFT, values: { jobId: CAFE, date: d(2026, 11, 2), start: 600, end: 960, breakMinutes: 15, rate: 3000n, note: 'Dłużej', externalUid: 'ics-20261102-1', secondRate: null }, base: 1_794_000_000_000 },
  },
  { name: 'bonus on a day', op: { kind: 'create', collection: 'bonuses', id: DAY_BONUS, values: { jobId: CAFE, date: d(2026, 11, 13), amount: 3800n, note: 'Napiwki', wholeMonth: false } } },
  { name: 'bonus for the whole month', op: { kind: 'create', collection: 'bonuses', id: MONTH_BONUS, values: { jobId: NIGHT, date: d(2026, 11, 1), amount: 30000n, note: 'Premia', wholeMonth: true } } },
  { name: 'old-shape bonus edited on the web gets wholeMonth', op: { kind: 'update', collection: 'bonuses', id: OLD_BONUS, values: { jobId: CAFE, date: d(2026, 11, 3), amount: 2600n, note: 'Stara', wholeMonth: false }, base: 1_794_000_000_000 } },
  { name: 'bonus: deleted on the web (tombstone)', op: { kind: 'delete', collection: 'bonuses', id: DAY_BONUS, base: NOW } },
  { name: 'payout with a received date', op: { kind: 'create', collection: 'payouts', id: PAYOUT, values: { jobId: NIGHT, month: 202611, amount: 190000n, note: 'Przelew', receivedOn: d(2026, 12, 10) } } },
  { name: 'payout in euro, no date, no note', op: { kind: 'create', collection: 'payouts', id: EUR_PAYOUT, values: { jobId: EUR, month: 202610, amount: 12345n, note: null, receivedOn: null } } },
  { name: 'payout: edited on the web', op: { kind: 'update', collection: 'payouts', id: PAYOUT, values: { jobId: NIGHT, month: 202611, amount: 195000n, note: null, receivedOn: d(2026, 12, 11) }, base: NOW } },
];

function build() {
  const server: Record<string, ServerDocument> = { ...appDocuments };
  const out = steps.map(({ name, op }) => {
    const key = `${op.collection}/${op.id}`;
    const plan = planWrite(op, server[key] ?? null, NOW, LIVE);
    if (!plan.ok) throw new Error(`${name}: ${plan.refusal}`);
    server[key] = plan.fields;
    return { name, collection: op.collection, id: op.id, fields: plan.fields };
  });
  return {
    note: 'Written by web/test/unit/write-fixtures.test.ts from the web’s document builders (UPDATE_WEB_WRITES=1). Read by app/src/test/.../web/WebWritesCompatTest.kt.',
    jobs,
    appDocuments,
    steps: out,
  };
}

test('web-writes.json is what the web’s builders write today', () => {
  const json = JSON.stringify(build(), null, 1) + '\n';
  if (process.env['UPDATE_WEB_WRITES'] === '1') {
    mkdirSync(dirname(FILE), { recursive: true });
    writeFileSync(FILE, json);
  }
  expect(existsSync(FILE), 'web-writes.json missing: run with UPDATE_WEB_WRITES=1').toBe(true);
  expect(readFileSync(FILE, 'utf8')).toBe(json);
});
