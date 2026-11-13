// Copies the Android app's texts the web version shows (Ukrainian: res/values, English: res/values-en) into
// src/i18n/android-strings.json, so both say exactly the same. Run `npm run strings` after changing them in the app;
// test/unit/strings.test.ts fails while the JSON is out of date.
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
export const RES = resolve(here, '../../app/src/main/res');
export const OUT = resolve(here, '../src/i18n/android-strings.json');

/** The app's strings the web screens use. */
export const KEYS = [
  'earnings_month', 'earnings_week', 'earnings_year', 'hours', 'shifts', 'average_rate',
  'by_job', 'by_day', 'by_month', 'month_bonuses_title', 'month_bonuses_none', 'month_bonuses_row',
  'received_pay_title', 'received_pay_caption', 'received_pay_year_caption', 'received_pay_calculated',
  'received_pay_received', 'received_pay_difference', 'received_pay_not_entered', 'received_pay_received_on_value',
  'received_tag_part', 'received_tag_all', 'including_bonuses', 'salary_value', 'bonuses_value', 'share_of_earnings',
  'earnings_gross', 'earnings_net', 'period_week', 'period_month', 'period_year', 'summary_title',
  'summary_empty_title', 'summary_empty_message', 'week_fixed_pay_note', 'no_shifts_this_day', 'day_free',
  'bonus', 'bonus_for_job', 'bonus_for_whole_month', 'break_short', 'overnight_shift', 'fixed_salary', 'hours_value',
  'jobs_title', 'no_jobs_title', 'fixed_salary_per_month', 'second_rate_summary', 'deduction_value',
  'previous_month', 'next_month', 'today', 'current_period', 'nav_calendar', 'nav_summary', 'currency',
];
export const PLURALS = ['shift_count', 'received_pay_count'];

/** Android resource text as shown: \' \" \n unescaped, surrounding quotes dropped. */
function unescape(text) {
  return text
    .replace(/^"(.*)"$/s, '$1')
    .replace(/\\n/g, '\n')
    .replace(/\\(['"@?\\])/g, '$1')
    .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&');
}

function read(folder) {
  const xml = readFileSync(resolve(RES, folder, 'strings.xml'), 'utf8');
  const strings = {};
  for (const key of KEYS) {
    const match = xml.match(new RegExp(`<string name="${key}"[^>]*>([\\s\\S]*?)</string>`));
    if (!match) throw new Error(`${folder}: no string ${key}`);
    strings[key] = unescape(match[1]);
  }
  const plurals = {};
  for (const key of PLURALS) {
    const block = xml.match(new RegExp(`<plurals name="${key}">([\\s\\S]*?)</plurals>`));
    if (!block) throw new Error(`${folder}: no plurals ${key}`);
    plurals[key] = Object.fromEntries([...block[1].matchAll(/<item quantity="(\w+)">([\s\S]*?)<\/item>/g)].map((m) => [m[1], unescape(m[2])]));
  }
  return { strings, plurals };
}

export function androidStrings() {
  return { uk: read('values'), en: read('values-en') };
}

export function render() {
  return JSON.stringify(androidStrings(), null, 2) + '\n';
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  writeFileSync(OUT, render());
  console.log(`Wrote ${OUT}`);
}
