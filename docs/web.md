# Web version

`web/` is a website for the data the app syncs to the cloud: **Calendar**, **Summary** and **Jobs**, with the same
look (Material 3, the app's colours, light and dark) and the same numbers as the phone. It is in Ukrainian or
English, from the browser's language.

- **View first, edit when you choose.** Every visit starts read-only. **Enable editing** (above the screens) lets you
  add, change and delete **shifts, bonuses and received payments** until you close the tab. Jobs, rates and pay
  settings are only changed in the app. See [Editing](#editing).
- **Same numbers.** The calculations are ported from the app and checked against figures the app's own Kotlin code
  writes (`web/test/fixtures`, see [Updating the golden figures](#updating-the-golden-figures)).
- **Nothing kept in the browser.** The sign-in lasts while the tab is open (session storage) and the data lives
  only in the page's memory. **Sign out** forgets everything and reloads the page. Firebase's own SDK notes the
  day it was used (a date and the browser name, no account data) in an IndexedDB database of its own; sign-out
  deletes it.
- **Demo:** add `?demo` to the address (e.g. `https://your-project.web.app/?demo`). Made-up data, no sign-in, no
  network call. Editing works there too, but the changes stay on that page only.

The steps below are for Windows (PowerShell). On macOS or Linux the commands are the same, except `copy` →
`cp` and `notepad` → any editor.

## 1. Register the web app in Firebase (once)

Use the Firebase project the app already syncs with.

1. [Firebase console](https://console.firebase.google.com) → your project → **Project settings** (gear) →
   **General** → **Your apps** → **Add app** → the **Web** icon (`</>`).
2. Nickname: e.g. `Shift Tracker web`. Tick **Also set up Firebase Hosting for this app**, pick the default
   site, **Register app**. Skip the "Add Firebase SDK" and "Install Firebase CLI" pages (**Next**, **Continue to
   console**): the site already has the SDK, and the CLI is step 2 below.
3. Back in **Your apps**, select the web app → **SDK setup and configuration** → **Config**. You need four values
   from it: `apiKey`, `projectId`, `appId`, and your Hosting address `PROJECT_ID.web.app`.
4. Let Google send the sign-in back to that address: [Google Cloud console](https://console.cloud.google.com) →
   the same project → **APIs & Services** → **Credentials** → **OAuth 2.0 Client IDs** → **Web client (auto
   created by Google Service)** → **Authorized redirect URIs**. If `https://PROJECT_ID.web.app/__/auth/handler` is
   not in the list, **Add URI**, paste it, **Save** (it can take a few minutes). Leave everything else on that
   page as it is: the Android app's sign-in uses this client too, and adding an address doesn't affect it.

Nothing else changes in the console:

- **Sign-in**: Google sign-in is already on (the app uses it).
- **Authorized domains** (Authentication → Settings): `PROJECT_ID.web.app`, `PROJECT_ID.firebaseapp.com` and
  `localhost` are there by default.
- **Security rules**: the site reads with the same rules as the app, so only the Google account whose address is
  in your published rules can see anything. Don't change them for the site.

## 2. Install the tools (once)

1. **Node.js 24** (LTS, 24.12 or newer): [nodejs.org](https://nodejs.org) → the Windows Installer (`.msi`), all
   defaults. Check in a new PowerShell window:

   ```powershell
   node --version   # v24.12.0 or newer
   ```

2. **Firebase CLI:**

   ```powershell
   npm install -g firebase-tools
   firebase --version
   ```

   If PowerShell says running scripts is disabled (`npm.ps1` / `firebase.ps1 cannot be loaded`), allow your own
   scripts once: `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned`, answer `Y`, and open a new window.
   (Or type `npm.cmd` / `firebase.cmd` instead.)

3. **The repository**, if you don't have it yet: [Git for Windows](https://git-scm.com/download/win), then

   ```powershell
   git clone https://github.com/PoNcHiK08/Shift-Tracker-M3-.git
   cd Shift-Tracker-M3-
   ```

## 3. Settings file `web/.env.local` (once)

```powershell
cd web
copy .env.example .env.local
notepad .env.local
```

Fill in the values from step 1.3 (no quotes, no spaces) and save:

```ini
VITE_FIREBASE_API_KEY=the apiKey value
VITE_FIREBASE_AUTH_DOMAIN=PROJECT_ID.web.app
VITE_FIREBASE_PROJECT_ID=PROJECT_ID
VITE_FIREBASE_APP_ID=the appId value
```

- **`AUTH_DOMAIN` is your `.web.app` address**, not the `authDomain` shown in the console
  (`PROJECT_ID.firebaseapp.com`). The sign-in then runs on the site's own domain, so it also works in browsers
  that block third-party storage (Safari, Firefox, Chrome in incognito).
- `.env.local` is in `.gitignore`: **never commit it**. These values aren't secret (they end up in the site's
  JavaScript for anyone to see; your Firestore rules are what protects the data), but the repository is public
  and has no reason to carry them.
- Without `.env.local` the site still builds; it then only offers the demo.

## 4. Build and publish

```powershell
cd web                 # from the repository root
npm ci                 # first time, and after pulling changes to package-lock.json
npm run build          # checks the types and writes the site to web\dist
firebase login         # once per computer: a browser window opens, sign in with the project's Google account
firebase deploy --only hosting --project PROJECT_ID
```

The last command prints `Hosting URL: https://PROJECT_ID.web.app`. Open it, **Sign in with Google** with the
account the app syncs with.

Run it **in `web\`**: the site's Hosting settings are `web\firebase.json`, which has nothing but Hosting in it.

> **Never run `firebase deploy` in `firebase\`.** That folder's `firebase.json` is for the rules: a deploy there
> would publish the committed `firestore.rules`, whose placeholder address lets nobody in, and the app on your
> phone would be refused until you publish your real rules again (README → Security rules). If that happens,
> nothing is lost: republish the rules with your address in the console.

### Publishing a newer version (redeploy)

Whenever the site changes (for example after editing was added), in PowerShell:

```powershell
cd $HOME\Shift-Tracker-web      # your clone
git checkout claude/link-review-1f6y6c
git pull
cd web
npm ci                           # the dependencies may have changed
npm run build
firebase deploy --only hosting --project PROJECT_ID
```

Your `web\.env.local` stays as it is (git doesn't touch it). Open the site again (Ctrl+F5 if the old version still
shows). Firebase keeps earlier versions: **Hosting** → **Release history** → ⋮ → **Rollback**.

### Trying it on your computer first

```powershell
cd web
npm run dev
```

Open <http://localhost:5173> (signed in, with your `.env.local`) or <http://localhost:5173/?demo>. Stop with
Ctrl+C. Signing in locally goes through `PROJECT_ID.web.app`, so it works once the site has been deployed (step 4). `npm run preview` serves the built `web\dist` at <http://localhost:4173> instead.

## 5. Optional: restrict the API key to your site

The web `apiKey` can only do what your Firestore rules allow, so this is extra: it stops other websites from
using your key against your project's quotas.

1. [Google Cloud console](https://console.cloud.google.com) → pick the same project → **APIs & Services** →
   **Credentials** → **API keys**.
2. Open the key whose value is your `VITE_FIREBASE_API_KEY` (normally **Browser key (auto created by
   Firebase)**). **Don't touch the Android key**: the app needs it as it is. If the web config shows the same key
   as the Android app, skip this section.
3. **Application restrictions** → **Websites** → add:
   - `https://PROJECT_ID.web.app/*`
   - `https://PROJECT_ID.firebaseapp.com/*`
   - `http://localhost:5173/*` and `http://localhost:4173/*` (only if you run the site locally)
4. Leave **API restrictions** as they are, **Save**. It takes up to 5 minutes; then check that sign-in and the
   data still work on the site.

## 6. Optional: your own domain

1. Firebase console → **Hosting** → **Add custom domain**, follow its DNS steps, wait for the certificate.
2. **Authentication** → **Settings** → **Authorized domains** → **Add domain** → your domain.
3. In `web\.env.local` set `VITE_FIREBASE_AUTH_DOMAIN=` your domain, then build and deploy again (step 4).
4. Google Cloud console → **APIs & Services** → **Credentials** → **OAuth 2.0 Client IDs** → **Web client (auto
   created by Google Service)** → **Authorized redirect URIs** → add `https://YOUR.DOMAIN/__/auth/handler` →
   **Save**.
5. If you restricted the API key (section 5), add `https://YOUR.DOMAIN/*` there too.

The `.web.app` address keeps working alongside.

## Editing

**What can be edited:** shifts, bonuses (on a day or for the whole month) and received payments: add, change,
delete, and Undo a delete. **Not here:** jobs, rates, pay settings, bulk changes ("apply the current rate to all
shifts"), imports and backups; those stay in the app.

**Turning it on.** Every visit starts with editing off: nothing on the screens can change anything. Above the
screens, **Enable editing** turns it on for this tab until you close or reload it; it is never remembered. Next to
it, **Download cloud snapshot** saves a JSON copy of every document the site has read. Save one before your first
edits: it is a copy for safekeeping, **not a backup the app can import** (the app's own backups are in the app:
Settings → Data).

**Where:**
- Calendar → a day: **Add shift**, **Bonus**, or tap a shift or bonus to change or delete it.
- Calendar → **Month bonuses** row: open a bonus, or **Add a bonus for the month**.
- Summary → Month: the month bonuses (open, or add one) and **Received pay** (**Add payment** under a job, or tap a
  payment).

**How it stays in step with the phone:**
- The site writes each record exactly as the app writes it (same fields, same limits, same rules for night shifts,
  breaks, amounts, whole-month bonuses), into the same cloud documents. The phone takes the change on its next sync
  (**Sync now**, opening the app, or its own changes), and doesn't send it back.
- **Nothing is overwritten blindly.** Saving checks the cloud's current version first. If the record changed on
  another device (or in another tab) since you opened it, the site doesn't save and says "This changed on another
  device. Reload to see the latest version." **Reload** shows the latest data; open the record again.
- **Deleting** leaves a hidden marker (as the app does), so the deletion reaches the phone too; **Undo** in the
  message at the bottom puts the record back. Undo only works while nobody changed the record since.
- **Unsaved changes:** closing a form (×, Cancel, Esc) or the tab with changes asks first.
- **Your computer's clock** counts as the time of a change, as the phone's does. The cloud refuses a change from a
  clock more than 10 minutes ahead; the site says so. Set the date and time automatically.
- The Firestore rules don't change for this: they already allow exactly these writes, and only for your account.

**If a save is refused:**
- "This changed on another device": press **Reload**, open the record again, redo your change.
- "The cloud refused the change": check the computer's date and time, and that the published rules have your address.
- "This record was saved by a newer version of the app": update the app; change it there.

## If something goes wrong

- **"This site was built without its Firebase settings"**: `web\.env.local` is missing or has an empty value
  when you ran `npm run build`. Fix it and build and deploy again.
- **Sign-in: `unauthorized-domain`**: the address you opened isn't in Authentication → Settings → Authorized
  domains (a custom domain needs section 6).
- **Google says `redirect_uri_mismatch`**: the redirect address is missing (step 1.4, or 6.4 for your own domain).
- **Sign-in: `popup-blocked`**: the site then signs in by going to Google and back. If it still fails, allow
  pop-ups for the site.
- **"The cloud refused access"**: signed in with another Google account than the one in your published rules, or
  the rules aren't published. Sign out and in with the right account.
- **Some records weren't shown** (a note under the screen title): documents the app itself couldn't use either
  (written by a newer app version, or damaged). Update the app; the phone shows the same.
- **The numbers differ from the phone**: refresh (the ↻ button; the site also re-reads when you come back to the
  tab, at most once a minute). The phone must have synced (Settings → Account & sync → **Sync now**). Check that
  the same Gross/Net choice is shown. Still different: please report the month and what each side shows.

## Checking the web against the phone

Pick two months, one with received pay and one with a bonus for the whole month. For each, on the phone and on
the site, compare:

- **Calendar**: the month tile (earned, hours, shifts, and the "Received" tag); three days with shifts (day total
  and each card); the **Month bonuses** row.
- **Summary → Month**: the total, each job's amount, **Month bonuses**, **Received pay** (calculated, received,
  difference). Then **Week** for a week that crosses into the next month (no received pay, no whole-month bonuses
  in it) and **Year**.
- With deductions set on some job, switch **Net** on the site and the earnings mode in the app's settings, and
  compare again.

## Notes and limits

- **Today** is your computer's date. The demo's today is fixed (13 November 2026).
- **An empty period** shows 0 in the first job's currency (the phone uses the currency of the job you added last,
  which the cloud doesn't record).
- **Same-day order**: two shifts that start at the same minute, or two bonuses on one day, can be listed in a
  different order than on the phone. Totals are the same.
- **Same-moment edits**: when the phone and the site change the same record before either syncs, the change made
  later (by each device's clock) wins whole, as between two phones.
- **Not live**: the site reads when opened, on ↻ and when you come back to the tab (at most once a minute). Each
  read after the first fetches only what changed (one Firestore read per changed record, plus one per
  collection).
- **Firestore "lite"**: the site uses Firebase's lightweight Firestore build, which has no cache and no
  offline storage at all (stricter than an in-memory cache).

## For developers

```powershell
cd web
npx playwright install chromium   # once: the browser the screen tests run in
npm test                          # unit tests (Node) and screen tests (Chromium)
npm run test:emulator             # the write layer on the Firestore emulator with firebase\firestore.rules (needs Java 21)
npm run build
npm run screenshots               # demo screenshots into web\screenshots (ignored by git); needs a build first
npm run strings                   # copy the app's texts from app\src\main\res after they change
```

- Layout: `src/domain` (calculations ported from the app), `src/data` (reading and checking the cloud documents,
  sign-in; `cloud.ts` and `writes.ts` are the only files that import Firebase; `writes.ts` is the only place that
  writes), `src/edit` (the documents the site writes and the editors' rules), `src/editors`, `src/screens`, `src/ui`,
  `src/i18n`.
- Writes and the app: `app/src/test/.../web/WebWritesCompatTest.kt` runs what the site writes through the app's own
  sync engine (and the reverse); its fixtures are `web/test/fixtures/writes/` (`UPDATE_WEB_WRITES=1 npm test` for the
  web's, `UPDATE_WEB_FIXTURES=1` on the Kotlin test for the app's).
- CI: `.github/workflows/web-ci.yml` installs, tests (including the emulator tests) and builds `web/` (no deploy,
  no secrets).

### Updating the golden figures

`web/test/fixtures` holds what the app computes for the demo data, edge cases and a random year. They are written
by `app/src/test/java/com/vkurylo/shifttracker/web/WebFixturesExport.kt`, which runs the app's own code (the sync
engine's download, the Calendar and Summary view models). By default that test only checks the files; the full
`./gradlew testDebugUnitTest` fails when they are out of date.

After a change to the app's calculations or document format, or to the demo (`node scripts/make-demo.mjs`):

```powershell
# from the repository root
$env:UPDATE_WEB_FIXTURES = "1"; .\gradlew.bat testDebugUnitTest --tests "*WebFixturesExport*"; Remove-Item Env:UPDATE_WEB_FIXTURES
cd web; npm test
```

(macOS/Linux: `UPDATE_WEB_FIXTURES=1 ./gradlew testDebugUnitTest --tests '*WebFixturesExport*'`.) Then port the
change to `web/src/domain` until `npm test` passes, and commit the fixtures with it.
