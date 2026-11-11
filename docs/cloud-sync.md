# Cloud sync: limits, phone tests, moving your app

Setting up Firebase and publishing the rules: see the [README](../README.md#cloud-sync-optional). This page is
for afterwards: what sync does not do, how to test it on phones, how to move the app you use every day to
the sync version, and (optional) how to sign releases with your own key.

UI names below are the English ones; the Ukrainian ones are in brackets where you tap.

## Limitations

- **One person.** `firestore.rules` lets in only the Google account whose address you put in them. Sharing data
  between two people isn't supported.
- **Not live.** A phone picks up other phones' changes when it syncs: when the app starts or comes back to the
  foreground (at most once a minute), 3 seconds after its own change, or with **Sync now**. There is no push.
- **Conflicts: the newer record wins, whole.** If two phones change the same shift before syncing, the change
  made later (by each phone's clock) replaces the other one entirely. Fields are not merged.
- **The phone's clock decides.** A phone whose clock is behind loses conflicts it should win. A clock up to
  10 minutes ahead is accepted. Further ahead, the cloud refuses that phone's changes, and Settings says to
  check the date and time.
  - Changes made while the clock was wrong stay waiting after you fix it, until real time is within
    10 minutes of their time; then **Sync now** (or reopening the app) sends them.
  - Editing such a record again doesn't help: a new change is always stamped after the previous one.
- **Deleted records stay as hidden markers** (tombstones), on the phone and in the cloud, so the deletion
  reaches every phone. They are never cleaned up; each takes a few hundred bytes.
- **Quotas (Firebase free plan).** Each record sent is one write (20 000 a day free). Each record read is one
  read (50 000 a day): the first link and a fresh download read everything, later syncs only what changed.
  - Automatic syncs stop at 5 000 writes a day per phone. **Sync now** has no limit, so after a big import
    you may need it.
- **Text up to 20 000 characters** per job name or note; the editors stop there. Older or imported text that
  is longer goes to the cloud cut to 20 000 characters. The phone keeps the full text, but other phones get
  the cut version. If one of them then changes that record, its version, with the cut text, comes back and
  replaces the full one. Shorten such a text yourself, or keep it in a backup.
- **Safety backups stay on the phone.** The app keeps the newest 5, they aren't in Android's own backup, and
  uninstalling the app deletes them. Save or share the ones you want to keep.
- **The import replaces data everywhere** while sync is on. The app warns you, and saves a safety backup of
  the importing phone's data first. Changes another phone made before the import but hadn't sent yet are
  replaced as well; changes made after it win.
- **Deleting the cloud data** (from any phone) makes the other phones stop and ask. If one of them answers
  **Upload again**, its data goes back up.
- **A phone restored from Android's own backup** (or set up by device transfer) gets the data but not the link
  to the account: turning on Sync there asks to **Merge**, and records that look the same are kept once.
- **No end-to-end encryption.** Google encrypts Firestore at rest, and the rules keep everyone else out, but
  you (the project owner, in the Firebase console) and Google can read the documents.
- **Deleting your Google account doesn't delete the Firestore data.** Use **Delete my cloud data** first.
- **Needs Google Play services** for signing in.
- **Weekly totals leave out bonuses for a whole month.** A week can span two months, so such a bonus is part of
  its month and of the year, never of a week or of a day (it is not in the calendar grid or the day panel).
- **Received pay is a check, not an earning.** It is set against the calculated pay after deductions (net),
  whatever Gross / Net is on, and is never added to any total. Several payments for one month are summed. A
  payment belongs to the month it was earned for, not the month it arrived.
- **Restoring a backup replaces received pay too.** A backup made before received pay existed has none, so
  importing it deletes the payments (as deletions that sync, like every other record). The safety backup taken
  first has them.
- **"Delete my cloud data" from a phone on an older app version leaves the received pay** in the cloud, because
  that version doesn't know the `payouts` collection. Do it from an updated phone.

## Testing on phones

Use the debug APK (`ShiftTracker-debug.apk`) from this branch's latest **Build APK** run: **Actions → Build APK →
the run → Artifacts**. It is a separate app (`com.vkurylo.shifttracker.debug`), so your everyday app isn't
touched.

Before testing:
- Publish the current `firestore.rules`, with your address in them.
- Sign in with that account.
- `adb logcat -s CloudSync CloudSignIn` shows what happens.
- **Firebase console → Firestore Database → Data** shows the documents; **Usage** shows reads and writes.

### One phone, airplane mode

1. Turn **Sync** on and wait for "Last sync: … · N received, N sent".
2. **Airplane mode on.**
   - Add a shift, change another, delete a bonus. The account row says **Waiting to be sent: 3**, and the
     app works as usual.
   - **Sync now** says it can't reach the cloud. Nothing is lost.
3. Close the app from Recents and open it again, still offline: still 3 waiting (they are in Room).
4. **Airplane mode off.** Within a minute or so the 3 go up by themselves (WorkManager waits for the network),
   without opening the app. Check the documents in the Firebase console.
5. *(Clock, optional.)*
   - Turn off automatic date and time, set the clock 30 minutes ahead, and change a shift.
   - **Sync now** → "The cloud refused the changes: this phone's clock is 30 min ahead…".
   - Turn automatic time back on: that change still waits. About 20 minutes later **Sync now** sends it
     (see Limitations).

### Two phones

Both phones run the debug APK and are signed in with the same account. A has data, B is empty.

1. **A: Sync on.** Everything goes up ("N sent").
2. **B: Sync on.** No question (B is empty): everything comes down. Both phones show the same jobs, shifts,
   bonuses and month totals.
3. **Change on A, see it on B.**
   - A: change a shift's end time. A sends it by itself a few seconds later.
   - B: tap **Sync now**: "1 received, 0 sent".
   - B: tap **Sync now** again: "0 received, 0 sent". A download never goes back up, so the two phones can't
     keep sending each other the same record.
4. **Deleting.** B: delete a shift, wait a few seconds → A: **Sync now** → it's gone on A too.
5. **Deleting a job deletes its entries.** A: delete a job that has shifts, wait a few seconds → B: **Sync now**
   → the job and its shifts are gone.
6. **Conflict.**
   - Airplane mode on both phones.
   - A: change a shift's times. A minute later, B: change the same shift's note.
   - Airplane mode off; **Sync now** on A, then B, then A.
   - Both phones show B's version: B's note with the old times. A's change is gone, because the later change
     wins, whole.
7. **Import with sync on.**
   - A: **Import data** → "Replace data on all your devices?" → **Replace everywhere**.
   - B: **Sync now** → B has the file's data.
   - A: **Safety backups** lists "Before importing a file". Its **Share** button sends it, for example to
     Telegram.
8. **Delete my cloud data.**
   - A: **Delete my cloud data** → confirm. A's sync turns off and its data stays; the documents disappear
     from the console.
   - B: **Sync now** → "This account's cloud data was deleted…". Tap the row → **Upload again** (B's data goes
     up) or **Turn sync off**.
9. **Write counter.** "today N" in the status grows only when records are sent.

### Month bonuses and received pay

Needs the rules from this version published first (README, "Security rules"). With one phone, or with two for the
last check.

1. **Month bonus.** Add a bonus, switch to **For the whole month**, pick the month, save.
   - **Calendar:** the month tiles include it and a **Month bonuses** row shows under them. The grid dots and the
     day panel don't show it, and the row opens the list.
   - **Summary → Month:** it is in the totals, in **By job** and in the **Month bonuses** section, where it opens
     in the editor. **Summary → Week** (any week of that month) doesn't include it. **Year** counts it in its month.
   - **Gross / Net:** in Net it is reduced by the job's deductions.
   - **Delete** it from the editor and tap **Undo**: it comes back as it was.
2. **Received pay.** Summary → Month → **Received pay**.
   - A job's row shows **Calculated (net)**, **Received** ("not entered" at first) and, once something is
     entered, the **Difference** (received minus calculated).
   - Tap a job → **Add payment**: amount, the month it was earned for, an optional received date and note.
     Add a second payment for the same month: they are summed ("2 payments").
   - **The month's totals did not move.** Compare the hero card and **By job** before and after.
   - Switch Gross / Net: the calculated figure in **Received pay** stays the net one.
   - Delete a payment and **Undo**. **Year** shows a line per job with payments.
3. **Two phones.** Do 1 and 2 on A, then **Sync now** on both: B shows the same month bonus (not on a day) and the
   same payments. **Sync now** again on B: "0 received, 0 sent".
4. **Backup.** **Export data** (the file says `"version": 6` and lists `payouts`), then **Import data** it back
   (or on a second phone): the month bonus, the payments and the totals are the same.

## Moving my real app to sync

Your everyday app is the release build: package `com.vkurylo.shifttracker`, signed with the dev key, from the
`latest-build` link. It has database v6 and exports backup format 4. The sync version is the release APK from
this branch: before this PR is merged, `ShiftTracker.apk` in the branch's **Build APK** run (Artifacts, kept
30 days); after the merge, the `latest-build` link.

### What happens when you install it over the old app

Android treats it as an update: same package, same key, higher version number. **The data stays.**

On first launch, the database migrates from v6 to v7 in one transaction. Every job, shift and bonus gets a
sync id, a change time (now) and a "not uploaded yet" mark; nothing else changes. Pay, totals and the widget
are the same. Sync stays off until you turn it on.

If the migration failed, the transaction would roll back and the app would crash on start. The v6 data stays
intact in the database, but this app version can't open it, and Android won't let the old version back over a
newer one. That is why the export below comes first.

### Before you install

1. **Export your data from the old app:** Settings → **Export data** (Експорт даних). Keep the file off the
   phone (Drive, a computer).
   - It is format 4, which both the old and the new app can import.
   - Check that the file opens and lists your jobs.
2. **Keep the old APK.** Download `ShiftTracker.apk` from the `latest-build` link now: after the merge, that
   link points at the new version.
3. **Clear out test data.** If the debug app synced test data to the same Google account, delete it from the
   cloud and stop the debug app from uploading it back:
   - In the debug app: **Delete my cloud data**, then **Sign out**.
   - Otherwise the real app's first sync asks you to merge with your test data.
4. **Check Firebase:**
   - The app `com.vkurylo.shifttracker` has the dev key's SHA-1 (README, "Setting up Firebase", step 3).
   - The current rules are published with your address.
   - The Build APK run for the new APK shows no "no SHA-1 fingerprint … for com.vkurylo.shifttracker"
     warning.

### Install and turn sync on

1. Install the new `ShiftTracker.apk` over the old one (Android says **Update**). Open it and check a few
   months: same shifts, same totals.
2. Settings → **Account & sync** → **Sign in with Google** (Увійти через Google). The row says "Cloud is
   reachable".
3. Turn on **Sync** (Синхронізація). The account is empty, so there is no question: everything goes up, one
   write per record. A few hundred records take a few seconds.
4. Check the status line ("N sent") and the documents in the Firebase console. From now on another phone can
   join: sign in, turn on Sync, and it downloads everything (or asks, if it has data of its own).

### If it goes wrong: rolling back

Android can't install an older version over a newer one, and the old app can't open the v7 database. So
rolling back means reinstalling:

1. **If the new app still opens, export again** (format 5) to keep what you changed since the update.
2. **Uninstall Shift Tracker.** This deletes its data on the phone, but the cloud copy stays.
3. **Install the old APK** you kept.
4. **If it crashes on start:** Android restored its automatic backup of the new version's database. Go to
   Settings → Apps → Shift Tracker → Storage → **Clear storage**, then open it again.
5. **Import data** with the file you exported before the update.
   - To keep changes made after the update instead, take the export from step 1, open it in a text editor and
     change `"version": 5` to `"version": 4` near the top.
   - Format 5 only adds a `sync_id` to each record, and the old app ignores fields it doesn't know.

Nothing in the cloud needs undoing. If you come back to the sync version later:
- The new app links your data afresh (with new sync ids).
- Turning on Sync then asks to **Merge**: records that look the same are kept once.
- Or **Delete my cloud data** first for a clean upload.

### Optional: rehearse with your real data first

The debug app is a separate app, so you can try the update on a copy of your real data, with your everyday
app untouched. Do this before the PR is merged, while `latest-build` still has the old debug APK:

1. Export from the real app (as above).
2. If the debug app synced test data, do step 3 of "Before you install" in it first. Then uninstall it (only
   its test data goes).
3. Install `ShiftTracker-debug.apk` from the `latest-build` link (old, v6).
4. In that debug app, **Import data** the export.
5. Install this branch's `ShiftTracker-debug.apk` over it, then open it and compare months and totals with
   the real app. Leave sync off there, so your real data goes to the cloud from the real app only.

## Updating to whole-month bonuses and received pay (database v8)

This update adds a flag to bonuses and a table for received pay. Do it in this order.

1. **Publish the new `firestore.rules`** (with your address) in the Firebase console, *before* installing the
   update on any phone. They accept the previous version's documents as well as the new ones, so a phone that
   hasn't updated yet keeps syncing. If you install first, the new version's sync is refused until you publish:
   Settings says "The cloud refused access…", nothing is lost, and the changes wait.
2. **Export your data** from the app you have now (Settings → **Export data**), as before any update.
3. **Install the update over the old app.** The database goes from v7 to v8 in one step: one new column
   (`whole_month`, 0 for every existing bonus, so they stay bonuses on their day) and an empty `payouts` table.
   No existing row is touched or marked as changed, so nothing uploads again. Check a few months: same totals.
4. Backups from now on are **format 6**. Files of formats 1–5 still import (their bonuses are on their day and
   they have no received pay).

### While one phone is updated and another is not

- The phone on the previous version keeps syncing and keeps working.
- It shows a month bonus as a bonus **on the 1st** of that month: the month's totals there are right, but the
  bonus appears on that day and in that week there. When that phone edits such a bonus, the "whole month" mark
  survives (uploads merge fields).
- It doesn't see received pay at all. If it deletes a job, the updated phone deletes that job's payments when the
  deletion reaches it.
- Update the other phone too; there is nothing else to do.

### Rolling back

As for the sync update: Android can't install an older version over a newer one, so go through uninstall,
reinstall and import. The format-6 file imports into the previous app after you open it in a text editor and
change `"version": 6` to `"version": 4` (or 5): the month bonuses become bonuses on the 1st and the payments are
dropped, because that version ignores fields it doesn't know. Nothing in the cloud needs undoing; payments stay in
the cloud for when you update again.

## Optional: your own release key

Today every APK is signed with the dev key in this repository, and its password is public. The cloud data is
safe either way (the rules check your Google account, not the key). The phone is not: anyone can build an APK
with this package and that key, and **if you installed it**, Android would accept it as an update and give it
your app's data. Signing your release builds with a key only you have closes that.

The debug app keeps the dev key. Nothing changes for it.

### 1. Make the key (once, on a computer with a JDK)

```bash
keytool -genkeypair -v -keystore shifttracker-release.jks -storetype PKCS12 \
  -alias shifttracker-release -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Shift Tracker"
keytool -list -v -keystore shifttracker-release.jks -alias shifttracker-release   # shows SHA1 and SHA256
```

PKCS12 uses the store password for the key as well. Keep the `.jks` file and its password in two safe places,
such as a password manager plus an offline copy, and never commit it. **Lose it and you can't update the app
any more**: you'd have to go through the reinstall below again with a new key.

### 2. Firebase

1. **Project settings → Your apps → `com.vkurylo.shifttracker` → Add fingerprint**: add the new key's SHA-1,
   then its SHA-256.
   - Keep the dev key's fingerprints there until no phone runs a dev-signed release any more.
   - The `.debug` app keeps only the dev key's.
2. Download `google-services.json` again, and replace the `GOOGLE_SERVICES_JSON` secret with it. The Build APK
   workflow warns when the file lacks the release key's SHA-1.

### 3. GitHub secrets

**Settings → Secrets and variables → Actions → New repository secret**, four of them:

| Secret | Value |
| --- | --- |
| `SIGNING_KEYSTORE_BASE64` | the `.jks` file, base64: `base64 -w0 shifttracker-release.jks` (macOS: `base64 -i shifttracker-release.jks`; Windows PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("shifttracker-release.jks"))`) |
| `SIGNING_STORE_PASSWORD` | the password |
| `SIGNING_KEY_ALIAS` | `shifttracker-release` |
| `SIGNING_KEY_PASSWORD` | the same password |

From the next run, `ShiftTracker.apk` is signed with your key. The **Choose the release key** step in the run's
log says which key it used and prints your key's SHA-1 and SHA-256 (handy for Firebase). A missing secret, or
a wrong password or alias, stops the build there and says which. Delete `SIGNING_KEYSTORE_BASE64` and builds
go back to the dev key.

Every build that has the secrets is signed with your key, including this repository's branch and
pull-request builds. Pull requests from forks don't get secrets, so they keep the dev key.

### 4. What it means for the data on the phone

Android refuses to install an APK signed with another key over the installed app ("App not installed" /
"package conflicts"). You have to uninstall it, which deletes its data on the phone: the database, the
settings, the safety backups and the widget.

Android also won't restore its automatic backup into an app signed with a different key, so the new install
starts empty. Do it in this order:

1. In the old app:
   - With sync on, tap **Sync now** and wait until nothing is waiting to be sent.
   - Then **Export data**, and save or share any safety backups you want to keep.
   - Keep the files off the phone.
2. Uninstall the old app and install the new `ShiftTracker.apk`.
3. Get your data back, either way:
   - **Sign in** and turn on **Sync**. The phone is empty, so it downloads everything from the cloud. The cloud
     data belongs to your Google account, not to a key, so it is all there.
   - Or **Import data** from the file.
4. Check a few months and the totals, then add the widget again.

Every later build signed with your key installs as an update, as before.

(Android 9+ can also accept a key change as an update, through APK key rotation with `apksigner rotate`. That
needs an extra signing step this project doesn't have yet; the reinstall above is the simple path.)
