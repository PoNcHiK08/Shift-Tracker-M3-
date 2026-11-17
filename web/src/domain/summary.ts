import { Amounts } from './amounts';
import type { Job } from './model';

/** How much of a figure is received pay rather than calculated. */
export type ReceivedPart = 'NONE' | 'PART' | 'ALL';

export interface JobSummaryFields {
  job: Job;
  paidMinutes: number;
  shiftEarnings: bigint;
  bonus: bigint;
  shiftCount: number;
  salary?: bigint;
  hourlyPaidMinutes?: number;
  received?: bigint;
  replaced?: bigint;
  replacedBonus?: bigint;
  replacedSalary?: bigint;
  receivedMonths?: number;
}

/** One job's part of a period (domain/model/MonthSummary.kt, JobSummary): one currency, plain minor units. */
export class JobSummary {
  readonly job: Job;
  readonly paidMinutes: number;
  readonly shiftEarnings: bigint;
  readonly bonus: bigint;
  readonly shiftCount: number;
  /** Monthly salary counted in the period. */
  readonly salary: bigint;
  /** Worked time paid by the hour (excludes shifts covered by a monthly salary). */
  readonly hourlyPaidMinutes: number;
  /** Payouts of this job's months with received pay (they replace [replaced]). */
  readonly received: bigint;
  readonly replaced: bigint;
  readonly replacedBonus: bigint;
  readonly replacedSalary: bigint;
  readonly receivedMonths: number;

  constructor(fields: JobSummaryFields) {
    this.job = fields.job;
    this.paidMinutes = fields.paidMinutes;
    this.shiftEarnings = fields.shiftEarnings;
    this.bonus = fields.bonus;
    this.shiftCount = fields.shiftCount;
    this.salary = fields.salary ?? 0n;
    this.hourlyPaidMinutes = fields.hourlyPaidMinutes ?? fields.paidMinutes;
    this.received = fields.received ?? 0n;
    this.replaced = fields.replaced ?? 0n;
    this.replacedBonus = fields.replacedBonus ?? 0n;
    this.replacedSalary = fields.replacedSalary ?? 0n;
    this.receivedMonths = fields.receivedMonths ?? 0;
  }

  copy(changes: Partial<JobSummaryFields>): JobSummary {
    return new JobSummary({ ...this.fields(), ...changes });
  }

  private fields(): JobSummaryFields {
    return {
      job: this.job, paidMinutes: this.paidMinutes, shiftEarnings: this.shiftEarnings, bonus: this.bonus,
      shiftCount: this.shiftCount, salary: this.salary, hourlyPaidMinutes: this.hourlyPaidMinutes,
      received: this.received, replaced: this.replaced, replacedBonus: this.replacedBonus,
      replacedSalary: this.replacedSalary, receivedMonths: this.receivedMonths,
    };
  }

  get currency(): string {
    return this.job.currency;
  }

  get calculated(): bigint {
    return this.shiftEarnings + this.bonus + this.salary;
  }

  /** The job's money in the period: calculated, with months that have received pay counting what arrived. */
  get earnings(): bigint {
    return this.calculated - this.replaced + this.received;
  }

  get countedBonus(): bigint {
    return this.bonus - this.replacedBonus;
  }

  get countedSalary(): bigint {
    return this.salary - this.replacedSalary;
  }

  get receivedPart(): ReceivedPart {
    if (this.receivedMonths === 0) return 'NONE';
    return this.calculated - this.replaced === 0n ? 'ALL' : 'PART';
  }
}

export interface MonthSummaryFields {
  totalPaidMinutes: number;
  shiftEarnings: Amounts;
  bonuses: Amounts;
  shiftCount: number;
  bonusCount: number;
  perJob: JobSummary[];
  salaries?: Amounts;
  fixedPayShiftCount?: number;
  received?: Amounts;
  replaced?: Amounts;
  replacedBonuses?: Amounts;
  replacedSalaries?: Amounts;
}

