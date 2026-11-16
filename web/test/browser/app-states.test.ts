import { afterEach, describe, expect, test } from 'vitest';
import '../../src/theme/theme.css';
import '../../src/app/app.css';
import '../../src/ui/material';
import '../../src/app/app';
import { Strings } from '../../src/i18n/i18n';
import type { App } from '../../src/app/app';
import type { Changes } from '../../src/data/changes';
import { DemoBackend } from '../../src/data/source';
import { DataStore, type CloudBackend, type CloudUser } from '../../src/data/store';
import { demoDocuments } from '../../src/demo/demo';
import { COLLECTIONS } from '../../src/data/documents-types';

/*
 * The app around its screens: signed out, signing in, loading, errors, refresh and sign-out, with a stand-in for
 * the cloud. And the safety promises: the demo makes no network call and nothing is stored in the browser.
 */

const USER: CloudUser = { uid: 'u1', email: 'someone@example.com' };

class FakeCloud implements CloudBackend {
  user: CloudUser | null = null;
  fail: unknown = null;
  reads = 0;
  private listener: ((user: CloudUser | null) => void) | null = null;
  watchUser(listener: (user: CloudUser | null) => void) {
    this.listener = listener;
    listener(this.user);
  }
  async redirectError() {
    return null;
  }
  async signIn() {
    this.user = USER;
    this.listener?.(USER);
  }
  async signOut() {
    this.user = null;
    this.listener?.(null);
  }
  async readEpoch() {
    return 'e1';
  }
  async changesSince(_uid: string, since: number | null): Promise<Changes> {
    this.reads++;
    if (this.fail) throw this.fail;
    if (since !== null) return { documents: [], newest: null };
    const all = demoDocuments();
    const documents = COLLECTIONS.flatMap((collection) =>
      Object.entries(all[collection]).map(([id, data]) => ({ collection, id, data, serverTime: 1 })),
    );
    return { documents, newest: 1 };
  }
}

async function mount(store: DataStore, options: { demo?: boolean; reload?: () => void } = {}): Promise<App> {
  const app = document.createElement('st-app');
  app.strings = new Strings('en');
  app.demo = options.demo ?? false;
  app.store = store;
  if (options.reload) app.reload = options.reload;
  document.body.append(app);
  await settle(app);
  return app;
}

async function settle(app: App) {
  for (let i = 0; i < 5; i++) {
    await new Promise((resolve) => setTimeout(resolve, 0));
    await app.updateComplete;
    await app.querySelector('st-shell')?.updateComplete;
  }
}

const testId = (app: App, id: string) => app.querySelector<HTMLElement>(`[data-testid="${id}"]`);

afterEach(() => {
  document.body.innerHTML = '';
});

describe('signed-in use', () => {
  test('signed out: the sign-in button and the demo; signing in loads the data', async () => {
    const cloud = new FakeCloud();
    const app = await mount(new DataStore(cloud));
    expect(testId(app, 'signed-out')!.textContent).toContain('Sign in with Google');
    expect(testId(app, 'refresh')).toBeNull();
    testId(app, 'sign-in')!.click();
    await settle(app);
    expect(testId(app, 'screen-calendar')).not.toBeNull();
    expect(testId(app, 'refresh')).not.toBeNull();
    expect(cloud.reads).toBe(1);
  });

  test('Refresh reads again; signing out forgets everything and reloads the page', async () => {
    const cloud = new FakeCloud();
    cloud.user = USER;
    let reloaded = 0;
    const store = new DataStore(cloud);
    const app = await mount(store, { reload: () => reloaded++ });
    testId(app, 'refresh')!.click();
    await settle(app);
    expect(cloud.reads).toBe(2);
    testId(app, 'sign-out')!.click();
    await settle(app);
    expect(reloaded).toBe(1);
    expect(store.state.user).toBeNull();
    expect(store.state.view.shifts).toEqual([]);
    expect(testId(app, 'signed-out')).not.toBeNull();
  });

  test('access refused: says so, with a way to try again', async () => {
    const cloud = new FakeCloud();
    cloud.user = USER;
    cloud.fail = { code: 'permission-denied' };
    const app = await mount(new DataStore(cloud));
    expect(testId(app, 'failed')!.textContent).toContain('refused access');
    cloud.fail = null;
    testId(app, 'try-again')!.click();
    await settle(app);
    expect(testId(app, 'screen-calendar')).not.toBeNull();
  });

  test('a refresh that fails keeps the data and says why', async () => {
    const cloud = new FakeCloud();
    cloud.user = USER;
    const app = await mount(new DataStore(cloud));
    cloud.fail = { code: 'unavailable' };
    testId(app, 'refresh')!.click();
    await settle(app);
    expect(testId(app, 'screen-calendar')).not.toBeNull();
    expect(testId(app, 'refresh-error')!.textContent).toContain('Can’t reach the cloud');
  });

  test('an account with no data yet', async () => {
    const cloud = new FakeCloud();
    cloud.user = USER;
    cloud.changesSince = async () => ({ documents: [], newest: null });
    const app = await mount(new DataStore(cloud));
    expect(testId(app, 'empty')!.textContent).toContain('No data in the cloud yet');
  });

  test('built without Firebase settings: offers the demo', async () => {
    const app = await mount(new DataStore(null));
    expect(testId(app, 'not-configured')!.textContent).toContain('?demo');
  });
});

describe('demo and the browser', () => {
  test('the demo shows its data with no sign-in, no network call and nothing stored', async () => {
    localStorage.clear();
    sessionStorage.clear();
    const before = performance.getEntriesByType('resource').length;
    const app = await mount(new DataStore(new DemoBackend(() => {})), { demo: true });
    expect(testId(app, 'screen-calendar')).not.toBeNull();
    expect(testId(app, 'sign-out')).toBeNull();
    const requests = performance.getEntriesByType('resource').slice(before).map((e) => e.name);
    expect(requests.filter((url) => /google|firebase/i.test(url))).toEqual([]);
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
    expect(await indexedDB.databases()).toEqual([]);
  });
});
