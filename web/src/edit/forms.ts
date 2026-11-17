import { durationMinutes, isOvernight, pay, paysHourly, net, payFor, type ShiftPay } from '../domain/calc';
import { fractionDigits } from '../domain/currencies';
import { firstDay, fromMonthKey, monthKey, monthOf, ymYear, type EpochDay, type YearMonth } from '../domain/dates';
import type { EarningsMode, Job, TimedRate } from '../domain/model';
import type { BonusRecord, PayoutRecord, ShiftRecord } from '../data/documents';
import type { BonusValues, PayoutValues, ShiftValues } from './documents';
import { AMOUNT_WHOLE_DIGITS, MAX_TEXT, noteValue, parseMinor, toInput } from './money-input';

/*
 * The editors' forms and their rules, as the app's view models have them (ui/shift/EditShiftViewModel.kt,
 * ui/bonus/EditBonusViewModel.kt, ui/summary/PayoutEditorViewModel.kt). Plain data, so the UI and the tests share
 * them.
 */

/** Unpaid break presets (EditShiftScreen.kt BreakPresets). */
export const BREAK_PRESETS = [0, 15, 30, 45, 60] as const;

// --- Shift ------------------------------------------------------------------------------------------------

export interface ShiftForm {
  isNew: boolean;
  jobId: string | null;
  date: EpochDay;
  start: number;
  end: number;
  /** As typed: digits only, up to four. */
  breakText: string;
  note: string;
  /** The rates saved with the shift (a snapshot of its job's when it was made). */
  rate: bigint;
  secondRate: TimedRate | null;
  /** Kept as it is (a shift imported from a calendar file has one). */
  externalUid: string | null;
}

/** A new shift on [date]: the first job (the list is in the app's order), 08:00–16:00, no break. */
export function newShiftForm(jobs: readonly Job[], date: EpochDay): ShiftForm {
  const job = jobs[0] ?? null;
  return {
    isNew: true, jobId: job?.id ?? null, date, start: 8 * 60, end: 16 * 60, breakText: '0', note: '',
    rate: job?.hourlyRate ?? 0n, secondRate: job?.secondRate ?? null, externalUid: null,
  };
}

export function editShiftForm(record: ShiftRecord): ShiftForm {
  return {
    isNew: false, jobId: record.jobId, date: record.date, start: record.start, end: record.end,
    breakText: String(record.breakMinutes), note: record.note ?? '', rate: record.rate, secondRate: record.secondRate,
    externalUid: record.externalUid,
  };
}

/** Picking a different job always takes that job's current rates; the same job keeps the form's. */
export function selectShiftJob(form: ShiftForm, job: Job): ShiftForm {
  return form.jobId === job.id ? form : { ...form, jobId: job.id, rate: job.hourlyRate, secondRate: job.secondRate };
}

export const setBreakText = (form: ShiftForm, text: string): ShiftForm => ({ ...form, breakText: text.replace(/[^0-9]/g, '').slice(0, 4) });

export function breakValue(form: ShiftForm): number | null {
  const text = form.breakText.trim() === '' ? '0' : form.breakText.trim();
  if (!/^[0-9]+$/.test(text)) return null;
  const value = Number(text);
  return value >= 0 && value <= 1440 ? value : null;
}

const sameRate = (a: TimedRate | null, b: TimedRate | null) =>
  a === b || (a !== null && b !== null && a.rate === b.rate && a.from === b.from && a.to === b.to);

export interface ShiftCheck {
  job: Job | null;
  jobMissing: boolean;
  breakInvalid: boolean;
  breakTooLong: boolean;
  noteTooLong: boolean;
  /** The job is paid a monthly salary in the shift's month: the shift only logs hours. */
  fixedPay: boolean;
  overnight: boolean;
  duration: number;
  pay: ShiftPay;
  /** An existing shift's rates differ from its job's current ones (a hint only: nothing is applied). */
  rateOutdated: boolean;
  canSave: boolean;
}

export function checkShift(form: ShiftForm, jobs: readonly Job[]): ShiftCheck {
  const job = jobs.find((j) => j.id === form.jobId) ?? null;
  const breakMinutes = breakValue(form);
  const duration = durationMinutes(form.start, form.end);
  const fixedPay = job !== null && !paysHourly(job, form.date);
  const breakTooLong = (breakMinutes ?? 0) >= duration;
  const noteTooLong = form.note.length > MAX_TEXT;
  const result: ShiftCheck = {
    job,
    jobMissing: job === null,
    breakInvalid: breakMinutes === null,
    breakTooLong,
    noteTooLong,
    fixedPay,
    overnight: isOvernight(form.start, form.end),
    duration,
    pay: pay(form.start, form.end, breakMinutes ?? 0, form.rate, form.secondRate),
    rateOutdated: !form.isNew && job !== null && !fixedPay && (job.hourlyRate !== form.rate || !sameRate(job.secondRate, form.secondRate)),
    canSave: false,
  };
  result.canSave = !result.jobMissing && !result.breakInvalid && !breakTooLong && !noteTooLong;
  return result;
}

/** The live Earnings card: gross, or after the job's deductions in the shift's month. */
export function shiftPreviewEarnings(form: ShiftForm, check: ShiftCheck, mode: EarningsMode): bigint {
  const gross = check.pay.earnings;
  if (mode !== 'NET' || !check.job) return gross;
  return net(gross, payFor(check.job, monthOf(form.date)).deductionBp);
}

