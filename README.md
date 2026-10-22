# Money Trail

Android app that reads EcoCash (USD + ZWG) and NMB Bank SMS alerts and turns them into a
transaction feed with monthly totals, categories and sanity checks. Everything is stored on the phone.

## Run it
1. Install Android Studio (Koala or newer) and open this folder.
2. Let Gradle sync (it downloads Gradle 8.9 and the dependencies the first time).
3. Plug in your phone (USB debugging on) or use a device, then press Run.
4. Allow SMS access. Existing messages are imported straight away, new ones are captured as they arrive.

Run the parser tests: `./gradlew test` (or right-click `SmsParserTest` > Run).

## Files
- `SmsParser.kt`  one place that knows every message format. Add new formats here.
- `Categorizer.kt` keyword rules for auto categories.
- `SmsReceiver.kt` / `SmsImporter.kt` live capture and inbox scan.
- `Db.kt`, `Txn.kt` Room database; the unique reference stops duplicates.
- `Analysis.kt` double-charge and balance-gap checks.
- `MainActivity.kt` the screens.

## Notes
- Google Play restricts SMS permissions, so install this directly on your phone (sideload).
- Not yet handled (no samples): fees, airtime purchases, bill payments, other banks. Send samples
  and add a pattern in `SmsParser.kt` plus a test.
