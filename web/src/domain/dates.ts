/*
 * Dates as the app has them, without time zones: a day is a LocalDate (here: days since 1970-01-01, "epoch day"),
 * a month a YearMonth (here: months since year 0, year * 12 + month - 1), a time a minute of the day (0..1439).
 * Pure integer arithmetic (Howard Hinnant's civil-date algorithms), so no browser time zone or DST can shift a day.
 */

export type EpochDay = number;
/** year * 12 + (month - 1). */
export type YearMonth = number;

export function epochDay(year: number, month: number, day: number): EpochDay {
  const y = month <= 2 ? year - 1 : year;
  const era = Math.floor(y / 400);
  const yoe = y - era * 400;
  const mp = (month + 9) % 12;
  const doy = Math.floor((153 * mp + 2) / 5) + day - 1;
  const doe = yoe * 365 + Math.floor(yoe / 4) - Math.floor(yoe / 100) + doy;
  return era * 146097 + doe - 719468;
}

export function civil(day: EpochDay): { year: number; month: number; day: number } {
  const z = day + 719468;
  const era = Math.floor(z / 146097);
  const doe = z - era * 146097;
  const yoe = Math.floor((doe - Math.floor(doe / 1460) + Math.floor(doe / 36524) - Math.floor(doe / 146096)) / 365);
  const doy = doe - (365 * yoe + Math.floor(yoe / 4) - Math.floor(yoe / 100));
  const mp = Math.floor((5 * doy + 2) / 153);
  const d = doy - Math.floor((153 * mp + 2) / 5) + 1;
  const m = mp < 10 ? mp + 3 : mp - 9;
  return { year: yoe + era * 400 + (m <= 2 ? 1 : 0), month: m, day: d };
}

export const yearMonth = (year: number, month: number): YearMonth => year * 12 + month - 1;
export const ymYear = (ym: YearMonth): number => Math.floor(ym / 12);
export const ymMonth = (ym: YearMonth): number => (((ym % 12) + 12) % 12) + 1;
export function monthOf(day: EpochDay): YearMonth {
  const c = civil(day);
  return yearMonth(c.year, c.month);
}
export const firstDay = (ym: YearMonth): EpochDay => epochDay(ymYear(ym), ymMonth(ym), 1);
export const lastDay = (ym: YearMonth): EpochDay => firstDay(ym + 1) - 1;
export const lengthOfMonth = (ym: YearMonth): number => lastDay(ym) - firstDay(ym) + 1;
export const dayOfMonth = (day: EpochDay): number => civil(day).day;

/** ISO day of the week: 1 = Monday .. 7 = Sunday (1970-01-01 was a Thursday). */
export const dayOfWeek = (day: EpochDay): number => ((((day + 3) % 7) + 7) % 7) + 1;
export const mondayOf = (day: EpochDay): EpochDay => day - (dayOfWeek(day) - 1);

/** yyyymm, as the app stores months (2026-11 is 202611). */
export const monthKey = (ym: YearMonth): number => ymYear(ym) * 100 + ymMonth(ym);
export function fromMonthKey(key: number): YearMonth {
  return yearMonth(Math.floor(key / 100), key % 100);
}

const pad = (n: number, width: number) => String(n).padStart(width, '0');
/** "2026-11-02" (LocalDate.toString). */
export function dateString(day: EpochDay): string {
  const c = civil(day);
  return `${pad(c.year, 4)}-${pad(c.month, 2)}-${pad(c.day, 2)}`;
}
/** "2026-11" (YearMonth.toString). */
export const monthString = (ym: YearMonth): string => `${pad(ymYear(ym), 4)}-${pad(ymMonth(ym), 2)}`;

export function parseDate(text: string): EpochDay {
  const [y, m, d] = text.split('-').map(Number);
  return epochDay(y!, m!, d!);
}

/** Today in the browser's own time zone (as LocalDate.now() on the phone). */
export function today(now: Date = new Date()): EpochDay {
  return epochDay(now.getFullYear(), now.getMonth() + 1, now.getDate());
}
