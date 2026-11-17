/**
 * The Firebase Web app's settings, from web/.env.local at build time (VITE_FIREBASE_*; see .env.example and
 * docs/web.md). They identify the project, they are not secrets: Firestore's rules decide who reads what. The
 * real values are never committed. Without them the site only offers the demo.
 */
export interface FirebaseSettings {
  apiKey: string;
  authDomain: string;
  projectId: string;
  appId: string;
}

type Env = Partial<Record<string, string | boolean | undefined>>;

export function firebaseSettings(env: Env = import.meta.env): FirebaseSettings | null {
  const value = (name: string) => {
    const v = env[name];
    return typeof v === 'string' ? v.trim() : '';
  };
  const settings = {
    apiKey: value('VITE_FIREBASE_API_KEY'),
    authDomain: value('VITE_FIREBASE_AUTH_DOMAIN'),
    projectId: value('VITE_FIREBASE_PROJECT_ID'),
    appId: value('VITE_FIREBASE_APP_ID'),
  };
  return Object.values(settings).every((v) => v !== '') ? settings : null;
}
