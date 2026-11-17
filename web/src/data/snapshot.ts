import type { CloudDocuments } from './documents-types';

/*
 * "Download cloud snapshot": every document this page has read, as JSON, for safekeeping before editing. It is a copy
 * of the cloud documents as they are (tombstones included), NOT a backup the app can import: the app's backups are
 * made and restored in the app. Nothing is stored anywhere; the browser saves the file where the user says.
 */

/**
 * Firestore timestamps (serverUpdatedAt) as ISO text; everything else as it is. The value before its toJSON() ran is
 * the holder's [key] (JSON.stringify calls toJSON first, and a Timestamp has one).
 */
function plain(this: Record<string, unknown>, key: string, value: unknown): unknown {
  const raw = this[key];
  if (raw && typeof raw === 'object' && typeof (raw as { toDate?: unknown }).toDate === 'function') {
    return (raw as { toDate: () => Date }).toDate().toISOString();
  }
  if (typeof value === 'bigint') return value.toString();
  return value;
}

export function snapshotJson(documents: CloudDocuments, takenAt: Date): string {
  const counts = Object.fromEntries(Object.entries(documents).map(([name, docs]) => [name, Object.keys(docs).length]));
  return (
    JSON.stringify(
      {
        about:
          'Shift Tracker cloud snapshot: a copy of the documents in your cloud account as the website read them, for safekeeping. ' +
          'Not an importable backup: the app makes and restores its own backups (Settings -> Data -> Export data).',
        takenAt: takenAt.toISOString(),
        counts,
        documents,
      },
      plain,
      1,
    ) + '\n'
  );
}

export function snapshotFileName(takenAt: Date): string {
  const p = (n: number) => String(n).padStart(2, '0');
  return `shift-tracker-cloud-snapshot-${takenAt.getFullYear()}-${p(takenAt.getMonth() + 1)}-${p(takenAt.getDate())}.json`;
}

/** Hands the snapshot to the browser as a file to save. */
export function downloadSnapshot(documents: CloudDocuments, takenAt: Date = new Date()): void {
  const blob = new Blob([snapshotJson(documents, takenAt)], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = snapshotFileName(takenAt);
  link.rel = 'noopener';
  document.body.append(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}
