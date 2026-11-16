import { durationMinutes } from '../domain/calc';
import { epochDay, fromMonthKey, type EpochDay } from '../domain/dates';
import { AMOUNT_WHOLE_DIGITS, fractionDigits, isValidCurrency, maxMinor } from '../domain/currencies';
import type { BonusWithJob, Job, PaySettings, PayType, PayoutWithJob, ShiftWithJob, TimedRate } from '../domain/model';
import type { DataView } from '../domain/screens';
import type { CloudDocuments, CollectionName, RawDocument } from './documents-types';

/*
 * Reading a user's cloud documents as the Android app reads them, so the web shows what the phone shows:
 *
 * 1. Each document is read field by field like FirestoreDocuments.kt's readRecord: a field missing or of the wrong
 *    type makes the whole document unreadable.
 * 2. It is used only if the app could use it (SyncMapping.kt's isUsable: a known layout version, a lowercase UUID,
 *    values within the editors' limits). Anything else is skipped and counted, never shown.
 * 3. Records then go where the phone's sync engine puts them: a shift, bonus or payout whose job doesn't exist is
 *    skipped; deleted records (tombstones, `deleted: true`) are kept only to apply ParentChildRule and are never shown.
 *
 * Nothing here writes anywhere.
 */

/** Layout version of the documents this app understands (RemoteModel.kt's DOC_SCHEMA). */
export const DOC_SCHEMA = 1;

const DAY_MINUTES = 1440;
const MAX_TEXT = 20_000;
/** Amounts of shifts and bonuses are checked against the currency with the most decimals: their job may be elsewhere. */
const MAX_FRACTION_DIGITS = 3;
const EPOCH_DAYS_MIN = -719_162;
const EPOCH_DAYS_MAX = 2_932_896;

interface Common {
  id: string;
  updatedAt: number;
  deleted: boolean;
  schema: number;
}

export interface JobRecord extends Common {
  kind: 'job';
  name: string;
  hourlyRate: bigint;
  color: number;
  secondRate: TimedRate | null;
  currency: string;
  payHistory: { fromMonth: number; payType: string; monthlyAmount: bigint; deductionBp: number }[];
}

export interface ShiftRecord extends Common {
  kind: 'shift';
  jobId: string;
  date: number;
  start: number;
  end: number;
  breakMinutes: number;
  rate: bigint;
  note: string | null;
  externalUid: string | null;
  secondRate: TimedRate | null;
}

export interface BonusRecord extends Common {
  kind: 'bonus';
  jobId: string;
  date: number;
  amount: bigint;
  note: string | null;
  wholeMonth: boolean;
}

export interface PayoutRecord extends Common {
  kind: 'payout';
  jobId: string;
  month: number;
  amount: bigint;
  note: string | null;
  receivedOn: number | null;
}

export type CloudRecord = JobRecord | ShiftRecord | BonusRecord | PayoutRecord;

class Unreadable extends Error {}

/** Firestore hands whole numbers to the web as numbers; anything else (a fraction, text) is not the app's. */
function long(data: RawDocument, name: string): number {
  const value = data[name];
  if (typeof value === 'number' && Number.isInteger(value)) return value;
  if (typeof value === 'bigint') return Number(value);
  throw new Unreadable(name);
}

function int(data: RawDocument, name: string): number {
  const value = long(data, name);
  if (value < -2147483648 || value > 2147483647) throw new Unreadable(name);
  return value;
}

function money(data: RawDocument, name: string): bigint {
  const value = data[name];
  if (typeof value === 'bigint') return value;
  return BigInt(long(data, name));
}

function bool(data: RawDocument, name: string): boolean {
  const value = data[name];
  if (typeof value !== 'boolean') throw new Unreadable(name);
  return value;
}

function string(data: RawDocument, name: string): string {
  const value = data[name];
  if (typeof value !== 'string') throw new Unreadable(name);
  return value;
}

/** Present and null, or a string. A missing key is unreadable: the app always writes it. */
function optionalString(data: RawDocument, name: string): string | null {
  if (!(name in data)) throw new Unreadable(name);
  const value = data[name];
  if (value === null || value === undefined) return null;
  if (typeof value !== 'string') throw new Unreadable(name);
  return value;
}

function optionalLong(data: RawDocument, name: string): number | null {
  if (!(name in data)) throw new Unreadable(name);
  return data[name] === null || data[name] === undefined ? null : long(data, name);
}

