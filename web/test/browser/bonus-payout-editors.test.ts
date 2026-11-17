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
import { epochDay, parseDate, yearMonth } from '../../src/domain/dates';
import { DEMO_TODAY } from '../../src/demo/demo';
import type { WriteOp } from '../../src/edit/plan';
import { calendarState, dayTotal, periodRange, summaryState, type CalendarState } from '../../src/domain/screens';
import { money } from '../../src/ui/format';

/*
 * The bonus and payment editors in the demo: a day bonus from the day panel, a whole-month bonus (no day shows it, the
 * month row and Summary do), payments from Summary -> Month, the unsaved-changes guard and Undo, and that the figures
 * on screen after each write are the app's calculation over the data held.
 */

const NOW = 1_800_000_000_000;
const dayTotalOf = (state: CalendarState, day: number) => dayTotal(state, day, 'GROSS').filter((v) => v > 0n).sortedByValue();
const NOVEMBER = yearMonth(2026, 11);

async function mount(lang: 'en' | 'uk' = 'en') {
  const backend = new DemoBackend(() => {}, () => NOW);
  const writes: WriteOp[] = [];
  const write = backend.write.bind(backend);
  backend.write = (uid, op, live) => (writes.push(op), write(uid, op, live));
  const store = new DataStore(backend);
  const app = document.createElement('st-app');
  app.strings = new Strings(lang);
  app.demo = true;
  app.store = store;
  document.body.append(app);
  await settle(app);
  app.editing = true;
  await settle(app);
  return { app, store, writes };
}

async function settle(app: App) {
  for (let i = 0; i < 6; i++) {
    await new Promise((resolve) => setTimeout(resolve, 0));
    await app.updateComplete;
    for (const el of app.querySelectorAll<HTMLElement & { updateComplete?: Promise<unknown> }>('st-shell, st-calendar, st-summary, st-bonus-editor, st-payout-editor, st-snackbar')) await el.updateComplete;
  }
}

const q = (app: App, selector: string) => app.querySelector<HTMLElement>(selector);
const byId = (app: App, id: string) => q(app, `[data-testid="${id}"]`);
const text = (el: Element | null) => el?.textContent?.replace(/\s+/g, ' ').trim() ?? null;
const ws = (s: string) => s.replace(/\s+/g, ' ').trim();

async function click(app: App, selector: string) {
  const el = q(app, selector);
  if (!el) throw new Error(`no ${selector}`);
  el.click();
  await settle(app);
}

async function type(app: App, testId: string, value: string, event: 'input' | 'change' = 'input') {
  const el = byId(app, testId) as HTMLInputElement;
  el.value = value;
  el.dispatchEvent(new Event(event, { bubbles: true }));
  await settle(app);
}

const editorOpen = (app: App) => [...app.querySelectorAll<HTMLDialogElement>('dialog.editor')].some((d) => d.open);
const go = async (app: App, hash: string) => {
  location.hash = hash;
  await new Promise((resolve) => setTimeout(resolve, 10));
  await settle(app);
};

afterEach(() => {
  document.body.innerHTML = '';
  history.replaceState(null, '', location.pathname + location.search);
});

