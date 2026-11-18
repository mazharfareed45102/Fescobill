# FESCO Bill (Android app)

A simple, ad-free Android app to view, download and share your **FESCO electricity bill**.

- **Type the reference number** (14 digits, e.g. `07 13523 0628703 R`) or the 10-digit Customer ID, or
- **Take a photo of an old bill** (or pick one from the gallery). The app reads the reference number for you, on the phone, with no internet needed for that step.
- The bill opens straight from the **official PITC/FESCO website** (`bill.pitc.com.pk/fescobill`). Nothing is changed and no other server is involved.
- **PDF**: saves the bill to `Downloads/FESCO Bills`.
- **Image**: saves the bill as a picture to `Pictures/FESCO Bills` (shows in the Gallery).
- **Share**: send the bill as a PDF or a picture (e.g. on WhatsApp).
- **Saved bills**: every bill you open is remembered. Tap it next time, or press and hold to give it a name ("Home", "Abu ji", …).
- **Opens instantly the second time**: the last bill for each number is kept on the phone (PDF + picture).
  It shows at once, even without internet, while the app checks FESCO for a newer bill in the background.
- **Fast even when the FESCO site is slow**: the app asks the site over several connections at once
  (over Wi-Fi and mobile data), retries failed requests by itself and uses whichever answer arrives first.
- **When the FESCO server is down**: the app says so plainly, keeps trying every 15 minutes in the
  background (also when closed) and sends a notification as soon as the bill is downloaded.
  The bill amount can meanwhile be requested by SMS (PITC SMS service, 8334).

Works on Android 5.0 and newer.

## Install the APK

1. Open the [latest release](../../releases/latest) on the phone and download **FescoBill.apk**.
   (You can also send the APK file to another phone on WhatsApp.)
2. Open the downloaded file and tap **Install**. If Android asks, allow *Install unknown apps* for your browser or file manager.
3. A newer APK installs over the old one and keeps the saved bills.

### اردو میں

1. فون پر اوپر دیے گئے لنک سے **FescoBill.apk** ڈاؤن لوڈ کریں۔
2. فائل کھول کر **Install** دبائیں۔ اگر فون اجازت مانگے تو "Install unknown apps" کی اجازت دیں۔
3. ایپ میں ریفرنس نمبر لکھیں یا پرانے بل کی تصویر لیں، پھر **بل دیکھیں** دبائیں۔

## How it works

The official site's search form opens the bill at
`https://bill.pitc.com.pk/fescobill/general?refno=<14 digits>` (or `?appno=<customer id>`).
Every FESCO bill website gets the bill from this one PITC server; third-party sites only add ads and redirects.

When you open a bill, the app does three things at once:

1. Shows the saved copy of that bill, if there is one.
2. Requests the bill directly (`BillFetcher`). A second and third connection start if the first has not
   answered after 4 and 10 seconds, and failed requests are retried straight away (up to 8 in total).
   If the site wants its search form first, the form is filled in and submitted like a browser would.
3. Opens the same address in a built-in browser view. If the site shows its search form, the app fills in
   the number and presses **Search**.

The first bill to arrive is shown, exactly as the website renders it. If the website says the number is
wrong, its own message is shown.

Every bill shown is saved as a self-contained page (`PageSnapshot`: styles, scripts and pictures are put
inside the HTML) plus a PDF and a picture. Next time it opens instantly, also without internet, and the app
checks the website for a newer bill in the background.

If the FESCO server cannot be reached, `BillRetryWorker` (WorkManager) tries again every 15 minutes for up
to 3 days and posts a notification when the bill has been downloaded.

**There is no other source for the bill.** Only PITC's server (`bill.pitc.com.pk`) has the bill data;
every FESCO bill website and app depends on it. When it does not accept connections, which happens around
bill issue dates, no app can download a bill until it is back. This app just does the waiting for you.
To check from outside Pakistan whether the server answers, run the *Check FESCO bill server* workflow
in the Actions tab.

PDFs come from the page's print output, the same as the site's **Print** button.
The picture is made from that PDF.

The reference number is read from photos with Google ML Kit text recognition, built into the app.
Every 14-digit number on the bill is scored by the label printed next to it (`REFERENCE No.`,
`OLD A/C NUMBER`, `CONSUMER ID`, …) and by the `U`/`R` letter after it. If more than one number is
possible, the app lets you choose.

## Build

Requirements: JDK 17+ and the Android SDK (platform 35).

```bash
./gradlew testDebugUnitTest assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

Every push to GitHub builds the APK with GitHub Actions (`.github/workflows/build-apk.yml`).
Pushes to the default branch also publish it as a release.

The APK is signed with `keystore/fescobill.jks` (password `fescobill`), kept in the repository on purpose.
Every build then has the same signature, so updates install over older versions.
It is a convenience key for a personal app, not a secret. Use your own private key before publishing on the Play Store.
