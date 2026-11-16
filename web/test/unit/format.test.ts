import { describe, expect, test } from 'vitest';
import { Amounts } from '../../src/domain/amounts';
import { epochDay, yearMonth } from '../../src/domain/dates';
import { amounts, cssColor, dateRange, decimal, decimalHours, fullDate, money, monthYear, onColor, shortDate, time, weekdayNames } from '../../src/ui/format';

/** Formatting as the app's Formatters.kt does it (Java's output for the same values, uk-UA and en-US). */

const NBSP = ' ';
const n = (s: string) => s.replace(/[  ]/g, ' ');

describe('money', () => {
  test('exact decimals from minor units, never through a float', () => {
    expect(decimal(123456789012345n, 2)).toBe('1234567890123.45');
    expect(decimal(5n, 2)).toBe('0.05');
    expect(decimal(-5n, 3)).toBe('-0.005');
    expect(decimal(1234n, 0)).toBe('1234');
  });

  test('a symbol of letters follows the number; a sign goes where the locale puts it', () => {
    expect(money(123456789n, 'PLN', 'uk-UA')).toBe(`1${NBSP}234${NBSP}567,89${NBSP}zł`);
    expect(money(123456789n, 'PLN', 'en-US')).toBe(`1,234,567.89${NBSP}zł`);
    expect(n(money(24100n, 'EUR', 'uk-UA'))).toBe('241,00 €');
    expect(money(24100n, 'EUR', 'en-US')).toBe('€241.00');
    expect(money(500n, 'UAH', 'en-US')).toBe('₴5.00');
    expect(n(money(500n, 'UAH', 'uk-UA'))).toBe('5,00 ₴');
    expect(money(-500n, 'USD', 'en-US')).toBe('-$5.00');
  });

  test('each currency’s own decimals; a currency without a symbol by its code', () => {
    expect(money(1234n, 'JPY', 'en-US')).toBe('¥1,234');
    expect(money(1234567n, 'KWD', 'en-US')).toBe(`1,234.567${NBSP}KWD`);
    expect(money(150000n, 'IDR', 'en-US')).toBe(`1,500.00${NBSP}IDR`);
  });

  test('several currencies: largest first; none: zero in the fallback currency', () => {
    const totals = Amounts.of('EUR', 9900n).plus(Amounts.of('PLN', 1115938n));
    expect(amounts(totals, 'en-US')).toBe(`11,159.38${NBSP}zł · €99.00`);
    expect(amounts(Amounts.EMPTY, 'en-US')).toBe(`0.00${NBSP}zł`);
  });
});

describe('hours and dates', () => {
  test('hours with up to two decimals; times of day', () => {
    expect(decimalHours(450, 'uk-UA')).toBe('7,5');
    expect(decimalHours(445, 'en-US')).toBe('7.42');
    expect(decimalHours(13365, 'en-US')).toBe('222.75');
    expect(time(5)).toBe('00:05');
    expect(time(22 * 60)).toBe('22:00');
  });

  test('as the app writes them', () => {
    expect(monthYear(yearMonth(2026, 11), 'uk-UA')).toBe('Листопад 2026');
    expect(monthYear(yearMonth(2026, 11), 'en-US')).toBe('November 2026');
    expect(fullDate(epochDay(2026, 11, 2), 'uk-UA')).toBe('Понеділок, 2 листопада');
    expect(fullDate(epochDay(2026, 11, 2), 'en-US')).toBe('Monday, 2 November');
    expect(shortDate(epochDay(2026, 11, 2), 'uk-UA')).toBe('Пн, 2 лист.');
    expect(dateRange(epochDay(2026, 10, 27), epochDay(2026, 11, 2), 'en-US')).toBe('27 Oct – 2 Nov 2026');
    expect(dateRange(epochDay(2026, 12, 28), epochDay(2027, 1, 3), 'en-US')).toBe('28 Dec 2026 – 3 Jan 2027');
    expect(weekdayNames('uk-UA')).toEqual(['Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб', 'Нд']);
  });
});

describe('job colours', () => {
  test('Android ARGB ints as CSS, with readable text on them', () => {
    expect(cssColor(-12345273)).toBe('rgb(67 160 71)');
    expect(cssColor(0xffffffff | 0)).toBe('rgb(255 255 255)');
    expect(onColor(0xffffffff | 0)).toBe('rgb(0 0 0 / 0.87)');
    expect(onColor(0xff000000 | 0)).toBe('#fff');
  });
});
