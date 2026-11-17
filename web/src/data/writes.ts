import { doc, getDoc, runTransaction, serverTimestamp, type Firestore } from 'firebase/firestore/lite';
import { EDITABLE_COLLECTIONS } from '../edit/documents';
import { planWrite, type Plan, type WriteOp } from '../edit/plan';

/*
 * THE ONLY PLACE THE SITE WRITES TO THE CLOUD (test/unit/write-guard.test.ts keeps it that way).
 *
 * One record per call, in users/{uid}/shifts|bonuses|payouts only. Each write is a Firestore transaction that
 * first reads the document from the server and decides from that version (src/edit/plan.ts): an edit or delete made
 * from an older version is refused, a delete is a tombstone, nothing is ever hard-deleted. The document goes out
 * merged into the stored one, as the app writes it (FirestoreRemoteStore.pushOnce), with the server's timestamp.
 * Firestore retries the transaction when the document changed between the read and the commit; every attempt
 * decides again from what it read, so a retry never writes on top of a newer version.
 *
 * When two devices save the same version at the same moment, the second commit can reach the rules after the first
 * one landed: the rules refuse it (its updatedAt is no longer newer) instead of Firestore retrying it. Such a refusal
 * is checked against the document as it is now: when the version changed, it is a plain "changed on another device".
 */

export type WriteResult = Plan & { collection: WriteOp['collection']; id: string };

export interface WriteOptions {
  /** The browser's clock (ms); a test can set it. */
  now?: () => number;
  /** Called after each attempt's read, with the attempt number (tests: to make another device write meanwhile). */
  onRead?: (attempt: number) => Promise<void> | void;
}

export async function writeRecord(
  db: Firestore,
  uid: string,
  op: WriteOp,
  liveJobIds: ReadonlySet<string>,
  options: WriteOptions = {},
): Promise<WriteResult> {
  if (!EDITABLE_COLLECTIONS.includes(op.collection)) throw new Error(`not writable: ${String(op.collection)}`);
  const now = options.now ?? Date.now;
  const ref = doc(db, 'users', uid, op.collection, op.id);
  let attempt = 0;
  const transaction = runTransaction(
    db,
    async (transaction) => {
      attempt++;
      const snapshot = await transaction.get(ref);
      await options.onRead?.(attempt);
      const result = planWrite(op, snapshot.exists() ? snapshot.data() : null, now(), liveJobIds);
      if (result.ok) transaction.set(ref, { ...result.fields, serverUpdatedAt: serverTimestamp() }, { merge: true });
      return result;
    },
    { maxAttempts: 5 },
  );
  let plan: Plan;
  try {
    plan = await transaction;
  } catch (error) {
    if ((error as { code?: string }).code !== 'permission-denied') throw error;
    const snapshot = await getDoc(ref).catch(() => null);
    if (!snapshot) throw error;
    const now2 = planWrite(op, snapshot.exists() ? snapshot.data() : null, now(), liveJobIds);
    if (now2.ok) throw error;
    plan = now2;
  }
  return { ...plan, collection: op.collection, id: op.id };
}