function asMap(value: unknown, name: string): RawDocument {
  if (value !== null && typeof value === 'object' && !Array.isArray(value)) return value as RawDocument;
  throw new Unreadable(name);
}

function timedRate(data: RawDocument): TimedRate | null {
  const value = data['secondRate'];
  if (value === null || value === undefined) return null;
  const rate = asMap(value, 'secondRate');
  return { rate: money(rate, 'rateGrosze'), from: int(rate, 'fromMinute'), to: int(rate, 'toMinute') };
}

function common(id: string, data: RawDocument): Common {
  return { id, updatedAt: long(data, 'updatedAt'), deleted: bool(data, 'deleted'), schema: int(data, 'v') };
}

/** A document of [collection], or null when it can't be read (the app gives it an unreadable layout and skips it). */
export function readRecord(collection: CollectionName, id: string, data: RawDocument): CloudRecord | null {
  try {
    switch (collection) {
      case 'jobs': {
        const history = data['payHistory'];
        if (!Array.isArray(history)) throw new Unreadable('payHistory');
        return {
          kind: 'job',
          ...common(id, data),
          name: string(data, 'name'),
          hourlyRate: money(data, 'hourlyRateGrosze'),
          color: int(data, 'color'),
          secondRate: timedRate(data),
          currency: string(data, 'currency'),
          payHistory: history.map((entry) => {
            const pay = asMap(entry, 'payHistory');
            return {
              fromMonth: int(pay, 'fromMonth'),
              payType: string(pay, 'payType'),
              monthlyAmount: money(pay, 'monthlyAmountMinor'),
              deductionBp: int(pay, 'deductionBp'),
            };
          }),
        };
      }
      case 'shifts':
        return {
          kind: 'shift',
          ...common(id, data),
          jobId: string(data, 'jobId'),
          date: long(data, 'dateEpochDay'),
          start: int(data, 'startMinute'),
          end: int(data, 'endMinute'),
          breakMinutes: int(data, 'unpaidBreakMinutes'),
          rate: money(data, 'hourlyRateGrosze'),
          note: optionalString(data, 'note'),
          externalUid: optionalString(data, 'externalUid'),
          secondRate: timedRate(data),
        };
      case 'bonuses':
        return {
          kind: 'bonus',
          ...common(id, data),
          jobId: string(data, 'jobId'),
          date: long(data, 'dateEpochDay'),
          amount: money(data, 'amountGrosze'),
          note: optionalString(data, 'note'),
          // Absent in a document written by an app version without month bonuses: a bonus on its day.
          wholeMonth: 'wholeMonth' in data ? bool(data, 'wholeMonth') : false,
        };
      case 'payouts':
        return {
          kind: 'payout',
          ...common(id, data),
          jobId: string(data, 'jobId'),
          month: int(data, 'month'),
          amount: money(data, 'amountMinor'),
          note: optionalString(data, 'note'),
          receivedOn: optionalLong(data, 'receivedEpochDay'),
        };
    }
  } catch (e) {
    if (e instanceof Unreadable) return null;
    throw e;
  }
}

// --- isUsable (SyncMapping.kt) ----------------------------------------------------------------------------

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const isUuid = (text: string) => UUID.test(text);
const minute = (value: number) => value >= 0 && value < DAY_MINUTES;
const inRange = (value: bigint, max: bigint) => value >= 0n && value <= max;
const epochDayOk = (value: number) => value >= EPOCH_DAYS_MIN && value <= EPOCH_DAYS_MAX;

function rateUsable(rate: TimedRate | null, digits: number): boolean {
  return rate === null || (inRange(rate.rate, maxMinor(digits)) && minute(rate.from) && minute(rate.to) && rate.from !== rate.to);
}

/** A real month in the years the cloud rules accept (Payout.monthFromKey). */
function monthKeyOk(key: number): boolean {
  const year = Math.trunc(key / 100);
  const month = key % 100;
  return key > 0 && year >= 1000 && year <= 9999 && month >= 1 && month <= 12;
}

