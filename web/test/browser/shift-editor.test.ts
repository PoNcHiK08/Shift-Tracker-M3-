import { afterEach, describe, expect, test } from 'vitest';
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
import { calendarState } from '../../src/domain/screens';
import { monthOf, parseDate } from '../../src/domain/dates';
import { DEMO_TODAY } from '../../src/demo/demo';
import type { WriteOp } from '../../src/edit/plan';
import { money } from '../../src/ui/format';

/*
 * The shift editor in the demo (writes go to the demo's in-memory cloud through the same checks as the real ones):
 * add, edit, night shifts, the break rule, the unsaved-changes guard, delete with Undo, a refused stale save, and that
 * nothing can be edited while editing is off.
 */

const NOW = 1_800_000_000_000;
const DAY13 = parseDate(DEMO_TODAY);

interface Mounted {
  app: App;
  store: DataStore;
  backend: DemoBackend;
  writes: WriteOp[];
}

async function mount(editing = true): Promise<Mounted> {
  const backend = new DemoBackend(() => {}, () => NOW);
  const writes: WriteOp[] = [];
  const write = backend.write.bind(backend);
  backend.write = (uid, op, live) => (writes.push(op), write(uid, op, live));
  const store = new DataStore(backend);
  const app = document.createElement('st-app');
  app.strings = new Strings('en');
  app.demo = true;
  app.store = store;
  document.body.append(app);
  await settle(app);
  app.editing = editing;
  await settle(app);
  return { app, store, backend, writes };
}

async function settle(app: App) {
  for (let i = 0; i < 6; i++) {
    await new Promise((resolve) => setTimeout(resolve, 0));
    await app.updateComplete;
    for (const el of app.querySelectorAll<HTMLElement & { updateComplete?: Promise<unknown> }>('st-shell, st-calendar, st-shift-editor, st-snackbar')) await el.updateComplete;
  }
}

const q = <T extends Element = HTMLElement>(app: App, selector: string) => app.querySelector<T & HTMLElement>(selector);
const byId = (app: App, id: string) => q(app, `[data-testid="${id}"]`);
const text = (el: Element | null) => el?.textContent?.replace(/\s+/g, ' ').trim() ?? null;

async function click(app: App, selector: string) {
  const el = q(app, selector);
  if (!el) throw new Error(`no ${selector}`);
  el.click();
  await settle(app);
}

async function setInput(app: App, testId: string, value: string, event: 'change' | 'input' = 'change') {
  const el = byId(app, testId) as HTMLInputElement;
  el.value = value;
  el.dispatchEvent(new Event(event, { bubbles: true }));
  await settle(app);
}

const dayCards = (app: App) => [...app.querySelectorAll('[data-testid="day-panel"] [data-testid="shift-card"]')];
const editorOpen = (app: App) => q<HTMLDialogElement>(app, 'dialog.editor')?.open === true;

afterEach(() => {
  document.body.innerHTML = '';
});

describe('adding a shift', () => {
  test('on the selected day: the live earnings card, Save, the day updates at once with the app’s figures', async () => {
    const { app, store, writes } = await mount();
    const before = dayCards(app).length;
    await click(app, '[data-testid="add-shift"]');
    expect(editorOpen(app)).toBe(true);
    expect(text(q(app, 'dialog.editor h2'))).toBe('New shift');
    // The first job (Biuro, salaried in November) -> pick the café: 31,50 zł/h.
    const cafe = store.state.view.jobs.find((j) => j.name.startsWith('Kawiarnia'))!;
    await click(app, `[data-testid="job-pills"] [data-job="${cafe.id}"]`);
    await setInput(app, 'shift-start', '06:00');
    await setInput(app, 'shift-end', '09:30');
    await click(app, '[data-break="15"]');
    expect(text(byId(app, 'paid-hours'))).toBe('3:15');
    expect(text(byId(app, 'earnings-amount'))).toBe(text({ textContent: money(10238n, 'PLN', 'en-US') } as Element));
    await setInput(app, 'shift-note', 'Wcześnie', 'input');
    await click(app, '[data-testid="editor-save"]');
    expect(editorOpen(app)).toBe(false);
    expect(writes).toHaveLength(1);
    expect(writes[0]).toMatchObject({ kind: 'create', collection: 'shifts', values: { start: 360, end: 570, breakMinutes: 15, rate: 3150n, note: 'Wcześnie' } });
    expect(text(byId(app, 'snackbar'))).toBe('Shift saved');
    expect(dayCards(app)).toHaveLength(before + 1);
    // The month's figures are the app's calculation over the data now held.
    const state = calendarState(store.state.view, 'GROSS', monthOf(DAY13));
    expect(text(byId(app, 'tile-shifts'))).toBe(`Shifts ${state.monthSummary.shiftCount}`);
  });

  test('a night shift ends the next day; a break as long as the shift can’t be saved', async () => {
    const { app, writes } = await mount();
    await click(app, '[data-testid="add-shift"]');
    await setInput(app, 'shift-start', '22:00');
    await setInput(app, 'shift-end', '06:00');
    expect(text(byId(app, 'ends-next-day'))).toBe('Ends the next day');
    await setInput(app, 'shift-break', '480', 'input');
    expect(text(byId(app, 'break-too-long'))).toBe('The break is longer than the shift');
    await click(app, '[data-testid="editor-save"]');
    expect(writes).toEqual([]);
    expect(editorOpen(app)).toBe(true);
  });
});

