<div align="center">

# 🌐 Mungil Browser
### Ultra-Lightweight (<5MB) Android Browser with Built-in Media Downloader & Offline Eruda DevTools

[![Android](https://img.shields.io/badge/Platform-Android%207.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://github.com/AMillionDriver/Mungil)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Size](https://img.shields.io/badge/APK%20Size-%3C%205%20MB-0EA5E9?style=for-the-badge&logo=google-play&logoColor=white)](https://github.com/AMillionDriver/Mungil/releases)
[![Build Status](https://img.shields.io/github/actions/workflow/status/AMillionDriver/Mungil/builder.yaml?branch=main&style=for-the-badge&label=Build)](https://github.com/AMillionDriver/Mungil/actions)
[![License](https://img.shields.io/badge/License-MIT-10B981?style=for-the-badge)](LICENSE)

<br/>

**Mungil Browser** is a fast, ergonomic, and featherweight (<5MB) Android web browser engineered for power users, developers, and everyday web exploration. Equipped with a native media stream sniffer, offline DevTools console, built-in tracker/ad blocking, and a modern Deep Slate aesthetic.

[📥 **Download Latest APK**](https://github.com/AMillionDriver/Mungil/releases) • [✨ **Features**](#-key-features) • [🛠️ **Tech Stack**](#️-tech-stack--architecture) • [🚀 **How to Build**](#-building-from-source)

</div>

---

## ✨ Key Features

### 🎬 Smart Media Sniffer & Instant Downloader
* **In-App Direct Stream Sniffing**: Automatically captures streaming MP4/WebM video and audio directly from DOM media elements without sending data to third parties.
* **Zero-Queue Web Fallbacks**: When dealing with protected platforms (YouTube, TikTok, Instagram, X/Twitter), access high-speed instant web download engines without getting stuck in congested cloud conversion queues.
* **Auto-Clipboard Sync**: Media URLs are automatically sanitized and copied to clipboard for seamless 1-tap pasting.
* **Direct MediaStore Storage**: Clean file management into Android's public `Movies/MungilBrowser` and `Music/MungilBrowser` directories.

### 🛠️ Built-in Offline Eruda DevTools
* **Native Web Inspector**: Inspect DOM nodes, inspect network requests, view JavaScript console logs, modify CSS on the fly, and run JavaScript snippets directly on mobile.
* **100% Offline Asset Delivery**: Eruda library is bundled internally inside the APK (`file:///android_asset/eruda/eruda.min.js`), requiring **zero internet connection** or CDN dependencies to launch.

### 🛡️ Clean Browsing & Smart Ad-Filtering
* **Intelligent Ad Shield**: Automatically blocks pop-up advertising networks, tracking beacons, and invasive redirect scripts (`syndication`, `popads`, `adsterra`, `doubleclick`, etc.).
* **Desktop / Mobile Switcher**: One-tap toggle between true Desktop Chrome user agent and native mobile views with dedicated responsive viewport scaling.

### 🎨 Deep Slate Modern Interface
* **Ergonomic Bottom Bar**: URL bar, tab switcher, DevTools toggler, and navigation controls anchored at the bottom for effortless one-handed reach.
* **Dynamic Floating Capsule**: A discrete, pill-shaped action capsule floats gracefully above content only when downloadable media or audio is detected on screen.
* **Tab Management**: Multi-tab browsing with live tab counters and instant memory management.

---

## 📱 Screenshots & Previews

| Ergonomic Bottom Bar | Dynamic Media Sniffer | Offline Eruda DevTools |
|:---:|:---:|:---:|
| Clean, dark-mode slate browsing with bottom navigation | Floating capsule appears when stream is detected | Complete mobile web development inspector |

---

## 📥 Download & Installation

1. Head over to the [**Latest Releases**](https://github.com/AMillionDriver/Mungil/releases) page or grab the latest build artifact from [**GitHub Actions**](https://github.com/AMillionDriver/Mungil/actions).
2. Download `app-debug.apk` (or `Mungil-Browser-v1.6.x.apk`).
3. Open the APK on your Android device (Android 7.0 / API 24 or newer).
4. Allow installation from unknown sources if prompted.
5. Enjoy lightning-fast browsing!

> **Note on Updates**: Starting from version **v1.6.1**, Mungil Browser includes a locked, permanent digital keystore (`mungil.keystore`). All future updates can be installed directly with one click without any signature conflict!

---

## 🛠️ Tech Stack & Architecture

* **Language**: [Kotlin](https://kotlinlang.org/) (100%)
* **Framework**: Android SDK (Target SDK 34 / Android 14, Min SDK 24 / Android 7.0)
* **Web Engine**: AndroidX WebKit (`androidx.webkit:webkit`)
* **DevTools**: Embedded [Eruda](https://github.com/liriliri/eruda) v3.4.1 (Offline asset bundling)
* **Architecture**: Clean MVVM-friendly single-activity architecture with custom JavaScript bridges and background thread management.
* **CI/CD**: Fully automated GitHub Actions workflow for automatic compilation and artifact distribution.

---

## 🚀 Building from Source

To build Mungil Browser locally using Android Studio or Gradle CLI:

```bash
# 1. Clone the repository
git clone https://github.com/AMillionDriver/Mungil.git
cd Mungil

# 2. Build the Debug APK
./gradlew assembleDebug

# 3. The compiled APK will be located at:
# app/build/outputs/apk/debug/app-debug.apk
```

---

## 📄 License

This project is licensed under the **MIT License** - see the [LICENSE](LICENSE) file for details.

---

<div align="center">
  <sub>Crafted with passion for a faster, cleaner, and unbloated mobile web experience.</sub>
</div>
