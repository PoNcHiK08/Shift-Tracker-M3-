# Releasing on Google Play

What the code already does for a Play release, and the checklist of what you do by hand, in order: Firebase, the
policy pages, then the Play Console up to a closed test. UI names are the English ones.

## What is ready in the code

- **Each user only sees their own data.** `firebase/firestore.rules` lets anyone signed in with Google (verified
  email) read and write `users/{their uid}` and nothing else. No list of addresses; publish the file as it is.
- **Delete account in the app** (Settings → Account & sync → Delete account): deletes the account's cloud data, then
  the Firebase account, and signs out; the phone keeps its data.
- **Policy pages** on the web app's Firebase Hosting: `/privacy` and `/delete-account`, in Ukrainian, English and
  Polish (`?lang=uk|en|pl`; without it, the browser's language). Settings → About links to both, in the app's
  language.
- **Play bundle (AAB)** from CI, signed with your release key (the Play upload key), next to the APKs.
- **Languages:** Ukrainian, English, Polish. The first launch uses the phone's language when it is one of these,
  otherwise English.

## Build and find the AAB

**CI (normal way).** Every run of **Build APK** (any push or pull request) builds
`ShiftTracker-<versionCode>.aab`, as long as the `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`,
`SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD` secrets are set. Without them the run warns "this build has no Play
bundle": the public dev key must never sign anything for Play.

1. GitHub → **Actions → Build APK** → the run (normally the newest one on the default branch).
2. **Artifacts → ShiftTracker-&lt;versionCode&gt;** → download and unzip.
3. Upload `ShiftTracker-<versionCode>.aab` to Play. (`ShiftTracker.apk` is the same build for installing by hand.)

The artifact is kept for 30 days. The `latest-build` GitHub release holds only the APKs.

**versionCode** is the workflow's run number + 100 (the run number shows in the run's title, e.g. "#240"). It goes
up with every run, on any branch, so each new run's AAB has a higher versionCode than every earlier one, which is
what Play requires. Two things to know:
- Play refuses a versionCode it already has. A **re-run** keeps its run number, so after uploading, use a **new** run
  for the next upload (push a commit, or *Run workflow* on the Build APK page).
- **Renaming `.github/workflows/build-apk.yml`** restarts the run number at 1. If you ever do, raise the `+ 100` in
  that file above the last versionCode you uploaded.

**Locally (only if you must).** Without `VERSION_CODE` a local build has versionCode 1, which Play refuses after
your first upload, so pass one higher than the last upload:

```bash
SIGNING_KEYSTORE_PATH=/path/to/release.keystore SIGNING_STORE_PASSWORD=… SIGNING_KEY_ALIAS=… \
SIGNING_KEY_PASSWORD=… VERSION_CODE=1000 ./gradlew bundleRelease
# → app/build/outputs/bundle/release/app-release.aab
```

A local build has cloud sync only with your `app/google-services.json` in place.

## Checklist (by hand, in this order)

### 1. Firebase: publish the rules

Firebase console → **Firestore Database → Rules** → paste the whole `firebase/firestore.rules` → **Publish**. (Or, in
`firebase/`: `firebase deploy --only firestore:rules --project PROJECT_ID`.)

Do this **before** testers install the app: until then, the cloud only lets in the address the old rules named. Your
own data stays where it is (`users/{your uid}`); nothing moves.

### 2. Google sign-in for everyone

Google Cloud console (same project) → **Google Auth Platform → Audience** (older name: *APIs & Services → OAuth
consent screen*): the publishing status must be **In production**. In *Testing*, only the listed test users can sign
in. The app asks only for the basic profile (email), so production needs no Google verification. Under **Branding**,
set the app name, a support email, and the privacy policy link from step 3.

### 3. Policy pages: contact address, then deploy

1. Choose the contact address testers and Play will see (a dedicated one is best; Play shows it publicly anyway).
2. Replace `CONTACT_EMAIL_PLACEHOLDER` in `web/public/privacy.html` and `web/public/delete-account.html`:
   ```bash
   sed -i 's/CONTACT_EMAIL_PLACEHOLDER/support@example.com/g' web/public/privacy.html web/public/delete-account.html
   ```
   (This is a public repository: commit it only if you're happy for the address to be public, which it will be on
   Play anyway.)
3. Deploy the site from `web/` (docs/web.md has the first-time setup):
   ```bash
   cd web && npm ci && npm run build && firebase deploy --only hosting --project PROJECT_ID
   ```
4. Open `https://PROJECT_ID.web.app/privacy` and `https://PROJECT_ID.web.app/delete-account` and check all three
   languages.

The app's About links point at `https://<project_id>.web.app`, taken from `google-services.json` at build time. For
your own domain, set a repository **variable** (not a secret) `SITE_URL` = `https://your.domain`: the Build APK
workflow passes it to the build.

### 4. Play Console: create the app and upload the first AAB

1. **Create app**: name *Shift Tracker* (or the new name: the app's own name is `app_name` in
   `app/src/main/res/values/strings.xml`), default language, App, Free, accept the declarations.
2. **Test and release → Testing → Closed testing** → create a track (e.g. *Testers*) → **Create release**.
3. **Play App Signing**: accept the default (Google keeps the app signing key). Your CI release key becomes the
   **upload key** when you upload the first AAB.
