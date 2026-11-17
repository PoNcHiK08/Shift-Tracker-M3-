import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, test } from 'vitest';
import { readDocuments, readRecord, type BonusRecord, type ShiftRecord } from '../../src/data/documents';
import { epochDay, yearMonth } from '../../src/domain/dates';
import type { Job } from '../../src/domain/model';
import {
  EDITABLE_COLLECTIONS,
  FIELD_SETS,
  bonusFields,
  checkWritable,
  newSyncId,
  nextUpdatedAt,
  payoutFields,
  shiftFields,
  type BonusValues,
  type PayoutValues,
  type ShiftValues,
} from '../../src/edit/documents';
import {
  bonusValues,
  checkBonus,
  checkPayout,
  checkShift,
  editBonusForm,
  editShiftForm,
  isDirty,
  newBonusForm,
  newPayoutForm,
  newShiftForm,
  payoutValues,
  selectShiftJob,
  setBonusMonth,
  setBreakText,
  setPayoutMonth,
  shiftValues,
} from '../../src/edit/forms';
import { cut, limitInput, noteValue, parseMinor, toInput } from '../../src/edit/money-input';
import { planWrite, type WriteOp } from '../../src/edit/plan';

/*
 * The documents the web writes (src/edit), against the app's own rules: exact field sets, limits, night shifts,
 * whole-month bonuses, tombstones, undo and the updatedAt rule.
 */

const JOB = '11111111-1111-4111-8111-111111111111';
const OTHER = '22222222-2222-4222-8222-222222222222';
const ID = '33333333-3333-4333-8333-333333333333';
const LIVE = new Set([JOB, OTHER]);
const DAY = epochDay(2026, 11, 13);

const job = (over: Partial<Job> = {}): Job => ({
  id: JOB, name: 'Kawiarnia', hourlyRate: 3150n, color: -1, secondRate: null, currency: 'PLN', payHistory: [], firstEntry: null, lastEntry: null, ...over,
});
const nightJob = job({ id: OTHER, name: 'Magazyn', hourlyRate: 3300n, secondRate: { rate: 3700n, from: 23 * 60, to: 6 * 60 } });
const JOBS = [job(), nightJob];

const shift: ShiftValues = {
  jobId: JOB, date: DAY, start: 9 * 60, end: 17 * 60, breakMinutes: 30, rate: 3150n, note: 'Rano', externalUid: null, secondRate: null,
};
const bonus: BonusValues = { jobId: JOB, date: DAY, amount: 4500n, note: null, wholeMonth: false };
const payout: PayoutValues = { jobId: JOB, month: 202611, amount: 160000n, note: 'Przelew', receivedOn: epochDay(2026, 12, 10) };

