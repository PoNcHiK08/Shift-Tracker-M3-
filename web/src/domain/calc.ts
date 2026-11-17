import { Amounts } from './amounts';
import { firstDay, lastDay, monthOf, type EpochDay, type YearMonth } from './dates';
import {
  DEFAULT_PAY,
  type BonusWithJob,
  type EarningsMode,
  type Job,
  type PaySettings,
  type Shift,
  type ShiftWithJob,
  type TimedRate,
} from './model';
import { JobSummary, MonthSummary } from './summary';

/*
 * The app's pay calculation (domain/calc/ShiftCalculator.kt and domain/model/PaySettings.kt), line for line.
 * All money is bigint: a long shift at the largest rate the app accepts (rate × minutes × paid minutes) is beyond
 * what a JavaScript number holds exactly. Divisions truncate like Kotlin's Long division (operands are never
 * negative where it matters). web/test/unit/parity.test.ts checks every result against the app's own.
 */

const MINUTES_PER_DAY = 1440;

/** A shift whose end is not after its start finishes on the following day. */
export function isOvernight(start: number, end: number): boolean {
  return end <= start;
}

/** Total minutes between start and end, crossing midnight when needed (equal: a full 24 h). */
export function durationMinutes(start: number, end: number): number {
  const diff = end - start;
  return diff <= 0 ? diff + MINUTES_PER_DAY : diff;
}

/** Paid minutes = duration minus the unpaid break, never negative. */
export function paidMinutes(start: number, end: number, breakMinutes: number): number {
  return Math.max(0, durationMinutes(start, end) - Math.max(0, breakMinutes));
}

/** Minutes of the shift inside the daily window (which may itself cross midnight). */
export function minutesInWindow(start: number, end: number, window: TimedRate): number {
  const shiftStart = start;
  const shiftEnd = shiftStart + durationMinutes(start, end);
  const from = window.from;
  const to = window.to;
  if (from === to) return 0;
  const windowLength = to > from ? to - from : to + MINUTES_PER_DAY - from;
  // A shift is at most 24 h long, so it can touch windows starting yesterday, today or tomorrow.
  let total = 0;
  for (let day = -1; day <= 1; day++) {
    const windowStart = day * MINUTES_PER_DAY + from;
    const windowEnd = windowStart + windowLength;
    const overlap = Math.min(shiftEnd, windowEnd) - Math.max(shiftStart, windowStart);
    if (overlap > 0) total += overlap;
  }
  return total;
}

export interface ShiftPay {
  durationMinutes: number;
  paidMinutes: number;
  /** Worked minutes (before the break) inside the second-rate window. */
  secondRateMinutes: number;
  earnings: bigint;
}

/**
 * Minutes inside the second-rate window are paid at that rate, the rest at the base rate; the unpaid break is
 * taken from both parts proportionally. One exact integer division, rounded half up to the minor unit.
 */
export function pay(start: number, end: number, breakMinutes: number, baseRate: bigint, secondRate: TimedRate | null): ShiftPay {
  const duration = durationMinutes(start, end);
  const paid = paidMinutes(start, end, breakMinutes);
  const secondMinutes = secondRate ? minutesInWindow(start, end, secondRate) : 0;
  const secondRateValue = secondRate?.rate ?? baseRate;
  const weighted = BigInt(duration - secondMinutes) * baseRate + BigInt(secondMinutes) * secondRateValue;
  const numerator = weighted * BigInt(paid);
  const denominator = BigInt(duration * 60);
  const earnings = (numerator + denominator / 2n) / denominator;
  return { durationMinutes: duration, paidMinutes: paid, secondRateMinutes: secondMinutes, earnings };
}

export const shiftPay = (shift: Shift): ShiftPay => pay(shift.start, shift.end, shift.breakMinutes, shift.rate, shift.secondRate);
export const shiftPaidMinutes = (shift: Shift): number => paidMinutes(shift.start, shift.end, shift.breakMinutes);

/** [gross] minus [deductionBp] basis points of it, the deduction rounded half up: 10 000 at 13.71 % → 8 629. */
export function net(gross: bigint, deductionBp: number): bigint {
  if (deductionBp <= 0 || gross <= 0n) return gross;
  const deduction = (gross * BigInt(deductionBp) + 5000n) / 10000n;
  return gross - deduction;
}

