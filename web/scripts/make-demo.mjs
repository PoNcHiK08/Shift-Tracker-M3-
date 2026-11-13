// Writes src/demo/demo-documents.json: the made-up data `?demo` shows, in exactly the shape the app stores in
// Firestore (FirestoreDocuments.kt), keyed by collection and document id. Deterministic (seeded), so it only
// changes when this script does. The Kotlin fixture exporter reads the same file and computes the app's own
// figures for it (web/test/fixtures/demo-expected.json), so the demo is checked against the app too.
//
// "Today" in the demo is DEMO_TODAY (src/demo/demo.ts): the calendar opens there and the data surrounds it.
import { writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const OUT = resolve(here, '../src/demo/demo-documents.json');

// Small deterministic generator (mulberry32).
let seed = 20261113;
function random() {
  seed |= 0;
  seed = (seed + 0x6d2b79f5) | 0;
  let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
  t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
  return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
}
function uuid() {
  const hex = Array.from({ length: 32 }, () => Math.floor(random() * 16).toString(16));
  hex[12] = '4';
  hex[16] = '89ab'[Math.floor(random() * 4)];
  const s = hex.join('');
  return `${s.slice(0, 8)}-${s.slice(8, 12)}-${s.slice(12, 16)}-${s.slice(16, 20)}-${s.slice(20)}`;
}

/** Days since 1970-01-01 of a calendar date. */
function epochDay(y, m, d) {
  return Math.round(Date.UTC(y, m - 1, d) / 86_400_000);
}
const minute = (h, m = 0) => h * 60 + m;
/** ARGB colour as the app stores it: a signed 32-bit Int. */
const argb = (hex) => hex | 0;

let clock = Date.UTC(2026, 7, 1, 8, 0, 0); // updatedAt values: one minute apart, in the past of "today"
const stamp = () => (clock += 60_000);

const docs = { jobs: {}, shifts: {}, bonuses: {}, payouts: {} };

function job(name, rate, color, { currency = 'PLN', secondRate = null, payHistory = [], deleted = false } = {}) {
  const id = uuid();
  docs.jobs[id] = {
    v: 1, updatedAt: stamp(), deleted, name, hourlyRateGrosze: rate, color: argb(color),
    secondRate, currency, payHistory,
  };
  return id;
}
function shift(jobId, [y, m, d], start, end, rate, { breakMin = 0, note = null, secondRate = null, deleted = false } = {}) {
  docs.shifts[uuid()] = {
    v: 1, updatedAt: stamp(), deleted, jobId, dateEpochDay: epochDay(y, m, d), startMinute: start, endMinute: end,
    unpaidBreakMinutes: breakMin, hourlyRateGrosze: rate, note, externalUid: null, secondRate,
  };
}
function bonus(jobId, [y, m, d], amount, { note = null, wholeMonth = false, deleted = false } = {}) {
  docs.bonuses[uuid()] = {
    v: 1, updatedAt: stamp(), deleted, jobId, dateEpochDay: epochDay(y, m, wholeMonth ? 1 : d), amountGrosze: amount, note, wholeMonth,
  };
}
function payout(jobId, [y, m], amount, { received = null, note = null, deleted = false } = {}) {
  docs.payouts[uuid()] = {
    v: 1, updatedAt: stamp(), deleted, jobId, month: y * 100 + m, amountMinor: amount, note,
    receivedEpochDay: received ? epochDay(...received) : null,
  };
}

const night = { rateGrosze: 3700, fromMinute: minute(23), toMinute: minute(6) };

// A café paid by the hour.
const cafe = job('Kawiarnia „Bajka”', 3150, 0xff43a047);
// A warehouse with a night rate and 13.71 % deductions from September.
const warehouse = job('Magazyn nocny', 3300, 0xff1e88e5, {
  secondRate: night,
  payHistory: [
    { fromMonth: 0, payType: 'HOURLY', monthlyAmountMinor: 0, deductionBp: 0 },
    { fromMonth: 202609, payType: 'HOURLY', monthlyAmountMinor: 0, deductionBp: 1371 },
  ],
});
// An office job on a fixed monthly salary from October (hourly before), 22.71 % deductions.
const office = job('Biuro (umowa)', 3000, 0xff8e24aa, {
  payHistory: [
    { fromMonth: 0, payType: 'HOURLY', monthlyAmountMinor: 0, deductionBp: 0 },
    { fromMonth: 202610, payType: 'FIXED_MONTHLY', monthlyAmountMinor: 520000, deductionBp: 2271 },
  ],
});
// Translations paid in euro.
const translations = job('Переклади', 1800, 0xfffb8c00, { currency: 'EUR' });
// A job deleted long ago, with its deleted shift: never shown.
const old = job('Stara praca', 2500, 0xff6d4c41, { deleted: true });
shift(old, [2026, 9, 2], minute(9), minute(17), 2500, { deleted: true });

// August to mid-December 2026.
const months = [8, 9, 10, 11, 12];
for (const m of months) {
  const days = new Date(Date.UTC(2026, m, 0)).getUTCDate();
  for (let d = 1; d <= days; d++) {
    if (m === 12 && d > 12) break;
    const weekday = new Date(Date.UTC(2026, m - 1, d)).getUTCDay(); // 0 = Sunday
    if (weekday >= 1 && weekday <= 5 && random() < 0.55) {
      shift(cafe, [2026, m, d], minute(7), minute(15), 3150, { breakMin: random() < 0.6 ? 30 : 0 });
    }
    const special = m === 10 && (d === 24 || d === 31); // added by hand below
    if ((weekday === 5 || weekday === 6) && !special && random() < 0.8) {
      shift(warehouse, [2026, m, d], minute(22), minute(6), 3300, { breakMin: 30, secondRate: night });
    }
    if (m >= 10 && weekday >= 2 && weekday <= 4 && random() < 0.5) {
      shift(office, [2026, m, d], minute(9), minute(17), 3000, { breakMin: 60 });
    }
  }
}
// The nights the clocks change (Europe/Warsaw): counted as 8 h of wall-clock time, as the app does.
shift(warehouse, [2026, 10, 24], minute(22), minute(6), 3300, { breakMin: 30, secondRate: night, note: 'Zmiana czasu' });
// A night across the end of October.
shift(warehouse, [2026, 10, 31], minute(22), minute(6), 3300, { breakMin: 30, secondRate: night });
// A long shift with a note, and one deleted (never shown).
shift(cafe, [2026, 11, 7], minute(10), minute(20, 30), 3150, { breakMin: 45, note: 'Inwentaryzacja' });
shift(cafe, [2026, 11, 9], minute(7), minute(15), 3150, { deleted: true });
// Translations in euro: a few evenings.
shift(translations, [2026, 10, 6], minute(18), minute(21), 1800, { note: 'Umowa najmu' });
shift(translations, [2026, 10, 20], minute(18), minute(22, 15), 1800);
shift(translations, [2026, 11, 3], minute(19), minute(21, 30), 1800);
shift(translations, [2026, 11, 12], minute(17), minute(20), 1800, { note: 'Пилно' });

// Tips on a few days, and bonuses for the whole month.
bonus(cafe, [2026, 9, 18], 4500, { note: 'Napiwki' });
bonus(cafe, [2026, 10, 30], 6200, { note: 'Napiwki' });
bonus(cafe, [2026, 11, 13], 3800, { note: 'Napiwki' });
bonus(warehouse, [2026, 10, 1], 30000, { wholeMonth: true, note: 'Premia za frekwencję' });
bonus(warehouse, [2026, 11, 1], 30000, { wholeMonth: true, note: 'Premia za frekwencję' });
bonus(office, [2026, 11, 1], 50000, { wholeMonth: true, note: 'Premia kwartalna' });
bonus(cafe, [2026, 11, 2], 9900, { deleted: true });

// Salary that actually arrived: September and October, for the month they were earned for.
payout(cafe, [2026, 9], 160000, { received: [2026, 10, 10], note: 'Przelew' });
payout(warehouse, [2026, 10], 150000, { received: [2026, 10, 25], note: 'Zaliczka' });
payout(warehouse, [2026, 10], 182340, { received: [2026, 11, 10], note: 'Reszta' });
payout(office, [2026, 10], 401908, { received: [2026, 11, 5] });
payout(cafe, [2026, 10], 99999, { deleted: true });

writeFileSync(OUT, JSON.stringify(docs, null, 1) + '\n');
console.log(`Wrote ${OUT}: ${Object.entries(docs).map(([k, v]) => `${Object.keys(v).length} ${k}`).join(', ')}`);
