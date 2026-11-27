# The Blueprint — MD Reader 2.0

This is the living record of how MD Reader evolved from a simple local novel viewer into a high-performance hybrid reader for both text and art.

## 🏛 The Core Philosophy
The app exists to solve one problem: **The gap between "the files I have" and "a reading experience that doesn't suck."**
Everything in the app is built to be "invisible"—the UI should vanish, leaving only the prose or the art.

---

## 📘 Phase 1: The Novel Foundation
The goal was a "reference-in-place" model. No importing, no copying, no database bloat.

### The "Reference" Model
Instead of copying files into an app-private sandbox, we use Android's **Storage Access Framework (SAF)**. 
- **Persistence:** `takePersistableUriPermission` ensures that once you pick a folder, the app can return to it after a reboot without asking again.
- **Natural Sort:** We don't use alphabetical sorting (which puts Chapter 10 before Chapter 2). We use a natural comparator so the reading order is actually human.
- **Sumi Ink Design:** A design language based on warm paper, hairline grids, and a single vermilion accent.

### Technical Stack
- **Language:** Kotlin + Jetpack Compose.
- **Parser:** A hand-rolled Markdown parser (~150 lines) that handles headings, bold, italic, and lists without adding a single external dependency.
- **Store:** A simple `library.json` for progress and project metadata.

---

## 🎨 Phase 2: The Comics Layer (v2.0)
Expanding the app to support web-comics required a total shift in how the app handles networking and memory.

### The "Kuro" Aesthetic
Comics don't belong on paper. They belong on a "black stage." The `ComicSkin` tokens force a high-contrast, always-black environment, regardless of the system theme, to make colors pop and borders vanish.

### High-Performance Reading
To get "10x faster" reading, we implemented a tiered loading pipeline:
1. **Pooled OkHttp:** An 8-lane HTTP/2 pipeline that handles images and metadata in parallel.
2. **Single-Flight Memoization:** `ComicMemo` ensures that if five different components ask for the same chapter list, the app only makes one network request.
3. **Aggressive Prefetching:** A warm window of 8 pages, with a background sweep that stays 24 pages ahead of the user.

### The Cloudflare Problem
Modern comic sites use aggressive anti-bot shields. We solve this the "Mihon way":
- **Invisible WebView:** When a challenge is detected, the app spins up a hidden WebView with the device's real User-Agent.
- **Cookie Harvesting:** Once the challenge is solved, the clearance cookies are harvested and attached to the OkHttp client.
- **Header Matching:** By matching the browser's HTTP/2 header order, the app becomes indistinguishable from a real browser.

### Zoom & Storage
- **Zoom as a Mode:** Zoom isn't a "pop-up"; it's a first-class reading mode with its own state and navigation.
- **Public Export:** Using SAF, users can export comic pages as standard `.webp` files to any public directory (e.g., `/Downloads/Comics/`), breaking the "app silo" and making the art accessible to other tools.

---

## 📈 Future Horizons
- **Recursive Folders:** Support for novels split across sub-directories.
- **Enhanced Search:** Global search across local files and online sources.
- **Customization:** User-definable fonts and line-heights for the novel reader.
