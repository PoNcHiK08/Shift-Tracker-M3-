import { isUsable, readRecord } from '../data/documents';
import type { RawDocument } from '../data/documents-types';
import {
  assertWritable,
  fieldsOf,
  nextUpdatedAt,
  valuesOf,
  type EditableCollection,
  type EditableRecord,
  type Fields,
  type Values,
} from './documents';

/*
 * What one write from the web does, decided from the document as the server has it right now (read in the same
 * transaction). Never a blind overwrite: an edit or delete goes through only while the server still has the version
 * the form was opened from; otherwise it is refused ("changed on another device"). Never a hard delete: deleting
 * writes a tombstone, and Undo writes the record back, both with a newer updatedAt.
 */

export type WriteOp =
  | { kind: 'create'; collection: EditableCollection; id: string; values: Values }
  /** [base]: the updatedAt of the version the form was opened from. */
  | { kind: 'update'; collection: EditableCollection; id: string; values: Values; base: number }
  | { kind: 'delete'; collection: EditableCollection; id: string; base: number }
  /** Undo of a delete: [base] is the tombstone's updatedAt, [values] what it held before. */
  | { kind: 'restore'; collection: EditableCollection; id: string; values: Values; base: number };

/** The document as the server has it now (null: there is none). */
export type ServerDocument = RawDocument | null;

export type Refusal =
  /** The server has another version than the one the form was opened from (or the record is gone). */
  | 'changed'
  /** A new record's id is taken (practically impossible with random UUIDs). */
  | 'exists'
  /** The server's document is one this app can't read (a newer app wrote it): left alone. */
  | 'unreadable'
  /** The record's job isn't one of the loaded, live jobs. */
  | 'no-job';

export type Plan = { ok: true; fields: Fields; updatedAt: number } | { ok: false; refusal: Refusal };

const KIND: Record<EditableCollection, EditableRecord['kind']> = { shifts: 'shift', bonuses: 'bonus', payouts: 'payout' };

function current(collection: EditableCollection, id: string, doc: RawDocument): EditableRecord | null {
  const record = readRecord(collection, id, doc);
  // A document the app couldn't use either (a newer layout, values out of range) is left as it is.
  return record && record.kind === KIND[collection] && isUsable(record) ? (record as EditableRecord) : null;
}

/**
 * The fields to write for [op] over [server], at [now] (the browser's clock). [liveJobIds]: the jobs a record may
 * belong to (loaded and not deleted). The fields are checked like the app checks a document before they are
 * returned (assertWritable): a document the app couldn't use is never written.
 */
export function planWrite(op: WriteOp, server: ServerDocument, now: number, liveJobIds: ReadonlySet<string>): Plan {
  const refuse = (refusal: Refusal): Plan => ({ ok: false, refusal });
  const write = (values: Values, deleted: boolean, previous: number | null): Plan => {
    const updatedAt = nextUpdatedAt(now, previous);
    const fields = fieldsOf(op.collection, values, { updatedAt, deleted });
    assertWritable(op.collection, op.id, fields);
    return { ok: true, fields, updatedAt };
  };

  if (op.kind === 'create') {
    if (server !== null) return refuse('exists');
    if (!liveJobIds.has(op.values.jobId)) return refuse('no-job');
    return write(op.values, false, null);
  }

  if (server === null) return refuse('changed');
  const record = current(op.collection, op.id, server);
  if (!record) return refuse('unreadable');
  if (record.updatedAt !== op.base) return refuse('changed');

  switch (op.kind) {
    case 'update':
      if (record.deleted) return refuse('changed');
      if (!liveJobIds.has(op.values.jobId)) return refuse('no-job');
      return write(op.values, false, record.updatedAt);
    case 'delete':
      if (record.deleted) return refuse('changed');
      // The tombstone keeps the record's last values, as the app's tombstones do.
      return write(valuesOf(record), true, record.updatedAt);
    case 'restore':
      if (!record.deleted) return refuse('changed');
      if (!liveJobIds.has(op.values.jobId)) return refuse('no-job');
      return write(op.values, false, record.updatedAt);
  }
}
