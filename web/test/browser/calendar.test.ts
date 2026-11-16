import { afterEach, describe, expect, test } from 'vitest';
import '../../src/theme/theme.css';
import '../../src/app/app.css';
import '../../src/screens/screens.css';
import '../../src/ui/material';
import '../../src/screens/calendar';
import type { CalendarScreen } from '../../src/screens/calendar';
import { Strings } from '../../src/i18n/i18n';
import { readDocuments } from '../../src/data/documents';
import { demoDocuments, DEMO_TODAY } from '../../src/demo/demo';
import { monthOf, parseDate } from '../../src/domain/dates';
import type { EarningsMode } from '../../src/domain/model';
import { decimalHours, money } from '../../src/ui/format';
import demoFixture from '../fixtures/datasets/demo.json';

/*
 * The Calendar screen in demo mode, in Chromium with the real Material components. The figures on screen are
 * checked against the app's own (the golden fixtures written by the Android code), formatted as the app formats them.
 */

const { view } = readDocuments(demoDocuments());
const TODAY = parseDate(DEMO_TODAY);
type Fixture = typeof demoFixture.expected.modes.GROSS.calendar[number];
const expectedMonth = (mode: EarningsMode, month: string): Fixture =>
  demoFixture.expected.modes[mode].calendar.find((m) => m.month === month)!;
const formatted = (strings: Strings, amounts: string[][]) =>
  [...amounts].sort((a, b) => Number(BigInt(b[1]!) - BigInt(a[1]!))).map(([c, v]) => money(BigInt(v!), c!, strings.locale));

async function mount(lang: 'uk' | 'en' = 'en', mode: EarningsMode = 'GROSS', showModeTag = true): Promise<CalendarScreen> {
  const el = document.createElement('st-calendar');
  el.strings = new Strings(lang);
  el.view = view;
  el.mode = mode;
  el.showModeTag = showModeTag;
  el.today = TODAY;
  document.body.append(el);
  await el.updateComplete;
  return el;
}

async function click(el: CalendarScreen, selector: string) {
  el.querySelector<HTMLElement>(selector)!.click();
  await el.updateComplete;
}

const text = (el: CalendarScreen, testId: string) => el.querySelector(`[data-testid="${testId}"]`)?.textContent?.replace(/\s+/g, ' ').trim() ?? null;
const lines = (el: CalendarScreen, testId: string) =>
  [...el.querySelectorAll(`[data-testid="${testId}"] > span`)].map((s) => s.textContent!.trim());

afterEach(() => {
  document.body.innerHTML = '';
});

describe('the month', () => {
  test('opens on today’s month, with the app’s tiles: earned per currency, hours, shifts', async () => {
    const el = await mount();
    const expected = expectedMonth('GROSS', '2026-11');
    expect(text(el, 'month-title')).toBe('November 2026');
    expect(lines(el, 'tile-amount')).toEqual(formatted(el.strings, expected.summary.totalEarnings));
    expect(text(el, 'tile-hours')).toBe(`Hours ${decimalHours(expected.summary.totalPaidMinutes, el.strings.locale)}`);
    expect(text(el, 'tile-shifts')).toBe(`Shifts ${expected.summary.shiftCount}`);
    expect(text(el, 'tile-earnings')).toContain('Earned this month · Gross');
    expect(el.querySelector('[data-testid="tile-earnings"] [data-testid="received-tag"]')).toBeNull();
  });

  test('net: the same screen in net amounts, tagged Net', async () => {
    const el = await mount('en', 'NET');
    expect(lines(el, 'tile-amount')).toEqual(formatted(el.strings, expectedMonth('NET', '2026-11').summary.totalEarnings));
    expect(text(el, 'tile-earnings')).toContain('· Net');
  });

  test('no deductions anywhere: no Gross/Net tag', async () => {
    const el = await mount('en', 'GROSS', false);
    expect(text(el, 'tile-earnings')).not.toContain('Gross');
    expect(el.querySelector('[data-testid="mode-tag"]')).toBeNull();
  });

  test('a month with received pay is marked; its figures are the app’s', async () => {
    const el = await mount();
    await click(el, '[data-testid="previous-month"]');
    await click(el, '[data-testid="previous-month"]');
    const expected = expectedMonth('GROSS', '2026-09');
    expect(text(el, 'month-title')).toBe('September 2026');
    expect(lines(el, 'tile-amount')).toEqual(formatted(el.strings, expected.summary.totalEarnings));
    expect(expected.summary.receivedPart).toBe('PART');
    expect(el.querySelector('[data-testid="tile-earnings"] [data-testid="received-tag"]')!.textContent!.trim()).toBe('Incl. received');
  });

  test('every month of the demo shows the app’s figures, back and forth', async () => {
    const el = await mount('uk');
    for (let i = 0; i < 4; i++) await click(el, '[data-testid="previous-month"]');
    for (const month of ['2026-07', '2026-08', '2026-09', '2026-10', '2026-11', '2026-12']) {
      const expected = expectedMonth('GROSS', month);
      const shown = lines(el, 'tile-amount');
      const want = expected.summary.totalEarnings.length ? formatted(el.strings, expected.summary.totalEarnings) : [money(0n, 'PLN', el.strings.locale)];
      expect(shown, month).toEqual(want);
      expect(text(el, 'tile-shifts'), month).toBe(`Зміни ${expected.summary.shiftCount}`);
      await click(el, '[data-testid="next-month"]');
    }
    await click(el, '[data-testid="today"]');
    expect(text(el, 'month-title')).toBe('Листопад 2026');
  });
});

