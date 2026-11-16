import type { EpochDay, YearMonth } from './dates';

/*
 * The app's domain model (domain/model/*.kt). Money is bigint minor units of the job's currency (grosze for PLN,
 * cents, yen, fils), never a floating-point number. Ids are the records' sync ids (their Firestore document ids).
 */

export type EarningsMode = 'GROSS' | 'NET';
export type PayType = 'HOURLY' | 'FIXED_MONTHLY';

/** A rate that applies every day between [from] and [to] (minutes of the day); to <= from crosses midnight. */
export interface TimedRate {
  rate: bigint;
  from: number;
  to: number;
}

/** A job's pay settings from month [from] on (null: from the very beginning), until the next entry. */
export interface PaySettings {
  from: YearMonth | null;
  payType: PayType;
  monthlyAmount: bigint;
  /** Deductions in basis points (13.71 % = 1371). */
  deductionBp: number;
}

export const DEFAULT_PAY: PaySettings = { from: null, payType: 'HOURLY', monthlyAmount: 0n, deductionBp: 0 };

export interface Job {
  id: string;
  name: string;
  hourlyRate: bigint;
  /** ARGB as a signed 32-bit int, as the app stores it. */
  color: number;
  secondRate: TimedRate | null;
  currency: string;
  /** Oldest first. */
  payHistory: PaySettings[];
  /** First and last day with a shift or bonus (all of the job's, not only the period shown); bound a monthly salary. */
  firstEntry: EpochDay | null;
  lastEntry: EpochDay | null;
}

/** A shift: [date] is the day it starts; it ends the next day when [end] is not after [start]. */
export interface Shift {
  id: string;
  jobId: string;
  date: EpochDay;
  /** Minutes of the day. */
  start: number;
  end: number;
  breakMinutes: number;
  /** The rates saved with the shift (a snapshot of the job's when it was saved). */
  rate: bigint;
  secondRate: TimedRate | null;
  note: string | null;
}

export interface Bonus {
  id: string;
  jobId: string;
  /** For a whole-month bonus: the 1st of its month. */
  date: EpochDay;
  amount: bigint;
  note: string | null;
  wholeMonth: boolean;
}

/** Salary that actually arrived, for the month it was earned for. */
export interface Payout {
  id: string;
  jobId: string;
  month: YearMonth;
  amount: bigint;
  note: string | null;
  receivedOn: EpochDay | null;
}

export interface ShiftWithJob {
  shift: Shift;
  job: Job;
}

export interface BonusWithJob {
  bonus: Bonus;
  job: Job;
}

export interface PayoutWithJob {
  payout: Payout;
  job: Job;
}