describe('exact field sets', () => {
  test('the same lists firestore.rules checks, for exactly three kinds (never jobs)', () => {
    const rules = readFileSync(resolve(__dirname, '../../../firebase/firestore.rules'), 'utf8');
    const list = (fn: string) => {
      const body = rules.slice(rules.indexOf(`function ${fn}(d)`));
      const lists = [...body.slice(0, body.indexOf('\n    }')).matchAll(/hasExactly\(d, \[([^\]]+)\]/g)].map((m) =>
        m[1]!.split(',').map((s) => s.trim().replace(/'/g, '')),
      );
      return lists[lists.length - 1]; // a bonus has two shapes: the current one (with wholeMonth) is the last
    };
    expect(FIELD_SETS.shifts).toEqual(list('isShift'));
    expect(FIELD_SETS.bonuses).toEqual(list('isBonus'));
    expect(FIELD_SETS.payouts).toEqual(list('isPayout'));
    expect(EDITABLE_COLLECTIONS).toEqual(['shifts', 'bonuses', 'payouts']);
  });

  test('each builder writes exactly its set (serverUpdatedAt is the server’s), as whole numbers', () => {
    const meta = { updatedAt: 1_700_000_000_000, deleted: false };
    const cases = [
      ['shifts', shiftFields(shift, meta)],
      ['bonuses', bonusFields(bonus, meta)],
      ['payouts', payoutFields(payout, meta)],
    ] as const;
    for (const [collection, fields] of cases) {
      expect(Object.keys(fields).sort()).toEqual(FIELD_SETS[collection].filter((f) => f !== 'serverUpdatedAt').sort());
      for (const value of Object.values(fields)) if (typeof value === 'number') expect(Number.isSafeInteger(value)).toBe(true);
      expect(checkWritable(collection, ID, fields)).toBeNull();
    }
    expect(shiftFields(shift, meta)).toEqual({
      v: 1, updatedAt: meta.updatedAt, deleted: false, jobId: JOB, dateEpochDay: DAY, startMinute: 540, endMinute: 1020,
      unpaidBreakMinutes: 30, hourlyRateGrosze: 3150, note: 'Rano', externalUid: null, secondRate: null,
    });
    expect(payoutFields(payout, meta)).toEqual({
      v: 1, updatedAt: meta.updatedAt, deleted: false, jobId: JOB, month: 202611, amountMinor: 160000, note: 'Przelew',
      receivedEpochDay: epochDay(2026, 12, 10),
    });
  });

  test('a bonus is always written with wholeMonth; a second rate as the app nests it', () => {
    expect(bonusFields(bonus, { updatedAt: 1, deleted: false })).toHaveProperty('wholeMonth', false);
    expect(bonusFields({ ...bonus, wholeMonth: true }, { updatedAt: 1, deleted: false })).toHaveProperty('wholeMonth', true);
    const night = shiftFields({ ...shift, jobId: OTHER, rate: 3300n, secondRate: nightJob.secondRate }, { updatedAt: 1, deleted: false });
    expect(night['secondRate']).toEqual({ rateGrosze: 3700, fromMinute: 1380, toMinute: 360 });
  });

  test('a document with a field too many or too few, or that the app couldn’t use, is not writable', () => {
    const fields = shiftFields(shift, { updatedAt: 1, deleted: false });
    expect(checkWritable('shifts', ID, { ...fields, extra: 1 })).toBe('fields');
    const { note: _n, ...missing } = fields;
    expect(checkWritable('shifts', ID, missing)).toBe('fields');
    expect(checkWritable('shifts', 'NOT-A-UUID', fields)).toBe('unusable');
    expect(checkWritable('shifts', ID, { ...fields, unpaidBreakMinutes: 480 })).toBe('unusable');
    expect(checkWritable('bonuses', ID, bonusFields({ ...bonus, amount: 0n }, { updatedAt: 1, deleted: false }))).toBe('unusable');
    expect(checkWritable('payouts', ID, payoutFields({ ...payout, month: 202613 }, { updatedAt: 1, deleted: false }))).toBe('unusable');
    expect(checkWritable('shifts', ID, shiftFields({ ...shift, note: 'x'.repeat(20_001) }, { updatedAt: 1, deleted: false }))).toBe('unusable');
    expect(checkWritable('shifts', ID, shiftFields({ ...shift, note: 'x'.repeat(20_000) }, { updatedAt: 1, deleted: false }))).toBeNull();
  });

  test('new ids are random lowercase UUIDs', () => {
    const ids = new Set(Array.from({ length: 50 }, newSyncId));
    expect(ids.size).toBe(50);
    for (const id of ids) expect(id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  });
});

describe('amounts and text (MoneyParser, TextLimits)', () => {
  test('as the app parses them', () => {
    expect(parseMinor('27,70', 2)).toBe(2770n);
    expect(parseMinor('27.7', 2)).toBe(2770n);
    expect(parseMinor(' 30 ', 2)).toBe(3000n);
    expect(parseMinor('1 234,56', 2)).toBe(123456n);
    expect(parseMinor('1 234,56', 2)).toBe(123456n);
    for (const bad of ['', 'abc', '-5', '1,234', '1e3', '+5', '٣']) expect(parseMinor(bad, 2), bad).toBeNull();
    expect(parseMinor('9999999,99', 2)).toBe(999_999_999n);
    expect(parseMinor('10000000', 2)).toBeNull();
    expect(parseMinor('1500', 0)).toBe(1500n);
    expect(parseMinor('1500,5', 0)).toBeNull();
    expect(parseMinor('12,3', 3)).toBe(12_300n);
    expect(parseMinor('12.3456', 3)).toBeNull();
    expect(parseMinor('9999999999.999', 3, 10)).toBe(9_999_999_999_999n);
    expect(parseMinor('10000000000', 2, 10)).toBeNull();
    expect(toInput(2770n, 2)).toBe('27,70');
    expect(toInput(3000n, 2)).toBe('30');
    expect(toInput(5n, 2)).toBe('0,05');
    expect(toInput(12_005n, 3, '.')).toBe('12.005');
  });

  test('notes: limit 20 000 UTF-16 units, never splitting an emoji; trimmed, empty = null', () => {
    expect(limitInput('', 'a'.repeat(20_001))).toHaveLength(20_000);
    expect(cut('a'.repeat(19_999) + '😀', 20_000)).toHaveLength(19_999);
    const over = 'b'.repeat(20_010);
    expect(limitInput(over, over.slice(0, 20_005))).toHaveLength(20_005); // already over: may only shrink
    expect(limitInput(over, over + 'c')).toBe(over);
    expect(noteValue('  ')).toBeNull();
    expect(noteValue('  Napiwki ')).toBe('Napiwki');
  });
});

describe('shift form', () => {
  test('a new shift: first job, its rates, 08:00–16:00', () => {
    const form = newShiftForm(JOBS, DAY);
    expect(form).toMatchObject({ jobId: JOB, start: 480, end: 960, breakText: '0', rate: 3150n, secondRate: null });
    expect(shiftValues(form, JOBS)).toMatchObject({ jobId: JOB, breakMinutes: 0, note: null, externalUid: null });
  });

  test('picking another job takes its rates; an existing shift keeps its snapshot and only hints', () => {
    const picked = selectShiftJob(newShiftForm(JOBS, DAY), nightJob);
    expect([picked.rate, picked.secondRate]).toEqual([3300n, nightJob.secondRate]);
    const record: ShiftRecord = { kind: 'shift', id: ID, updatedAt: 5, deleted: false, schema: 1, ...shift, rate: 3000n, externalUid: 'ics-1' };
    const form = editShiftForm(record);
    const check = checkShift(form, JOBS);
    expect(check.rateOutdated).toBe(true);
    expect(shiftValues(form, JOBS)).toMatchObject({ rate: 3000n, externalUid: 'ics-1' });
    expect(checkShift(newShiftForm(JOBS, DAY), JOBS).rateOutdated).toBe(false);
  });

  test('night shifts end the next day; the break must be shorter than the shift', () => {
    const night = { ...newShiftForm(JOBS, DAY), start: 22 * 60, end: 6 * 60 };
    const check = checkShift(night, JOBS);
    expect([check.overnight, check.duration]).toEqual([true, 480]);
    expect(checkShift({ ...night, end: 22 * 60 }, JOBS).duration).toBe(1440);
    expect(checkShift(setBreakText(night, '480'), JOBS).breakTooLong).toBe(true);
    expect(checkShift(setBreakText(night, '479'), JOBS).canSave).toBe(true);
    expect(setBreakText(night, '3a0x99').breakText).toBe('3099');
    expect(checkShift(setBreakText(night, '3099'), JOBS).breakInvalid).toBe(true);
    const values = shiftValues(night, JOBS)!;
    expect(checkWritable('shifts', ID, shiftFields(values, { updatedAt: 1, deleted: false }))).toBeNull();
  });

  test('no job, a long note: can’t save', () => {
    expect(checkShift(newShiftForm([], DAY), []).canSave).toBe(false);
    expect(checkShift({ ...newShiftForm(JOBS, DAY), note: 'x'.repeat(20_001) }, JOBS).canSave).toBe(false);
  });

  test('unsaved changes are noticed', () => {
    const form = newShiftForm(JOBS, DAY);
    expect(isDirty(form, { ...form })).toBe(false);
    expect(isDirty(form, { ...form, note: 'x' })).toBe(true);
    expect(isDirty(form, { ...form, rate: 1n })).toBe(true);
  });
});

describe('bonus and payout forms', () => {
  test('a whole-month bonus is stored on the 1st of its month; switching back keeps the day', () => {
    const form = { ...newBonusForm(JOBS, DAY), amountText: '500', wholeMonth: true };
    expect(bonusValues(form, JOBS)).toMatchObject({ date: epochDay(2026, 11, 1), wholeMonth: true, amount: 50000n });
    expect(bonusValues({ ...form, wholeMonth: false }, JOBS)).toMatchObject({ date: DAY, wholeMonth: false });
    expect(setBonusMonth(form, yearMonth(2027, 2)).date).toBe(epochDay(2027, 2, 1));
  });

  test('an old-shape bonus (no wholeMonth) is edited as a day bonus and written with the field', () => {
    const doc: Record<string, unknown> = { v: 1, updatedAt: 5, deleted: false, jobId: JOB, dateEpochDay: DAY, amountGrosze: 4500, note: null };
    const record = readRecord('bonuses', ID, doc) as BonusRecord;
    const values = bonusValues(editBonusForm(record, job()), JOBS)!;
    expect(bonusFields(values, { updatedAt: 6, deleted: false })).toMatchObject({ wholeMonth: false, amountGrosze: 4500 });
  });

  test('amounts: more than zero, the job currency’s decimals, up to 10 whole digits', () => {
    const eurJob = job({ id: OTHER, currency: 'EUR' });
    const yen = job({ id: OTHER, currency: 'JPY' });
    expect(checkBonus({ ...newBonusForm(JOBS, DAY), amountText: '0' }, JOBS).canSave).toBe(false);
    expect(checkBonus({ ...newBonusForm([yen], DAY), amountText: '15.5' }, [yen]).amount).toBeNull();
    expect(checkBonus({ ...newBonusForm([yen], DAY), amountText: '1500' }, [yen]).amount).toBe(1500n);
    expect(checkPayout({ ...newPayoutForm(eurJob, yearMonth(2026, 11)), amountText: '9999999999,99' }, [eurJob]).amount).toBe(999_999_999_999n);
    expect(checkPayout({ ...newPayoutForm(eurJob, yearMonth(2026, 11)), amountText: '10000000000' }, [eurJob]).canSave).toBe(false);
  });

  test('a payout belongs to the month it was earned for (years 1000–9999); received-on is optional', () => {
    const form = { ...newPayoutForm(job(), yearMonth(2026, 11)), amountText: '1600' };
    expect(payoutValues(form, JOBS)).toEqual({ jobId: JOB, month: 202611, amount: 160000n, note: null, receivedOn: null });
    expect(setPayoutMonth(form, yearMonth(10000, 1)).month).toBe(form.month);
    expect(setPayoutMonth(form, yearMonth(999, 12)).month).toBe(form.month);
    expect(setPayoutMonth(form, yearMonth(2027, 1)).month).toBe(yearMonth(2027, 1));
  });
});

describe('writes: updatedAt, conflicts, tombstones, undo', () => {
  const NOW = 1_800_000_000_000;
  const doc = (over: Record<string, unknown> = {}) => ({ ...shiftFields(shift, { updatedAt: 1000, deleted: false }), serverUpdatedAt: { seconds: 1 }, ...over });

  test('updatedAt is the clock, or one millisecond after the current version when the clock is behind it', () => {
    expect(nextUpdatedAt(NOW, null)).toBe(NOW);
    expect(nextUpdatedAt(NOW, 1000)).toBe(NOW);
    expect(nextUpdatedAt(NOW, NOW)).toBe(NOW + 1);
    expect(nextUpdatedAt(NOW, NOW + 5 * 60_000)).toBe(NOW + 5 * 60_000 + 1);
  });

  test('create: a new document, only for a live job, never over an existing one', () => {
    const op: WriteOp = { kind: 'create', collection: 'shifts', id: ID, values: shift };
    const plan = planWrite(op, null, NOW, LIVE);
    expect(plan).toMatchObject({ ok: true, updatedAt: NOW, fields: { deleted: false, updatedAt: NOW } });
    expect(planWrite(op, doc(), NOW, LIVE)).toEqual({ ok: false, refusal: 'exists' });
    expect(planWrite(op, null, NOW, new Set([OTHER]))).toEqual({ ok: false, refusal: 'no-job' });
  });

  test('update: only over the version the form was opened from; always newer than it', () => {
    const op: WriteOp = { kind: 'update', collection: 'shifts', id: ID, values: { ...shift, note: 'Nowa' }, base: 1000 };
    expect(planWrite(op, doc(), NOW, LIVE)).toMatchObject({ ok: true, fields: { note: 'Nowa', updatedAt: NOW } });
    expect(planWrite(op, doc({ updatedAt: 2000 }), NOW, LIVE)).toEqual({ ok: false, refusal: 'changed' });
    expect(planWrite(op, null, NOW, LIVE)).toEqual({ ok: false, refusal: 'changed' });
    expect(planWrite(op, doc({ deleted: true }), NOW, LIVE)).toEqual({ ok: false, refusal: 'changed' });
    expect(planWrite(op, doc({ v: 2 }), NOW, LIVE)).toEqual({ ok: false, refusal: 'unreadable' });
    expect(planWrite(op, doc({ startMinute: 'x' }), NOW, LIVE)).toEqual({ ok: false, refusal: 'unreadable' });
    // The other device's clock is ahead of this one: still newer.
    const ahead = planWrite({ ...op, base: NOW + 60_000 }, doc({ updatedAt: NOW + 60_000 }), NOW, LIVE);
    expect(ahead).toMatchObject({ ok: true, updatedAt: NOW + 60_001 });
  });

  test('delete writes a tombstone with the record’s values and a newer updatedAt; undo writes them back, newer still', () => {
    const del = planWrite({ kind: 'delete', collection: 'shifts', id: ID, base: 1000 }, doc(), NOW, LIVE);
    expect(del).toMatchObject({ ok: true, fields: { deleted: true, note: 'Rano', startMinute: 540 } });
    if (!del.ok) throw new Error();
    expect(del.updatedAt).toBeGreaterThan(1000);
    const tombstone = { ...del.fields, serverUpdatedAt: { seconds: 2 } };
    const undo = planWrite({ kind: 'restore', collection: 'shifts', id: ID, values: shift, base: del.updatedAt }, tombstone, NOW, LIVE);
    expect(undo).toMatchObject({ ok: true, updatedAt: NOW + 1, fields: { deleted: false, note: 'Rano' } });
    // Undo only puts back the tombstone it made: anything newer on the server wins.
    expect(planWrite({ kind: 'restore', collection: 'shifts', id: ID, values: shift, base: del.updatedAt }, doc({ updatedAt: NOW + 5 }), NOW, LIVE))
      .toEqual({ ok: false, refusal: 'changed' });
    expect(planWrite({ kind: 'delete', collection: 'shifts', id: ID, base: 1000 }, tombstone, NOW, LIVE)).toEqual({ ok: false, refusal: 'changed' });
  });

  test('what the web writes reads back as the phone and the web show it', () => {
    const plans = [
      planWrite({ kind: 'create', collection: 'shifts', id: ID, values: shift }, null, NOW, LIVE),
      planWrite({ kind: 'create', collection: 'bonuses', id: OTHER.replace('2222-4', '2223-4'), values: { ...bonus, wholeMonth: true, date: epochDay(2026, 11, 1) } }, null, NOW, LIVE),
      planWrite({ kind: 'create', collection: 'payouts', id: ID.replace('3333-4', '3334-4'), values: payout }, null, NOW, LIVE),
    ];
    const [s, b, p] = plans.map((plan) => (plan.ok ? plan.fields : null));
    const jobDoc = { v: 1, updatedAt: 1, deleted: false, name: 'Kawiarnia', hourlyRateGrosze: 3150, color: -1, secondRate: null, currency: 'PLN', payHistory: [] };
    const { view, skipped } = readDocuments({
      jobs: { [JOB]: jobDoc },
      shifts: { [ID]: s! },
      bonuses: { [OTHER.replace('2222-4', '2223-4')]: b! },
      payouts: { [ID.replace('3333-4', '3334-4')]: p! },
    });
    expect(skipped).toBe(0);
    expect(view.shifts[0]!.shift).toMatchObject({ start: 540, end: 1020, breakMinutes: 30, rate: 3150n, note: 'Rano' });
    expect(view.bonuses[0]!.bonus).toMatchObject({ wholeMonth: true, amount: 4500n });
    expect(view.payouts[0]!.payout).toMatchObject({ month: yearMonth(2026, 11), amount: 160000n, receivedOn: epochDay(2026, 12, 10) });
  });
});
