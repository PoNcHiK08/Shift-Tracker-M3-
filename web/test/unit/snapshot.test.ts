import { expect, test } from 'vitest';
import { snapshotFileName, snapshotJson } from '../../src/data/snapshot';
import { emptyDocuments } from '../../src/data/documents-types';

/** The cloud snapshot: every document as read, timestamps as ISO text, clearly not an importable backup. */
test('the snapshot holds every document, with server timestamps as text', () => {
  const documents = emptyDocuments();
  const stamp = { seconds: 1, nanoseconds: 0, toDate: () => new Date('2026-11-17T08:00:00Z'), toJSON: () => ({ seconds: 1 }) };
  documents.shifts['a'] = { v: 1, deleted: true, serverUpdatedAt: stamp };
  documents.jobs['j'] = { v: 1, name: 'Kawiarnia' };
  const json = JSON.parse(snapshotJson(documents, new Date('2026-11-17T09:30:00Z')));
  expect(json.about).toMatch(/Not an importable backup/);
  expect(json.takenAt).toBe('2026-11-17T09:30:00.000Z');
  expect(json.counts).toEqual({ jobs: 1, shifts: 1, bonuses: 0, payouts: 0 });
  expect(json.documents.shifts.a).toEqual({ v: 1, deleted: true, serverUpdatedAt: '2026-11-17T08:00:00.000Z' });
  expect(snapshotFileName(new Date(2026, 10, 7, 12))).toBe('shift-tracker-cloud-snapshot-2026-11-07.json');
});