4. Upload `ShiftTracker-<versionCode>.aab`, add release notes (uk, en, pl), save. Don't roll out yet: finish steps 5–7
   first.

### 5. Firebase: the Play signing key's fingerprints

Apps installed from Play are signed by Google with the **app signing key**, not your key. Google sign-in in those
apps fails ("Google refused the sign-in… SHA-1") until Firebase knows that key.

1. Play Console → **Test and release → App integrity → App signing**: copy the **App signing key certificate**
   SHA-1 and SHA-256. (Your upload key's fingerprints are listed there too; Firebase already has them if your CI
   builds sign in today.)
2. Firebase console → **Project settings → Your apps → com.vkurylo.shifttracker → Add fingerprint**: add both.
3. Download `google-services.json` again and update the `GOOGLE_SERVICES_JSON` secret, so the file in CI builds
   matches (sign-in itself checks the fingerprints in Firebase).

### 6. Play Console: App content

**Policy → App content**, each item:

| Item | Answer |
| --- | --- |
| Privacy policy | `https://PROJECT_ID.web.app/privacy` |
| App access | *All functionality is available without special access*. Note for review: "The app works fully without an account. Optional cloud sync: Settings → Account & sync → Sign in with Google, with any Google account." |
| Ads | No, the app contains no ads |
| Content rating | Questionnaire: category *Utility, productivity, communication or other*; no violence, sexuality, language, controlled substances, gambling, user interaction or sharing of location |
| Target audience | 18 and over (it's for working adults; don't select any age under 13) |
| News app | No |
| Financial features | *My app doesn't provide any financial features* (it records pay; it moves no money) |
| Health apps | No |
| Government apps | No |
| Advertising ID | No (the app doesn't use it; the merged manifest has no `AD_ID` permission) |
| Data safety | See below |

**Data safety** (answers for this app as it is now):

- *Does your app collect or share any of the required user data types?* **Yes** (only when the user turns on sync).
- *Is all of the user data collected by your app encrypted in transit?* **Yes.**
- *Which account creation methods?* Sign in with Google (an "OAuth" option), and it is optional.
- *Do you provide a way for users to request that their data is deleted?* **Yes**, with the URL
  `https://PROJECT_ID.web.app/delete-account`.
- **Data types collected** (for each: *Collected*, **not** shared, **optional** for the user, purposes **App
  functionality** and **Account management**, not processed ephemerally):
  - Personal info → **Email address** (the Google account's, in Firebase Authentication).
  - Personal info → **Name** (Google sign-in hands Firebase the name with the account; the app doesn't use it, but
    Firebase stores it, so declare it).
  - Financial info → **Other financial info** (pay rates, earnings, bonuses, received payments).
  - App activity → **Other user-generated content** (jobs, shifts and their notes).
- **Not collected:** location, contacts, photos, files, device IDs for ads, crash logs or diagnostics (the crash
  report stays on the phone; the user shares it themselves if they want). Shared with third parties: **nothing**.
- Check Google's per-SDK list for Firebase Authentication and Cloud Firestore before you submit, in case it changed:
  <https://firebase.google.com/docs/android/play-data-disclosure>.

### 7. Play Console: store listing

**Grow users → Store presence → Main store listing**, in Ukrainian, English and Polish: short description (80
characters), full description, app icon 512×512, feature graphic 1024×500, at least 2 phone screenshots. Contact
details: the address from step 3. Screenshots: use sample data (e.g. "Cafe", "Office"), not your real jobs.

### 8. Closed test

1. Closed testing track → **Testers**: add a Google Group or an email list with your 15–20 testers; copy the
   **opt-in link** and send it to them. They join with the Google account they use for Play.
2. Back on the release from step 4: **Review release → Start rollout to Closed testing**. Google reviews it first
   (often a few days for a new app).
3. Testers open the opt-in link, accept, and install from Play.

New personal developer accounts must run a closed test with **at least 12 testers who stay opted in for 14 days in
a row** before they can apply for production (check the current numbers on your Play Console dashboard). Keep the
testers in for the whole 14 days.

### 9. Before production

- Firestore usage: the free Spark plan allows 50,000 reads and 20,000 writes a day for the whole project. The app
  limits automatic writes per phone per day; with 20 testers this is far below the limit. Firebase console →
  **Usage and billing** shows it.
- Watch **Quality → Android vitals** for crashes and ANRs from testers.
- Deleting an account by email (`/delete-account` "Without the app"): find the user in Firebase console →
  **Authentication → Users** by email, delete their `users/{uid}` document and its `jobs`, `shifts`, `bonuses` and
  `payouts` subcollections in **Firestore**, then delete the user in Authentication. Reply within 30 days.
- Apply for production from the Play Console dashboard when it offers it.

## Updating later

- New version: a new CI run → its AAB → upload to the track (versionCode is higher by itself).
- New rules (`firestore.rules` changed): publish them **before** the release that needs them reaches phones.
- Policy changes: edit `web/public/privacy.html` (all three languages and the "Last updated" date), deploy hosting.
- Renaming the app: `app_name` in `values/strings.xml`, the pages' `<title>` and text, and the Play listing.
