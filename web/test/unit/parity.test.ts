import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, test } from 'vitest';
import type { Amounts } from '../../src/domain/amounts';
import { bonusAmount, earnings, net, pay, paysHourly, shiftEarnings, shiftPaidMinutes } from '../../src/domain/calc';
import { fractionDigits, isValidCurrency, knownCurrencies } from '../../src/domain/currencies';
import { dateString, monthOf, monthString, parseDate } from '../../src/domain/dates';
import type { BonusWithJob, EarningsMode, Job, PaySettings, TimedRate } from '../../src/domain/model';
import type { JobYearReconciliation, PayReconciliation } from '../../src/domain/reconciler';
import { breakdownIsEmpty, calendarState, periodRange, summaryState, type BreakdownEntry, type DataView, type SummaryPeriod } from '../../src/domain/screens';
import type { JobSummary, MonthSummary } from '../../src/domain/summary';
import { readDocuments } from '../../src/data/documents';
import { demoDocuments } from '../../src/demo/demo';

/*
 * The golden fixtures (test/fixtures, written by the app's own Kotlin code: app/src/test/.../web/WebFixturesExport.kt)
 * against this port. Every number must be the app's, exactly. See docs/web.md to write the fixtures again.
 */

const FIXTURES = resolve(__dirname, '../fixtures');
const load = (name: string) => JSON.parse(readFileSync(resolve(FIXTURES, name), 'utf8'));

// --- Serialized as the exporter writes them -------------------------------------------------------------------

const money = (value: bigint) => value.toString();
const amounts = (a: Amounts) => [...a.byCurrency.entries()].map(([code, value]) => [code, money(value)]);
const rate = (r: TimedRate | null) => (r ? { rate: money(r.rate), from: r.from, to: r.to } : null);

function summary(s: MonthSummary) {
  return {
    totalPaidMinutes: s.totalPaidMinutes,
    shiftCount: s.shiftCount,
    bonusCount: s.bonusCount,
    fixedPayShiftCount: s.fixedPayShiftCount,
    isEmpty: s.isEmpty,
    receivedPart: s.receivedPart,
    shiftEarnings: amounts(s.shiftEarnings),
    bonuses: amounts(s.bonuses),
    salaries: amounts(s.salaries),
    received: amounts(s.received),
    replaced: amounts(s.replaced),
    replacedBonuses: amounts(s.replacedBonuses),
    replacedSalaries: amounts(s.replacedSalaries),
    calculatedEarnings: amounts(s.calculatedEarnings),
    totalEarnings: amounts(s.totalEarnings),
    countedBonuses: amounts(s.countedBonuses),
    countedSalaries: amounts(s.countedSalaries),
    currencies: s.currencies,
    averageRates: [...new Set(s.perJob.map((j) => j.currency))].map((currency) => ({ currency, rate: money(s.averageRate(currency)) })),
    perJob: s.perJob.map(jobSummary),
  };
}

function jobSummary(j: JobSummary) {
  return {
    job: j.job.id,
    currency: j.currency,
    paidMinutes: j.paidMinutes,
    hourlyPaidMinutes: j.hourlyPaidMinutes,
    shiftCount: j.shiftCount,
    shiftEarnings: money(j.shiftEarnings),
    bonus: money(j.bonus),
    salary: money(j.salary),
    received: money(j.received),
    replaced: money(j.replaced),
    replacedBonus: money(j.replacedBonus),
    replacedSalary: money(j.replacedSalary),
    receivedMonths: j.receivedMonths,
    calculated: money(j.calculated),
    earnings: money(j.earnings),
    countedBonus: money(j.countedBonus),
    countedSalary: money(j.countedSalary),
    receivedPart: j.receivedPart,
  };
}

const bonusLine = (b: BonusWithJob, mode: EarningsMode) => ({ id: b.bonus.id, job: b.job.id, amount: money(bonusAmount(b, mode)) });

const breakdown = (e: BreakdownEntry) => ({
  date: dateString(e.date),
  paidMinutes: e.paidMinutes,
  earnings: amounts(e.earnings),
  shiftCount: e.shiftCount,
  bonusCount: e.bonusCount,
  hasSalary: e.hasSalary,
  receivedPart: e.receivedPart,
  isEmpty: breakdownIsEmpty(e),
});

const reconciliation = (r: PayReconciliation) => ({
  job: r.job.id,
  month: monthString(r.month),
  calculatedNet: money(r.calculatedNet),
  payouts: r.payouts.map((p) => p.id),
  received: money(r.received),
  difference: r.difference === null ? null : money(r.difference),
});

const yearReconciliation = (r: JobYearReconciliation) => ({
  job: r.job.id,
  months: r.months.map(reconciliation),
  calculatedNet: money(r.calculatedNet),
  received: money(r.received),
  difference: money(r.difference),
});

