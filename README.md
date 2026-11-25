# J.A.R.V.I.S. AI — Android Siri-Style Lock-Screen Assistant

Ye Android app aapke existing **J.A.R.V.I.S. AI** (`jarvis-voice-ai`) ke liye banaya gaya hai:
- **Wake-Word ("Hey Jarvis")**: Phone lock hone par bhi background mic sunta hai (Sherpa-ONNX offline KWS).
- **Voiceprint Security ("Sirf Meri Awaz Pe")**: Offline speaker verification (`3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx`) — aap apni awaz ke 3 samples record karenge, uske baad kisi aur ki awaz par command reject ho jayegi.
- **Siri-Style Phone Actions**: Direct Phone Call (Contacts se naam dhoondh kar), Alarm & Timer, Flashlight/Torch ON-OFF, Volume/Media control, WhatsApp/SMS, Wi-Fi/Bluetooth panel, Installed Apps open karna, Incoming Notifications padhna, aur Gemini / JARVIS Cloud AI.

---

## Option 2: Bina PC Download ke APK Kaise Banayein (GitHub Actions)

1. **GitHub par naya Repository banayein:**
   - Browser me [github.com/new](https://github.com/new) kholein (e.g. naam rakhein `jarvis-android`, Public ya Private).
2. **Ye folder upload karein:**
   - Is `jarvis-android` folder ke andar ki saari files aur folders (`.github` folder zaroor shamil ho!) GitHub par upload karein (`uploading an existing file`).
3. **APK Download karein:**
   - GitHub repo ke **Actions** tab me jayein -> **"Build JARVIS Android APK"** workflow automatically chalega (ya **Run workflow** dabayein).
   - 2–4 minute me build complete hone par niche **Artifacts** section me **`JARVIS-AI-Android-APK`** par click karke `.zip` download karein -> extract karein -> `app-debug.apk` apne Android phone me install karein!

---

## Phone me Setup (1 Baar)

1. App khol kar **1, 2, 3, 4, 5 Permissions** enable karein (Mic, Call, Contacts, Display over other apps, Battery Unrestricted, Notification Reader, Default Digital Assistant).
2. **Voiceprint Lock** card me **"Record Voice Sample"** par click karke 3 baar apni awaz record karein.
3. **AI Brain Settings** me apna **Gemini API Key** daal dein (recommended: isse lock screen par bina ruke Hindi/English voice direct samajh aati hai).
4. **Start Shield** dabayein — ab phone lock hone par bhi **"Hey Jarvis"** bol kar test karein!
