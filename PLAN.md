# novel.md.reader — v1 plan

A minimal Android reader for novels stored as one folder of `.md` / `.txt` chapters.

> **Status:** v1 built, installed on the phone, and confirmed working.
> See `README.md` for build and usage instructions.

## Decisions locked in

| Topic | Decision |
| --- | --- |
| Platform | Native Android, Kotlin + Jetpack Compose (Material 3) |
| Chapter format | Mixed `.md` and `.txt`, detected per file |
| Folder model | One imported folder = one novel/project |
| Import mode | **Reference in place** via Storage Access Framework — no copying |
| v1 scope | Prev/Next, chapter list, remembers position |
| Distribution | Debug/release APK sideloaded with `adb` — no Play Store |
| App name / id | `novel.md.reader` / `com.mdreader` |

## How import works (the important part)

Because we reference instead of copy, the app must keep permission to the folder:

1. `ACTION_OPEN_DOCUMENT_TREE` opens Android's folder picker.
2. `takePersistableUriPermission(..., FLAG_GRANT_READ_URI_PERMISSION)` — survives reboots and app restarts.
3. List children once at import (`DocumentsContract`), keep `{displayName, documentUri}` per chapter.
4. Read chapter text on demand via `ContentResolver.openInputStream(uri)`.
5. Sort with a **natural sort** so `chapter_2.md` precedes `chapter_10.md`. Plain alphabetical fails this.
6. Provide a **Refresh** action that re-lists the folder, so chapters added later show up.

**Trade-off you accepted:** if the folder is moved, deleted, or lives on a removed SD card, the project can't open. v1 handles this with a clear "folder unavailable — re-import" state rather than a crash.

## Storage of app data

No database for v1. One JSON file in `filesDir/library.json`:

```json
{
  "projects": [
    {
      "id": "uuid",
      "name": "My Novel",
      "treeUri": "content://...",
      "chapters": [{ "name": "ch01.md", "uri": "content://..." }],
      "importedAt": 1730000000
    }
  ],
  "progress": {
    "<projectId>": { "chapterIndex": 12, "scrollIndex": 0, "scrollOffset": 0 }
  }
}
```

Serialized with Android's built-in `org.json` — zero extra dependencies, no annotation processing.

## Screens

**1. Library**
- List of imported novels (name + chapter count + last-read).
- `+ Import folder` → SAF picker.
- Long-press → delete project (removes app record only, never touches files).
- Empty state with a short "pick the folder with your chapters" hint.

**2. Reader**
- Top bar: project name, `Chapter 3 of 120`, chapter-list icon.
- Body: scrollable chapter text, restores saved scroll position.
- Bottom bar: `Prev` / `Next`, disabled at first/last chapter.
- System back returns to Library; position is saved on exit and on chapter change.

**3. Chapter list**
- Modal bottom sheet listing chapters in reading order, current one highlighted; tap to jump.

## Markdown subset (hand-rolled renderer)

Small parser, ~150 lines, no third-party markdown dependency:

- ATX headings (`#`–`###`)
- Bold / italic inline
- Bullet and numbered lists
- Blockquotes
- Horizontal rules (`---`)
- Fenced code blocks
- Paragraphs and blank-line spacing

`.txt` files bypass the parser and render as paragraphs. Anything unsupported degrades to plain text — never breaks the chapter.

## Architecture

Single Gradle module, no Hilt, no Room, no navigation library. Two composables + a small state holder:

```
app/src/main/java/com/mdreader/
  MainActivity.kt            // sets content, hosts Library/Reader switch
  data/LibraryStore.kt       // JSON load/save, project CRUD, progress
  data/ChapterSource.kt      // SAF listing, reading, permission checks
  data/NaturalSort.kt        // filename comparator
  ui/LibraryScreen.kt
  ui/ReaderScreen.kt
  ui/ChapterListSheet.kt
  md/MarkdownParser.kt       // -> List<Block>
  md/MarkdownText.kt         // Blocks -> Compose
```

Toolchain: AGP 8.x, Gradle 8.13 (installed), JDK 21 (installed), compileSdk/targetSdk 36, minSdk 26.

## Build order

1. **Scaffold** — Gradle project, empty Compose app, prove it installs and launches on the phone.
2. **Import + Library** — SAF picker, permission persistence, JSON store, natural sort, list/delete.
3. **Reader** — load chapter, Prev/Next, chapter list, save/restore position.
4. **Markdown** — parser + renderer, with JVM unit tests.
5. **Edge cases** — empty folder, folder gone, unreadable file, first/last chapter, huge chapter.

Each phase ends with something installable, so you can look at it on the phone and redirect early.

## Acceptance criteria for v1

- Import a folder of ~100 mixed `.md`/`.txt` files in under 3 seconds.
- Chapters appear in natural, correct reading order.
- Prev/Next moves exactly one chapter; buttons disable at the ends.
- Kill the app, reopen, and the same chapter + scroll position returns.
- Adding a file to the folder and tapping Refresh makes it appear in the right place.
- Moving the folder away produces a readable error, not a crash.

## Testing

- **Unit tests (JVM):** natural sort ordering, markdown parser blocks.
- **On-device:** debug APK via `adb install -r`. There is no emulator installed and no AVD, and `adb devices` currently shows nothing connected — so the phone will need to be plugged in with USB debugging on.
- **Sample data:** a small folder of numbered chapters generated locally, plus your real folder pulled with `adb pull` once the phone is connected.

## Deliberately not in v1

Font size control, themes, bookmarks, in-book search, EPUB/HTML, subfolders inside a novel, cloud sync, Play Store release. All are additive later; none block the design. *(An S/M/L reader text-size control was added post-v1; fine-grained size/line-height tuning still lives in `Theme.kt`.)*

## Resolved decisions

1. App name / package id: `novel.md.reader` / `com.mdreader`.
2. Minimum Android version: 8.0 (API 26); the test phone runs Android 11.
3. Novel folders are flat — subfolders are ignored by import.

## Deferred to v2

Font size control, themes, bookmarks, in-book search, EPUB support, recursive
subfolders, and a signed release build.
