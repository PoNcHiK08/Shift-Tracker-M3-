import { describe, expect, test } from 'vitest';
import { demoDocuments, isDemo, DEMO_TODAY } from '../../src/demo/demo';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

describe('demo data', () => {
  const docs = demoDocuments();

  test('opens with ?demo only', () => {
    expect(isDemo('?demo')).toBe(true);
    expect(isDemo('?x=1&demo')).toBe(true);
    expect(isDemo('')).toBe(false);
    expect(isDemo('?demonstration')).toBe(false);
  });

  test('every document has a sync id the cloud accepts and refers to a job of the data', () => {
    for (const collection of ['jobs', 'shifts', 'bonuses', 'payouts'] as const) {
      for (const [id, doc] of Object.entries(docs[collection])) {
        expect(id).toMatch(UUID);
        expect(doc['v']).toBe(1);
        if (collection !== 'jobs') expect(Object.keys(docs.jobs)).toContain(doc['jobId']);
      }
    }
  });

  test('has what the screens show: several jobs and currencies, month bonuses, payouts, deleted records', () => {
    const jobs = Object.values(docs.jobs);
    expect(new Set(jobs.map((j) => j['currency']))).toEqual(new Set(['PLN', 'EUR']));
    expect(Object.values(docs.bonuses).some((b) => b['wholeMonth'] === true)).toBe(true);
    expect(Object.keys(docs.payouts).length).toBeGreaterThan(2);
    for (const collection of ['jobs', 'shifts', 'bonuses', 'payouts'] as const) {
      expect(Object.values(docs[collection]).some((d) => d['deleted'] === true), collection).toBe(true);
    }
    expect(DEMO_TODAY).toBe('2026-11-13');
  });
});
