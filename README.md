# novel.md.reader

A small Android reader for novels that live as a folder of `.md` / `.txt`
chapters. You pick the folder once, read with Previous / Next, jump around from
the chapter index, and come back to wherever you stopped.

The interface wants to get out of the way: warm paper and sumi ink, a hairline
grid, a single vermilion accent, serif type for the prose. It follows the
system light/dark setting.

One folder is one novel. Chapters are read in place through Android's Storage
Access Framework, so nothing gets copied into the app and your files stay where
they are.

## Requirements

- Android 8.0 (API 26) or newer
- JDK 17+ and Android SDK 36 to build
- `adb` for installing on a phone

## Build

```bash
./gradlew :app:assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

## Install

Turn on Developer options and USB debugging on the phone, plug it in, then:

```bash
adb devices                      # confirm the phone shows up as "device"
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Launch **novel.md.reader** from the app drawer.

## Use

1. Tap Import folder and pick the folder holding your chapters. Grant access
   when Android asks.
2. The novel shows up in the library. Tap it to read.
3. Previous / Next sit at the bottom. Index opens the full chapter list, which
   filters by title or filename and also carries an S / M / L text size switch.
4. Position saves itself as you read. Reopening a novel puts you back on the
   same chapter at the same scroll offset.
5. Added chapters to the folder later? Use the project's ⋯ → Refresh chapters.

Sorting is natural, so `chapter_2` comes before `chapter_10`. Only files
directly inside the chosen folder are listed (no subfolders), and hidden files
are skipped.

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
  ui/Design.kt             shared hairlines, micro labels, import action
  ui/LibraryScreen.kt
  ui/ReaderScreen.kt
  ui/ChapterListSheet.kt
  ui/MarkdownView.kt       blocks -> Compose
  ui/theme/Theme.kt
```

App data lives in `filesDir/library.json`: the projects, their chapter URIs,
reading positions, and the text size choice. Uninstalling deletes that file, so
you'd have to import again. The chapter files themselves are never touched.

## Tests

```bash
./gradlew :app:testDebugUnitTest
```

Covers natural sorting and the Markdown parser (17 tests).

`tools/sample-novel/` holds 12 mixed `.md`/`.txt` chapters if you want
something to poke at:

```bash
adb push tools/sample-novel /sdcard/Documents/sample-novel
```

Then import `/sdcard/Documents/sample-novel` in the app.

## Known limits

- Moving, renaming, or deleting the folder breaks the project until you import
  it again.
- There's no bookmarking, no search inside a chapter, no theme beyond the
  system light/dark setting. Text size is the S / M / L switch in the index.
- Markdown covers headings, bold/italic/strikethrough, lists, quotes, rules,
  and fenced code. Anything else renders as plain text.
- Subfolders inside a novel folder are ignored.

No plans to grow past this. The app reads folders of chapters, and that's the
whole list.