import { initializeApp, type FirebaseApp } from 'firebase/app';
import {
  GoogleAuthProvider,
  browserPopupRedirectResolver,
  browserSessionPersistence,
  getRedirectResult,
  initializeAuth,
  onAuthStateChanged,
  signInWithPopup,
  signInWithRedirect,
  signOut,
  type Auth,
} from 'firebase/auth';
// Firestore "lite": plain reads over HTTPS, with no cache, no offline storage and no live listeners at all.
import {
  Timestamp,
  collection,
  doc,
  getDoc,
  getDocs,
  initializeFirestore,
  limit,
  orderBy,
  query,
  startAfter,
  where,
  type Firestore,
  type Query,
  type QueryDocumentSnapshot,
  type QuerySnapshot,
} from 'firebase/firestore/lite';
import type { Changes, DocumentChange } from './changes';
import type { Plan, WriteOp } from '../edit/plan';
import { writeRecord } from './writes';
import { COLLECTIONS, type CollectionName } from './documents-types';
import type { FirebaseSettings } from './firebase-config';
import { errorCode, type CloudBackend, type CloudUser } from './store';

/*
 * The only module that talks to Firebase, loaded only when signed-in use is possible (never in the demo).
 *
 * It reads users/{uid} and its four collections, exactly as the app's FirestoreRemoteStore.kt reads them (by
 * `serverUpdatedAt`, 500 per page). It writes nothing itself: writes go through data/writes.ts only (shifts, bonuses
 * and payouts; test/unit/write-guard.test.ts).
 *
 * Nothing is kept after the tab closes: the sign-in lives in this tab's session storage only, and Firestore lite
 * keeps no cache. (Firebase's own SDK notes the day it was used, with no account data, in an IndexedDB database of
 * its own; signing out deletes it. See docs/web.md.)
 */

const PAGE_SIZE = 500;
const SERVER_UPDATED_AT = 'serverUpdatedAt';
/** Firebase's per-day usage note (@firebase/app's heartbeat): no account data, deleted on sign-out. */
export const HEARTBEAT_DATABASE = 'firebase-heartbeat-database';

/** Sign-in errors after which a full-page redirect is tried instead of a popup. */
const USE_REDIRECT = new Set(['auth/popup-blocked', 'auth/operation-not-supported-in-this-environment', 'auth/web-storage-unsupported']);
/** The person closed the popup: no error to show. */
const CANCELLED = new Set(['auth/popup-closed-by-user', 'auth/cancelled-popup-request', 'auth/user-cancelled']);

export interface FirebaseHandles {
  app: FirebaseApp;
  auth: Auth;
  db: Firestore;
}

/** Firebase as this site uses it: sign-in for this tab only, Firestore without any cache. */
export function connect(settings: FirebaseSettings): FirebaseHandles {
  const app = initializeApp(settings, { automaticDataCollectionEnabled: false });
  const auth = initializeAuth(app, {
    persistence: browserSessionPersistence,
    popupRedirectResolver: browserPopupRedirectResolver,
  });
  const db = initializeFirestore(app, {});
  return { app, auth, db };
}

function millis(value: unknown): number | null {
  return value instanceof Timestamp ? value.toMillis() : null;
}

export class FirebaseBackend implements CloudBackend {
  constructor(private readonly firebase: FirebaseHandles) {}

  watchUser(listener: (user: CloudUser | null) => void): void {
    onAuthStateChanged(this.firebase.auth, (user) => listener(user ? { uid: user.uid, email: user.email } : null));
  }

  async redirectError(): Promise<unknown> {
    try {
      await getRedirectResult(this.firebase.auth);
      return null;
    } catch (error) {
      return CANCELLED.has(errorCode(error) ?? '') ? null : error;
    }
  }

  async signIn(): Promise<void> {
    const provider = new GoogleAuthProvider();
    provider.setCustomParameters({ prompt: 'select_account' });
    try {
      await signInWithPopup(this.firebase.auth, provider);
    } catch (error) {
      const code = errorCode(error) ?? '';
      if (CANCELLED.has(code)) return;
      if (!USE_REDIRECT.has(code)) throw error;
      await signInWithRedirect(this.firebase.auth, provider);
    }
  }

  async signOut(): Promise<void> {
    await signOut(this.firebase.auth);
    await new Promise<void>((resolve) => {
      if (typeof indexedDB === 'undefined') return resolve();
      const request = indexedDB.deleteDatabase(HEARTBEAT_DATABASE);
      request.onsuccess = request.onerror = request.onblocked = () => resolve();
    });
  }

  /** The single write path (data/writes.ts). */
  write(uid: string, op: WriteOp, liveJobIds: ReadonlySet<string>): Promise<Plan> {
    return writeRecord(this.firebase.db, uid, op, liveJobIds);
  }

  async readEpoch(uid: string): Promise<string | null> {
    const marker = await getDoc(doc(this.firebase.db, 'users', uid));
    const epoch = marker.exists() ? marker.get('epoch') : null;
    return typeof epoch === 'string' ? epoch : null;
  }

  async changesSince(uid: string, since: number | null): Promise<Changes> {
    const found = await Promise.all(COLLECTIONS.map((name) => this.changedIn(uid, name, since)));
    const documents = found.flat();
    const times = documents.map((d) => d.serverTime).filter((t): t is number => t !== null);
    return { documents, newest: times.length ? Math.max(...times) : null };
  }

  /** As FirestoreRemoteStore.changedIn: by server time, page by page. */
  private async changedIn(uid: string, name: CollectionName, since: number | null): Promise<DocumentChange[]> {
    let base: Query = query(collection(this.firebase.db, 'users', uid, name), orderBy(SERVER_UPDATED_AT));
    if (since !== null) base = query(base, where(SERVER_UPDATED_AT, '>=', Timestamp.fromMillis(since)));
    const found: DocumentChange[] = [];
    let last: QueryDocumentSnapshot | null = null;
    for (;;) {
      const page: QuerySnapshot = await getDocs(last ? query(base, startAfter(last), limit(PAGE_SIZE)) : query(base, limit(PAGE_SIZE)));
      for (const snapshot of page.docs) {
        const data = snapshot.data();
        found.push({ collection: name, id: snapshot.id, data, serverTime: millis(data[SERVER_UPDATED_AT]) });
      }
      last = page.docs[page.docs.length - 1] ?? null;
      if (page.size < PAGE_SIZE) return found;
    }
  }
}