export function shiftValues(form: ShiftForm, jobs: readonly Job[]): ShiftValues | null {
  const check = checkShift(form, jobs);
  if (!check.canSave || !check.job) return null;
  return {
    jobId: check.job.id, date: form.date, start: form.start, end: form.end, breakMinutes: breakValue(form) ?? 0,
    rate: form.rate, note: noteValue(form.note), externalUid: form.externalUid, secondRate: form.secondRate,
  };
}

// --- Bonus ------------------------------------------------------------------------------------------------

export interface BonusForm {
  isNew: boolean;
  jobId: string | null;
  /** Kept when switching to "whole month" and back, so nothing is lost. */
  date: EpochDay;
  wholeMonth: boolean;
  amountText: string;
  note: string;
}

export function newBonusForm(jobs: readonly Job[], date: EpochDay, wholeMonth = false): BonusForm {
  return { isNew: true, jobId: jobs[0]?.id ?? null, date, wholeMonth, amountText: '', note: '' };
}

export function editBonusForm(record: BonusRecord, job: Job | null, separator = ','): BonusForm {
  const digits = fractionDigits(job?.currency ?? 'PLN');
  return {
    isNew: false, jobId: record.jobId, date: record.date, wholeMonth: record.wholeMonth,
    amountText: toInput(record.amount, digits, separator), note: record.note ?? '',
  };
}

/** The month of a bonus for the whole month: its date becomes the 1st (EditBonusViewModel.setMonth). */
export const setBonusMonth = (form: BonusForm, month: YearMonth): BonusForm => ({ ...form, date: firstDay(month) });

export interface AmountCheck {
  job: Job | null;
  jobMissing: boolean;
  amount: bigint | null;
  noteTooLong: boolean;
  canSave: boolean;
}

function amountOf(text: string, job: Job | null): bigint | null {
  const value = parseMinor(text, fractionDigits(job?.currency ?? 'PLN'), AMOUNT_WHOLE_DIGITS);
  return value !== null && value > 0n ? value : null;
}

export function checkBonus(form: BonusForm, jobs: readonly Job[]): AmountCheck {
  const job = jobs.find((j) => j.id === form.jobId) ?? null;
  const amount = amountOf(form.amountText, job);
  const noteTooLong = form.note.length > MAX_TEXT;
  return { job, jobMissing: job === null, amount, noteTooLong, canSave: job !== null && amount !== null && !noteTooLong };
}

export function bonusValues(form: BonusForm, jobs: readonly Job[]): BonusValues | null {
  const check = checkBonus(form, jobs);
  if (!check.canSave || !check.job || check.amount === null) return null;
  return {
    jobId: check.job.id,
    // A bonus for the whole month has no day of its own: it is the 1st of its month.
    date: form.wholeMonth ? firstDay(monthOf(form.date)) : form.date,
    amount: check.amount,
    note: noteValue(form.note),
    wholeMonth: form.wholeMonth,
  };
}

// --- Payout (received pay) --------------------------------------------------------------------------------

export const PAYOUT_MIN_YEAR = 1000;
export const PAYOUT_MAX_YEAR = 9999;

export interface PayoutForm {
  isNew: boolean;
  /** The job is fixed: a payout belongs to the job whose row it was opened from. */
  jobId: string;
  /** The month the pay was earned for (not when it arrived). */
  month: YearMonth;
  amountText: string;
  receivedOn: EpochDay | null;
  note: string;
}

export function newPayoutForm(job: Job, month: YearMonth): PayoutForm {
  return { isNew: true, jobId: job.id, month, amountText: '', receivedOn: null, note: '' };
}

export function editPayoutForm(record: PayoutRecord, job: Job | null, separator = ','): PayoutForm {
  return {
    isNew: false, jobId: record.jobId, month: fromMonthKey(record.month),
    amountText: toInput(record.amount, fractionDigits(job?.currency ?? 'PLN'), separator), receivedOn: record.receivedOn,
    note: record.note ?? '',
  };
}

/** The month it was earned for; years stay within what the cloud rules accept (PayoutEditorViewModel.setMonth). */
export function setPayoutMonth(form: PayoutForm, month: YearMonth): PayoutForm {
  const year = ymYear(month);
  return year < PAYOUT_MIN_YEAR || year > PAYOUT_MAX_YEAR ? form : { ...form, month };
}

export function checkPayout(form: PayoutForm, jobs: readonly Job[]): AmountCheck {
  const job = jobs.find((j) => j.id === form.jobId) ?? null;
  const amount = amountOf(form.amountText, job);
  const noteTooLong = form.note.length > MAX_TEXT;
  return { job, jobMissing: job === null, amount, noteTooLong, canSave: job !== null && amount !== null && !noteTooLong };
}

export function payoutValues(form: PayoutForm, jobs: readonly Job[]): PayoutValues | null {
  const check = checkPayout(form, jobs);
  if (!check.canSave || !check.job || check.amount === null) return null;
  return { jobId: check.job.id, month: monthKey(form.month), amount: check.amount, note: noteValue(form.note), receivedOn: form.receivedOn };
}

// --- Unsaved changes --------------------------------------------------------------------------------------

/** Whether a form differs from how it was opened (the unsaved-changes guard). */
export function isDirty<T extends object>(initial: T, current: T): boolean {
  const plain = (value: unknown) => JSON.stringify(value, (_k, v) => (typeof v === 'bigint' ? `${v}n` : v));
  return plain(initial) !== plain(current);
}
