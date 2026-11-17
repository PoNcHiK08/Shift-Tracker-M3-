import { afterEach, describe, expect, test } from 'vitest';
import '../../src/theme/theme.css';
import '../../src/app/app.css';
import '../../src/screens/screens.css';
import '../../src/ui/material';
import '../../src/screens/summary';
import '../../src/screens/jobs';
import type { SummaryScreen } from '../../src/screens/summary';
import type { JobsScreen } from '../../src/screens/jobs';
import { Strings } from '../../src/i18n/i18n';
import { readDocuments } from '../../src/data/documents';
import { demoDocuments, DEMO_TODAY } from '../../src/demo/demo';
import { parseDate } from '../../src/domain/dates';
import type { EarningsMode } from '../../src/domain/model';
import { amounts as formatAmounts, decimalHours, money } from '../../src/ui/format';
import { Amounts } from '../../src/domain/amounts';
import demoFixture from '../fixtures/datasets/demo.json';

/*
 * Summary and Jobs in demo mode, in Chromium. Every figure shown is checked against the app's own (golden fixtures
 * written by the Android code), formatted as the app formats it.
 */

const { view } = readDocuments(demoDocuments());
const TODAY = parseDate(DEMO_TODAY);
const jobOf = (id: string) => view.jobs.find((j) => j.id === id)!;
type Period = (typeof demoFixture.expected.modes.GROSS.summary)[number];
const expected = (mode: EarningsMode, period: string, from: string): Period =>
  demoFixture.expected.modes[mode].summary.find((p) => p.period === period && p.from === from)!;
const ws = (s: string) => s.replace(/\s+/g, ' ').trim();
const lines = (strings: Strings, values: string[][]) =>
  [...values].sort((a, b) => Number(BigInt(b[1]!) - BigInt(a[1]!))).map(([c, v]) => ws(money(BigInt(v!), c!, strings.locale)));
const amountsOf = (values: string[][]) => new Amounts(new Map(values.map(([c, v]) => [c!, BigInt(v!)])));

async function mountSummary(mode: EarningsMode = 'GROSS', showModeTag = true): Promise<SummaryScreen> {
  const el = document.createElement('st-summary');
  el.strings = new Strings('en');
  el.view = view;
  el.mode = mode;
  el.showModeTag = showModeTag;
  el.today = TODAY;
  el.addEventListener('mode-change', (e) => {
    el.mode = (e as CustomEvent<EarningsMode>).detail;
  });
  document.body.append(el);
  await el.updateComplete;
  return el;
}

async function click(el: HTMLElement & { updateComplete: Promise<boolean> }, selector: string) {
  el.querySelector<HTMLElement>(selector)!.click();
  await el.updateComplete;
}

const all = (el: Element, selector: string) => [...el.querySelectorAll(selector)].map((e) => ws(e.textContent!));

/** What a Summary period shows, against the app's figures for it. */
function checkPeriod(el: SummaryScreen, period: Period) {
  const s = el.strings;
  const summary = period.summary;
  expect(all(el, '[data-testid="totals-amount"] > span'), `${period.period} ${period.from}`).toEqual(
    summary.totalEarnings.length ? lines(s, summary.totalEarnings) : [ws(money(0n, view.jobs[0]!.currency, s.locale))],
  );
  expect(all(el, '[data-testid="totals-hours"] .title-large')).toEqual([decimalHours(summary.totalPaidMinutes, s.locale)]);
  expect(all(el, '[data-testid="totals-shifts"] .title-large')).toEqual([String(summary.shiftCount)]);
  const received = el.querySelector('[data-testid="totals"] [data-testid="received-tag"]')?.textContent?.trim() ?? 'NONE';
  expect(received).toBe({ NONE: 'NONE', PART: 'Incl. received', ALL: 'Received' }[summary.receivedPart]);
  // Nothing in the period: the app shows its empty state instead of the lists.
  const empty = summary.isEmpty && period.receivedPay.length === 0 && period.receivedPayYear.length === 0;
  expect(el.querySelector('[data-testid="summary-empty"]') !== null, `${period.period} ${period.from} empty`).toBe(empty);
  if (empty) {
    expect(el.querySelector('[data-testid="breakdown"], [data-testid="job-summary"]')).toBeNull();
    return;
  }
  expect(all(el, '[data-testid="job-earnings"]')).toEqual(summary.perJob.map((j) => ws(money(BigInt(j.earnings), j.currency, s.locale))));
  expect([...el.querySelectorAll('[data-testid="received-pay"]')].map((r) => r.getAttribute('data-job'))).toEqual(period.receivedPay.map((r) => r.job));
  for (const row of period.receivedPay) {
    const item = el.querySelector(`[data-testid="received-pay"][data-job="${row.job}"]`)!;
    const currency = jobOf(row.job).currency;
    expect(ws(item.querySelector('[data-testid="calculated"]')!.textContent!)).toContain(ws(money(BigInt(row.calculatedNet), currency, s.locale)));
    if (row.difference !== null) {
      const diff = BigInt(row.difference);
      expect(ws(item.querySelector('[data-testid="difference"]')!.textContent!)).toContain(ws((diff > 0n ? '+' : '') + money(diff, currency, s.locale)));
    } else expect(item.querySelector('[data-testid="difference"]')).toBeNull();
  }
  for (const line of period.receivedPayYear) {
    const item = el.querySelector(`[data-testid="received-pay-year"][data-job="${line.job}"]`)!;
    expect(ws(item.querySelector('[data-testid="received"]')!.textContent!)).toContain(ws(money(BigInt(line.received), jobOf(line.job).currency, s.locale)));
  }
  expect(all(el, '[data-testid="breakdown-amount"]')).toEqual(
    period.breakdown.map((b) => (b.isEmpty ? '—' : ws(formatAmounts(amountsOf(b.earnings), s.locale)))),
  );
}

