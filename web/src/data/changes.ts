import { emptyDocuments, type CloudDocuments, type CollectionName, type RawDocument } from './documents-types';

/*
 * Keeping the signed-in user's documents in memory, as the phone keeps its copy: the first load reads every
 * document; a refresh reads only those the server changed since (by `serverUpdatedAt`, as SyncEngine does) and
 * puts them in place. Deleted records arrive as tombstones (`deleted: true`), so they are seen like any change.
 * Nothing is ever stored outside this page's memory.
 */

/** One document as read from the cloud, with the server's time of its last write (ms), if it has one. */
export interface DocumentChange {
  collection: CollectionName;
  id: string;
  data: RawDocument;
  serverTime: number | null;
}

export interface Changes {
  documents: DocumentChange[];
  /** The newest server time among them (null when none has one). */
  newest: number | null;
}

/**
 * A refresh asks again for a minute before the newest change it has seen: a write the server stamped a moment
 * earlier but made visible a moment later (a slow transaction) is still found. Seeing a document twice is harmless.
 */
export const OVERLAP_MS = 60_000;

export function sinceFor(cursor: number | null): number | null {
  return cursor === null ? null : Math.max(0, cursor - OVERLAP_MS);
}

/**
 * [documents] with [changes] in place: a known document is replaced where it stands, a new one goes last (the
 * order the phone would have stored them in). Returns new maps; the old ones stay as they were.
 */
export function applyChanges(documents: CloudDocuments, changes: readonly DocumentChange[]): CloudDocuments {
  const next = emptyDocuments();
  for (const name of Object.keys(next) as CollectionName[]) next[name] = { ...documents[name] };
  for (const change of changes) next[change.collection][change.id] = change.data;
  return next;
}

export function newestOf(cursor: number | null, changes: Changes): number | null {
  if (changes.newest === null) return cursor;
  return cursor === null ? changes.newest : Math.max(cursor, changes.newest);
}