export function isUsable(record: CloudRecord): boolean {
  if (!(record.schema <= DOC_SCHEMA && isUuid(record.id) && record.updatedAt >= 0)) return false;
  switch (record.kind) {
    case 'job': {
      if (record.name.trim() === '' || record.name.length > MAX_TEXT || !isValidCurrency(record.currency)) return false;
      const digits = fractionDigits(record.currency);
      const months = record.payHistory.map((p) => p.fromMonth);
      return (
        inRange(record.hourlyRate, maxMinor(digits)) &&
        rateUsable(record.secondRate, digits) &&
        new Set(months).size === months.length &&
        record.payHistory.every(
          (p) =>
            (p.fromMonth === 0 || (Math.trunc(p.fromMonth / 100) >= 1 && Math.trunc(p.fromMonth / 100) <= 9999 && p.fromMonth % 100 >= 1 && p.fromMonth % 100 <= 12)) &&
            inRange(p.monthlyAmount, maxMinor(digits, AMOUNT_WHOLE_DIGITS)) &&
            p.deductionBp >= 0 &&
            p.deductionBp <= 10_000,
        )
      );
    }
    case 'shift': {
      if (!isUuid(record.jobId) || !epochDayOk(record.date) || !minute(record.start) || !minute(record.end)) return false;
      const duration = durationMinutes(record.start, record.end);
      return (
        record.breakMinutes >= 0 &&
        record.breakMinutes < duration &&
        inRange(record.rate, maxMinor(MAX_FRACTION_DIGITS)) &&
        rateUsable(record.secondRate, MAX_FRACTION_DIGITS) &&
        (record.note?.length ?? 0) <= MAX_TEXT &&
        (record.externalUid?.length ?? 0) <= MAX_TEXT
      );
    }
    case 'bonus':
      return (
        isUuid(record.jobId) &&
        epochDayOk(record.date) &&
        record.amount >= 1n &&
        record.amount <= maxMinor(MAX_FRACTION_DIGITS, AMOUNT_WHOLE_DIGITS) &&
        (record.note?.length ?? 0) <= MAX_TEXT
      );
    case 'payout':
      return (
        isUuid(record.jobId) &&
        monthKeyOk(record.month) &&
        record.amount >= 1n &&
        record.amount <= maxMinor(MAX_FRACTION_DIGITS, AMOUNT_WHOLE_DIGITS) &&
        (record.receivedOn === null || epochDayOk(record.receivedOn)) &&
        (record.note?.length ?? 0) <= MAX_TEXT
      );
  }
}

// --- What the phone ends up with ----------------------------------------------------------------------------

export interface ReadResult {
  view: DataView;
  /** Documents that couldn't be read or used, or whose job doesn't exist: never shown. */
  skipped: number;
}

/** SQLite's NOCASE: only A-Z fold; then code point order (UTF-8 byte order), as Room's ORDER BY name COLLATE NOCASE. */
function compareNoCase(a: string, b: string): number {
  const fold = (s: string) => s.replace(/[A-Z]/g, (c) => c.toLowerCase());
  const x = [...fold(a)].map((c) => c.codePointAt(0)!);
  const y = [...fold(b)].map((c) => c.codePointAt(0)!);
  for (let i = 0; i < Math.min(x.length, y.length); i++) if (x[i] !== y[i]) return x[i]! - y[i]!;
  return x.length - y.length;
}

function payType(name: string): PayType {
  // An unknown type (written by a newer app version) reads as hourly, as in the app.
  return name === 'FIXED_MONTHLY' ? 'FIXED_MONTHLY' : 'HOURLY';
}

/**
 * The user's data as the phone shows it, from their cloud documents. Records are kept in the order given (the
 * order the phone stored them in decides ties, e.g. two bonuses on one day).
 */
