import android from './android-strings.json';
import { WEB_STRINGS, type WebKey } from './web-strings';

/** The app's two languages. Ukrainian when the browser prefers it, English otherwise. */
export type Lang = 'uk' | 'en';

export type AndroidKey = keyof (typeof android)['uk']['strings'];
export type PluralKey = keyof (typeof android)['uk']['plurals'];
export type StringKey = AndroidKey | WebKey;

/** The first of the browser's languages that the app has; English when none is Ukrainian. */
export function pickLanguage(languages: readonly string[]): Lang {
  for (const tag of languages) {
    const base = tag.toLowerCase().split('-')[0];
    if (base === 'uk') return 'uk';
    if (base === 'en') return 'en';
  }
  return 'en';
}

/** BCP-47 locale used for numbers and dates (as the app's Locale). */
export function localeOf(lang: Lang): string {
  return lang === 'uk' ? 'uk-UA' : 'en-US';
}

/**
 * Fills an Android format string: %1$s, %1$d, %s, %d and %%. Numbers are written as Java's String.format writes
 * them (plain digits, no grouping).
 */
export function formatAndroid(template: string, args: readonly (string | number | bigint)[]): string {
  let next = 0;
  return template.replace(/%(?:(\d+)\$)?([sd%])/g, (_, position: string | undefined, kind: string) => {
    if (kind === '%') return '%';
    const index = position ? Number(position) - 1 : next++;
    const value = args[index];
    return value === undefined ? '' : String(value);
  });
}

export class Strings {
  readonly locale: string;
  private readonly plurals: Intl.PluralRules;

  constructor(readonly lang: Lang) {
    this.locale = localeOf(lang);
    this.plurals = new Intl.PluralRules(this.locale);
  }

  t(key: StringKey, ...args: (string | number | bigint)[]): string {
    const table = android[this.lang].strings as Record<string, string>;
    const template = table[key] ?? WEB_STRINGS[this.lang][key as WebKey];
    if (template === undefined) throw new Error(`No string ${key}`);
    return formatAndroid(template, args);
  }

  /** Android plurals: Ukrainian has one / few / many / other, English one / other. */
  plural(key: PluralKey, count: number): string {
    const forms = android[this.lang].plurals[key] as Record<string, string>;
    const form = forms[this.plurals.select(count)] ?? forms['other'] ?? '';
    return formatAndroid(form, [count]);
  }
}

export function browserStrings(): Strings {
  return new Strings(pickLanguage(typeof navigator === 'undefined' ? [] : navigator.languages ?? [navigator.language]));
}