describe('bonuses', () => {
  test('a bonus on the selected day: amount in the job’s currency; the day total is the app’s', async () => {
    const { app, store, writes } = await mount();
    await click(app, '[data-testid="add-bonus"]');
    expect(text(q(app, 'st-bonus-editor dialog.editor h2'))).toBe('New bonus');
    await click(app, '[data-testid="editor-save"]');
    expect(text(byId(app, 'amount-invalid'))).toBe('Enter an amount, e.g. 75');
    expect(writes).toEqual([]);
    await type(app, 'bonus-amount-input', '12,5');
    await type(app, 'bonus-note', 'Napiwki');
    await click(app, 'st-bonus-editor [data-testid="editor-save"]');
    expect(writes[0]).toMatchObject({ kind: 'create', collection: 'bonuses', values: { amount: 1250n, wholeMonth: false, date: parseDate(DEMO_TODAY), note: 'Napiwki' } });
    expect(text(byId(app, 'snackbar'))).toBe('Bonus saved');
    const state = calendarState(store.state.view, 'GROSS', NOVEMBER);
    const day = parseDate(DEMO_TODAY);
    expect([...app.querySelectorAll('[data-testid="day-total"] > span')].map(text)).toEqual(
      [...dayTotalOf(state, day)].map(([c, v]) => ws(money(v, c, 'en-US'))),
    );
    expect([...app.querySelectorAll('[data-testid="day-panel"] [data-testid="bonus-card"]')].some((c) => text(c)!.includes('Napiwki'))).toBe(true);
  });

  test('a bonus for the whole month: stored on the 1st, shown in the month row and Summary, on no day', async () => {
    const { app, store, writes } = await mount();
    await click(app, '[data-testid="add-bonus"]');
    await click(app, '[data-whole-month="true"]');
    expect(text(byId(app, 'bonus-month-label'))).toBe('November 2026');
    expect(text(byId(app, 'bonus-hint'))).toContain("isn't shown on any day");
    await click(app, '[data-testid="bonus-month-next"]');
    await click(app, '[data-testid="bonus-month-previous"]');
    await type(app, 'bonus-amount-input', '250');
    await click(app, 'st-bonus-editor [data-testid="editor-save"]');
    expect(writes[0]).toMatchObject({ values: { wholeMonth: true, date: epochDay(2026, 11, 1), amount: 25000n } });
    const state = calendarState(store.state.view, 'GROSS', NOVEMBER);
    expect(text(byId(app, 'month-bonuses-row'))).toContain(`Month bonuses · ${state.monthBonuses.length}`);
    expect(state.bonusesByDate.get(epochDay(2026, 11, 1))?.some((b) => b.bonus.wholeMonth) ?? false).toBe(false);
    expect([...app.querySelectorAll('[data-testid="tile-amount"] > span')].map(text)).toEqual(
      state.monthSummary.totalEarnings.sortedByValue().map(([c, v]) => ws(money(v, c, 'en-US'))),
    );
    await go(app, '#/summary');
    const month = summaryState(store.state.view, 'GROSS', periodRange('MONTH', parseDate(DEMO_TODAY)));
    expect(app.querySelectorAll('[data-testid="summary-month-bonuses"] [data-testid="bonus-card"]')).toHaveLength(month.monthBonuses.length);
  });

  test('edit from the month row, guard on close, delete and Undo', async () => {
    const { app, store, writes } = await mount();
    await click(app, '[data-testid="month-bonuses-row"]');
    const card = q(app, '[data-testid="month-bonuses-list"] [data-testid="bonus-card"]')!;
    const id = card.dataset['id']!;
    card.click();
    await settle(app);
    expect(text(q(app, 'st-bonus-editor dialog.editor h2'))).toBe('Edit bonus');
    expect((byId(app, 'bonus-amount-input') as HTMLInputElement).value).toMatch(/^\d+(\.\d+)?$/);
    await type(app, 'bonus-amount-input', '1');
    await click(app, 'st-bonus-editor [data-testid="editor-cancel"]');
    expect(byId(app, 'confirm-discard')).not.toBeNull();
    await click(app, '[data-testid="confirm-discard-yes"]');
    expect(writes).toEqual([]);
    card.click();
    await settle(app);
    await click(app, 'st-bonus-editor [data-testid="editor-delete"]');
    await click(app, '[data-testid="confirm-delete-yes"]');
    expect(store.record('bonuses', id)).toMatchObject({ deleted: true });
    expect(text(byId(app, 'snackbar'))).toContain('Bonus deleted');
    await click(app, '[data-testid="snackbar-action"]');
    await settle(app);
    expect(store.record('bonuses', id)).toMatchObject({ deleted: false, wholeMonth: true });
    expect(writes.map((w) => w.kind)).toEqual(['delete', 'restore']);
  });
});