export function readDocuments(documents: CloudDocuments): ReadResult {
  let skipped = 0;
  const usable = <K extends CloudRecord['kind']>(collection: CollectionName, kind: K) => {
    const records: Extract<CloudRecord, { kind: K }>[] = [];
    for (const [id, data] of Object.entries(documents[collection] ?? {})) {
      const record = readRecord(collection, id, data);
      if (record && record.kind === kind && isUsable(record)) records.push(record as Extract<CloudRecord, { kind: K }>);
      else skipped++;
    }
    return records;
  };
  const jobs = usable('jobs', 'job').map((r) => ({ ...r }));
  const jobIds = new Set(jobs.map((j) => j.id));
  // A shift, bonus or payout without its job is skipped (SyncEngine: NO_JOB).
  const withJob = <T extends { jobId: string }>(records: T[]) => records.filter((r) => jobIds.has(r.jobId) || (skipped++, false));
  const shifts = withJob(usable('shifts', 'shift')).map((r) => ({ ...r }));
  const bonuses = withJob(usable('bonuses', 'bonus')).map((r) => ({ ...r }));
  const payouts = withJob(usable('payouts', 'payout')).map((r) => ({ ...r }));

  enforceParentChildRule(jobs, [shifts, bonuses, payouts]);

  const liveShifts = shifts.filter((s) => !s.deleted);
  const liveBonuses = bonuses.filter((b) => !b.deleted);
  // Each job's first and last day with a (live) shift or bonus: where a monthly salary counts.
  const activity = new Map<string, { first: EpochDay; last: EpochDay }>();
  for (const { jobId, date } of [...liveShifts, ...liveBonuses]) {
    const span = activity.get(jobId);
    if (!span) activity.set(jobId, { first: date, last: date });
    else activity.set(jobId, { first: Math.min(span.first, date), last: Math.max(span.last, date) });
  }

  const domainJobs = new Map<string, Job>();
  const liveJobs = jobs.filter((j) => !j.deleted);
  for (const record of liveJobs) {
    const history: PaySettings[] = [...record.payHistory]
      .sort((a, b) => a.fromMonth - b.fromMonth)
      .map((p) => ({
        from: p.fromMonth === 0 ? null : fromMonthKey(p.fromMonth),
        payType: payType(p.payType),
        monthlyAmount: p.monthlyAmount,
        deductionBp: p.deductionBp,
      }));
    domainJobs.set(record.id, {
      id: record.id,
      name: record.name,
      hourlyRate: record.hourlyRate,
      color: record.color,
      secondRate: record.secondRate,
      currency: record.currency,
      payHistory: history,
      firstEntry: activity.get(record.id)?.first ?? null,
      lastEntry: activity.get(record.id)?.last ?? null,
    });
  }

  const stable = <T>(items: T[], compare: (a: T, b: T) => number) =>
    items.map((item, index) => ({ item, index })).sort((a, b) => compare(a.item, b.item) || a.index - b.index).map((x) => x.item);

  const viewShifts: ShiftWithJob[] = stable(
    liveShifts.filter((s) => domainJobs.has(s.jobId)),
    (a, b) => a.date - b.date || a.start - b.start,
  ).map((s) => ({
    job: domainJobs.get(s.jobId)!,
    shift: { id: s.id, jobId: s.jobId, date: s.date, start: s.start, end: s.end, breakMinutes: s.breakMinutes, rate: s.rate, secondRate: s.secondRate, note: s.note },
  }));
  const viewBonuses: BonusWithJob[] = stable(liveBonuses.filter((b) => domainJobs.has(b.jobId)), (a, b) => a.date - b.date).map((b) => ({
    job: domainJobs.get(b.jobId)!,
    bonus: { id: b.id, jobId: b.jobId, date: b.date, amount: b.amount, note: b.note, wholeMonth: b.wholeMonth },
  }));
  const viewPayouts: PayoutWithJob[] = stable(
    payouts.filter((p) => !p.deleted && domainJobs.has(p.jobId)),
    (a, b) => a.month - b.month,
  ).map((p) => ({
    job: domainJobs.get(p.jobId)!,
    payout: { id: p.id, jobId: p.jobId, month: fromMonthKey(p.month), amount: p.amount, note: p.note, receivedOn: p.receivedOn },
  }));
  const viewJobs = stable([...domainJobs.values()], (a, b) => compareNoCase(a.name, b.name));
  return { view: { jobs: viewJobs, shifts: viewShifts, bonuses: viewBonuses, payouts: viewPayouts }, skipped };
}

/**
 * ParentChildRule.kt: a deleted job whose live entries were changed after its deletion comes back with only those
 * entries; live entries not newer than the deletion are deleted with it.
 */
export function enforceParentChildRule(
  jobs: { id: string; deleted: boolean; updatedAt: number }[],
  entries: { jobId: string; deleted: boolean; updatedAt: number }[][],
): void {
  for (const job of jobs) {
    if (!job.deleted) continue;
    const live = entries.flatMap((list) => list.filter((e) => e.jobId === job.id && !e.deleted));
    if (live.length === 0) continue;
    const deletedAt = job.updatedAt;
    const newest = Math.max(...live.map((e) => e.updatedAt));
    for (const entry of live) {
      if (entry.updatedAt <= deletedAt) {
        entry.deleted = true;
        entry.updatedAt = Math.max(entry.updatedAt + 1, deletedAt);
      }
    }
    if (newest > deletedAt) {
      job.deleted = false;
      job.updatedAt = newest;
    }
  }
}

export { epochDay };
