# MD Reader

A small Android reader for novels that live as a folder of `.md` / `.txt`
chapters. Pick the folder once, read with **Previous / Next**, jump around from
the chapter list, and come back to exactly where you stopped.

One folder = one novel. Chapters are read **in place** through Android's Storage
Access Framework — nothing is copied into the app.

## Requirements

- Android 8.0 (API 26) or newer
- JDK 17+ and Android SDK 36 to build (this machine has both)
- `adb` for installing on a phone

## Build

```bash
./gradlew :app:assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

## Install

Enable **Developer options → USB debugging** on the phone, plug it in, then:

```bash
adb devices                      # confirm the phone is listed as "device"
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Launch **MD Reader** from the app drawer.

## Use

1. Tap **Import folder** and pick the folder holding your chapters. Grant access
   when Android asks.
2. The novel appears in the library; tap it to start reading.
3. Use **Previous / Next** at the bottom, or **Chapters** to jump anywhere.
4. Position is saved automatically; reopening the novel returns to the same
   chapter and scroll offset.
5. Chapter files added later: open the project's **⋮ → Refresh chapters**.

Sorting is natural, so `chapter_2` comes before `chapter_10`. Only files directly
inside the chosen folder are listed (no subfolders), and hidden files are skipped.

## Project layout

```
app/src/main/java/com/mdreader/
  MainActivity.kt          activity + edge-to-edge setup
  data/ChapterSource.kt    SAF listing and reading
  data/Import.kt           folder -> project
  data/LibraryStore.kt     library.json persistence
  data/NaturalSort.kt      filename comparator
  data/Models.kt           Chapter / Project / Progress
  md/Markdown.kt           Markdown subset parser (pure Kotlin)
  ui/MDReaderApp.kt        library <-> reader routing
  ui/LibraryScreen.kt
  ui/ReaderScreen.kt
  ui/ChapterListSheet.kt
  ui/MarkdownView.kt       blocks -> Compose
  ui/theme/Theme.kt
```

State lives in `filesDir/library.json` (projects, chapter URIs, reading
positions). Uninstalling the app deletes that file, so the library has to be
imported again — the chapter files themselves are never modified.

## Tests

```bash
gradle :app:testDebugUnitTest
```

Covers natural sorting and the Markdown parser (17 tests).

`tools/sample-novel/` holds 12 mixed `.md`/`.txt` chapters for trying the app:

```bash
adb push tools/sample-novel /sdcard/Documents/sample-novel
```

Then import `/sdcard/Documents/sample-novel` in the app.

## Known limits (v1)

- Moving, renaming, or deleting the folder breaks the project until you import
  it again.
- No font size / theme controls, bookmarks, or in-book search yet.
- Markdown covers headings, bold/italic/strikethrough, lists, quotes, rules,
  fenced code. Anything else renders as plain text.
- Subfolders inside a novel folder are ignored.
