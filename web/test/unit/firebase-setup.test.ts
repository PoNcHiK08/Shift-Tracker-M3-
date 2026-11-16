import { beforeEach, describe, expect, test, vi } from 'vitest';

/*
 * How the site sets Firebase up (data/cloud.ts), with Firebase replaced by stand-ins: the sign-in lasts only while
 * the tab is open, Firestore keeps no cache, and a blocked popup falls back to a redirect.
 */

const calls = vi.hoisted(() => ({
  initializeApp: [] as unknown[][],
  initializeAuth: [] as unknown[][],
  initializeFirestore: [] as unknown[][],
  popup: 0,
  redirect: 0,
  signOut: 0,
  popupError: null as unknown,
}));

vi.mock('firebase/app', () => ({
  initializeApp: (...args: unknown[]) => (calls.initializeApp.push(args), { name: 'app' }),
}));

vi.mock('firebase/auth', () => ({
  browserSessionPersistence: { type: 'SESSION' },
  browserLocalPersistence: { type: 'LOCAL' },
  indexedDBLocalPersistence: { type: 'LOCAL-IDB' },
  browserPopupRedirectResolver: { resolver: true },
  initializeAuth: (...args: unknown[]) => (calls.initializeAuth.push(args), { name: 'auth' }),
  GoogleAuthProvider: class {
    setCustomParameters() {}
  },
  getRedirectResult: async () => null,
  onAuthStateChanged: () => () => {},
  signInWithPopup: async () => {
    calls.popup++;
    if (calls.popupError) throw calls.popupError;
  },
  signInWithRedirect: async () => {
    calls.redirect++;
  },
  signOut: async () => {
    calls.signOut++;
  },
}));

vi.mock('firebase/firestore/lite', () => ({
  initializeFirestore: (...args: unknown[]) => (calls.initializeFirestore.push(args), { name: 'db' }),
  Timestamp: class {},
}));

const SETTINGS = { apiKey: 'k', authDomain: 'example.web.app', projectId: 'p', appId: 'a' };

beforeEach(() => {
  calls.initializeApp.length = 0;
  calls.initializeAuth.length = 0;
  calls.initializeFirestore.length = 0;
  calls.popup = calls.redirect = calls.signOut = 0;
  calls.popupError = null;
});

describe('Firebase setup', () => {
  test('sign-in is kept for this tab only; Firestore is the lite build, with no cache', async () => {
    const { connect } = await import('../../src/data/cloud');
    connect(SETTINGS);
    expect(calls.initializeApp[0]).toEqual([SETTINGS, { automaticDataCollectionEnabled: false }]);
    const [, authOptions] = calls.initializeAuth[0] as [unknown, { persistence: unknown }];
    expect(authOptions.persistence).toEqual({ type: 'SESSION' });
    expect(calls.initializeFirestore).toHaveLength(1);
  });

  test('a blocked popup falls back to a redirect; a closed one is no error', async () => {
    const { connect, FirebaseBackend } = await import('../../src/data/cloud');
    const backend = new FirebaseBackend(connect(SETTINGS));
    await backend.signIn();
    expect([calls.popup, calls.redirect]).toEqual([1, 0]);

    calls.popupError = { code: 'auth/popup-blocked' };
    await backend.signIn();
    expect([calls.popup, calls.redirect]).toEqual([2, 1]);

    calls.popupError = { code: 'auth/popup-closed-by-user' };
    await expect(backend.signIn()).resolves.toBeUndefined();
    expect(calls.redirect).toBe(1);

    calls.popupError = { code: 'auth/unauthorized-domain' };
    await expect(backend.signIn()).rejects.toEqual({ code: 'auth/unauthorized-domain' });
  });

  test('settings come from VITE_FIREBASE_*; any one missing means not configured', async () => {
    const { firebaseSettings } = await import('../../src/data/firebase-config');
    const env = {
      VITE_FIREBASE_API_KEY: 'k', VITE_FIREBASE_AUTH_DOMAIN: 'example.web.app', VITE_FIREBASE_PROJECT_ID: 'p', VITE_FIREBASE_APP_ID: 'a',
    };
    expect(firebaseSettings(env)).toEqual(SETTINGS);
    expect(firebaseSettings({ ...env, VITE_FIREBASE_APP_ID: ' ' })).toBeNull();
    expect(firebaseSettings({})).toBeNull();
  });
});
