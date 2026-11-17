import documents from './demo-documents.json';
import type { CloudDocuments } from '../data/documents-types';

/**
 * Demo mode (`?demo`): made-up data in the app's cloud document shape, with no sign-in and no Firebase call at all.
 * Its "today" is fixed, so screenshots and tests always show the same month.
 */
export const DEMO_TODAY = '2026-11-13';

export function isDemo(search: string = typeof location === 'undefined' ? '' : location.search): boolean {
  return new URLSearchParams(search).has('demo');
}

export function demoDocuments(): CloudDocuments {
  return documents as unknown as CloudDocuments;
}
