import { describe, expect, test } from 'vitest';
import { epochDay } from '../../src/domain/dates';
import { plusMonths, stepAnchor } from '../../src/screens/summary';
import { deductionText } from '../../src/screens/jobs';

/** Summary's ‹ › move an anchor date as the app does (java.time plusWeeks / plusMonths / plusYears). */
describe('Summary paging', () => {
  test('a month later from the 31st is the month’s last day; it then stays there', () => {
    const jan31 = epochDay(2027, 1, 31);
    const feb = plusMonths(jan31, 1);
    expect(feb).toBe(epochDay(2027, 2, 28));
    expect(plusMonths(feb, 1)).toBe(epochDay(2027, 3, 28));
    expect(plusMonths(epochDay(2028, 1, 31), 1)).toBe(epochDay(2028, 2, 29));
    expect(plusMonths(epochDay(2026, 1, 15), -2)).toBe(epochDay(2025, 11, 15));
  });

  test('weeks move by seven days, years keep the day (29 Feb -> 28 Feb)', () => {
    expect(stepAnchor(epochDay(2026, 11, 13), 'WEEK', -1)).toBe(epochDay(2026, 11, 6));
    expect(stepAnchor(epochDay(2028, 2, 29), 'YEAR', 1)).toBe(epochDay(2029, 2, 28));
    expect(stepAnchor(epochDay(2026, 11, 13), 'MONTH', 1)).toBe(epochDay(2026, 12, 13));
  });
});

describe('Jobs', () => {
  test('deductions as the app’s editor writes them', () => {
    expect(deductionText(1371, 'uk-UA')).toBe('13,71');
    expect(deductionText(1300, 'uk-UA')).toBe('13');
    expect(deductionText(1305, 'en-US')).toBe('13.05');
    expect(deductionText(5, 'en-US')).toBe('0.05');
  });
});
