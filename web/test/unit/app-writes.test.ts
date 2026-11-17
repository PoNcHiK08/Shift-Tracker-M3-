import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, test } from 'vitest';
import { readDocuments, readRecord } from '../../src/data/documents';
import { epochDay, yearMonth } from '../../src/domain/dates';
import { collectionOf, fieldsOf, valuesOf, type EditableCollection, type EditableRecord } from '../../src/edit/documents';

/*
 * What the app uploads for records saved on a phone (test/fixtures/writes/app-writes.json, written by the app's own
 * repositories and sync engine in app/src/test/.../web/WebWritesCompatTest.kt) is read by the web as the phone has it,
 * and the web's builders would write each of these documents back exactly as they are.
 */

const load = (name: string) => JSON.parse(readFileSync(resolve(__dirname, '../fixtures/writes', name), 'utf8'));
const app = load('app-writes.json');
const jobs = load('web-writes.json').jobs;

describe('documents the app wrote', () => {
  const documents = { jobs, shifts: app.documents.shifts, bonuses: app.documents.bonuses, payouts: app.documents.payouts };

  test('are all read; deleted ones are not shown', () => {
    const { view, skipped } = readDocuments(documents);
    expect(skipped).toBe(0);
    expect(view.shifts).toHaveLength(2);
    expect(view.bonuses).toHaveLength(2);
    expect(view.payouts).toHaveLength(2);
    expect(Object.keys(app.documents.shifts)).toHaveLength(3);
    expect(Object.keys(app.documents.payouts)).toHaveLength(3);
  });

  test('values as saved on the phone', () => {
    const { view } = readDocuments(documents);
    const night = view.shifts.find((s) => s.shift.start === 22 * 60)!;
    expect(night.shift).toMatchObject({ date: epochDay(2026, 11, 21), end: 6 * 60, breakMinutes: 30, rate: 3300n, secondRate: { rate: 3700n, from: 1380, to: 360 } });
    const day = view.shifts.find((s) => s.shift.start === 9 * 60)!;
    expect(day.shift).toMatchObject({ note: 'Z telefonu', rate: 3150n });
    expect(view.bonuses.map((b) => [b.bonus.wholeMonth, b.bonus.amount, b.bonus.date])).toEqual(
      expect.arrayContaining([[false, 4200n, epochDay(2026, 11, 20)], [true, 30000n, epochDay(2026, 11, 1)]]),
    );
    const received = view.payouts.find((p) => p.payout.receivedOn !== null)!;
    expect(received.payout).toMatchObject({ month: yearMonth(2026, 11), amount: 190000n, note: 'Przelew', receivedOn: epochDay(2026, 12, 10) });
    expect(view.payouts.find((p) => p.job.currency === 'EUR')!.payout).toMatchObject({ amount: 12345n, note: null, receivedOn: null });
  });

  test('the web would write each one back field for field', () => {
    let count = 0;
    for (const collection of ['shifts', 'bonuses', 'payouts'] as EditableCollection[]) {
      for (const [id, doc] of Object.entries(app.documents[collection] as Record<string, Record<string, unknown>>)) {
        const record = readRecord(collection, id, doc) as EditableRecord;
        expect(collectionOf(record)).toBe(collection);
        expect(fieldsOf(collection, valuesOf(record), { updatedAt: record.updatedAt, deleted: record.deleted }), `${collection}/${id}`).toEqual(doc);
        count++;
      }
    }
    expect(count).toBe(8);
  });
});
