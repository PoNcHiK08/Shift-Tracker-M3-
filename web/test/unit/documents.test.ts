import { describe, expect, test } from 'vitest';
import { readDocuments, readRecord } from '../../src/data/documents';
import { emptyDocuments, type CloudDocuments, type RawDocument } from '../../src/data/documents-types';
import { epochDay } from '../../src/domain/dates';

/*
 * Reading the cloud documents as the phone reads them (FirestoreDocuments.kt, SyncMapping.kt, SyncEngine.kt): what
 * is shown, and what is skipped without breaking the rest.
 */

const JOB = '11111111-1111-4111-8111-111111111111';
const OTHER_JOB = '22222222-2222-4222-8222-222222222222';
const id = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const DAY = epochDay(2026, 11, 2);

const job = (over: RawDocument = {}): RawDocument => ({
  v: 1, updatedAt: 1000, deleted: false, name: 'Kawiarnia', hourlyRateGrosze: 3000, color: -1, secondRate: null,
  currency: 'PLN', payHistory: [], ...over,
});
const shift = (over: RawDocument = {}): RawDocument => ({
  v: 1, updatedAt: 1000, deleted: false, jobId: JOB, dateEpochDay: DAY, startMinute: 540, endMinute: 1020,
  unpaidBreakMinutes: 0, hourlyRateGrosze: 3000, note: null, externalUid: null, secondRate: null, ...over,
});
const bonus = (over: RawDocument = {}): RawDocument => ({
  v: 1, updatedAt: 1000, deleted: false, jobId: JOB, dateEpochDay: DAY, amountGrosze: 5000, note: null, wholeMonth: false, ...over,
});
const payout = (over: RawDocument = {}): RawDocument => ({
  v: 1, updatedAt: 1000, deleted: false, jobId: JOB, month: 202611, amountMinor: 100000, note: null, receivedEpochDay: null, ...over,
});

function docs(parts: Partial<CloudDocuments>): CloudDocuments {
  return { ...emptyDocuments(), jobs: { [JOB]: job() }, ...parts };
}

describe('tombstones', () => {
  test('a deleted shift, bonus, payout or job is never shown', () => {
    const { view, skipped } = readDocuments({
      jobs: { [JOB]: job(), [OTHER_JOB]: job({ name: 'Old', deleted: true }) },
      shifts: { [id(1)]: shift(), [id(2)]: shift({ deleted: true, startMinute: 1100, endMinute: 1200 }) },
      bonuses: { [id(3)]: bonus({ deleted: true }) },
      payouts: { [id(4)]: payout({ deleted: true }) },
    });
    expect(view.jobs.map((j) => j.id)).toEqual([JOB]);
    expect(view.shifts.map((s) => s.shift.id)).toEqual([id(1)]);
    expect(view.bonuses).toEqual([]);
    expect(view.payouts).toEqual([]);
    expect(skipped).toBe(0);
  });

  test('entries of a deleted job go with it, unless changed after the deletion (ParentChildRule)', () => {
    const { view } = readDocuments({
      jobs: { [JOB]: job({ deleted: true, updatedAt: 5000 }) },
      shifts: { [id(1)]: shift({ updatedAt: 4000 }) },
      bonuses: {},
      payouts: {},
    });
    expect(view.jobs).toEqual([]);
    expect(view.shifts).toEqual([]);

    const back = readDocuments({
      jobs: { [JOB]: job({ deleted: true, updatedAt: 5000 }) },
      shifts: { [id(1)]: shift({ updatedAt: 4000 }), [id(2)]: shift({ updatedAt: 6000, startMinute: 1100, endMinute: 1200 }) },
      bonuses: {},
      payouts: {},
    }).view;
    expect(back.jobs.map((j) => j.id)).toEqual([JOB]);
    expect(back.shifts.map((s) => s.shift.id)).toEqual([id(2)]);
  });

  test('a deleted job takes its salary activity with it: no first or last entry from deleted shifts', () => {
    const { view } = readDocuments(docs({ shifts: { [id(1)]: shift({ deleted: true }), [id(2)]: shift({ dateEpochDay: DAY + 3 }) } }));
    expect(view.jobs[0]!.firstEntry).toBe(DAY + 3);
    expect(view.jobs[0]!.lastEntry).toBe(DAY + 3);
  });
});