// --- Pay settings by month (PaySettings.kt) ------------------------------------------------------------

/** The settings in force in [month]: the latest history entry starting on or before it. */
export function payFor(job: Job, month: YearMonth): PaySettings {
  let found: PaySettings | undefined;
  for (const entry of job.payHistory) if (entry.from === null || entry.from <= month) found = entry;
  return found ?? DEFAULT_PAY;
}

export const hasDeductions = (job: Job): boolean => job.payHistory.some((p) => p.deductionBp > 0);

/** Whether shifts of the job on [date] earn their hourly rate (false: covered by a monthly salary). */
export const paysHourly = (job: Job, date: EpochDay): boolean => payFor(job, monthOf(date)).payType === 'HOURLY';

/** The months a fixed salary is counted in: from the job's first to its last month with any shift or bonus. */
export function isActiveIn(job: Job, month: YearMonth): boolean {
  if (job.firstEntry === null || job.lastEntry === null) return false;
  return month >= monthOf(job.firstEntry) && month <= monthOf(job.lastEntry);
}

// --- Earnings ------------------------------------------------------------------------------------------

function inMode(gross: bigint, job: Job, month: YearMonth, mode: EarningsMode): bigint {
  return mode === 'NET' ? net(gross, payFor(job, month).deductionBp) : gross;
}

/** What a shift earns: its hourly pay, or 0 when its job pays a fixed monthly salary that month. */
export function shiftEarnings(item: ShiftWithJob, mode: EarningsMode = 'GROSS'): bigint {
  const gross = paysHourly(item.job, item.shift.date) ? shiftPay(item.shift).earnings : 0n;
  return inMode(gross, item.job, monthOf(item.shift.date), mode);
}

/** A bonus, gross or after its job's deductions in the bonus's month. */
export function bonusAmount(item: BonusWithJob, mode: EarningsMode = 'GROSS'): bigint {
  return inMode(item.bonus.amount, item.job, monthOf(item.bonus.date), mode);
}

/** The monthly salary [job] earns in [month]: when that month is fixed-salary and the job is active in it; else 0. */
export function salary(job: Job, month: YearMonth, mode: EarningsMode = 'GROSS'): bigint {
  const pay = payFor(job, month);
  const gross = pay.payType === 'FIXED_MONTHLY' && isActiveIn(job, month) ? pay.monthlyAmount : 0n;
  return mode === 'NET' ? net(gross, pay.deductionBp) : gross;
}

/** Distinct values in first-seen order (Kotlin's Set + Set / groupBy keys). */
function ordered<T>(...lists: Iterable<T>[]): T[] {
  const seen = new Set<T>();
  for (const list of lists) for (const item of list) seen.add(item);
  return [...seen];
}

function groupBy<T, K>(items: readonly T[], key: (item: T) => K): Map<K, T[]> {
  const groups = new Map<K, T[]>();
  for (const item of items) {
    const k = key(item);
    const list = groups.get(k);
    if (list) list.push(item);
    else groups.set(k, [item]);
  }
  return groups;
}

const sumNumbers = <T>(items: readonly T[], value: (item: T) => number): number => items.reduce((total, item) => total + value(item), 0);
const sumMoney = <T>(items: readonly T[], value: (item: T) => bigint): bigint => items.reduce((total, item) => total + value(item), 0n);

/** Kotlin's String.compareTo: UTF-16 code units. */
export function compareStrings(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0;
}

/**
 * Totals plus a per-job breakdown (shifts, bonuses and monthly salaries). Money is totalled per currency; jobs
 * are sorted by currency (the biggest total first), then by earnings (highest first), then by name.
 * [salaryMonths] are the whole months the period covers (none for a week).
 */
