import { describe, expect, test } from 'vitest';
import { formatAndroid, pickLanguage, Strings } from '../../src/i18n/i18n';
import { WEB_STRINGS } from '../../src/i18n/web-strings';
import { render, OUT } from '../../scripts/sync-strings.mjs';
import { readFileSync } from 'node:fs';

describe('language', () => {
  test('Ukrainian when the browser prefers it, English otherwise', () => {
    expect(pickLanguage(['uk-UA', 'en'])).toBe('uk');
    expect(pickLanguage(['uk'])).toBe('uk');
    expect(pickLanguage(['pl-PL', 'uk'])).toBe('uk');
    expect(pickLanguage(['en-GB', 'uk'])).toBe('en');
    expect(pickLanguage(['de-DE'])).toBe('en');
    expect(pickLanguage([])).toBe('en');
  });
});

describe('Android format strings', () => {
  test('positional and plain arguments, and %%', () => {
    expect(formatAndroid('%1$s–%2$s: %3$s', ['23:00', '06:00', '37,00 zł/h'])).toBe('23:00–06:00: 37,00 zł/h');
    expect(formatAndroid('%d payments', [2])).toBe('2 payments');
    expect(formatAndroid('%1$d%% of this month', [42])).toBe('42% of this month');
    expect(formatAndroid('deductions %1$s %%', ['13,71'])).toBe('deductions 13,71 %');
  });

  test('plurals follow each language (Ukrainian: one, few, many)', () => {
    const uk = new Strings('uk');
    expect([1, 2, 5, 11, 21, 22, 25].map((n) => uk.plural('shift_count', n))).toEqual([
      '1 зміна', '2 зміни', '5 змін', '11 змін', '21 зміна', '22 зміни', '25 змін',
    ]);
    const en = new Strings('en');
    expect([1, 2].map((n) => en.plural('received_pay_count', n))).toEqual(['1 payment', '2 payments']);
  });
});

describe('texts', () => {
  test("the app's texts are copied from its strings.xml and up to date (npm run strings)", () => {
    expect(readFileSync(OUT, 'utf8')).toBe(render());
  });

  test('every web-only text exists in both languages', () => {
    expect(Object.keys(WEB_STRINGS.en).sort()).toEqual(Object.keys(WEB_STRINGS.uk).sort());
    for (const lang of ['uk', 'en'] as const) {
      for (const [key, value] of Object.entries(WEB_STRINGS[lang])) expect(value, `${lang}.${key}`).not.toBe('');
    }
  });

  test('a text the app has reads the same as in the app', () => {
    expect(new Strings('uk').t('received_tag_all')).toBe('Отримано');
    expect(new Strings('en').t('salary_value', '5 200,00 zł')).toBe('salary 5 200,00 zł');
  });
});
