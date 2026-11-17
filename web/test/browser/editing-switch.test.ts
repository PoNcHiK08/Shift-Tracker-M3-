import { afterEach, describe, expect, test, vi } from 'vitest';
import '../../src/theme/theme.css';
import '../../src/app/app.css';
import '../../src/screens/screens.css';
import '../../src/editors/editors.css';
import '../../src/ui/material';
import '../../src/app/app';
import type { App } from '../../src/app/app';
import { Strings } from '../../src/i18n/i18n';
import { DemoBackend } from '../../src/data/source';
import { DataStore } from '../../src/data/store';

/*
 * The editing switch: off at the start of every session, on only for this page (nothing stored), and the cloud
 * snapshot next to it. Closing the tab with unsaved changes asks first.
 */

async function mount(): Promise<App> {
  const app = document.createElement('st-app');
  app.strings = new Strings('en');
  app.demo = true;
  app.store = new DataStore(new DemoBackend(() => {}, () => 1_800_000_000_000));
  document.body.append(app);
  await settle(app);
  return app;
}

async function settle(app: App) {
  for (let i = 0; i < 6; i++) {
    await new Promise((resolve) => setTimeout(resolve, 0));
    await app.updateComplete;
    for (const el of app.querySelectorAll<HTMLElement & { updateComplete?: Promise<unknown> }>('st-shell, st-calendar, st-shift-editor, md-switch')) await el.updateComplete;
  }
}

const byId = (app: App, id: string) => app.querySelector<HTMLElement>(`[data-testid="${id}"]`);

async function toggle(app: App) {
  const sw = byId(app, 'editing-switch') as HTMLElement & { selected: boolean };
  sw.click();
  await settle(app);
}

afterEach(() => {
  document.body.innerHTML = '';
  vi.restoreAllMocks();
});

describe('editing switch', () => {
  test('off at the start: no Add, no openable cards; on: they appear; off again: gone', async () => {
    localStorage.clear();
    sessionStorage.clear();
    const app = await mount();
    expect((byId(app, 'editing-switch') as HTMLElement & { selected: boolean }).selected).toBe(false);
    expect(byId(app, 'editing-state')!.textContent).toContain('View only');
    expect(byId(app, 'add-shift')).toBeNull();
    await toggle(app);
    expect(app.editing).toBe(true);
    expect(byId(app, 'add-shift')).not.toBeNull();
    expect(byId(app, 'editing-state')!.textContent).toContain('Editing is on until you close this tab');
    expect(byId(app, 'editing-state')!.textContent).toContain('In the demo, changes stay on this page only.');
    // For this session only: nothing is stored anywhere.
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
    expect(await indexedDB.databases()).toEqual([]);
    await toggle(app);
    expect(byId(app, 'add-shift')).toBeNull();
  });

  test('a new page starts with editing off, whatever the last one did', async () => {
    const first = await mount();
    await toggle(first);
    expect(first.editing).toBe(true);
    document.body.innerHTML = '';
    const second = await mount();
    expect(second.editing).toBe(false);
    expect(byId(second, 'add-shift')).toBeNull();
  });

  test('the snapshot is offered next to the switch and saves every document held as JSON', async () => {
    const app = await mount();
    let saved: { name: string; blob: Blob } | null = null;
    const create = vi.spyOn(URL, 'createObjectURL').mockImplementation((blob) => {
      saved = { name: '', blob: blob as Blob };
      return 'blob:snapshot';
    });
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      if (saved) saved.name = this.download;
    });
    byId(app, 'snapshot')!.click();
    expect(create).toHaveBeenCalledTimes(1);
    expect(click).toHaveBeenCalledTimes(1);
    expect(saved!.name).toMatch(/^shift-tracker-cloud-snapshot-\d{4}-\d{2}-\d{2}\.json$/);
    const json = JSON.parse(await saved!.blob.text());
    expect(json.counts).toEqual({ jobs: 5, shifts: 111, bonuses: 7, payouts: 5 });
    expect(json.about).toMatch(/Not an importable backup/);
    expect(byId(app, 'editing-bar')!.textContent).toContain('It is not a backup the app can import.');
  });

  test('closing the tab with unsaved changes in an editor asks first; without changes it doesn’t', async () => {
    const app = await mount();
    await toggle(app);
    byId(app, 'add-shift')!.click();
    await settle(app);
    const unload = () => {
      const event = new Event('beforeunload', { cancelable: true });
      window.dispatchEvent(event);
      return event.defaultPrevented;
    };
    expect(unload()).toBe(false);
    const note = byId(app, 'shift-note') as HTMLTextAreaElement;
    note.value = 'x';
    note.dispatchEvent(new Event('input', { bubbles: true }));
    await settle(app);
    expect(unload()).toBe(true);
  });
});