const paySettings = (p: PaySettings) => ({
  from: p.from === null ? null : monthString(p.from),
  payType: p.payType,
  monthlyAmount: money(p.monthlyAmount),
  deductionBp: p.deductionBp,
});

const job = (j: Job) => ({
  id: j.id,
  name: j.name,
  currency: j.currency,
  hourlyRate: money(j.hourlyRate),
  color: j.color,
  secondRate: rate(j.secondRate),
  payHistory: j.payHistory.map(paySettings),
  firstEntry: j.firstEntry === null ? null : dateString(j.firstEntry),
  lastEntry: j.lastEntry === null ? null : dateString(j.lastEntry),
});

function calendar(view: DataView, mode: EarningsMode, month: string) {
  const ym = monthOf(parseDate(`${month}-01`));
  const state = calendarState(view, mode, ym);
  const dates = [...new Set([...state.shiftsByDate.keys(), ...state.bonusesByDate.keys()])].filter((d) => monthOf(d) === ym).sort((a, b) => a - b);
  return {
    month,
    summary: summary(state.monthSummary),
    monthBonuses: state.monthBonuses.map((b) => bonusLine(b, mode)),
    days: dates.map((date) => {
      const shifts = state.shiftsByDate.get(date) ?? [];
      const bonuses = state.bonusesByDate.get(date) ?? [];
      return {
        date: dateString(date),
        paidMinutes: shifts.reduce((t, s) => t + shiftPaidMinutes(s.shift), 0),
        total: amounts(earnings(shifts, bonuses, mode)),
        shifts: shifts.map((s) => ({
          id: s.shift.id,
          job: s.job.id,
          paysHourly: paysHourly(s.job, s.shift.date),
          paidMinutes: shiftPaidMinutes(s.shift),
          earnings: money(shiftEarnings(s, mode)),
        })),
        bonuses: bonuses.map((b) => bonusLine(b, mode)),
      };
    }),
  };
}

function summaryOf(view: DataView, mode: EarningsMode, period: SummaryPeriod, from: string) {
  const state = summaryState(view, mode, periodRange(period, parseDate(from)));
  return {
    period,
    from: dateString(state.range.from),
    to: dateString(state.range.to),
    summary: summary(state.summary),
    breakdown: state.breakdown.map(breakdown),
    monthBonuses: state.monthBonuses.map((b) => bonusLine(b, mode)),
    receivedPay: state.receivedPay.map(reconciliation),
    receivedPayYear: state.receivedPayYear.map(yearReconciliation),
  };
}

// --- The tests -------------------------------------------------------------------------------------------------

describe('calculations (calc.json)', () => {
  const calc = load('calc.json');

  test(`shift pay: ${calc.shifts.length} shifts across midnight, with breaks, second-rate windows and the largest rates`, () => {
    const actual = calc.shifts.map((c: any) => {
      const second = c.second ? { rate: BigInt(c.second.rate), from: c.second.from, to: c.second.to } : null;
      const result = pay(c.start, c.end, c.break, BigInt(c.rate), second);
      return {
        ...c,
        durationMinutes: result.durationMinutes,
        paidMinutes: result.paidMinutes,
        secondRateMinutes: result.secondRateMinutes,
        earnings: money(result.earnings),
        overnight: c.end <= c.start,
      };
    });
    expect(actual).toEqual(calc.shifts);
  });

  test(`net: ${calc.net.length} amounts and deductions, rounded half up`, () => {
    expect(calc.net.map((c: any) => ({ ...c, net: money(net(BigInt(c.gross), c.bp)) }))).toEqual(calc.net);
  });

  test('currencies and their decimals are the app’s', () => {
    expect(knownCurrencies().map(([code, digits]) => ({ code, digits }))).toEqual(calc.currencies);
    expect(isValidCurrency('XYZ')).toBe(false);
    expect(fractionDigits('PLN')).toBe(2);
  });
});

for (const name of ['demo', 'edge', 'random']) {
  describe(`dataset "${name}"`, () => {
    const fixture = load(`datasets/${name}.json`);
    const { view } = readDocuments(fixture.documents);

    if (name === 'demo') {
      test('is the demo data the site shows', () => {
        expect(fixture.documents).toEqual(demoDocuments());
      });
    }

    test('jobs, as the Jobs screen lists them', () => {
      expect(view.jobs.map(job)).toEqual(fixture.expected.jobs);
    });

    for (const mode of ['GROSS', 'NET'] as const) {
      const expected = fixture.expected.modes[mode];

      test(`${mode}: every month of the calendar (tiles, month bonuses, every day)`, () => {
        for (const month of expected.calendar) expect(calendar(view, mode, month.month), month.month).toEqual(month);
      });

      test(`${mode}: every week, month and year of Summary`, () => {
        for (const period of expected.summary) {
          expect(summaryOf(view, mode, period.period, period.from), `${period.period} ${period.from}`).toEqual(period);
        }
      });
    }
  });
}
