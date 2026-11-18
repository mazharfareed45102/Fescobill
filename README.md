# FESCO Bill (Android app)

A simple, ad-free Android app to view, download and share your **FESCO electricity bill**.

- **Type the reference number** (14 digits, e.g. `07 13523 0628703 R`) or the 10-digit Customer ID, or
- **Take a photo of an old bill** (or pick one from the gallery). The app reads the reference number for you, on the phone, with no internet needed for that step.
- The bill opens straight from the **official PITC/FESCO website** (`bill.pitc.com.pk/fescobill`). Nothing is changed and no other server is involved.
- **PDF**: saves the bill to `Downloads/FESCO Bills`.
- **Image**: saves the bill as a picture to `Pictures/FESCO Bills` (shows in the Gallery).
- **Share**: send the bill as a PDF or a picture (e.g. on WhatsApp).
- **Saved bills**: every bill you open is remembered. Tap it next time, or press and hold to give it a name ("Home", "Abu ji", …).

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
The app opens that address in a built-in browser view. If the site shows its search form instead,
the app fills in the number and presses **Search** for you. The page is checked after every load,
so you see exactly what the website shows: the bill, or the website's own "not found" message.

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
