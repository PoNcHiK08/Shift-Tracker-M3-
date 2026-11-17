import { applyChanges, newestOf, sinceFor, type Changes } from './changes';
import { emptyDocuments, type CloudDocuments } from './documents-types';
import { readDocuments } from './documents';
import { EMPTY_VIEW, type DataView } from '../domain/screens';

/*
 * The signed-in user's data for the screens, and when it is read: once on opening, again on Refresh and when the
 * tab comes back into view (at most once a minute). There is no live listener, and nothing is kept anywhere but in
 * this page's memory: signing out drops it all and reloads the page.
 *
 * READ-ONLY: a CloudBackend can only read. The site never writes to the cloud (test/unit/no-writes.test.ts).
 */

export interface CloudUser {
  uid: string;
  email: string | null;
}

/** What the store needs from Firebase (cloud.ts); tests give it a fake. */
export interface CloudBackend {
  /** Calls [listener] with the signed-in user (or null) once known and on every change. */
  watchUser(listener: (user: CloudUser | null) => void): void;
  /** An error left by a sign-in that went through a redirect, if any. */
  redirectError(): Promise<unknown>;
  signIn(): Promise<void>;
  signOut(): Promise<void>;
  /** The marker of the account's cloud data (users/{uid}.epoch); null when there is none. */
  readEpoch(uid: string): Promise<string | null>;
  /** Every document of the four collections with a server time at or after [since] (all of them when null). */
  changesSince(uid: string, since: number | null): Promise<Changes>;
}

export type LoadError = { kind: 'access-denied' } | { kind: 'offline' } | { kind: 'other'; detail: string };

export type Phase =
  /** The site was built without its Firebase settings. */
  | { kind: 'not-configured' }
  /** Waiting for Firebase to say who is signed in. */
  | { kind: 'starting' }
  | { kind: 'signed-out'; signInError: string | null }
  | { kind: 'signing-in' }
  /** Signed in, the first load is under way. */
  | { kind: 'loading' }
  /** Signed in, the first load failed (nothing to show). */
  | { kind: 'failed'; error: LoadError }
  | { kind: 'ready' };

export interface Snapshot {
  phase: Phase;
  user: CloudUser | null;
  view: DataView;
  /** Documents that couldn't be shown (unreadable, unknown layout, or their job doesn't exist). */
  skipped: number;
  /** When the data was last read (ms), null before the first load. */
  loadedAt: number | null;
  refreshing: boolean;
  /** A refresh that failed while older data is on screen. */
  refreshError: LoadError | null;
}

/** Firebase's error code for [error] ("permission-denied", "auth/popup-blocked"), if it has one. */
export function errorCode(error: unknown): string | null {
  const code = (error as { code?: unknown } | null)?.code;
  return typeof code === 'string' ? code : null;
}

