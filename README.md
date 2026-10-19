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
minified release build, under 1 MB.

```bash
adb install -r novel.md.reader-v1.1.apk
```

Or download it on the phone itself and open it, allowing installs from unknown
sources when Android asks. If you'd rather build it, run
`./gradlew :app:assembleRelease` and check `PLAN.md` for how the whole thing
fits together.