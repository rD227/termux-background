# Termux Background

> **✨ Status:**  Archived 已归档

# You can use this instead on android
# 在Android上，你可以用这个代替：
 [click here](https://github.com/rD227/FloatPicture)

🖼️ A Termux plugin and Android companion app for adding custom background images to your Termux terminal — with live reload, blur effects, opacity control, and scroll animations.

[![License: GPLv3](https://img.shields.io/badge/License-GPLv3-yellow.svg)](https://www.gnu.org/licenses/gpl-3.0.html)
[![GitHub stars](https://img.shields.io/github/stars/Justadudeinspace/termux-background.svg)](https://github.com/rD227/termux-background)

**original repository:** [termux-background](https://github.com/Justadudeinspace/termux-background)

## Overview

Termux Background brings Windows Terminal-style background customization to Termux, allowing you to personalize your Android terminal experience with custom images, visual effects, and real-time configuration updates. The project consists of two components:

- **Android Companion App**: User-friendly GUI for selecting and configuring background images
- **CLI Tool**: Command-line interface for managing background settings directly from Termux

## Features

### 🎨 Visual Customization
- **Custom Background Images**: Set PNG or JPEG images as your terminal background
- **Opacity Control**: Adjust background transparency to maintain text readability
- **Blur Effect**: Apply gaussian blur for a modern, aesthetic appearance
- **Scroll Animation**: Enable smooth scrolling animation for dynamic backgrounds
- **Live Preview**: See changes in real-time before applying

### ⚡ Performance & Usability
- **Live Reload**: Instantly apply settings without restarting Termux
- **Storage Access Framework**: Modern file picker with no legacy storage permissions required
- **Dependency Detection**: Automatic verification of required Termux components
- **Safe Operations**: Write protection when dependencies are missing
- **Settings Preservation**: Maintains all existing `termux.properties` configurations

## Requirements

### Hard Dependencies
Both must be installed for the app to function:

1. **[Termux](https://f-droid.org/en/packages/com.termux/)** - Terminal emulator for Android
2. **[Termux:API](https://f-droid.org/en/packages/com.termux.api/)** - API bridge for system integration

> **Note:** The Apply and Reset buttons remain disabled until both dependencies are detected. Install from F-Droid for best compatibility.

## Installation

### Option 1: Pre-built Packages (Recommended)

#### Android App
Download and install the latest APK from the [Releases](https://github.com/Justadudeinspace/termux-background/releases) page:

```bash
# Download latest release
wget https://github.com/Justadudeinspace/termux-background/releases/download/latest/termux-background.apk

# Install via ADB (if using from PC)
adb install termux-background.apk
```

#### CLI Tool
```bash
# Download the Debian package
wget https://github.com/Justadudeinspace/termux-background/releases/download/v1.0.4/termux-background_1.0.4_all.deb

# Install with dpkg
dpkg -i termux-background_1.0.4_all.deb
```

### Option 2: Build from Source

#### Prerequisites
- Android Studio or Android SDK
- Gradle
- JDK 8 or higher

#### Building the Android App
```bash
# Clone the repository
git clone https://github.com/Justadudeinspace/termux-background.git
cd termux-background

# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease
```

## 📦 APK Builds & Releases

### Debug builds (CI)
- Triggered on PRs, pushes to `main`, or manual runs.
- Produces a debug APK as a workflow artifact (`apk-debug`).

### Release builds (signed)
- Tag a version to publish:
  - git tag v1.0.0
  - git push origin v1.0.0
- Workflow builds a signed release APK and attaches it to a GitHub Release.

### Required GitHub Secrets (for release signing)
Set these in GitHub → Repo → Settings → Secrets and variables → Actions:

- ANDROID_KEYSTORE_BASE64
- ANDROID_KEYSTORE_PASSWORD
- ANDROID_KEY_ALIAS
- ANDROID_KEY_PASSWORD
