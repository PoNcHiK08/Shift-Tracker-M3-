import { afterEach, describe, expect, test } from 'vitest';
import '../../src/theme/theme.css';
import '../../src/app/app.css';
import '../../src/ui/material';
import '../../src/app/app';
import { Strings } from '../../src/i18n/i18n';
import type { App } from '../../src/app/app';

async function mount(lang: 'uk' | 'en', demo: boolean): Promise<App> {
  const app = document.createElement('st-app');
  app.strings = new Strings(lang);
  app.demo = demo;
  document.body.append(app);
  await app.updateComplete;
  await app.querySelector('st-shell')!.updateComplete;
  return app;
}

afterEach(() => {
  document.body.innerHTML = '';
  history.replaceState(null, '', location.pathname + location.search);
});

describe('app frame', () => {
  test('shows the three destinations, the first one selected, in the browser language', async () => {
    const app = await mount('uk', false);
    const bar = [...app.querySelectorAll<HTMLAnchorElement>('.nav-bar .nav-item')];
    expect(bar.map((a) => a.textContent?.trim())).toEqual(['Календар', 'Підсумок', 'Роботи']);
    expect(bar[0]!.getAttribute('aria-current')).toBe('page');
    expect(app.querySelector('h1')!.textContent).toBe('Календар');
  });

  test('a destination opens its screen', async () => {
    const app = await mount('en', false);
    app.querySelector<HTMLAnchorElement>('.nav-bar [data-route="summary"]')!.click();
    await new Promise((resolve) => window.addEventListener('hashchange', resolve, { once: true }));
    await app.updateComplete;
    await app.querySelector('st-shell')!.updateComplete;
    expect(app.querySelector('h1')!.textContent).toBe('Summary');
    expect(app.querySelector('.nav-bar [data-route="summary"]')!.getAttribute('aria-current')).toBe('page');
  });

  test('phone width: a bottom bar; wide: a navigation rail', async () => {
    const app = await mount('en', false);
    const bar = app.querySelector<HTMLElement>('.nav-bar')!;
    const rail = app.querySelector<HTMLElement>('.nav-rail')!;
    const wide = window.matchMedia('(min-width: 600px)').matches;
    expect(getComputedStyle(bar).display === 'none').toBe(wide);
    expect(getComputedStyle(rail).display === 'none').toBe(!wide);
  });

  test('the demo says it is a demo', async () => {
    const app = await mount('en', true);
    expect(app.querySelector('[data-testid="demo-banner"]')!.textContent).toContain('Demo');
    const plain = await mount('en', false);
    expect(plain.querySelector('[data-testid="demo-banner"]')).toBeNull();
  });
});
