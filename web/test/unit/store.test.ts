import { describe, expect, test } from 'vitest';
import { applyChanges, OVERLAP_MS, type Changes, type DocumentChange } from '../../src/data/changes';
import { emptyDocuments, type RawDocument } from '../../src/data/documents-types';
import { classify, DataStore, FOCUS_REFRESH_MS, type CloudBackend, type CloudUser } from '../../src/data/store';
import { DemoBackend, createStore } from '../../src/data/source';
import { epochDay } from '../../src/domain/dates';

const JOB = '11111111-1111-4111-8111-111111111111';
const id = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const DAY = epochDay(2026, 11, 2);
const USER: CloudUser = { uid: 'u1', email: 'someone@example.com' };

const job: RawDocument = {
  v: 1, updatedAt: 1, deleted: false, name: 'Kawiarnia', hourlyRateGrosze: 3000, color: -1, secondRate: null, currency: 'PLN', payHistory: [],
};
const shift = (over: RawDocument = {}): RawDocument => ({
  v: 1, updatedAt: 1, deleted: false, jobId: JOB, dateEpochDay: DAY, startMinute: 540, endMinute: 1020, unpaidBreakMinutes: 0,
  hourlyRateGrosze: 3000, note: null, externalUid: null, secondRate: null, ...over,
});
const change = (collection: DocumentChange['collection'], docId: string, data: RawDocument, serverTime: number): DocumentChange => ({
  collection, id: docId, data, serverTime,
});

/** A cloud in memory: documents with server times, and a record of what was asked. */
class FakeCloud implements CloudBackend {
  epoch: string | null = 'e1';
  docs: DocumentChange[] = [];
  asked: (number | null)[] = [];
  fail: unknown = null;
  signedOut = 0;
  private listener: ((user: CloudUser | null) => void) | null = null;

  watchUser(listener: (user: CloudUser | null) => void) {
    this.listener = listener;
  }
  emit(user: CloudUser | null) {
    this.listener?.(user);
  }
  async redirectError() {
    return null;
  }
  async signIn() {
    this.emit(USER);
  }
  async signOut() {
    this.signedOut++;
    this.emit(null);
  }
  async readEpoch() {
    if (this.fail) throw this.fail;
    return this.epoch;
  }
  async changesSince(_uid: string, since: number | null): Promise<Changes> {
    this.asked.push(since);
    if (this.fail) throw this.fail;
    const documents = this.docs.filter((d) => since === null || (d.serverTime ?? 0) >= since);
    const times = documents.map((d) => d.serverTime ?? 0);
    return { documents, newest: times.length ? Math.max(...times) : null };
  }
}

function setup() {
  const cloud = new FakeCloud();
  let now = 1_000_000;
  const store = new DataStore(cloud, () => now);
  return { cloud, store, advance: (ms: number) => (now += ms) };
}

const settle = () => new Promise((resolve) => setTimeout(resolve, 0));

describe('loading', () => {
  test('signed out until someone signs in; then everything is read once', async () => {
    const { cloud, store } = setup();
    cloud.docs = [change('jobs', JOB, job, 100), change('shifts', id(1), shift(), 200)];
    store.start();
    cloud.emit(null);
    expect(store.state.phase.kind).toBe('signed-out');
    await store.signIn();
    await settle();
    expect(store.state.phase.kind).toBe('ready');
    expect(store.state.user).toEqual(USER);
    expect(store.state.view.shifts).toHaveLength(1);
    expect(cloud.asked).toEqual([null]);
  });

  test('a refresh reads only what changed since (with a minute of overlap) and puts it in place', async () => {
    const { cloud, store } = setup();
    cloud.docs = [change('jobs', JOB, job, 100_000), change('shifts', id(1), shift(), 200_000)];
    store.start();
    cloud.emit(USER);
    await settle();
    cloud.docs.push(change('shifts', id(1), shift({ deleted: true, updatedAt: 2 }), 300_000));
    cloud.docs.push(change('shifts', id(2), shift({ startMinute: 1100, endMinute: 1200 }), 300_000));
    await store.refresh();
    expect(cloud.asked).toEqual([null, 200_000 - OVERLAP_MS]);
    expect(store.state.view.shifts.map((s) => s.shift.id)).toEqual([id(2)]);
  });

  test('when the account’s cloud data was deleted and started again, everything is read again', async () => {
    const { cloud, store } = setup();
    cloud.docs = [change('jobs', JOB, job, 100_000), change('shifts', id(1), shift(), 200_000)];
    store.start();
    cloud.emit(USER);
    await settle();
    cloud.epoch = 'e2';
    cloud.docs = [change('jobs', JOB, job, 500_000)];
    await store.refresh();
    expect(cloud.asked).toEqual([null, null]);
    expect(store.state.view.shifts).toEqual([]);
  });

  test('two refreshes at once read once', async () => {
    const { cloud, store } = setup();
    store.start();
    cloud.emit(USER);
    await Promise.all([store.refresh(), store.refresh()]);
    expect(cloud.asked).toEqual([null]);
  });

  test('coming back to the tab reads again at most once a minute', async () => {
    const { cloud, store, advance } = setup();
    store.start();
    cloud.emit(USER);
    await settle();
    store.onFocus();
    await settle();
    expect(cloud.asked).toHaveLength(1);
    advance(FOCUS_REFRESH_MS);
    store.onFocus();
    await settle();
    expect(cloud.asked).toHaveLength(2);
  });

  test('records that can’t be shown are counted, not shown', async () => {
    const { cloud, store } = setup();
    cloud.docs = [change('jobs', JOB, job, 1), change('shifts', id(1), shift({ v: 9 }), 2), change('shifts', 'bad', shift(), 3)];
    store.start();
    cloud.emit(USER);
    await settle();
    expect(store.state.skipped).toBe(2);
    expect(store.state.view.shifts).toEqual([]);
  });
});

