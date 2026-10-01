"""cap add android-ის შემდეგ: ვამატებთ ჩვენს პლაგინს, MainActivity-ს და Android-ის ნებართვებს."""
import pathlib
import shutil

root = pathlib.Path(__file__).resolve().parent.parent
java_dir = root / "android" / "app" / "src" / "main" / "java" / "ge" / "kadastr" / "savele"
java_dir.mkdir(parents=True, exist_ok=True)
for name in ("NmeaBluetoothPlugin.java", "MainActivity.java"):
    shutil.copy(root / "native" / name, java_dir / name)
    print("copied", name)

manifest = root / "android" / "app" / "src" / "main" / "AndroidManifest.xml"
text = manifest.read_text(encoding="utf-8")
perms = [
    '<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />',
    '<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />',
    '<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />',
    '<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />',
    '<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />',
    '<uses-permission android:name="android.permission.INTERNET" />',
]
add = ""
for p in perms:
    key = p.split('"')[1]
    if key not in text:
        add += "    " + p + "\n"
text = text.replace("</manifest>", add + "</manifest>")
manifest.write_text(text, encoding="utf-8")
print("manifest patched")