describe('received pay', () => {
  test('add a payment from Summary -> Month: the comparison and the totals count what arrived', async () => {
    const { app, store, writes } = await mount();
    await go(app, '#/summary');
    const row = q(app, '[data-testid="received-pay"]')!;
    const jobId = row.dataset['job']!;
    const job = store.state.view.jobs.find((j) => j.id === jobId)!;
    (row.querySelector('[data-testid="add-payment"]') as HTMLElement).click();
    await settle(app);
    expect(text(q(app, 'st-payout-editor dialog.editor h2'))).toBe('New payment');
    expect(text(byId(app, 'payout-month-label'))).toBe('November 2026');
    await type(app, 'payout-amount', '1000');
    await type(app, 'payout-received-on', '2026-12-10', 'change');
    await type(app, 'payout-note', 'Przelew');
    await click(app, 'st-payout-editor [data-testid="editor-save"]');
    expect(writes[0]).toMatchObject({
      kind: 'create', collection: 'payouts',
      values: { jobId, month: 202611, amount: 100000n, receivedOn: epochDay(2026, 12, 10), note: 'Przelew' },
    });
    expect(text(byId(app, 'snackbar'))).toBe('Payment saved');
    const month = summaryState(store.state.view, 'GROSS', periodRange('MONTH', parseDate(DEMO_TODAY)));
    expect(month.summary.receivedPart).not.toBe('NONE');
    expect([...app.querySelectorAll('[data-testid="totals-amount"] > span')].map(text)).toEqual(
      month.summary.totalEarnings.sortedByValue().map(([c, v]) => ws(money(v, c, 'en-US'))),
    );
    const updated = q(app, `[data-testid="received-pay"][data-job="${jobId}"]`)!;
    expect(text(updated.querySelector('[data-testid="received"]'))).toContain(ws(money(100000n, job.currency, 'en-US')));
  });

  test('edit a payment (clear its date), then delete it and Undo', async () => {
    const { app, store, writes } = await mount();
    await go(app, '#/summary');
    await click(app, '[data-testid="previous-period"]');
    await click(app, '[data-testid="previous-period"]'); // September: the demo has payments
    const payment = q(app, '[data-testid="edit-payment"]')!;
    const id = payment.closest<HTMLElement>('[data-testid="payment"]')!.dataset['id']!;
    payment.click();
    await settle(app);
    expect(text(q(app, 'st-payout-editor dialog.editor h2'))).toBe('Payment');
    if (byId(app, 'payout-received-on-clear')) await click(app, '[data-testid="payout-received-on-clear"]');
    await click(app, 'st-payout-editor [data-testid="editor-save"]');
    expect(writes[0]).toMatchObject({ kind: 'update', id, values: { receivedOn: null } });
    (q(app, `[data-testid="payment"][data-id="${id}"] [data-testid="edit-payment"]`) as HTMLElement).click();
    await settle(app);
    await click(app, 'st-payout-editor [data-testid="editor-delete"]');
    await click(app, '[data-testid="confirm-delete-yes"]');
    expect(store.record('payouts', id)).toMatchObject({ deleted: true });
    expect(q(app, `[data-testid="payment"][data-id="${id}"]`)).toBeNull();
    await click(app, '[data-testid="snackbar-action"]');
    await settle(app);
    expect(q(app, `[data-testid="payment"][data-id="${id}"]`)).not.toBeNull();
    expect(writes.map((w) => w.kind)).toEqual(['update', 'delete', 'restore']);
  });

  test('the month a payment is for: the stepper, within the years the rules accept', async () => {
    const { app } = await mount('uk');
    await go(app, '#/summary');
    (q(app, '[data-testid="add-payment"]') as HTMLElement).click();
    await settle(app);
    expect(text(byId(app, 'payout-month-label'))).toBe('Листопад 2026');
    await click(app, '[data-testid="payout-month-next"]');
    expect(text(byId(app, 'payout-month-label'))).toBe('Грудень 2026');
    expect(editorOpen(app)).toBe(true);
  });
});

describe('editing off', () => {
  test('Summary offers no Add payment, no Add month bonus, and payments open nothing', async () => {
    const { app } = await mount();
    app.editing = false;
    await settle(app);
    await go(app, '#/summary');
    expect(byId(app, 'add-payment')).toBeNull();
    expect(byId(app, 'summary-add-month-bonus')).toBeNull();
    expect(byId(app, 'edit-payment')).toBeNull();
    expect(app.querySelector('st-payout-editor')).toBeNull();
  });
});
