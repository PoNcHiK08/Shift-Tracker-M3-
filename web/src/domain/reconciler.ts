import { Amounts } from './amounts';
import { monthOf, ymYear, type YearMonth } from './dates';
import { compareStrings, sortJobs, summarize } from './calc';
import type { BonusWithJob, EarningsMode, Job, Payout, PayoutWithJob, ShiftWithJob } from './model';
import { JobSummary, MonthSummary } from './summary';

/*
 * Received pay (domain/calc/PayReconciler.kt): a job's payouts for a month REPLACE that job-month's calculated money
 * (shifts, bonuses, salary) in the month and year totals; they are never added on top. Weeks stay calculated.
 */

/** One job's received pay for one month, against the calculated pay after deductions (net, always). */
export class PayReconciliation {
  constructor(
    readonly job: Job,
    readonly month: YearMonth,
    readonly calculatedNet: bigint,
    readonly payouts: Payout[],
  ) {}

  get currency(): string {
    return this.job.currency;
  }

  get received(): bigint {
    return this.payouts.reduce((t, p) => t + p.amount, 0n);
  }

  /** Received minus calculated; null while nothing is entered. */
  get difference(): bigint | null {
    return this.payouts.length === 0 ? null : this.received - this.calculatedNet;
  }
}

/** One job's received pay over the months of a year that have any. */
export class JobYearReconciliation {
  constructor(readonly job: Job, readonly months: PayReconciliation[]) {}

  get currency(): string {
    return this.job.currency;
  }

  get received(): bigint {
    return this.months.reduce((t, m) => t + m.received, 0n);
  }

  get calculatedNet(): bigint {
    return this.months.reduce((t, m) => t + m.calculatedNet, 0n);
  }

  get difference(): bigint {
    return this.received - this.calculatedNet;
  }
}

/** The rule for one job-month: with payouts, their sum replaces the calculated pay entirely. */
export function actualMonthPay(calculated: bigint, payouts: readonly Payout[]): { amount: bigint; received: boolean } {
  return payouts.length === 0
    ? { amount: calculated, received: false }
    : { amount: payouts.reduce((t, p) => t + p.amount, 0n), received: true };
}

function associateBy<T>(items: readonly T[], key: (item: T) => string): Map<string, T> {
  const map = new Map<string, T>();
  for (const item of items) map.set(key(item), item);
  return map;
}

/**
 * Totals for a period in which received pay counts as the real money: every job-month of [months] (the period's
 * whole months; none for a week) with payouts counts their sum instead of its calculated money.
 */
export function actualSummary(
  shifts: readonly ShiftWithJob[],
  bonuses: readonly BonusWithJob[],
  jobs: readonly Job[],
  months: readonly YearMonth[],
  mode: EarningsMode,
  payouts: readonly PayoutWithJob[],
): MonthSummary {
  const calculated = summarize(shifts, bonuses, jobs, months, mode);
  const relevant = payouts.filter((p) => months.includes(p.payout.month));
  if (relevant.length === 0) return calculated;

  const jobsById = associateBy([...relevant.map((p) => p.job), ...jobs], (j) => j.id);
  const groups = new Map<string, { jobId: string; month: YearMonth; rows: PayoutWithJob[] }>();
  for (const row of relevant) {
    const key = `${row.job.id}|${row.payout.month}`;
    const group = groups.get(key);
    if (group) group.rows.push(row);
    else groups.set(key, { jobId: row.job.id, month: row.payout.month, rows: [row] });
  }
  const swaps = new Map<string, JobSummary[]>();
  for (const { jobId, month, rows } of groups.values()) {
    const job = jobsById.get(jobId)!;
    const part = summarize(
      shifts.filter((s) => s.job.id === jobId && monthOf(s.shift.date) === month),
      bonuses.filter((b) => b.job.id === jobId && monthOf(b.bonus.date) === month),
      [job],
      [month],
      mode,
    ).perJob[0];
    const pay = actualMonthPay(part?.calculated ?? 0n, rows.map((r) => r.payout));
    const swap = new JobSummary({
      job, paidMinutes: 0, shiftEarnings: 0n, bonus: 0n, shiftCount: 0,
      received: pay.amount,
      replaced: part?.calculated ?? 0n,
      replacedBonus: part?.bonus ?? 0n,
      replacedSalary: part?.salary ?? 0n,
      receivedMonths: 1,
    });
    const list = swaps.get(job.id);
    if (list) list.push(swap);
    else swaps.set(job.id, [swap]);
  }

  const byJob = associateBy(calculated.perJob, (j) => j.job.id);
  const ids = [...new Set([...byJob.keys(), ...swaps.keys()])];
  const unsorted = ids.map((jobId) => {
    const base = byJob.get(jobId) ?? new JobSummary({ job: jobsById.get(jobId)!, paidMinutes: 0, shiftEarnings: 0n, bonus: 0n, shiftCount: 0 });
    const jobSwaps = swaps.get(jobId) ?? [];
    const total = (pick: (j: JobSummary) => bigint) => jobSwaps.reduce((t, j) => t + pick(j), 0n);
    return base.copy({
      received: total((j) => j.received),
      replaced: total((j) => j.replaced),
      replacedBonus: total((j) => j.replacedBonus),
      replacedSalary: total((j) => j.replacedSalary),
      receivedMonths: jobSwaps.length,
    });
  });
  const swapped = unsorted.filter((j) => j.receivedMonths > 0);
  const summary = calculated.copy({
    received: Amounts.sum(swapped, (j) => j.currency, (j) => j.received),
    replaced: Amounts.sum(swapped, (j) => j.currency, (j) => j.replaced),
    replacedBonuses: Amounts.sum(swapped, (j) => j.currency, (j) => j.replacedBonus),
    replacedSalaries: Amounts.sum(swapped, (j) => j.currency, (j) => j.replacedSalary),
  });
  const currencyOrder = summary.totalEarnings.sortedByValue().map(([code]) => code);
  return summary.copy({ perJob: sortJobs(unsorted, currencyOrder) });
}