describe('errors', () => {
  test('a first load that fails says why and can be tried again', async () => {
    const { cloud, store } = setup();
    cloud.fail = { code: 'permission-denied' };
    store.start();
    cloud.emit(USER);
    await settle();
    expect(store.state.phase).toEqual({ kind: 'failed', error: { kind: 'access-denied' } });
    cloud.fail = null;
    await store.refresh();
    expect(store.state.phase.kind).toBe('ready');
  });

  test('a refresh that fails keeps what is on screen', async () => {
    const { cloud, store } = setup();
    cloud.docs = [change('jobs', JOB, job, 1)];
    store.start();
    cloud.emit(USER);
    await settle();
    cloud.fail = { code: 'unavailable' };
    await store.refresh();
    expect(store.state.phase.kind).toBe('ready');
    expect(store.state.view.jobs).toHaveLength(1);
    expect(store.state.refreshError).toEqual({ kind: 'offline' });
  });

  test('error kinds', () => {
    expect(classify({ code: 'permission-denied' })).toEqual({ kind: 'access-denied' });
    expect(classify({ code: 'firestore/permission-denied' })).toEqual({ kind: 'access-denied' });
    expect(classify({ code: 'unavailable' })).toEqual({ kind: 'offline' });
    expect(classify(new Error('boom'), false)).toEqual({ kind: 'offline' });
    expect(classify(new TypeError('Failed to fetch'))).toEqual({ kind: 'offline' });
    expect(classify({ code: 'internal' })).toEqual({ kind: 'other', detail: 'internal' });
  });

  test('a sign-in that fails shows Firebase’s reason', async () => {
    const { cloud, store } = setup();
    store.start();
    cloud.emit(null);
    cloud.signIn = async () => {
      throw { code: 'auth/unauthorized-domain' };
    };
    await store.signIn();
    expect(store.state.phase).toEqual({ kind: 'signed-out', signInError: 'unauthorized-domain' });
  });
});

describe('signing out', () => {
  test('drops every record from memory', async () => {
    const { cloud, store } = setup();
    cloud.docs = [change('jobs', JOB, job, 1), change('shifts', id(1), shift(), 2)];
    store.start();
    cloud.emit(USER);
    await settle();
    expect(store.state.view.shifts).toHaveLength(1);
    await store.signOut();
    expect(cloud.signedOut).toBe(1);
    expect(store.state.phase.kind).toBe('signed-out');
    expect(store.state.user).toBeNull();
    expect(store.state.view).toEqual({ jobs: [], shifts: [], bonuses: [], payouts: [] });
    expect(store.state.loadedAt).toBeNull();
    // Nothing of the old account comes back: the next sign-in reads from scratch.
    await store.signIn();
    await settle();
    expect(cloud.asked).toEqual([null, null]);
  });

  test('a load still under way for a user who signed out is dropped', async () => {
    const { cloud, store } = setup();
    cloud.docs = [change('jobs', JOB, job, 1)];
    let release!: () => void;
    const gate = new Promise<void>((resolve) => (release = resolve));
    const original = cloud.changesSince.bind(cloud);
    cloud.changesSince = async (uid, since) => {
      await gate;
      return original(uid, since);
    };
    store.start();
    cloud.emit(USER);
    await store.signOut();
    release();
    await settle();
    expect(store.state.phase.kind).toBe('signed-out');
    expect(store.state.view.jobs).toEqual([]);
  });
});

describe('changes', () => {
  test('a changed document stays where it was, a new one goes last; the old maps are left alone', () => {
    const before = { ...emptyDocuments(), shifts: { [id(1)]: shift(), [id(2)]: shift() } };
    const after = applyChanges(before, [change('shifts', id(3), shift(), 1), change('shifts', id(1), shift({ updatedAt: 9 }), 2)]);
    expect(Object.keys(after.shifts)).toEqual([id(1), id(2), id(3)]);
    expect(after.shifts[id(1)]!['updatedAt']).toBe(9);
    expect(before.shifts[id(1)]!['updatedAt']).toBe(1);
  });
});

describe('demo and setup', () => {
  test('the demo shows its data without signing in', async () => {
    let left = false;
    const store = new DataStore(new DemoBackend(() => (left = true)));
    store.start();
    await settle();
    expect(store.state.phase.kind).toBe('ready');
    expect(store.state.view.jobs.length).toBeGreaterThan(0);
    expect(store.state.skipped).toBe(0);
    await store.signOut();
    expect(left).toBe(true);
  });

  test('without Firebase settings the site says so (and never loads Firebase)', async () => {
    const store = await createStore(false, null);
    expect(store.state.phase.kind).toBe('not-configured');
  });
});
