# Shift Tracker

Offline Android app for tracking work shifts and earnings across several jobs.
Started for part-time work in Poland (PLN by default, week starts on **Monday**), now usable anywhere: each job has its
own currency, is paid hourly or a fixed monthly salary, and earnings can be shown gross or net.
UI in **Ukrainian** by default with **English** as an alternative.

100% native: Kotlin, Jetpack Compose, Material 3 — no WebViews, Flutter or React Native.

## Features

- **Jobs** — name, color from a palette, **currency** and **pay**. Deleting a job asks first and shows how many shifts go with it.
  - **Currency** per job (ISO 4217, searchable list with PLN, UAH, EUR, USD, GBP, CZK on top; a new job starts with the
    latest job's currency). Decimals follow the currency (0 for JPY, 3 for KWD) in input, limits and display. Shifts and
    bonuses are in their job's currency, and it is locked once the job has entries, so old amounts are never
    re-denominated. Amounts in different currencies are **never added up**: totals are shown per currency (with one
    currency everything looks as before).
  - **Pay type**: *hourly* (rate plus an optional **second rate for a time window**, e.g. 33 zł/h base and 37 zł/h from
    23:00 to 06:00) or a *fixed monthly salary*. A salaried job's shifts log hours only; its salary counts once per
    month, for every month from its first to its last month with a shift or bonus (a month of leave in between still
    counts). The week view shows only hours for salaried jobs and says why.
  - **Deductions**: an optional percentage per job (e.g. 13.71 %), applied to shift pay, salary and bonuses alike
    (net = gross − deduction, rounded half-up in minor units). A plain number you enter; the app doesn't compute taxes.
  - Pay changes never rewrite the past: a job with entries changes pay type, salary or deductions *from a chosen month*
    (any month, default the current one), and earlier months keep theirs (hourly rates are also saved with each shift).
- **Shifts** — job, date, start/end time (overnight shifts supported), unpaid break, note.
  The time dial gives a light haptic tick for each hour or minute your finger selects (at most one per frame) and a
  confirm click on OK; opening the dial or changing the time from code stays silent. The phone's touch-feedback /
  vibration settings apply.
  Minutes inside the second-rate window are paid at that rate, the rest at the base rate; the unpaid break is taken
  from both parts proportionally. Rates are *saved with the shift*, so changing a job's rates later never rewrites past
  earnings (the editor offers "use current rate" when they differ).
- **Bonuses** — one-off extra payments for a job on a given day (premium, tips…). They count towards earnings in the
  calendar and summary, but not towards hours or the average hourly rate.
- **Calendar** — swipeable month grid with colored dots per job. Tapping a day morphs its cell from a circle into an
  M3 Expressive squircle and shows that day in a Bento panel right under the grid (shift/bonus cards, day total,
  "Add shift" / "Bonus"), so you can tap through days without opening and closing a sheet. The month's earnings,
  hours and shifts are the first thing on screen: one compact connected row right under the month name
  (all three share one height).
  Under the finger every day morphs into the squircle too.
- **Summary** — week / month / year totals (hours, earnings, average rate) with a per-job breakdown,
  plus per-day bars for a week and per-month bars for a year. Paging slides the period label in the direction of travel.
- **Home-screen widget** (Jetpack Glance, Material 3) — a shift calendar and the next shift, no money on the home
  screen: the month grid (Monday first, today filled, shift days tinted with their job's color) or, one row tall, the
  current week; the shift in progress ("Until 06:00", overnight shifts included) or the next one within 60 days; and
  "+ Shift". Resizable; the layout follows both width and height (drawn for the exact sizes the launcher reports):
  2×1 next shift and "+", 3×1/4×1 one bar (this week | next shift | "+"), 2×2/2×3 a column (month, next shift when
  there's room, full-width "+ Shift"), 3×2 and up the month next to the next shift. Texts shrink to fit rather than
  being cut with "…". Taps open the new-shift editor on today, that shift's editor or Jobs (no jobs yet); the calendar
  opens the app; an open editor is never stacked on. Dynamic colors on Android 12+, the app's palette before; texts
  and dates in the app's language. It updates as soon as anything changes in the app, and on its own every 30 min
  and on clock / time zone / locale changes (so a new day or a finished shift can take up to 30 min to show).
- **Settings** — jobs & rates, **Gross / Net** for every earnings figure (when any job has deductions, amounts carry a
  small "Gross" / "Net" tag), theme (system/light/dark), dynamic color (Android 12+), language, JSON export/import.
  Exports are format version 5 (every job, shift and bonus carries its sync id, so a backup restored on another phone
  holds the same records rather than look-alike copies).
  An import checks the whole file before touching anything (format versions 1–5; files older than 4 import as PLN,
  hourly, no deductions; currency codes, pay types, months, salaries and percentages are validated; a file from a newer app version
  gets its own message; unique job IDs and sync ids, amounts within what the editors accept, a break shorter than the shift, a
  second-rate window that isn't empty, bonuses above zero) and replaces the data in one transaction, so a bad or
  half-written file leaves the existing data exactly as it was. Records the file doesn't have are kept as deleted
  (tombstones), records it does have are updated in place, so the replacement can later reach other devices.
- **Lists** look like Android 16: every group (a day's entries, by-job, by-day/month, settings sections, jobs) is a
  segmented list — items 2 dp apart, large outer and small inner corners; pressing an item rounds its corners.
  Single choices (week/month/year, unpaid break presets, theme, language) are M3 Expressive connected button groups;
  picking a job (shift/bonus editors, schedule import) uses toggle buttons that morph from a squircle into a pill.
- **Schedule import** — pick an `.ics` file, choose the job, preview the events (duplicates are detected and unchecked), confirm.
  An imported shift's id comes from its calendar event (UID + date + start, or the time slot when there is no UID), so
  two phones importing the same file get the same shifts, and importing an event again never adds a copy: ticking a
  duplicate in the preview only moves its shift's start/end to the calendar's (your break and note stay; a break is
  only cut down if it would no longer be shorter than the shift), and a deleted one comes back.
  Imported shifts take that job's rates, currency and pay settings.
  Handles folded lines, `TZID` / UTC / floating times, `DURATION`, Outlook time-zone names, and skips all-day/cancelled events.
  A broken event (an impossible date or duration) is skipped and counted instead of failing the whole file.
  Time zones are matched by name only: IANA IDs, Windows/Outlook names, path-style IDs (`/mozilla.org/…/Europe/Warsaw`)
  and the `X-LIC-LOCATION` of a `VTIMEZONE` block. The rules inside a custom `VTIMEZONE` are *not* interpreted, so an
  ID that matches none of these is listed in the preview with a warning that its times were read as local time.

## Download the APK

Every push builds the app on GitHub Actions:

- **Permanent link to the newest build of the default branch:**
  `https://github.com/PoNcHiK08/Shift-Tracker-M3-/releases/download/latest-build/ShiftTracker.apk`
  (only pushes to the default branch update it).
- Builds of other branches and pull requests: **Actions → Build APK → that run → Artifacts**.

All builds are signed with the same development key (`app/signing/dev.keystore`), so a new build installs
as an update over the old one and keeps your data. Android will ask to allow installs from your browser /
file manager the first time.

### Signing

| Release build has… | Result |
| --- | --- |
| `SIGNING_KEYSTORE_PATH`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD` | Signed with that key (a missing variable stops the build and names it) |
| `ALLOW_DEV_RELEASE_SIGNING=true` (env) or `-PallowDevReleaseSigning=true` | Signed with the public dev key, with a warning banner in the build log |
| neither | The build stops before signing and explains both options |

Debug builds always use the dev key. The GitHub workflow opts in explicitly for its builds. The dev key's
password is in this repo, so anyone can sign an APK with it: fine for test builds, **not** for Google Play —
generate your own key and pass it through the `SIGNING_*` variables (e.g. from GitHub Secrets).

## Crash reports

Sideloaded APKs have no Play Console, so the app keeps its own: if it crashes (or closes itself while a
detail screen is open), the next launch shows the error with **Copy** / **Share** buttons. Release builds
are shrunk but not obfuscated, so the report is readable without a mapping file.

## Build locally

Requirements: JDK 17+ and the Android SDK (Android Studio installs both).

```bash
./gradlew testDebugUnitTest   # unit tests + Robolectric UI smoke test
ALLOW_DEV_RELEASE_SIGNING=true ./gradlew assembleRelease
                              # app/build/outputs/apk/release/app-release.apk, signed with the dev key
./gradlew installDebug        # install a debug build on a connected phone
```

## Cloud sync (optional, work in progress)

Signing in with Google in **Settings → Account & sync** will keep the data of several phones in step through
Cloud Firestore. The app keeps working exactly as before without it: Room on the phone stays the only data
the app reads, and a build made without a Firebase config shows "Not available in this build". So far the
app signs in and checks that it can reach the cloud; syncing itself comes in the next steps.

### Setting up Firebase (once)

1. [Firebase console](https://console.firebase.google.com) → create a project (Analytics not needed).
2. **Authentication → Sign-in method → Google → Enable**.
3. **Project settings → Your apps → Add app → Android**, twice: `com.vkurylo.shifttracker` and
   `com.vkurylo.shifttracker.debug`. For each, add both fingerprints of the dev key that signs every build
   (`app/signing/dev.keystore`):
   - SHA-1 `0E:53:EF:F6:AF:70:26:32:27:B4:33:41:1E:A5:6A:38:B5:5E:7C:7B`
   - SHA-256 `8F:6C:D8:05:54:CC:9A:71:5F:4C:94:5C:26:97:68:72:50:E7:F6:E0:51:AC:9A:1E:61:D5:28:24:A1:B6:11:27`
4. Download `google-services.json` (after steps 2–3, so it has both apps and the Google sign-in client).
   **Never commit it** (it is in `.gitignore`).
   - GitHub builds: **Settings → Secrets and variables → Actions → New repository secret**, name
     `GOOGLE_SERVICES_JSON`, value: the file's whole content (or the same base64-encoded). Without the secret
     the workflow still builds, without cloud sync, and says so in a warning.
   - Local builds: put the file at `app/google-services.json`.
5. **Firestore Database → Create database**, production mode, a location near you (e.g. `europe-central2`,
   Warsaw; it can't be changed later).
6. **Publish the security rules** (below).

### Security rules

[`firebase/firestore.rules`](firebase/firestore.rules) lets only you in: the signed-in owner of
`users/{uid}`, signed in with Google, whose verified email is the one in the rules. It also checks every
field of every document, and lets a record be replaced only by a later version of it.

The repository is public, so the committed file has the placeholder `YOUR_EMAIL_HERE`, and as committed it
lets nobody in. To publish: **Firestore Database → Rules**, paste the whole file, replace `YOUR_EMAIL_HERE`
with your Google account's address **there, in the console only**, then **Publish**. (With the Firebase CLI:
deploy a local copy with your address in it, never a committed one.) Republish whenever the file changes.

What protects your data is these rules, not the app's fingerprints: the dev key's password is public, so
anyone can build an APK that Firebase accepts as this app, and the `google-services.json` values end up
inside every APK anyway. With the rules, such an app can only sign in with the stranger's own Google
account, which can't read or write anything.

Tests (they need Node 20+ and Java 21, and run in GitHub Actions whenever the rules or the sync code change):

```bash
cd firebase/rules-test && npm ci && npm test   # the rules on the Firestore emulator
firebase/run-emulator-tests.sh                 # the app's Firestore code and sync engine against the rules
```

## Tech stack

| Area | Choice |
| --- | --- |
| UI | Jetpack Compose, Material 3 Expressive (material3 1.5.0-beta02 on Compose BOM 2026.09), dynamic color, edge-to-edge |
| Motion | Springs (MotionScheme.expressive; screen transitions use them ~15 % quicker — same damping, stiffness × 1.38): bouncy spatial springs for position/size/shape, non-overshooting effects springs for colour/alpha; press feedback that squeezes and morphs shapes (segmented list items round their inner corners, day cells become squircles, button groups and icon buttons morph); circle -> squircle cell morph (graphics-shapes `Morph` of `MaterialShapes.Circle`/`Square`); odometer-style rolling digits for money and hours; container transform (`SharedTransitionLayout` + `sharedBounds`) from shift/bonus cards and "add" buttons into the full-screen editors, also driven by predictive back |
| Architecture | Single activity, MVVM + unidirectional data flow (ViewModel + StateFlow) |
| Navigation | Navigation Compose 2.10, type-safe `@Serializable` routes. Two levels: a root stack (home + detail screens) and a nested tab host. Material 3 transition patterns on M3 Expressive springs: shared axis X (30 dp) for forward/back, fade through for tabs, container transform for card -> editor, and M3 predictive back: while you drag, the closing screen shrinks to 90 %, rounds its corners, casts a shadow and drifts towards the swiping edge; when you let go it keeps going from that exact point (shrinks to 85 % and fades out within at most ~250 ms) instead of pausing first, and a cancelled swipe returns it to its place. An editor opened from a card is only held by the finger: it shrinks a little, rounds its corners and drifts, on a spring and up to a limit (rubber band), however far you drag, with the calendar visible behind it; it never morphs into the card under the finger. On release it flies into its card from where it was held (on a calm spring, ~5 % longer than a normal close) in plain view all the way: its content fades out early, the card's own look fades in on the shrinking container and lands exactly in the card's place. This happens on every back, however quick the flick (even one released before predictive back has started) and with the system back button; the editor's own close/save/delete buttons keep the normal container transform. Normal open/close keeps the container transform springs. Icon-only bottom bar built from M3 Expressive toggle buttons |
| Storage | Room 2.8 (jobs, shifts, bonuses; exported schemas, auto-migrations up to v6 and a manual v6 → v7 that adds sync metadata: a UUID `sync_id`, `updated_at`, and deletions kept as hidden tombstones) + DataStore (settings); money stored as `Long` minor units of each job's currency (the `*_grosze` names predate other currencies); pay settings history per job |
| Cloud sync (optional) | Firebase Auth (Google sign-in through Credential Manager) + Cloud Firestore; Room stays the source of truth |
| DI | Hilt 2.60 (KSP) |
| Build | AGP 9.5 (built-in Kotlin), Kotlin 2.4, Gradle 9.8, version catalog, R8 for release |
| Language | Per-app language via AppCompat (`values/` = Ukrainian, `values-en/` = English) |

## Known questions

- **Pay on the nights the clocks change.** A shift's length is wall-clock time. A 22:00–06:00 shift on the night
  the clocks go back (24→25 October 2026) really lasts 9 hours but is paid as 8; on the night they go forward
  (last Sunday of March) it lasts 7 and is paid as 8. Whether to pay real elapsed time is an open product decision,
  so the calculator deliberately stays as it is for now. (Schedule import already converts UTC and `TZID`
  times correctly across these nights; this is only about how a shift's hours are counted.)

## Project layout

```
app/src/main/java/com/vkurylo/shifttracker/
├── domain/        models, ShiftCalculator, MoneyParser, IcsParser, ImportPlanner, repository interfaces
├── data/          Room entities/DAOs/database, repositories, DataStore, JSON backup, SAF file access
├── di/            Hilt modules
└── ui/            theme, shared components, navigation, and one package per screen
                   (calendar, summary, settings, jobs, shift, importics)
app/src/test/      calculator (incl. second rate), money parser, .ics parser (incl. DST and time zones),
                   import planner, DB migrations from v1 and v6, tombstones and the job/entry rule, JSON backup (validation, versions, rollback),
                   time-dial haptics, predictive back frame by frame, an end-to-end UI smoke test, and the sync
                   engine (last write wins, first sign-in, several phones) and its Firestore documents
firebase/          firestore.rules, its tests (rules-test/) and the emulator test runner
```
