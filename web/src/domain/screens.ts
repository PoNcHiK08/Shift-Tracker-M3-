import { Amounts } from './amounts';
import { earnings, salary, shiftPaidMinutes, summarize, wholeMonths } from './calc';
import { civil, epochDay, firstDay, lastDay, mondayOf, monthOf, yearMonth, type EpochDay, type YearMonth } from './dates';
import type { BonusWithJob, EarningsMode, Job, PayoutWithJob, ShiftWithJob } from './model';
import { actualSummary, forMonth, forYear, type JobYearReconciliation, type PayReconciliation } from './reconciler';
import { MonthSummary, type ReceivedPart } from './summary';

/**
 * What the app's screens compute from the data (ui/calendar/CalendarViewModel.kt, ui/summary/SummaryViewModel.kt),
 * over a [DataView]: the user's live records, already read and checked as the phone has them in Room.
 */
export interface DataView {
  /** Live jobs, in the app's order (by name, ignoring ASCII case). */
  jobs: Job[];
  /** Live shifts of live jobs, by date and start time. */
  shifts: ShiftWithJob[];
  /** Live bonuses of live jobs, by date. */
  bonuses: BonusWithJob[];
  /** Live payouts of live jobs, by month. */
  payouts: PayoutWithJob[];
}

export const EMPTY_VIEW: DataView = { jobs: [], shifts: [], bonuses: [], payouts: [] };

export function shiftsBetween(view: DataView, from: EpochDay, to: EpochDay): ShiftWithJob[] {
  return view.shifts.filter((s) => s.shift.date >= from && s.shift.date <= to);
}

export function bonusesBetween(view: DataView, from: EpochDay, to: EpochDay): BonusWithJob[] {
  return view.bonuses.filter((b) => b.bonus.date >= from && b.bonus.date <= to);
}

export function payoutsBetween(view: DataView, from: YearMonth, to: YearMonth): PayoutWithJob[] {
  return view.payouts.filter((p) => p.payout.month >= from && p.payout.month <= to);
}

/** Bonuses that belong to a day (a whole-month bonus has no day: not in the grid, a day or a week). */
export const onDays = (bonuses: readonly BonusWithJob[]): BonusWithJob[] => bonuses.filter((b) => !b.bonus.wholeMonth);
export const forWholeMonths = (bonuses: readonly BonusWithJob[]): BonusWithJob[] => bonuses.filter((b) => b.bonus.wholeMonth);

function groupByDate<T>(items: readonly T[], date: (item: T) => EpochDay): Map<EpochDay, T[]> {
  const map = new Map<EpochDay, T[]>();
  for (const item of items) {
    const d = date(item);
    const list = map.get(d);
    if (list) list.push(item);
    else map.set(d, [item]);
  }
  return map;
}

// --- Calendar --------------------------------------------------------------------------------------------

export interface CalendarState {
  month: YearMonth;
  /** Shifts of the month and its neighbours, by date. */
  shiftsByDate: Map<EpochDay, ShiftWithJob[]>;
  /** Day bonuses (not whole-month ones), by date. */
  bonusesByDate: Map<EpochDay, BonusWithJob[]>;
  /** The month's bonuses for the whole month: in its totals, shown in their own row. */
  monthBonuses: BonusWithJob[];
  /** The month's tiles; a job with received pay for the month counts that instead of its calculated pay. */
  monthSummary: MonthSummary;
}

export function calendarState(view: DataView, mode: EarningsMode, month: YearMonth): CalendarState {
  const from = firstDay(month - 1);
  const to = lastDay(month + 1);
  const shifts = shiftsBetween(view, from, to);
  const bonuses = bonusesBetween(view, from, to);
  const payouts = payoutsBetween(view, month, month);
  const monthShifts = shifts.filter((s) => monthOf(s.shift.date) === month);
  const monthBonuses = bonuses.filter((b) => monthOf(b.bonus.date) === month);
  return {
    month,
    shiftsByDate: groupByDate(shifts, (s) => s.shift.date),
    bonusesByDate: groupByDate(onDays(bonuses), (b) => b.bonus.date),
    monthBonuses: forWholeMonths(monthBonuses),
    monthSummary: actualSummary(monthShifts, monthBonuses, view.jobs, [month], mode, payouts),
  };
}

/** The day panel: the day's shifts and bonuses and what they earn (per currency, never added across). */
export function dayTotal(state: CalendarState, date: EpochDay, mode: EarningsMode): Amounts {
  return earnings(state.shiftsByDate.get(date) ?? [], state.bonusesByDate.get(date) ?? [], mode);
}

// --- Summary ---------------------------------------------------------------------------------------------

export type SummaryPeriod = 'WEEK' | 'MONTH' | 'YEAR';

export interface PeriodRange {
  period: SummaryPeriod;
  from: EpochDay;
  to: EpochDay;
}

export function periodRange(period: SummaryPeriod, anchor: EpochDay): PeriodRange {
  switch (period) {
    case 'WEEK': {
      const monday = mondayOf(anchor);
      return { period, from: monday, to: monday + 6 };
    }
    case 'MONTH': {
      const month = monthOf(anchor);
      return { period, from: firstDay(month), to: lastDay(month) };
    }
    case 'YEAR': {
      const year = civil(anchor).year;
      return { period, from: epochDay(year, 1, 1), to: epochDay(year, 12, 31) };
    }
  }
}

