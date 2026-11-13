/**
 * A user's records as the Android app stores them in Firestore (FirestoreDocuments.kt), one document per record
 * keyed by its sync id: users/{uid}/jobs, /shifts, /bonuses and /payouts. Values are left as they come (unknown);
 * documents.ts reads and checks them like the app does.
 */
export type RawDocument = Record<string, unknown>;

export interface CloudDocuments {
  jobs: Record<string, RawDocument>;
  shifts: Record<string, RawDocument>;
  bonuses: Record<string, RawDocument>;
  payouts: Record<string, RawDocument>;
}

export const COLLECTIONS = ['jobs', 'shifts', 'bonuses', 'payouts'] as const;
export type CollectionName = (typeof COLLECTIONS)[number];

export function emptyDocuments(): CloudDocuments {
  return { jobs: {}, shifts: {}, bonuses: {}, payouts: {} };
}