export function classify(error: unknown, online: boolean = typeof navigator === 'undefined' || navigator.onLine !== false): LoadError {
  const code = errorCode(error)?.replace(/^firestore\//, '') ?? null;
  if (code === 'permission-denied' || code === 'unauthenticated') return { kind: 'access-denied' };
  if (!online || code === 'unavailable' || code === 'deadline-exceeded' || code === 'auth/network-request-failed') return { kind: 'offline' };
  if (error instanceof TypeError && /fetch|network/i.test(error.message)) return { kind: 'offline' };
  return { kind: 'other', detail: code ?? (error instanceof Error ? error.message : String(error)) };
}

/** The tab came back: read again, but not more often than this. */
export const FOCUS_REFRESH_MS = 60_000;

const EMPTY: Omit<Snapshot, 'phase'> = { user: null, view: EMPTY_VIEW, skipped: 0, loadedAt: null, refreshing: false, refreshError: null };

export class DataStore {
  private snapshot: Snapshot;
  private readonly listeners = new Set<(snapshot: Snapshot) => void>();
  private documents: CloudDocuments = emptyDocuments();
  private epoch: string | null = null;
  private cursor: number | null = null;
  private lastStarted = Number.NEGATIVE_INFINITY;
  private inFlight: Promise<void> | null = null;
  /** Bumped on every user change: a load for a user who has since signed out is dropped. */
  private generation = 0;

  constructor(
    private readonly backend: CloudBackend | null,
    private readonly now: () => number = Date.now,
  ) {
    this.snapshot = { ...EMPTY, phase: backend ? { kind: 'starting' } : { kind: 'not-configured' } };
  }

  get state(): Snapshot {
    return this.snapshot;
  }

  subscribe(listener: (snapshot: Snapshot) => void): () => void {
    this.listeners.add(listener);
    listener(this.snapshot);
    return () => this.listeners.delete(listener);
  }

  private set(changes: Partial<Snapshot>) {
    this.snapshot = { ...this.snapshot, ...changes };
    for (const listener of this.listeners) listener(this.snapshot);
  }

  /** Starts following who is signed in; loads the data of whoever is. */
  start(): void {
    const backend = this.backend;
    if (!backend) return;
    void backend.redirectError().then((error) => {
      if (error && this.snapshot.phase.kind === 'signed-out') this.set({ phase: { kind: 'signed-out', signInError: signInMessage(error) } });
    });
    backend.watchUser((user) => {
      if (user?.uid === this.snapshot.user?.uid && this.snapshot.phase.kind !== 'starting') return;
      this.forget();
      if (!user) {
        const previous = this.snapshot.phase;
        this.set({ ...EMPTY, phase: { kind: 'signed-out', signInError: previous.kind === 'signed-out' ? previous.signInError : null } });
        return;
      }
      this.set({ ...EMPTY, user, phase: { kind: 'loading' } });
      void this.refresh();
    });
  }

  async signIn(): Promise<void> {
    if (!this.backend) return;
    this.set({ phase: { kind: 'signing-in' } });
    try {
      await this.backend.signIn();
      // Signed in: watchUser takes over. Closed without choosing an account: back to the button.
      if (this.snapshot.phase.kind === 'signing-in') this.set({ phase: { kind: 'signed-out', signInError: null } });
    } catch (error) {
      this.set({ phase: { kind: 'signed-out', signInError: signInMessage(error) } });
    }
  }

  /** Signs out and drops every record held in memory. */
  async signOut(): Promise<void> {
    this.forget();
    this.set({ ...EMPTY, phase: { kind: 'signed-out', signInError: null } });
    await this.backend?.signOut();
  }

  private forget() {
    this.generation++;
    this.documents = emptyDocuments();
    this.epoch = null;
    this.cursor = null;
    this.inFlight = null;
    this.lastStarted = Number.NEGATIVE_INFINITY;
  }

  /** The tab is visible again: read what changed, unless that was done less than a minute ago. */
  onFocus(): void {
    if (this.snapshot.phase.kind !== 'ready' && this.snapshot.phase.kind !== 'failed') return;
    if (this.now() - this.lastStarted < FOCUS_REFRESH_MS) return;
    void this.refresh();
  }

  /**
   * Reads what changed since the last load (everything the first time, or when the account's cloud data was
   * deleted and started again: its marker changed). A refresh already under way is joined, not repeated.
   */
  refresh(): Promise<void> {
    if (this.inFlight) return this.inFlight;
    const backend = this.backend;
    const user = this.snapshot.user;
    if (!backend || !user) return Promise.resolve();
    const generation = this.generation;
    this.lastStarted = this.now();
    if (this.snapshot.phase.kind === 'failed') this.set({ phase: { kind: 'loading' } });
    else if (this.snapshot.phase.kind === 'ready') this.set({ refreshing: true });
    const run = (async () => {
      try {
        const epoch = await backend.readEpoch(user.uid);
        const whole = this.cursor === null || epoch !== this.epoch;
        const changes = await backend.changesSince(user.uid, whole ? null : sinceFor(this.cursor));
        if (generation !== this.generation) return;
        this.documents = applyChanges(whole ? emptyDocuments() : this.documents, changes.documents);
        this.cursor = newestOf(whole ? null : this.cursor, changes);
        this.epoch = epoch;
        const { view, skipped } = readDocuments(this.documents);
        this.set({ phase: { kind: 'ready' }, view, skipped, loadedAt: this.now(), refreshing: false, refreshError: null });
      } catch (error) {
        if (generation !== this.generation) return;
        const problem = classify(error);
        if (this.snapshot.loadedAt === null) this.set({ phase: { kind: 'failed', error: problem }, refreshing: false });
        else this.set({ refreshing: false, refreshError: problem });
      } finally {
        if (generation === this.generation) this.inFlight = null;
      }
    })();
    this.inFlight = run;
    return run;
  }
}

/** A sign-in error as one short line for the person (Firebase's code, without its "auth/" prefix). */
function signInMessage(error: unknown): string {
  const code = errorCode(error);
  if (code) return code.replace(/^auth\//, '');
  return error instanceof Error ? error.message : String(error);
}
