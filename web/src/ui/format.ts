import type { Amounts } from '../domain/amounts';
import { civil, ymMonth, ymYear, type EpochDay, type YearMonth } from '../domain/dates';
import { DEFAULT_CURRENCY, currencySymbol, fractionDigits } from '../domain/currencies';

/*
 * Numbers, money and dates written as the app writes them (ui/util/Formatters.kt), with the browser's Intl in place
 * of Java's formatters. Dates are calendar days, so they are formatted in UTC: no time zone can move them.
 */

/** [minor] units as an exact decimal string ("-1234.50"), never through a float. */
export function decimal(minor: bigint, digits: number): string {
  const negative = minor < 0n;
  const abs = (negative ? -minor : minor).toString().padStart(digits + 1, '0');
  const text = digits === 0 ? abs : `${abs.slice(0, -digits)}.${abs.slice(-digits)}`;
  return negative ? `-${text}` : text;
}

const LETTERS = /^\p{L}+$/u;

/**
 * [minor] units of [currency] with its decimals. A symbol made of letters ("zł", "Kč", "CHF") follows the number
 * after a no-break space; a sign ("€", "$", "₴") is placed where the reader's locale places it.
 */
export function money(minor: bigint, currency: string, locale: string): string {
  const digits = fractionDigits(currency);
  const symbol = currencySymbol(currency);
  const value = decimal(minor, digits) as unknown as number; // Intl takes an exact decimal string
  if (LETTERS.test(symbol)) {
    const number = new Intl.NumberFormat(locale, { minimumFractionDigits: digits, maximumFractionDigits: digits }).format(value);
    return `${number} ${symbol}`;
  }
  // The locale's place for a currency sign, from a currency with a one-character sign (as Java's currency pattern
  // with the symbol swapped in: "€241.00", "241,00 €"); Intl would add a space after a sign it doesn't know.
  const format = new Intl.NumberFormat(locale, {
    style: 'currency',
    currency: 'USD',
    currencyDisplay: 'narrowSymbol',
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  });
  return format
    .formatToParts(value)
    .map((part) => (part.type === 'currency' ? symbol : part.value))
    .join('');
}

/** Several currencies' totals, largest first: "1 477,00 zł · 200,00 €". */
export function amounts(values: Amounts, locale: string, fallbackCurrency: string = DEFAULT_CURRENCY): string {
  if (values.isEmpty) return money(0n, fallbackCurrency, locale);
  return values
    .sortedByValue()
    .map(([code, value]) => money(value, code, locale))
    .join(' · ');
}

export const rate = (minor: bigint, currency: string, locale: string): string => `${money(minor, currency, locale)}/h`;

/** 450 -> "7,5" (up to two decimals). */
export function decimalHours(minutes: number, locale: string): string {
  return new Intl.NumberFormat(locale, { minimumFractionDigits: 0, maximumFractionDigits: 2 }).format(minutes / 60);
}

/** A minute of the day as "08:05". */
export function time(minute: number): string {
  const h = Math.floor(minute / 60);
  return `${String(h).padStart(2, '0')}:${String(minute % 60).padStart(2, '0')}`;
}

function utc(day: EpochDay): Date {
  return new Date(day * 86_400_000);
}

function capitalize(text: string, locale: string): string {
  return text.charAt(0).toLocaleUpperCase(locale) + text.slice(1);
}

function part(day: EpochDay, locale: string, options: Intl.DateTimeFormatOptions, type: Intl.DateTimeFormatPartTypes): string {
  return (
    new Intl.DateTimeFormat(locale, { ...options, timeZone: 'UTC' }).formatToParts(utc(day)).find((p) => p.type === type)?.value ?? ''
  );
}

/** The month's name on its own ("листопад", "November"), as Java's "LLLL". */
function standaloneMonth(ym: YearMonth, locale: string): string {
  const day = Date.UTC(ymYear(ym), ymMonth(ym) - 1, 1) / 86_400_000;
  return new Intl.DateTimeFormat(locale, { month: 'long', timeZone: 'UTC' }).format(utc(day));
}

/** "Листопад 2026". */
export function monthYear(ym: YearMonth, locale: string): string {
  return `${capitalize(standaloneMonth(ym, locale), locale)} ${ymYear(ym)}`;
}

/** "Листопад". */
export function monthName(ym: YearMonth, locale: string): string {
  return capitalize(standaloneMonth(ym, locale), locale);
}

/** "Понеділок, 2 листопада" ("EEEE, d MMMM": the month as part of a date). */
export function fullDate(day: EpochDay, locale: string): string {
  const weekday = part(day, locale, { weekday: 'long' }, 'weekday');
  const month = part(day, locale, { day: 'numeric', month: 'long' }, 'month');
  return capitalize(`${weekday}, ${civil(day).day} ${month}`, locale);
}

/** "Пн, 2 лист." ("EEE, d MMM"). */
export function shortDate(day: EpochDay, locale: string): string {
  const weekday = part(day, locale, { weekday: 'short' }, 'weekday');
  const month = part(day, locale, { day: 'numeric', month: 'short' }, 'month');
  return capitalize(`${weekday}, ${civil(day).day} ${month}`, locale);
}

/** "27 жовт. – 2 лист. 2026"; the year once when both ends share it. */
export function dateRange(from: EpochDay, to: EpochDay, locale: string): string {
  const dayMonth = (day: EpochDay) => `${civil(day).day} ${part(day, locale, { day: 'numeric', month: 'short' }, 'month')}`;
  const sameYear = civil(from).year === civil(to).year;
  const start = sameYear ? dayMonth(from) : `${dayMonth(from)} ${civil(from).year}`;
  return `${start} – ${dayMonth(to)} ${civil(to).year}`;
}

/** A medium date ("13 лист. 2026 р.", "Nov 13, 2026"). */
export function mediumDate(day: EpochDay, locale: string): string {
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeZone: 'UTC' }).format(utc(day));
}

/** Short weekday names Monday first ("Пн" … "Нд"). */
export function weekdayNames(locale: string): string[] {
  // 2024-01-01 was a Monday.
  const monday = Date.UTC(2024, 0, 1) / 86_400_000;
  return Array.from({ length: 7 }, (_, i) => capitalize(part(monday + i, locale, { weekday: 'short' }, 'weekday'), locale));
}

/** A job's colour (Android ARGB int) as CSS. */
export function cssColor(argb: number): string {
  const v = argb >>> 0;
  return `rgb(${(v >>> 16) & 255} ${(v >>> 8) & 255} ${v & 255})`;
}

/** Readable text on a job's colour (Components.kt onColor: by relative luminance). */
export function onColor(argb: number): string {
  const v = argb >>> 0;
  const channel = (c: number) => {
    const s = c / 255;
    return s <= 0.04045 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
  };
  const luminance = 0.2126 * channel((v >>> 16) & 255) + 0.7152 * channel((v >>> 8) & 255) + 0.0722 * channel(v & 255);
  return luminance > 0.5 ? 'rgb(0 0 0 / 0.87)' : '#fff';
}
