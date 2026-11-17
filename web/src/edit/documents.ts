import { DOC_SCHEMA, isUsable, readRecord, type BonusRecord, type CloudRecord, type PayoutRecord, type ShiftRecord } from '../data/documents';
import type { RawDocument } from '../data/documents-types';
import type { TimedRate } from '../domain/model';

/*
 * The documents the web writes, field for field as the app writes them (data/sync/firebase/FirestoreDocuments.kt
 * toFields; firestore.rules checks the same exact sets). Only three kinds: shifts, bonuses and payouts. Jobs are
 * never written.
 *
 *   shifts/{syncId}  { v, updatedAt, serverUpdatedAt, deleted, jobId, dateEpochDay, startMinute, endMinute,
 *                      unpaidBreakMinutes, hourlyRateGrosze, note, externalUid, secondRate }
 *   bonuses/{syncId} { v, updatedAt, serverUpdatedAt, deleted, jobId, dateEpochDay, amountGrosze, note, wholeMonth }
 *   payouts/{syncId} { v, updatedAt, serverUpdatedAt, deleted, jobId, month, amountMinor, note, receivedEpochDay }
 *
 * The builders leave out serverUpdatedAt: the write layer adds the server's timestamp (as FirestoreRemoteStore).
 * Every number is a whole JS number, which Firestore stores as an integer (money stays far below 2^53).
 */

export type EditableCollection = 'shifts' | 'bonuses' | 'payouts';
export const EDITABLE_COLLECTIONS: readonly EditableCollection[] = ['shifts', 'bonuses', 'payouts'];

/** The exact fields of each kind, serverUpdatedAt included (as firestore.rules lists them). */
export const FIELD_SETS: Record<EditableCollection, readonly string[]> = {
  shifts: ['v', 'updatedAt', 'serverUpdatedAt', 'deleted', 'jobId', 'dateEpochDay', 'startMinute', 'endMinute', 'unpaidBreakMinutes', 'hourlyRateGrosze', 'note', 'externalUid', 'secondRate'],
  bonuses: ['v', 'updatedAt', 'serverUpdatedAt', 'deleted', 'jobId', 'dateEpochDay', 'amountGrosze', 'note', 'wholeMonth'],
  payouts: ['v', 'updatedAt', 'serverUpdatedAt', 'deleted', 'jobId', 'month', 'amountMinor', 'note', 'receivedEpochDay'],
};

/** What a shift, bonus or payout holds, without the sync bookkeeping (id, updatedAt, deleted, schema). */
export type ShiftValues = Omit<ShiftRecord, 'kind' | 'id' | 'updatedAt' | 'deleted' | 'schema'>;
export type BonusValues = Omit<BonusRecord, 'kind' | 'id' | 'updatedAt' | 'deleted' | 'schema'>;
export type PayoutValues = Omit<PayoutRecord, 'kind' | 'id' | 'updatedAt' | 'deleted' | 'schema'>;

export type EditableRecord = ShiftRecord | BonusRecord | PayoutRecord;
export type Values = ShiftValues | BonusValues | PayoutValues;

/** A document's fields as written (no serverUpdatedAt). */
export type Fields = Record<string, string | number | boolean | null | Record<string, number>>;

export interface Meta {
  updatedAt: number;
  deleted: boolean;
}

const int = (value: bigint): number => {
  const n = Number(value);
  if (!Number.isSafeInteger(n)) throw new RangeError(`not a whole number Firestore can store exactly: ${value}`);
  return n;
};

const timedRate = (rate: TimedRate | null) => (rate ? { rateGrosze: int(rate.rate), fromMinute: rate.from, toMinute: rate.to } : null);

function common(meta: Meta) {
  return { v: DOC_SCHEMA, updatedAt: meta.updatedAt, deleted: meta.deleted };
}

export function shiftFields(values: ShiftValues, meta: Meta): Fields {
  return {
    ...common(meta),
    jobId: values.jobId,
    dateEpochDay: values.date,
    startMinute: values.start,
    endMinute: values.end,
    unpaidBreakMinutes: values.breakMinutes,
    hourlyRateGrosze: int(values.rate),
    note: values.note,
    externalUid: values.externalUid,
    secondRate: timedRate(values.secondRate),
  };
}

/** Always with wholeMonth, as the app writes it (a document without it is the previous app version's shape). */
export function bonusFields(values: BonusValues, meta: Meta): Fields {
  return {
    ...common(meta),
    jobId: values.jobId,
    dateEpochDay: values.date,
    amountGrosze: int(values.amount),
    note: values.note,
    wholeMonth: values.wholeMonth,
  };
}

/** [values.month] is the yyyymm key (202611), as stored. */
export function payoutFields(values: PayoutValues, meta: Meta): Fields {
  return {
    ...common(meta),
    jobId: values.jobId,
    month: values.month,
    amountMinor: int(values.amount),
    note: values.note,
    receivedEpochDay: values.receivedOn,
  };
}

export function fieldsOf(collection: EditableCollection, values: Values, meta: Meta): Fields {
  switch (collection) {
    case 'shifts':
      return shiftFields(values as ShiftValues, meta);
    case 'bonuses':
      return bonusFields(values as BonusValues, meta);
    case 'payouts':
      return payoutFields(values as PayoutValues, meta);
  }
}

/** The values a record holds (to edit it, delete it or put it back). */
export function valuesOf(record: EditableRecord): Values {
  const { kind: _kind, id: _id, updatedAt: _u, deleted: _d, schema: _s, ...values } = record;
  return values;
}

export const collectionOf = (record: EditableRecord): EditableCollection =>
  record.kind === 'shift' ? 'shifts' : record.kind === 'bonus' ? 'bonuses' : 'payouts';

/**
 * Last write wins (LastWriteWins.kt): a write must be newer than the version it replaces, and firestore.rules
 * refuses one that isn't. The browser's clock, or one millisecond after the current version when the clock is
 * behind it (another device's clock ahead of this one).
 */
export function nextUpdatedAt(now: number, current: number | null): number {
  return current === null ? now : Math.max(now, current + 1);
}

/** A new record's sync id: a random lowercase UUID, as the app makes them (also the document id). */
export function newSyncId(): string {
  return crypto.randomUUID();
}

/**
 * Checks a document before it is written: the exact field set of its kind, and the app would read and use it
 * (FirestoreDocuments.readRecord, SyncMapping.isUsable). A document that fails is never sent.
 */
export function checkWritable(collection: EditableCollection, id: string, fields: Fields): string | null {
  const expected = FIELD_SETS[collection].filter((f) => f !== 'serverUpdatedAt');
  const keys = Object.keys(fields);
  if (keys.length !== expected.length || !expected.every((k) => keys.includes(k))) return 'fields';
  const record: CloudRecord | null = readRecord(collection, id, fields as RawDocument);
  if (!record) return 'unreadable';
  if (!isUsable(record)) return 'unusable';
  return null;
}

export class NotWritableError extends Error {
  constructor(readonly reason: string) {
    super(`document not writable: ${reason}`);
  }
}

export function assertWritable(collection: EditableCollection, id: string, fields: Fields): void {
  const problem = checkWritable(collection, id, fields);
  if (problem) throw new NotWritableError(problem);
}