export function summarize(
  shifts: readonly ShiftWithJob[],
  bonuses: readonly BonusWithJob[] = [],
  jobs: readonly Job[] = [],
  salaryMonths: readonly YearMonth[] = [],
  mode: EarningsMode = 'GROSS',
): MonthSummary {
  const salaries = new Map<string, bigint>();
  for (const job of jobs) {
    const total = sumMoney(salaryMonths, (month) => salary(job, month, mode));
    if (total > 0n) salaries.set(job.id, total);
  }
  if (shifts.length === 0 && bonuses.length === 0 && salaries.size === 0) return MonthSummary.EMPTY;
  const shiftsByJob = groupBy(shifts, (s) => s.job.id);
  const bonusesByJob = groupBy(bonuses, (b) => b.job.id);
  const jobsById = new Map(jobs.map((job) => [job.id, job]));
  const unsorted = ordered(shiftsByJob.keys(), bonusesByJob.keys(), salaries.keys()).map((jobId) => {
    const jobShifts = shiftsByJob.get(jobId) ?? [];
    const jobBonuses = bonusesByJob.get(jobId) ?? [];
    const hourly = jobShifts.filter((s) => paysHourly(s.job, s.shift.date));
    return new JobSummary({
      job: jobsById.get(jobId) ?? jobShifts[0]?.job ?? jobBonuses[0]!.job,
      paidMinutes: sumNumbers(jobShifts, (s) => shiftPaidMinutes(s.shift)),
      shiftEarnings: sumMoney(hourly, (s) => shiftEarnings(s, mode)),
      bonus: sumMoney(jobBonuses, (b) => bonusAmount(b, mode)),
      shiftCount: jobShifts.length,
      salary: salaries.get(jobId) ?? 0n,
      hourlyPaidMinutes: sumNumbers(hourly, (s) => shiftPaidMinutes(s.shift)),
    });
  });
  const shiftEarningsTotal = Amounts.sum(unsorted, (j) => j.currency, (j) => j.shiftEarnings);
  const bonusTotals = Amounts.sum(unsorted, (j) => j.currency, (j) => j.bonus);
  const salaryTotals = Amounts.sum(unsorted.filter((j) => j.salary > 0n), (j) => j.currency, (j) => j.salary);
  const currencyOrder = shiftEarningsTotal.plus(bonusTotals).plus(salaryTotals).sortedByValue().map(([code]) => code);
  const perJob = sortJobs(unsorted, currencyOrder);
  return new MonthSummary({
    totalPaidMinutes: sumNumbers(perJob, (j) => j.paidMinutes),
    shiftEarnings: shiftEarningsTotal,
    bonuses: bonusTotals,
    shiftCount: shifts.length,
    bonusCount: bonuses.length,
    perJob,
    salaries: salaryTotals,
    fixedPayShiftCount: shifts.filter((s) => !paysHourly(s.job, s.shift.date)).length,
  });
}

/** By currency (in [currencyOrder]), then earnings (highest first), then name. Stable, like Kotlin's sortedWith. */
export function sortJobs(jobs: readonly JobSummary[], currencyOrder: readonly string[]): JobSummary[] {
  return [...jobs].sort((a, b) => {
    const byCurrency = currencyOrder.indexOf(a.currency) - currencyOrder.indexOf(b.currency);
    if (byCurrency !== 0) return byCurrency;
    if (a.earnings !== b.earnings) return b.earnings > a.earnings ? 1 : -1;
    return compareStrings(a.job.name, b.job.name);
  });
}

/** Earnings of a set of shifts and bonuses (e.g. one day), per currency; no monthly salaries. */
export function earnings(shifts: readonly ShiftWithJob[], bonuses: readonly BonusWithJob[], mode: EarningsMode = 'GROSS'): Amounts {
  return Amounts.sum(shifts, (s) => s.job.currency, (s) => shiftEarnings(s, mode)).plus(
    Amounts.sum(bonuses, (b) => b.job.currency, (b) => bonusAmount(b, mode)),
  );
}

/** The whole months between [from] and [to] (inclusive): where monthly salaries are counted. */
export function wholeMonths(from: EpochDay, to: EpochDay): YearMonth[] {
  const months: YearMonth[] = [];
  for (let month = monthOf(from); month <= monthOf(to); month++) {
    if (firstDay(month) >= from && lastDay(month) <= to) months.push(month);
  }
  return months;
}
