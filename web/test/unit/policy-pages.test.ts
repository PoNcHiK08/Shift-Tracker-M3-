// The static policy pages Google Play links to (web/public → Firebase Hosting): /privacy and /delete-account, each in
// Ukrainian, English and Polish, self-contained, with the contact placeholder, and routed before the app's catch-all.
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, test } from 'vitest';

const web = resolve(import.meta.dirname, '../..');
const page = (name: string) => readFileSync(resolve(web, 'public', name), 'utf8');
const LANGUAGES = ['uk', 'en', 'pl'] as const;

/** One language's section of [html], as markup. */
function sectionHtml(html: string, lang: string): string {
  const match = html.match(new RegExp(`<section[^>]*data-lang="${lang}"[^>]*>([\\s\\S]*?)</section>`));
  expect(match, `section ${lang}`).not.toBeNull();
  return match![1]!;
}

/** The text of one language's section of [html] (tags removed, entities decoded, whitespace collapsed). */
function section(html: string, lang: string): string {
  return sectionHtml(html, lang).replace(/<[^>]+>/g, ' ').replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&').replace(/\s+/g, ' ');
}

describe.each(['privacy.html', 'delete-account.html'])('%s', (name) => {
  const html = page(name);

  test('has one section per language, each with the contact address placeholder', () => {
    for (const lang of LANGUAGES) {
      expect(html.match(new RegExp(`data-lang="${lang}"`, 'g'))?.length, lang).toBe(1);
      expect(section(html, lang), lang).toContain('CONTACT_EMAIL_PLACEHOLDER');
    }
  });

  test('has a language switch to every language', () => {
    for (const lang of LANGUAGES) expect(html).toMatch(new RegExp(`<a[^>]*href="\\?lang=${lang}"[^>]*hreflang="${lang}"`));
  });

  test('is self-contained: no external scripts, styles or fonts, no tracking', () => {
    expect(html).not.toMatch(/<script[^>]+src=/i);
    expect(html).not.toMatch(/<link[^>]+stylesheet/i);
    expect(html).not.toMatch(/https?:\/\/(?!(policies|firebase)\.google\.com\/)/i);
    expect(html).not.toMatch(/gtag|googletagmanager|analytics\.js/i);
  });
});

describe('privacy policy', () => {
  const html = page('privacy.html');
  const en = section(html, 'en');

  test('says exactly what the app stores and where', () => {
    for (const fact of ['on your phone', 'Firebase Authentication', 'email address', 'Cloud Firestore', 'jobs', 'shifts', 'rates', 'bonuses', 'received payments']) {
      expect(en, fact).toContain(fact);
    }
  });

  test('says what the app never does', () => {
    for (const fact of ['No ads', 'No analytics', 'never sold', 'crash', 'only if you copy or share it yourself']) {
      expect(en, fact).toContain(fact);
    }
  });

  test('explains export and deletion, and links the deletion page', () => {
    for (const fact of ['Export data', 'Delete my cloud data', 'Delete account']) expect(en, fact).toContain(fact);
    for (const lang of LANGUAGES) expect(sectionHtml(html, lang), lang).toMatch(new RegExp(`href="/delete-account\\?lang=${lang}"`));
  });
});

describe('account deletion page', () => {
  const html = page('delete-account.html');

  test('explains deleting in the app and by email without it', () => {
    const en = section(html, 'en');
    for (const fact of ['Settings', 'Account & sync', 'Delete account', 'Without the app', 'stays']) expect(en, fact).toContain(fact);
    expect(section(html, 'uk')).toContain('Видалити акаунт');
    expect(section(html, 'pl')).toContain('Usuń konto');
  });
});

describe('hosting', () => {
  const hosting = JSON.parse(readFileSync(resolve(web, 'firebase.json'), 'utf8')).hosting as {
    rewrites: { source: string; destination: string }[];
  };

  test('routes /privacy and /delete-account to their pages before the app takes every other path', () => {
    const sources = hosting.rewrites.map((r) => r.source);
    const catchAll = sources.indexOf('**');
    for (const [path, file] of [['/privacy', '/privacy.html'], ['/delete-account', '/delete-account.html']] as const) {
      const i = sources.indexOf(path);
      expect(i, path).toBeGreaterThanOrEqual(0);
      expect(i, path).toBeLessThan(catchAll);
      expect(hosting.rewrites[i]!.destination).toBe(file);
    }
  });
});