describe('editing a shift', () => {
  test('opens with its values; a changed note is saved over the version it was opened from', async () => {
    const { app, writes } = await mount();
    const card = dayCards(app)[0] as HTMLElement;
    const id = card.dataset['id']!;
    card.click();
    await settle(app);
    expect(text(q(app, 'dialog.editor h2'))).toBe('Edit shift');
    await setInput(app, 'shift-note', 'Zmieniona', 'input');
    await click(app, '[data-testid="editor-save"]');
    expect(writes[0]).toMatchObject({ kind: 'update', id, values: { note: 'Zmieniona' } });
    expect(text(q(app, `[data-testid="shift-card"][data-id="${id}"]`))).toContain('Zmieniona');
  });

  test('an older rate on the shift: a hint, and nothing to apply it with', async () => {
    const { app, store } = await mount();
    const card = dayCards(app)[0] as HTMLElement;
    const record = store.record('shifts', card.dataset['id']!)!;
    if (record.kind !== 'shift') throw new Error();
    const { kind: _k, id, updatedAt, deleted: _d, schema: _s, ...values } = record;
    await store.write({ kind: 'update', collection: 'shifts', id, values: { ...values, rate: values.rate - 100n }, base: updatedAt });
    await settle(app);
    (q(app, `[data-testid="shift-card"][data-id="${id}"]`) as HTMLElement).click();
    await settle(app);
    expect(text(byId(app, 'rate-outdated'))).toContain('current rate is different');
    expect([...app.querySelectorAll('dialog.editor md-text-button, dialog.editor button')].map(text).filter((t) => /apply|current rate/i.test(t ?? ''))).toEqual([]);
  });

  test('a save from a version another device has changed is refused: the message and Reload', async () => {
    const { app, store, backend } = await mount();
    const card = dayCards(app)[0] as HTMLElement;
    const id = card.dataset['id']!;
    card.click();
    await settle(app);
    // Another device changes the shift (the server's version moves on; this page still has the old one).
    const record = store.record('shifts', id)!;
    const { kind: _k, id: _i, updatedAt, deleted: _d, schema: _s, ...values } = record as Extract<typeof record, { kind: 'shift' }>;
    const other = await backend.write('demo', { kind: 'update', collection: 'shifts', id, values: { ...values, note: 'Z telefonu' }, base: updatedAt }, new Set(store.state.view.jobs.map((j) => j.id)));
    expect(other.ok).toBe(true);
    await setInput(app, 'shift-note', 'Z przeglądarki', 'input');
    await click(app, '[data-testid="editor-save"]');
    expect(text(byId(app, 'editor-problem'))).toContain('This changed on another device. Reload to see the latest version.');
    expect(editorOpen(app)).toBe(true);
    await click(app, '[data-testid="editor-reload"]');
    expect(editorOpen(app)).toBe(false);
  });
});

describe('unsaved changes and delete', () => {
  test('closing with changes asks first; keep editing keeps them; discard writes nothing', async () => {
    const { app, writes } = await mount();
    (dayCards(app)[0] as HTMLElement).click();
    await settle(app);
    await setInput(app, 'shift-note', 'Nie zapisuj', 'input');
    await click(app, '[data-testid="editor-cancel"]');
    expect(byId(app, 'confirm-discard')).not.toBeNull();
    await click(app, '[data-testid="confirm-discard-no"]');
    expect(editorOpen(app)).toBe(true);
    expect((byId(app, 'shift-note') as HTMLTextAreaElement).value).toBe('Nie zapisuj');
    // Escape asks too.
    q(app, 'dialog.editor')!.dispatchEvent(new Event('cancel', { cancelable: true }));
    await settle(app);
    expect(byId(app, 'confirm-discard')).not.toBeNull();
    await click(app, '[data-testid="confirm-discard-yes"]');
    expect(editorOpen(app)).toBe(false);
    expect(writes).toEqual([]);
  });

  test('closing without changes doesn’t ask', async () => {
    const { app } = await mount();
    (dayCards(app)[0] as HTMLElement).click();
    await settle(app);
    await click(app, '[data-testid="editor-close"]');
    expect(editorOpen(app)).toBe(false);
  });

  test('delete asks, writes a tombstone, and Undo puts the shift back', async () => {
    const { app, store, writes } = await mount();
    const id = (dayCards(app)[0] as HTMLElement).dataset['id']!;
    const before = dayCards(app).length;
    (dayCards(app)[0] as HTMLElement).click();
    await settle(app);
    await click(app, '[data-testid="editor-delete"]');
    expect(byId(app, 'confirm-delete')).not.toBeNull();
    await click(app, '[data-testid="confirm-delete-yes"]');
    expect(writes.at(-1)).toMatchObject({ kind: 'delete', id });
    expect(dayCards(app)).toHaveLength(before - 1);
    expect(store.record('shifts', id)).toMatchObject({ deleted: true });
    expect(text(byId(app, 'snackbar'))).toContain('Shift deleted');
    await click(app, '[data-testid="snackbar-action"]');
    await settle(app);
    expect(writes.at(-1)).toMatchObject({ kind: 'restore', id });
    expect(dayCards(app)).toHaveLength(before);
    expect(store.record('shifts', id)).toMatchObject({ deleted: false });
    expect(text(byId(app, 'snackbar'))).toBe('Restored');
  });
});

describe('editing off', () => {
  test('no Add button, and the cards open nothing', async () => {
    const { app } = await mount(false);
    expect(byId(app, 'add-shift')).toBeNull();
    const card = dayCards(app)[0] as HTMLElement;
    expect(card.getAttribute('role')).toBeNull();
    card.click();
    await settle(app);
    expect(q(app, 'dialog.editor')).toBeNull();
  });
});
