# novel.md.reader

A minimal Android reader for novels that live as a folder of `.md` / `.txt`
chapters. You pick the folder once, read with Previous / Next, jump around from
the chapter index, and come back to wherever you stopped. Chapters are read in
place through Android's Storage Access Framework, so nothing gets copied into
the app and your files stay where they are.

The interface wants to get out of the way: warm paper and sumi ink, a hairline
grid, a single vermilion accent, serif type for the prose. It follows the
system light/dark setting.

## Download

Grab the APK from the [latest release](https://github.com/xzeronelysium/novel.md.reader/releases/latest)
and install it on your phone. Requires Android 8.0 or newer. The APK is a
minified release build, about 1 MB.

```bash
adb install -r novel.md.reader-v1.6.apk
```

Or download it on the phone itself and open it, allowing installs from unknown
sources when Android asks. If you'd rather build it, run
`./gradlew :app:assembleRelease` and check `PLAN.md` for how the whole thing
fits together.

## Rewrite

Under the chapter rail, **ORIGINAL | REWRITE | ⚙** rewrites the open chapter
with a model on Ollama Cloud (base URL and API key in ⚙; the key is sealed with
the Android Keystore) or on a SuperGrok subscription (⚙ → Connection → Grok: the
app shows a code, you approve it on x.ai, and the grant is sealed and renews
itself; it uses the sign-in of xAI's Grok CLI, which xAI has not published as an
API). The same events, better told: every beat, name and number
kept, with texture, staging and sharper dialogue added. The original is never
touched, and one tap flips between the two.

**Rewrite ahead** (⚙ → Rewrite ahead) rewrites a run of chapters, from one number
to another, in order, so they are ready before you read them. It runs as a
background service with a progress notification and a Stop button, skips chapters
that already have a rewrite (or redoes them), and stops at the first failure,
keeping what it finished.

A rewrite is one fixed script, no agent loop:

1. **Memory.** Earlier chapters (the last 20, the last 100, or all of them) are
   downloaded if needed and read into a small wiki per novel: people (state,
   voice, aliases), places, world rules, open threads and the story so far.
2. **Plan.** The model maps the chapter's beats and their causes, and splits it
   into scenes, each with a type, a mood to amplify and details to add.
3. **Write.** Each scene is written on its own, then checked in code (length,
   beats, numbers, banned words, repeated descriptions, invented names). A
   failing scene gets one repair with the exact reasons.
4. **Record.** What the chapter establishes goes back into the memory.

Everything stays in app-private storage under `rewrites/<novel>/`. The prompts
are in `data/RewritePrompts.kt`; system screens, chat logs, letters and scene
breaks each have a fixed Markdown form that the reader draws in its own way.

Dialogue may be reworded for naturalness while preserving meaning, intent and
character voice. The plan reserves exact wording only for phrases that depend
on it, such as catchphrases and wordplay. Code checks those phrases survive;
it does not prove semantic equivalence or identify every speaker. The writing
instructions preserve whether each passage is spoken, thought or reported.

Debug builds include a prompt lab for measuring prompt changes on real
chapters: `tools/lab/lab.py start <run>` rewrites a fixed test set on the phone,
and `tools/lab/lab.py pull <run>` scores it and writes a side-by-side report to
`~/mdreader-lab`.
