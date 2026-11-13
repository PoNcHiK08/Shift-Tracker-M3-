import { defineConfig } from 'vite';

export default defineConfig({
  build: {
    target: 'es2022',
    // One chunk for the app, one for Firebase: the demo never loads Firebase at all.
    chunkSizeWarningLimit: 900,
  },
});
