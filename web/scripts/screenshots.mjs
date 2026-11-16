// Screenshots of the demo (?demo) at phone and desktop width, light and dark, for a look by eye. They are written
// to web/screenshots/ (ignored by git, never committed). Needs a built site: `npm run build`, then
// `npm run screenshots` (it serves dist/ itself with `vite preview`).
import { spawn } from 'node:child_process';
import { mkdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { chromium } from 'playwright';

const ROOT = resolve(import.meta.dirname, '..');
const OUT = resolve(ROOT, 'screenshots');
const PORT = 4173;
const SIZES = { phone: { width: 390, height: 844 }, desktop: { width: 1280, height: 800 } };
const SCHEMES = ['light', 'dark'];
const SCREENS = (process.env.SCREENS ?? 'calendar,summary,jobs').split(',');
const LANG = process.env.SCREENSHOT_LANG ?? 'uk';

async function waitForServer(url) {
  for (let i = 0; i < 100; i++) {
    try {
      if ((await fetch(url)).ok) return;
    } catch {}
    await new Promise((r) => setTimeout(r, 200));
  }
  throw new Error(`no server at ${url}`);
}

mkdirSync(OUT, { recursive: true });
const server = spawn('npx', ['vite', 'preview', '--port', String(PORT), '--strictPort'], { cwd: ROOT, stdio: 'ignore' });
try {
  await waitForServer(`http://localhost:${PORT}/`);
  const browser = await chromium.launch();
  for (const [sizeName, viewport] of Object.entries(SIZES)) {
    for (const scheme of SCHEMES) {
      const page = await browser.newPage({ viewport, colorScheme: scheme, locale: LANG, deviceScaleFactor: 2 });
      for (const screen of SCREENS) {
        await page.goto(`http://localhost:${PORT}/?demo#/${screen}`);
        await page.waitForSelector(`[data-testid="screen-${screen}"]`);
        await page.evaluate(() => document.fonts.ready);
        const file = resolve(OUT, `${screen}-${sizeName}-${scheme}.png`);
        await page.screenshot({ path: file, fullPage: true });
        console.log(file);
      }
      await page.close();
    }
  }
  await browser.close();
} finally {
  server.kill();
}
