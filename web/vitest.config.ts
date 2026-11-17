import { defineConfig } from 'vitest/config';
import { playwright } from '@vitest/browser-playwright';

export default defineConfig({
  test: {
    projects: [
      {
        // Pure logic (calculations, parsing, golden fixtures): Node, fast.
        test: {
          name: 'unit',
          include: ['test/unit/**/*.test.ts'],
          environment: 'node',
        },
      },
      {
        // The write layer against the Firestore emulator and the real firebase/firestore.rules
        // (npm run test:emulator starts the emulator; needs Java 21).
        test: {
          name: 'emulator',
          include: ['test/emulator/**/*.test.ts'],
          environment: 'node',
          testTimeout: 30_000,
          fileParallelism: false,
        },
      },
      {
        // Screens with the real Material Web components, in Chromium.
        test: {
          name: 'browser',
          include: ['test/browser/**/*.test.ts'],
          browser: {
            enabled: true,
            headless: true,
            provider: playwright(),
            instances: [{ browser: 'chromium' }],
            screenshotFailures: false,
          },
        },
      },
    ],
  },
});