/**
 * Totals for any period (week, month, year), per currency. When a job has payouts for a whole month of the period,
 * that job-month's calculated money ([replaced]) is swapped for what arrived ([received]); hours, counts, shift
 * earnings and the average rate stay calculated.
 */
export class MonthSummary {
  static readonly EMPTY = new MonthSummary({
    totalPaidMinutes: 0, shiftEarnings: Amounts.EMPTY, bonuses: Amounts.EMPTY, shiftCount: 0, bonusCount: 0, perJob: [],
  });

  readonly totalPaidMinutes: number;
  readonly shiftEarnings: Amounts;
  readonly bonuses: Amounts;
  readonly shiftCount: number;
  readonly bonusCount: number;
  readonly perJob: JobSummary[];
  readonly salaries: Amounts;
  readonly fixedPayShiftCount: number;
  readonly received: Amounts;
  readonly replaced: Amounts;
  readonly replacedBonuses: Amounts;
  readonly replacedSalaries: Amounts;

  constructor(f: MonthSummaryFields) {
    this.totalPaidMinutes = f.totalPaidMinutes;
    this.shiftEarnings = f.shiftEarnings;
    this.bonuses = f.bonuses;
    this.shiftCount = f.shiftCount;
    this.bonusCount = f.bonusCount;
    this.perJob = f.perJob;
    this.salaries = f.salaries ?? Amounts.EMPTY;
    this.fixedPayShiftCount = f.fixedPayShiftCount ?? 0;
    this.received = f.received ?? Amounts.EMPTY;
    this.replaced = f.replaced ?? Amounts.EMPTY;
    this.replacedBonuses = f.replacedBonuses ?? Amounts.EMPTY;
    this.replacedSalaries = f.replacedSalaries ?? Amounts.EMPTY;
  }

  copy(changes: Partial<MonthSummaryFields>): MonthSummary {
    return new MonthSummary({
      totalPaidMinutes: this.totalPaidMinutes, shiftEarnings: this.shiftEarnings, bonuses: this.bonuses,
      shiftCount: this.shiftCount, bonusCount: this.bonusCount, perJob: this.perJob, salaries: this.salaries,
      fixedPayShiftCount: this.fixedPayShiftCount, received: this.received, replaced: this.replaced,
      replacedBonuses: this.replacedBonuses, replacedSalaries: this.replacedSalaries,
      ...changes,
    });
  }

  /** What the app calculates for the period, before any received pay replaces part of it. */
  get calculatedEarnings(): Amounts {
    return this.shiftEarnings.plus(this.bonuses).plus(this.salaries);
  }

  /** The period's money: calculated, except job-months with received pay, which count what arrived. */
  get totalEarnings(): Amounts {
    return this.calculatedEarnings.minus(this.replaced).plus(this.received);
  }

  get countedBonuses(): Amounts {
    return this.bonuses.minus(this.replacedBonuses);
  }

  get countedSalaries(): Amounts {
    return this.salaries.minus(this.replacedSalaries);
  }

  get receivedPart(): ReceivedPart {
    if (this.received.isEmpty) return 'NONE';
    const left = this.calculatedEarnings.minus(this.replaced);
    return [...left.byCurrency.values()].every((v) => v === 0n) ? 'ALL' : 'PART';
  }

  /** Currencies with any entry in the period, largest total first. */
  get currencies(): string[] {
    return this.totalEarnings.sortedByValue().map(([code]) => code);
  }

  /** Average hourly pay in [currency] for hourly-paid time; bonuses and salaries are left out. */
  averageRate(currency: string): bigint {
    const minutes = this.perJob.filter((j) => j.currency === currency).reduce((t, j) => t + j.hourlyPaidMinutes, 0);
    return minutes > 0 ? (this.shiftEarnings.get(currency) * 60n) / BigInt(minutes) : 0n;
  }

  get isEmpty(): boolean {
    return this.shiftCount === 0 && this.bonusCount === 0 && this.salaries.isEmpty && this.received.isEmpty;
  }
}