/** The range one step before or after (Summary's ‹ › buttons). */
export function stepRange(range: PeriodRange, step: number): PeriodRange {
  const c = civil(range.from);
  switch (range.period) {
    case 'WEEK':
      return periodRange('WEEK', range.from + 7 * step);
    case 'MONTH':
      return periodRange('MONTH', firstDay(yearMonth(c.year, c.month) + step));
    case 'YEAR':
      return periodRange('YEAR', epochDay(c.year + step, 1, 1));
  }
}

export interface BreakdownEntry {
  date: EpochDay;
  paidMinutes: number;
  earnings: Amounts;
  shiftCount: number;
  bonusCount: number;
  hasSalary: boolean;
  receivedPart: ReceivedPart;
}

export const breakdownIsEmpty = (e: BreakdownEntry): boolean =>
  e.shiftCount === 0 && e.bonusCount === 0 && !e.hasSalary && e.receivedPart === 'NONE';

export interface SummaryState {
  range: PeriodRange;
  summary: MonthSummary;
  /** Days of the week (WEEK) or months of the year (YEAR); empty for MONTH. */
  breakdown: BreakdownEntry[];
  /** Month view: the month's bonuses for the whole month. */
  monthBonuses: BonusWithJob[];
  /** Month view: per job, what arrived against the calculated net pay. */
  receivedPay: PayReconciliation[];
  /** Year view: per job with received pay, over the months that have it. */
  receivedPayYear: JobYearReconciliation[];
}

export function summaryState(view: DataView, mode: EarningsMode, range: PeriodRange): SummaryState {
  const shifts = shiftsBetween(view, range.from, range.to);
  const allBonuses = bonusesBetween(view, range.from, range.to);
  const payouts =
    range.period === 'WEEK' ? [] : payoutsBetween(view, monthOf(range.from), monthOf(range.to));
  // A week can span two months and a whole-month bonus belongs to one of them: it is never part of a week.
  const bonuses = range.period === 'WEEK' ? onDays(allBonuses) : allBonuses;
  const jobs = view.jobs;
  return {
    range,
    // A week covers no whole month and observes no payouts: it stays calculated.
    summary: actualSummary(shifts, bonuses, jobs, wholeMonths(range.from, range.to), mode, payouts),
    breakdown: breakdown(range, shifts, bonuses, jobs, mode, payouts),
    monthBonuses: range.period === 'MONTH' ? forWholeMonths(bonuses) : [],
    receivedPay: range.period === 'MONTH' ? forMonth(monthOf(range.from), jobs, shifts, bonuses, payouts) : [],
    receivedPayYear: range.period === 'YEAR' ? forYear(civil(range.from).year, jobs, shifts, bonuses, payouts) : [],
  };
}

function entry(date: EpochDay, shifts: ShiftWithJob[], bonuses: BonusWithJob[], mode: EarningsMode, salaries: Amounts = Amounts.EMPTY): BreakdownEntry {
  return {
    date,
    paidMinutes: shifts.reduce((t, s) => t + paidOf(s), 0),
    earnings: earnings(shifts, bonuses, mode).plus(salaries),
    hasSalary: !salaries.isEmpty,
    shiftCount: shifts.length,
    bonusCount: bonuses.length,
    receivedPart: 'NONE',
  };
}

const paidOf = (s: ShiftWithJob): number => shiftPaidMinutes(s.shift);

function breakdown(
  range: PeriodRange,
  shifts: ShiftWithJob[],
  bonuses: BonusWithJob[],
  jobs: Job[],
  mode: EarningsMode,
  payouts: PayoutWithJob[],
): BreakdownEntry[] {
  switch (range.period) {
    case 'WEEK':
      return Array.from({ length: 7 }, (_, offset) => {
        const day = range.from + offset;
        return entry(day, shifts.filter((s) => s.shift.date === day), bonuses.filter((b) => b.bonus.date === day), mode);
      });
    case 'YEAR': {
      const year = civil(range.from).year;
      return Array.from({ length: 12 }, (_, index) => {
        const month = yearMonth(year, index + 1);
        const monthShifts = shifts.filter((s) => monthOf(s.shift.date) === month);
        const monthBonuses = bonuses.filter((b) => monthOf(b.bonus.date) === month);
        // Each month of the year carries its fixed salaries.
        let salaries = Amounts.EMPTY;
        for (const job of jobs) {
          const amount = salary(job, month, mode);
          if (amount > 0n) salaries = salaries.plus(Amounts.of(job.currency, amount));
        }
        const calculated = entry(firstDay(month), monthShifts, monthBonuses, mode, salaries);
        // Jobs of the month with received pay count what arrived instead of their calculated pay.
        const actual = actualSummary(monthShifts, monthBonuses, jobs, [month], mode, payouts);
        if (actual.receivedPart === 'NONE') return calculated;
        return { ...calculated, earnings: calculated.earnings.minus(actual.replaced).plus(actual.received), receivedPart: actual.receivedPart };
      });
    }
    case 'MONTH':
      return [];
  }
}

export { summarize };