/** Per job of [month]: the calculated net pay and the payouts for the month (matched by the month earned for). */
export function forMonth(
  month: YearMonth,
  jobs: readonly Job[],
  shifts: readonly ShiftWithJob[],
  bonuses: readonly BonusWithJob[],
  payouts: readonly PayoutWithJob[],
): PayReconciliation[] {
  const summary = summarize(
    shifts.filter((s) => monthOf(s.shift.date) === month),
    bonuses.filter((b) => monthOf(b.bonus.date) === month),
    jobs,
    [month],
    'NET',
  );
  const calculated = new Map(summary.perJob.map((j) => [j.job.id, j.earnings]));
  const received = new Map<string, Payout[]>();
  for (const row of payouts) {
    if (row.payout.month !== month) continue;
    const list = received.get(row.job.id);
    if (list) list.push(row.payout);
    else received.set(row.job.id, [row.payout]);
  }
  const knownJobs = associateBy([...payouts.map((p) => p.job), ...jobs, ...summary.perJob.map((j) => j.job)], (j) => j.id);
  const calculatedOrder = summary.perJob.map((j) => j.job.id);
  const payoutOnly = [...received.keys()]
    .filter((id) => !calculated.has(id))
    .sort((a, b) => compareStrings(knownJobs.get(a)!.name.toLowerCase(), knownJobs.get(b)!.name.toLowerCase()));
  return [...calculatedOrder, ...payoutOnly].map(
    (id) => new PayReconciliation(knownJobs.get(id)!, month, calculated.get(id) ?? 0n, received.get(id) ?? []),
  );
}

/** Per job with payouts in [year]: the months that have payouts, each against its calculated net pay. */
export function forYear(
  year: number,
  jobs: readonly Job[],
  shifts: readonly ShiftWithJob[],
  bonuses: readonly BonusWithJob[],
  payouts: readonly PayoutWithJob[],
): JobYearReconciliation[] {
  const ofYear = payouts.filter((p) => ymYear(p.payout.month) === year);
  const months = [...new Set(ofYear.map((p) => p.payout.month))].sort((a, b) => a - b);
  const rows = months.flatMap((month) => forMonth(month, jobs, shifts, bonuses, ofYear).filter((r) => r.payouts.length > 0));
  const byJob = new Map<string, PayReconciliation[]>();
  for (const row of rows) {
    const list = byJob.get(row.job.id);
    if (list) list.push(row);
    else byJob.set(row.job.id, [row]);
  }
  return [...byJob.values()]
    .map((list) => new JobYearReconciliation(list[0]!.job, list))
    .sort((a, b) => compareStrings(a.currency, b.currency) || compareStrings(a.job.name.toLowerCase(), b.job.name.toLowerCase()));
}
