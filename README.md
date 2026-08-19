<div align="center">

# 📘 QBooK

**A clean, ad-free, privacy-friendly way to use Facebook on Android.**

</div>

---

## 📖 What is QBooK?

QBooK is an Android app that lets you browse Facebook without ads, trackers, or the clutter that Facebook normally shows you. It looks and feels like a real app — not a browser tab — but underneath, it's showing you the actual Facebook website, just cleaned up.

There's no separate QBooK account, no sign-up, and no company collecting your data. You log in with your normal Facebook account, exactly as you would in a browser, and QBooK simply makes the experience better:

- 🚫 No ads
- 🕵️ No trackers watching what you do
- 📴 Works offline for posts, reels, and stories you've already seen
- 🎨 Dark mode, custom fonts, custom app icons
- 👤 Switch between multiple Facebook accounts easily
- 🔒 Extra privacy and security options

---

## ⚙️ How It Works

Think of QBooK as a smart window into Facebook, rather than a rebuilt copy of it.

1. **You open QBooK** → it loads the real Facebook website inside the app (the same site you'd get in a browser).
2. **Before anything appears on screen**, QBooK quietly removes ads, sponsored posts, and tracking scripts.
3. **Facebook's own features keep working** — Reels, Stories, Messenger, dark mode — because you're using Facebook's real site, just with the annoying parts filtered out.
4. **While you scroll**, QBooK quietly saves a copy of posts, reels, and stories in the background, so you can still open and view them later even with no internet connection.
5. **Your login and password never touch QBooK's code.** You always log in through Facebook's own login page, so your account stays exactly as safe as it would be in any browser.

In simple terms: **Facebook runs the app, QBooK just cleans it up and adds nice extras on top.**

---

## ✨ Features

### 🚫 Ad & Tracker Blocking
QBooK blocks ads and hidden tracking scripts before they ever load, using a huge, regularly updated block-list (the same kind of lists used by popular ad-blocker browser extensions). Facebook's own servers are never blocked, so login and normal features always keep working.

### 📰 Feed Control
Turn off things you don't want to see — Stories, Reels, "People You May Know," Suggested Pages, Memories, Birthdays, Marketplace, Groups, Watch, or Gaming — each one can be hidden on its own.

### 📴 Offline Reading
QBooK saves your feed, reels, and stories in the background as you use the app, so you can keep browsing them later even without internet or mobile data. No extra button-pressing needed — it just happens automatically.

### 👤 Multiple Accounts
Keep separate Facebook logins (like Personal, Business, or a Page account) inside QBooK and switch between them without logging out each time. You can also back up your accounts and settings, and restore them later.

### 🎨 Look & Feel
- Light, dark, or true black (AMOLED) themes
- Automatic color matching with your phone's theme (Material You, Android 12+)
- Adjustable text size and a choice of fonts — even your own custom font
- 16 different app icons to choose from

### 🌐 Browsing Extras
Desktop site mode, pull-to-refresh, background audio (keep listening after you switch apps), pinch-to-zoom, and a choice of opening links inside or outside the app.

### 🧪 QBooK Labs (Experimental Extras)
A separate section for newer, still-being-polished features like:
- A media Download Center to find and manage everything you've saved
- Reel downloading with format choices
- A floating quick-action toolbox
- A small progress indicator while something downloads

### 🔒 Privacy & Security
- Lock the app with your fingerprint or PIN
- Appear offline / hide your active status
- Block screenshots and screen recording while QBooK is open
- Automatically strip tracking codes from links before you open them
- One-tap buttons to clear cache, cookies, or all app data

### 🛠️ No Hidden Backend
QBooK has no server of its own. There's no account system, no analytics, no crash reporting, and nothing being tracked about how you use the app. The only things QBooK connects to are Facebook itself and, optionally, GitHub (just to check for app updates — and you can turn that off in Settings).

---

## 🔑 Opening Settings

Settings are hidden from the main screen on purpose (to keep things simple and clean). To open them:

**Tap the screen three times with three fingers at once.**

---

## 🏗️ How to Build It

You'll need a computer with **Android Studio** installed (it's free, from Google) — this is the standard tool for building Android apps.

**Step 1 — Get the code**
```bash
git clone https://github.com/SA-SUJON/QBooK.git
cd QBooK
```

**Step 2 — Build the app**

For a test version you can install and try right away:
```bash
./gradlew assembleDebug
```

For the full release version:
```bash
./gradlew assembleRelease
```

That's it — Android Studio (or the command above) will download everything else it needs on its own and produce an installable app file (`.apk`) you can put on your phone.

**Note:** The official, signed version of QBooK is signed with a private key that isn't included in this project (for security reasons — anyone with that key could push fake "updates" to real users). If you build it yourself without that key, you'll still get a working app — it just won't be able to receive updates from the official QBooK releases, and you'll need to reinstall manually for future versions.

---

## 🔐 Permissions QBooK Asks For

| Permission | Why |
|---|---|
| 📷 Camera & 🎤 Microphone | For Facebook's own camera, video calls, and voice messages |
| 🖼️ Photos & Videos | To upload media to Facebook, and to save things you download |
| 📍 Location | Only used if a Facebook feature asks for it (like check-ins) |
| 🔔 Notifications | To show you new Facebook notifications |
| 📦 Install unknown apps | Needed so QBooK can install its own updates |

QBooK never asks for anything it doesn't actually use for a feature you can see and control.

---

## ©️ Copyright & License

QBooK is created and owned by its developer. All rights are reserved — this project does not currently include an open-source license, so please don't copy, redistribute, or reuse the code without permission.

**QBooK is an independent, unofficial project.** It is **not made by, affiliated with, endorsed by, or connected to Meta Platforms, Inc.** in any way. "Facebook" and "Meta" are trademarks of Meta Platforms, Inc., and are used here only to describe what QBooK connects to.