afterEach(() => {
  document.body.innerHTML = '';
});

describe('Summary', () => {
  test('opens on this month, as the app shows it', async () => {
    const el = await mountSummary();
    expect(ws(el.querySelector('[data-testid="period-label"]')!.textContent!)).toBe('November 2026');
    checkPeriod(el, expected('GROSS', 'MONTH', '2026-11-01'));
    expect(el.querySelectorAll('[data-testid="summary-month-bonuses"] [data-testid="bonus-card"]')).toHaveLength(
      expected('GROSS', 'MONTH', '2026-11-01').monthBonuses.length,
    );
  });

  test('a month with received pay: its totals count what arrived, and the comparison is net', async () => {
    const el = await mountSummary();
    await click(el, '[data-testid="previous-period"]');
    await click(el, '[data-testid="previous-period"]');
    const september = expected('GROSS', 'MONTH', '2026-09-01');
    expect(september.summary.receivedPart).toBe('PART');
    checkPeriod(el, september);
    expect(el.querySelector('[data-testid="current-period"]')).not.toBeNull();
    await click(el, '[data-testid="current-period"]');
    expect(ws(el.querySelector('[data-testid="period-label"]')!.textContent!)).toBe('November 2026');
  });

  test('weeks stay calculated: never received pay, never whole-month bonuses', async () => {
    const el = await mountSummary();
    await click(el, '[role="radio"][data-value="WEEK"]');
    expect(ws(el.querySelector('[data-testid="period-label"]')!.textContent!)).toBe('9 Nov – 15 Nov 2026');
    for (const from of ['2026-11-09', '2026-11-02', '2026-10-26', '2026-10-19', '2026-10-12', '2026-10-05']) {
      const week = expected('GROSS', 'WEEK', from);
      checkPeriod(el, week);
      expect(el.querySelector('[data-testid="totals"] [data-testid="received-tag"]')).toBeNull();
      expect(el.querySelector('[data-testid="week-fixed-note"]') !== null).toBe(week.summary.fixedPayShiftCount > 0);
      await click(el, '[data-testid="previous-period"]');
    }
  });

  test('the year: months with their received pay, and received pay per job', async () => {
    const el = await mountSummary();
    await click(el, '[role="radio"][data-value="YEAR"]');
    checkPeriod(el, expected('GROSS', 'YEAR', '2026-01-01'));
    await click(el, '[data-testid="next-period"]');
    expect(el.querySelector('[data-testid="summary-empty"]')).not.toBeNull();
  });

  test('Gross / Net: switching shows the app’s net figures; hidden when no job has deductions', async () => {
    const el = await mountSummary();
    await click(el, '[data-testid="mode"] [data-value="NET"]');
    expect(el.mode).toBe('NET');
    checkPeriod(el, expected('NET', 'MONTH', '2026-11-01'));
    expect(ws(el.querySelector('[data-testid="totals"] [data-testid="mode-tag"]')!.textContent!)).toBe('Net');
    const plain = await mountSummary('GROSS', false);
    expect(plain.querySelector('[data-testid="mode"]')).toBeNull();
  });

  test('every period the app computed for the demo, in both modes', async () => {
    for (const mode of ['GROSS', 'NET'] as const) {
      for (const period of demoFixture.expected.modes[mode].summary) {
        document.body.innerHTML = '';
        const el = await mountSummary(mode);
        await click(el, `[role="radio"][data-value="${period.period}"]`);
        // Page from today's period to the one wanted.
        const target = parseDate(period.from);
        for (let guard = 0; guard < 80; guard++) {
          const shown = (el as unknown as { current: { from: number } }).current.from;
          if (shown === target) break;
          await click(el, shown > target ? '[data-testid="previous-period"]' : '[data-testid="next-period"]');
        }
        checkPeriod(el, period);
      }
    }
  });
});

describe('Jobs', () => {
  test('lists the jobs in the app’s order, with what each pays this month', async () => {
    const el = document.createElement('st-jobs') as JobsScreen;
    el.strings = new Strings('uk');
    el.view = view;
    el.today = TODAY;
    document.body.append(el);
    await el.updateComplete;
    expect(all(el, '[data-testid="job-name"]')).toEqual(demoFixture.expected.jobs.map((j) => j.name));
    const rates = all(el, '[data-testid="job-rates"]');
    expect(rates[0]).toBe(ws('Оклад 5 200,00 zł/міс. · утримання 22,71 %'));
    expect(rates[1]).toBe(ws('31,50 zł/h'));
    expect(rates[2]).toBe(ws('33,00 zł/h · 23:00–06:00: 37,00 zł/h · утримання 13,71 %'));
    expect(rates[3]).toBe(ws('18,00 €/h'));
    expect(el.querySelector('button, md-icon-button, input')).toBeNull();
  });
});
