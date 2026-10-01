# საველე აზომვა — Android აპი (Bluetooth GNSS მიმღებით)

აპი იხსნება საიტს `https://azome.netlify.app` (შეცვალეთ `capacitor.config.json`-ში, თუ საიტის მისამართი სხვაა)
და ამატებს ერთ რამეს, რასაც ბრაუზერი ვერ აკეთებს: პირდაპირ უკავშირდება Bluetooth GNSS მიმღებს (Stonex S850 და სხვა SPP მიმღებები),
კითხულობს NMEA-ს (GGA, GST) და აჩვენებს RTK Fixed / Float, თანამგზავრებს, HDOP-ს, ჰორიზონტალურ და ვერტიკალურ ცდომილებას.

საიტის ცვლილებები ხელახალი აწყობის გარეშე ჩაიტვირთება — Netlify-ზე ატვირთვა საკმარისია.

## APK-ს აწყობა (GitHub-ზე, კომპიუტერზე არაფრის დაყენების გარეშე)

1. github.com → **New repository** (მაგ. `savele-apk`, შეიძლება Private).
2. ამ საქაღალდის შიგთავსი ატვირთეთ რეპოში:

   ```
   cd "D:\kadastri\savele api\android-build"
   git init
   git add .
   git commit -m "Android app"
   git branch -M main
   git remote add origin https://github.com/<თქვენი-სახელი>/savele-apk.git
   git push -u origin main
   ```

3. GitHub-ზე რეპოში: **Actions** → **Build APK** → (თუ თავისით არ დაიწყო) **Run workflow**.
4. ~5–8 წუთში დასრულდება. გახსენით შესრულებული run → ქვემოთ **Artifacts** → `savele-azomva-apk` → გადმოწერეთ ZIP, შიგნით არის `app-debug.apk`.
5. `app-debug.apk` გადაიტანეთ ტაბლეტზე და დააყენეთ (Android: „უცნობი წყაროებიდან ინსტალაცია“ ერთხელ უნდა დაუშვათ).

## გამოყენება

1. Stonex S850 დააწყვილეთ ტაბლეტთან: Android → Bluetooth. **Cube-a და სხვა აპები მიმღებიდან გათიშეთ** (SPP-ზე ერთდროულად ერთი კავშირია).
2. გახსენით აპი → კადასტრი → პროექტი → ჩიპი (GPS) → **GNSS მიმღები (Bluetooth)** → აირჩიეთ S850 → **🔌 გამოყენება**.
3. ჩიპზე გამოჩნდება მაგ. `RTK Fixed ±0.02 ↕0.03 მ · 18🛰`.

### ± ცდომილების შესახებ
± მეტრებში მოდის NMEA **GST** წინადადებიდან. თუ მიმღები GST-ს არ გამოსცემს, ჩანს ფიქსის ტიპი, თანამგზავრების რაოდენობა და HDOP, ხოლო „±“ არა.
ამ შემთხვევაში მიმღების NMEA გამოტანის პარამეტრებში ჩართეთ GST (Stonex Cube-a-ში მიმღების პარამეტრებში).

## ფაილები
- `capacitor.config.json` — აპის სახელი და საიტის მისამართი
- `native/NmeaBluetoothPlugin.java` — Bluetooth SPP (NMEA) პლაგინი
- `native/MainActivity.java` — პლაგინის რეგისტრაცია
- `scripts/patch_android.py` — აწყობისას ამატებს პლაგინს და ნებართვებს
- `.github/workflows/build-apk.yml` — ავტომატური აწყობა