describe('whole-month bonuses', () => {
  test('have their own row, with the count and total, and open into a list; they are on no day', async () => {
    const el = await mount();
    const expected = expectedMonth('GROSS', '2026-11');
    const total = expected.monthBonuses.reduce((t, b) => t + BigInt(b.amount), 0n);
    expect(text(el, 'month-bonuses-row')).toContain(`Month bonuses · ${expected.monthBonuses.length}`);
    expect(text(el, 'month-bonuses-total')).toBe(`+${money(total, 'PLN', el.strings.locale)}`.replace(/\s+/g, ' '));
    expect(el.querySelector('[data-testid="month-bonuses-list"]')).toBeNull();
    await click(el, '[data-testid="month-bonuses-row"]');
    const cards = el.querySelectorAll('[data-testid="month-bonuses-list"] [data-testid="bonus-card"]');
    expect(cards).toHaveLength(expected.monthBonuses.length);
    expect(cards[0]!.textContent).toContain('For the whole month');
    // No day of the month shows a whole-month bonus.
    const monthBonusIds = new Set(expected.monthBonuses.map((b) => b.id));
    for (const day of expected.days) for (const b of day.bonuses) expect(monthBonusIds.has(b.id)).toBe(false);
  });

  test('a month without any has no row', async () => {
    const el = await mount();
    await click(el, '[data-testid="next-month"]');
    expect(el.querySelector('[data-testid="month-bonuses-row"]')).toBeNull();
  });
});

describe('grid and day', () => {
  test('six rows; days with shifts have a dot per job; today is selected', async () => {
    const el = await mount();
    expect(el.querySelectorAll('.month-grid .day-cell')).toHaveLength(42);
    const expected = expectedMonth('GROSS', '2026-11');
    for (const day of expected.days) {
      const n = Number(day.date.slice(-2));
      const button = el.querySelector(`.day[data-day="${n}"]`)!;
      const jobs = new Set(day.shifts.map((s) => s.job)).size;
      expect(button.querySelectorAll('.dot').length, day.date).toBe(Math.min(jobs, 3));
      expect(button.querySelector('.bonus-mark') !== null, day.date).toBe(day.bonuses.length > 0);
    }
    expect(el.querySelector('.day[aria-selected="true"]')!.getAttribute('data-day')).toBe('13');
  });

  test('the selected day: title, hours, total and every shift and bonus as the app shows them', async () => {
    const el = await mount();
    const expected = expectedMonth('GROSS', '2026-11');
    for (const day of expected.days.slice(0, 8)) {
      await click(el, `.day[data-day="${Number(day.date.slice(-2))}"]`);
      const pays = [...el.querySelectorAll('[data-testid="day-panel"] [data-testid="shift-pay"]')].map((p) => p.textContent!.trim());
      expect(pays, day.date).toEqual(
        day.shifts.map((s) => (s.paysHourly ? money(BigInt(s.earnings), view.jobs.find((j) => j.id === s.job)!.currency, el.strings.locale) : 'Salary')),
      );
      const bonuses = [...el.querySelectorAll('[data-testid="day-panel"] [data-testid="bonus-amount"]')].map((p) => p.textContent!.trim());
      expect(bonuses, day.date).toEqual(day.bonuses.map((b) => `+${money(BigInt(b.amount), view.jobs.find((j) => j.id === b.job)!.currency, el.strings.locale)}`));
      expect(lines(el, 'day-total'), day.date).toEqual(formatted(el.strings, day.total.filter(([, v]) => BigInt(v!) > 0n)));
    }
  });

  test('a free day says so', async () => {
    const el = await mount();
    const busy = new Set(expectedMonth('GROSS', '2026-11').days.map((d) => Number(d.date.slice(-2))));
    const free = [...Array(30).keys()].map((i) => i + 1).find((n) => !busy.has(n))!;
    await click(el, `.day[data-day="${free}"]`);
    expect(text(el, 'day-free')).toBe('Day off — no shifts');
    expect(text(el, 'day-subtitle')).toBe('No shifts on this day.');
  });

  test('arrow keys move the selection, across into the next month', async () => {
    const el = await mount();
    const selected = el.querySelector<HTMLButtonElement>('.day[aria-selected="true"]')!;
    selected.focus();
    selected.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
    await el.updateComplete;
    expect(el.querySelector('.day[aria-selected="true"]')!.getAttribute('data-day')).toBe('20');
    for (let i = 0; i < 2; i++) {
      el.querySelector('.day[aria-selected="true"]')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
      await el.updateComplete;
    }
    expect(text(el, 'month-title')).toBe('December 2026');
    expect(el.querySelector('.day[aria-selected="true"]')!.getAttribute('data-day')).toBe('4');
    expect(monthOf(TODAY)).toBe(monthOf(parseDate('2026-11-01')));
  });

  test('wide screens put the day beside the month; phones under it', async () => {
    const el = await mount();
    const wide = window.matchMedia('(min-width: 840px)').matches;
    expect(getComputedStyle(el.querySelector('.calendar')!).flexDirection).toBe(wide ? 'row' : 'column');
  });
});
