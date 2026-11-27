# MD Reader 2.0

A lean, high-performance reader for novels and comics. 

Whether it's a local folder of `.md` files or a sprawling web-comic from MangaKakalot, MD Reader is designed to stay out of your way. No bloat, no tracking, just your content on a screen that feels like actual paper (or pure ink).

## 📖 The Experience

### For Novelists
Import a folder of `.md` or `.txt` chapters once. The app references them in place via Android's Storage Access Framework—nothing is copied, your files stay exactly where they are. 
- **Sumi & Paper:** A high-contrast, serif-driven aesthetic that respects your eyes.
- **Zero Friction:** Natural sorting (Chapter 2 comes before Chapter 10), instant jump-to-chapter, and a reading position that remembers exactly where you left off.

### For Comic Lovers
A dedicated "Kuro" (black) stage designed for immersive reading.
- **Zoom Mode:** A first-class reading experience with native-resolution decoding. Pinch to 6x, pan across wide panels, and swipe through pages with zero lag.
- **Mihon-Grade Speed:** Parallel loading, aggressive prefetching, and a pooled HTTP/2 pipeline. Pages appear before you even reach them.
- **Cloudflare-Proof:** Built-in session handling that clears challenges the honest way, ensuring your favorite sources actually load.
- **Own Your Art:** Export entire chapters as plain `.webp` files to any public folder on your device, or crop and save a specific panel to your Downloads.

## 🚀 Quick Start

### Installation
Grab the latest APK from the [Releases](https://github.com/xzeronelysium/novel.md.reader/releases) page. 
- Requires Android 8.0+.
- Minified release build (~1.5 MB).

```bash
adb install -r md-reader-v2.0.apk
```

### Building from Source
If you're into the code:
```bash
./gradlew :app:assembleRelease
```
Check `PLAN.md` for the architectural blueprint.

## 🛠 Technical Soul
- **Language:** Kotlin + Jetpack Compose.
- **Zero-Dependency Core:** Hand-rolled Markdown parser and custom image pipelines to keep the APK tiny and the performance peak.
- **Privacy First:** No database, no cloud, no telemetry. Your library and history live in local JSON files.