describe('fields', () => {
  test('unknown fields (a newer app version, serverUpdatedAt) are ignored', () => {
    const { view, skipped } = readDocuments(
      docs({ shifts: { [id(1)]: shift({ serverUpdatedAt: { seconds: 1 }, tips: 12, somethingNew: 'x' }) } }),
    );
    expect(view.shifts).toHaveLength(1);
    expect(skipped).toBe(0);
  });

  test('a missing or mistyped field makes only that document unreadable', () => {
    const missing = shift();
    delete missing['unpaidBreakMinutes'];
    const { view, skipped } = readDocuments(
      docs({
        shifts: {
          [id(1)]: missing,
          [id(2)]: shift({ startMinute: '540' }),
          [id(3)]: shift({ hourlyRateGrosze: 12.5 }),
          [id(4)]: shift({ deleted: 'no' }),
          [id(5)]: shift({ startMinute: 600, endMinute: 660 }),
        },
      }),
    );
    expect(view.shifts.map((s) => s.shift.id)).toEqual([id(5)]);
    expect(skipped).toBe(4);
  });

  test('a note key must be there (null or text), as the app always writes it', () => {
    const noNote = bonus();
    delete noNote['note'];
    expect(readRecord('bonuses', id(1), noNote)).toBeNull();
    expect(readRecord('bonuses', id(1), bonus({ note: null }))).not.toBeNull();
    expect(readRecord('bonuses', id(1), bonus({ note: 7 }))).toBeNull();
  });

  test('a bonus in the old shape (no wholeMonth) is a bonus on its day', () => {
    const old = bonus();
    delete old['wholeMonth'];
    const { view } = readDocuments(docs({ bonuses: { [id(1)]: old, [id(2)]: bonus({ wholeMonth: true }) } }));
    expect(view.bonuses.map((b) => [b.bonus.id, b.bonus.wholeMonth])).toEqual([
      [id(1), false],
      [id(2), true],
    ]);
    expect(readRecord('bonuses', id(3), bonus({ wholeMonth: 'yes' }))).toBeNull();
  });

  test('a newer layout version, a bad id or values outside the editors’ limits are skipped', () => {
    const { view, skipped } = readDocuments(
      docs({
        shifts: {
          [id(1)]: shift({ v: 2 }),
          'not-a-uuid': shift(),
          ['AAAAAAAA-0000-4000-8000-00000000000A']: shift(),
          [id(3)]: shift({ startMinute: 1440 }),
          [id(4)]: shift({ unpaidBreakMinutes: 480 }),
          [id(5)]: shift({ hourlyRateGrosze: -1 }),
        },
        bonuses: { [id(6)]: bonus({ amountGrosze: 0 }) },
        payouts: { [id(7)]: payout({ month: 202613 }) },
      }),
    );
    expect(view.shifts).toEqual([]);
    expect(view.bonuses).toEqual([]);
    expect(view.payouts).toEqual([]);
    expect(skipped).toBe(8);
  });

  test('an entry whose job is missing is skipped; a job with an unknown currency is skipped with it', () => {
    const { view, skipped } = readDocuments({
      jobs: { [JOB]: job(), [OTHER_JOB]: job({ currency: 'XYZ' }) },
      shifts: { [id(1)]: shift({ jobId: OTHER_JOB }), [id(2)]: shift({ jobId: '33333333-3333-4333-8333-333333333333' }) },
      bonuses: {},
      payouts: {},
    });
    expect(view.jobs.map((j) => j.id)).toEqual([JOB]);
    expect(view.shifts).toEqual([]);
    expect(skipped).toBe(3);
  });

  test('an unknown pay type (newer app) reads as hourly; pay history is in month order', () => {
    const { view } = readDocuments(
      docs({
        jobs: {
          [JOB]: job({
            payHistory: [
              { fromMonth: 202610, payType: 'FIXED_MONTHLY', monthlyAmountMinor: 500000, deductionBp: 0 },
              { fromMonth: 0, payType: 'PIECEWORK', monthlyAmountMinor: 0, deductionBp: 1371 },
            ],
          }),
        },
      }),
    );
    expect(view.jobs[0]!.payHistory.map((p) => [p.from, p.payType, p.deductionBp])).toEqual([
      [null, 'HOURLY', 1371],
      [2026 * 12 + 9, 'FIXED_MONTHLY', 0],
    ]);
  });

  test('a collection missing altogether reads as empty', () => {
    const { view } = readDocuments({ jobs: { [JOB]: job() } } as unknown as CloudDocuments);
    expect(view.jobs).toHaveLength(1);
    expect(view.shifts).toEqual([]);
  });
});
