import type { Changes } from './changes';
import { planWrite, type Plan, type WriteOp } from '../edit/plan';
import type { RawDocument } from './documents-types';
import { COLLECTIONS } from './documents-types';
import { firebaseSettings, type FirebaseSettings } from './firebase-config';
import { DataStore, type CloudBackend, type CloudUser } from './store';
import { demoDocuments } from '../demo/demo';

/** The demo's made-up account: its documents come from the site itself, with no sign-in and no network. */
export const DEMO_USER: CloudUser = { uid: 'demo', email: null };

/**
 * The demo: made-up documents, read from the site itself. Editing works too, but only in this page's memory (no
 * network; a reload brings the made-up data back), through the same checks as the real writes.
 */
export class DemoBackend implements CloudBackend {
  private readonly written = new Map<string, RawDocument>();

  constructor(
    private readonly leave: () => void = () => location.assign(location.pathname),
    private readonly now: () => number = Date.now,
  ) {}

  async write(_uid: string, op: WriteOp, liveJobIds: ReadonlySet<string>): Promise<Plan> {
    const key = `${op.collection}/${op.id}`;
    const current = this.written.get(key) ?? (demoDocuments()[op.collection][op.id] as RawDocument | undefined) ?? null;
    const plan = planWrite(op, current, this.now(), liveJobIds);
    if (plan.ok) this.written.set(key, plan.fields);
    return plan;
  }

  watchUser(listener: (user: CloudUser | null) => void): void {
    listener(DEMO_USER);
  }

  async redirectError(): Promise<unknown> {
    return null;
  }

  async signIn(): Promise<void> {}

  async signOut(): Promise<void> {
    this.leave();
  }

  async readEpoch(): Promise<string | null> {
    return 'demo';
  }

  async changesSince(_uid: string, since: number | null): Promise<Changes> {
    if (since !== null) return { documents: [], newest: null };
    const all = demoDocuments();
    const documents = COLLECTIONS.flatMap((collection) =>
      Object.entries(all[collection]).map(([id, data]) => ({ collection, id, data, serverTime: null })),
    );
    return { documents, newest: 0 };
  }
}

/**
 * The store for this page: the demo's, or the signed-in user's through Firebase (loaded only then, so the demo
 * never downloads or starts Firebase), or one that says the site has no Firebase settings.
 */
export async function createStore(demo: boolean, settings: FirebaseSettings | null = firebaseSettings()): Promise<DataStore> {
  if (demo) return new DataStore(new DemoBackend());
  if (!settings) return new DataStore(null);
  const { connect, FirebaseBackend } = await import('./cloud');
  return new DataStore(new FirebaseBackend(connect(settings)));
}
