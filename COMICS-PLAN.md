# Comics plan — read comics like Mihon, with BatCave + Weeb Central hardcoded

Two inputs: the Mihon source tree (`mihon/`, in the channel folder) and the
two Mihon extension sources for the sites the user named — `BatCave.kt`,
`Dto.kt`, `DleGuardResolver.kt` (keiyoushi/extensions-source, `src/en/batcave`,
baseUrl `https://batcave.biz`) and `WeebCentral.kt` (`src/en/weebcentral`,
baseUrl `https://weebcentral.com`). Both were read in full; everything below
is grounded in them.

## What Mihon actually does (the parts worth copying)

- **Model**: Manga → chapters → pages. A `Page` carries just an index + image
  URL; pages are never persisted, only cached. Reading position is
  `Chapter.lastPageRead` — one int, exactly like our scroll index.
- **Source contract** (`mihon/source-api/.../HttpSource.kt`): name, baseUrl,
  headers; `getPopular/getSearch/getLatest(page)` → list + hasNextPage;
  details; chapter list; `getPageList(chapter)` → `List<Page>`;
  `imageRequest(page)` for any per-image headers. Extensions are separate APKs
  loaded by DexClassLoader — **we skip all of that**: two hardcoded in-process
  objects, same as the existing FreeWebNovelClient.
- **Reader** (`mihon/app/.../ui/reader/`): modes LTR / RTL / vertical pager /
  webtoon continuous scroll. Page order per visible page:
  current → prev → next → rest-ahead → rest-behind, downloaded in rolling
  chunks starting at 3 concurrent; on mobile data the window clamps to
  ±4 pages. Tap zones: left third = back, right third = forward, middle =
  menu (RTL flips which side is which). Reaching the last page preloads the
  next chapter's page list.
- **Cache** (`mihon/app/.../data/cache/ChapterCache.kt`): TTL disk cache,
  page lists 1 hour (image URLs rotate), images 1 day, under
  `cacheDir/chapter_cache/{pages,images}`. The viewer's own small window of
  decoded bitmaps is the memory cache; Coil memory cache is deliberately
  disabled for reader pages.

## Site contracts (from the extensions)

### Weeb Central — `https://weebcentral.com` (no anti-bot, do this one first)

- Rate limit in the extension: **1 request per 2 s** — port the pacing.
- Search: `GET /search/data?text=<q>&limit=32&offset=<32*(page-1)>&display_mode=Full Display`
  (plus sort params). Entries: `article > section > a`; title =
  `div:not([class]):last-child`; cover = `source[srcset]` with `small`→
  `normal` replaced; hasNextPage = a `button` exists.
- Series page `/series/{id}/{slug}`: `section[x-data] > section[0]` holds
  cover, author (`li:has(strong:contains(Author)) span > a`), status
  (`li:has(strong:contains(Status)) > a` → ongoing/complete/hiatus/canceled),
  genres (`li:has(strong:contains(Tag),strong:contains(Type)) a`);
  `section[1]` holds title (`h1`) and description
  (`li:has(strong:contains(Description)) > p`).
- Chapters: `GET /series/{seriesId}/full-chapter-list` (newest first —
  reverse for reading order). Each `div[x-data] > a`: name =
  `span.flex > span`, url = the anchor, date = `time[datetime]` (ISO).
- Pages: chapter URL + `/images?is_prev=False&reading_style=long_strip`;
  images = `section[x-data~=scroll] > img` (`abs:src`), in order. Image
  request adds a `Host` header in the extension — copy that.

### BatCave — `https://batcave.biz` (DLE engine, has anti-bot)

- Popular: `POST /comix` with form `dlenewssortby=rating&dledirection=desc&set_new_sort=dle_sort_cat_1&set_direction_sort=dle_direction_cat_1`;
  latest: `GET /`; search: `GET /search/<query>` (+ `/page/N`).
  Entries: `#dle-content > .readed`; title `.readed__title > a`; full poster
  = `/uploads/posts/hd/<id>.webp` derived from the comic URL.
- Series page: title `header.page__header h1`; cover `div.page__poster img`;
  description = Publisher/Year list items + `div.page__text`; status from the
  `Release type` list item (Ongoing/Completed); tags `div.page__tags a`.
- Chapters: a `window.__DATA__ =` JS object on the series page —
  JSON `{news_id, chapters:[{id, posi, title, date}], xhash}`. Chapter
  identity = `/reader/{news_id}/{chapter.id}{xhash}`.
- Pages: `POST /engine/ajax/controller.php?mod=api&action=reader/getChapterData`
  with JSON `{news_id, chapter_id}` → `{data:{images:[urls]}}`.
- **DLE Guard (the hard part)**: requests can 302 to `/_c`, a JS challenge
  that sets a `__guard_trust` cookie. The extension solves it with a
  headless WebView (30 s, poll every 250 ms for the cookie, 5 s trust window
  so parallel requests don't each solve, one failure = give up with "open in
  WebView" message). A port needs the same: detect the `/_c` redirect, run an
  offscreen `WebView` once, harvest the cookie from `CookieManager`, attach
  it to the HttpURLConnection cookie store, retry. This is the single
  riskiest piece of the whole feature — schedule it as its own phase and
  keep a graceful "site protection blocked us, try again" error like the
  existing 403 handling.

## Target design in the MD reader

Reuse the app's existing shapes — this is deliberately a sibling of the
FreeWebNovel code, not a rewrite.

1. **Model** (`data/Models.kt`): `Project.kind` — `NOVEL` (default) or
   `COMIC` — plus `sourceId: String?` ("freewebnovel" / "batcave" /
   "weebcentral"). `Chapter` works as-is (url = site path or `/reader/...`
   for BatCave). Comic reading position = `Progress.scrollIndex` as the page
   number; nothing new to persist. `totalChapters`, `coverUrl`, synopsis
   fields are already there.
2. **Sources** (`data/comics/`): one object per site implementing a tiny
   in-process interface:
   `search(q, page)`, `popular(page)`, `latest(page)`, `details(path)`,
   `chapters(path)`, `pageImages(chapterPath)`, `imageHeaders(url)`.
   `WeebCentral.kt` and `BatCave.kt` each ~200 lines over the existing
   `HttpURLConnection` helper — same gzip + size-cap + backoff plumbing the
   novel client already has (and WeebCentral's 1-req/2 s pacing).
   No HTML library needed: the app already hand-parses; if selectors get
   painful, Jsoup is a ~350 KB addition — decision point, default is no.
3. **Page cache** (`data/comics/PageCache.kt`, new): Mihon's TTL design on
   the existing Vault pattern — `cacheDir/comic_cache/pages/` (page lists,
   1 h TTL, keyed by chapter url) and `cacheDir/comic_cache/images/`
   (1 day TTL, keyed by image url hash), pruned to a byte budget like
   `CoverCache`. Decode downsampled to view size (`CoverCache.decode`
   pattern).
4. **Reader** (`ui/ComicReaderScreen.kt`, new, no changes to the text
   reader): **one mode only — continuous vertical strip (webtoon style),
   per the user's explicit decision. No paged mode, no tap zones.**
   - `LazyColumn` of page images, continuous scroll, one page per item.
   - Menu/chapter access stays where the app already puts it: the top-bar
     INDEX button. No middle-tap menu.
   - Preload window ±3 pages ahead-first (mobile data: clamp, reuse the
     prefetch-skip-on-cellular behavior already in the app). Near the last
     page, prefetch the next chapter's page list.
   - Zoom/pan and RTL are explicitly out of scope.
5. **Shelf/detail integration**: `MDReaderApp` routes by `kind`:
   comics open `ComicReaderScreen`; detail page reuses cover/synopsis/status
   layout; the Add dock becomes a source picker — novels (FreeWebNovel),
   comics (BatCave, Weeb Central). `LibraryScreen` rows get a small COMIC
   marker. `ChapterListSheet` reused as-is.

## Phases, each with an acceptance gate

- **A. Weeb Central online reading** (model + source + strip reader +
  shelf integration). Gate: search → add → open → read a chapter → kill
  the app → same chapter/page restores; shelf shows comics beside novels;
  unit tests for the parser (fixtures from real pages, like
  `FreeWebNovelParserTest`); rate limit visible as polite pacing. Reading is
  continuous vertical webtoon style only — no paged mode, no tap zones.
- **B. BatCave + DLE Guard**. Gate: search → details → chapters → pages all
  work; after the first `/_c` hit the guard is solved once by the headless
  WebView and never again within the session; if solving fails the reader
  shows a plain error and a retry, never a crash or a loop.
- **C. Offline comic downloads** (only if wanted): Vault-pattern
  `comic/<source>/<series>/<chapter>/NNN.jpg` + the existing download
  progress sheet. Gate: airplane-mode reading of a downloaded chapter.

## Risks / decisions

- BatCave's DLE Guard is JS-driven and can change without notice; the
  extension itself needed a `DleGuardResolver` redesign (v14). Expect
  maintenance.
- Both sites are aggregator sites — same legal posture as the existing
  FreeWebNovel source; nothing new is being decided there.
- If BatCave guard-solving proves unusable in-app, a fallback is shipping
  Weeb Central first (Phase A/B) and BatCave browse-only-with-manual-retry;
  the user decides after Phase C's first attempt.
- APK size: staying dependency-free keeps the ~1.2 MB release; adding Jsoup
  is acceptable only if hand-parsing the two sites turns out measurably
  worse than the FreeWebNovel parser was.

Sources: `mihon/` repo (HttpSource.kt, ChapterCache.kt, ui/reader/,
viewer/navigation/) in the channel folder; keiyoushi/extensions-source
`src/en/batcave` (BatCave.kt 318 l, Dto.kt, DleGuardResolver.kt) and
`src/en/weebcentral` (WeebCentral.kt 303 l) at commit-current main.

## Status (2026-11-25)

- **Phase A — shipped.** Weeb Central search, series page, chapter list and page
  images read from the live site with the selectors above; comics live on the
  shelf (`Project.kind = COMIC`, `sourceId`), open in a continuous vertical
  strip reader, and restore chapter + page after the process is killed. Parser
  tests plus a live-page smoke check pass; release installed and verified on the
  emulator (the phone was disconnected at install time).
- **Phase B — blocked by Cloudflare.** batcave.biz now serves Cloudflare's
  "Just a moment" challenge, not only the DLE `/_c` redirect. The offscreen
  WebView clears it (the real page loads with title "Read Comics Online for
  Free | BatCave.biz") and the cookies are harvested, but plain
  `HttpURLConnection` requests are still refused with the clearance cookies
  attached. Making BatCave work therefore needs every page and AJAX request
  proxied through the WebView (`outerHTML` / JS fetch), which is not shipped.
  The dock shows a plain "protection blocked the request" error with retry.

## Addendum — how Mihon itself passes Cloudflare (2026-11-25, from the mihon/ source)

The user asked to match how the Mihon *app* handles this. Mihon does not
proxy everything through a WebView; it makes plain OkHttp requests work.
From `mihon/core/common/.../network/`:

- `NetworkHelper.kt`: one OkHttp client for everything, with
  `AndroidCookieJar()` — a **two-way** cookie jar backed by the *system
  `android.webkit.CookieManager`*, the same store its WebViews use — and
  `UserAgentInterceptor` stamping one fixed browser UA on every request
  (`NetworkPreferences`: "Mozilla/5.0 (Linux; Android 10; K) …
  Chrome/149 … Mobile Safari/537.36").
- `interceptor/CloudflareInterceptor.kt`: a response is a challenge iff
  header **`cf-mitigated: challenge`** and `Server: cloudflare` (the
  official Cloudflare detection header). On a challenge:
  1. Per-host read/write lock — concurrent requests to the same host all
     wait for **one** WebView solve instead of each opening their own.
  2. The clearance check is `cf_clearance` cookie present *and changed*.
  3. Headless WebView loads the challenge URL — **for non-GET requests,
     the site root instead** (`newBuilder().encodedPath("/")`), because a
     WebView can only GET and "the clearance covers the whole site".
  4. The WebView is created with `setUserAgent(request's UA)` — the *same*
     UA the client sends, which is what our code already does.
  5. It waits up to 30 s; if the challenge turns **interactive**
     (`interactiveBegin` event) it aborts — even Mihon gives up there and
     fails with a readable message. On failure it deletes the bad
     `cf_clearance` so it isn't replayed.
  6. Then `chain.proceed(request)` again — fresh cookies, same UA, and it
     passes because OkHttp speaks **HTTP/2 with browser-like header
     ordering**, matching what Cloudflare saw in the WebView.

**What our shipped code is missing** (checked against
`data/comics/{ComicHttp,BatCaveSource}.kt`):

1. **No `Set-Cookie` handling at all** — cookies are snapshotted once from
   the WebView and replayed forever; response cookies (`__cf_bm` rotates
   ~30 min) are dropped. Mihon's jar is bidirectional and shared live.
2. **Detection by body markers**, not the `cf-mitigated` header.
3. **No single-flight lock** — each concurrent request can trigger its own
   solve; Mihon funnels a host through one WebView.
4. **HTTP/1.1 via `HttpURLConnection`** vs Mihon's OkHttp/HTTP-2 stack.
   With UA and cookies already matching, this fingerprint difference is
   the leading suspect for "cookies attached but still refused".

**Fix order** (empirical, stop when it passes on the emulator against
live batcave.biz):

1. Make the cookie flow bidirectional against the system CookieManager
   (read before request, write every `Set-Cookie` back), switch challenge
   detection to `cf-mitigated`, add the per-host single-flight solve, and
   solve non-GET on the site root per Mihon. Keep `HttpURLConnection`.
2. If still challenged: move the **comic** HTTP layer to OkHttp (HTTP/2,
   same header order as a browser), keeping everything else — this is
   Mihon's actual stack and the one justified new dependency; expect
   roughly +0.8–1 MB APK, report the measured number.
3. If even OkHttp is challenged with an interactive Turnstile, that is
   the same wall Mihon hits — ship the clean error + retry and report; no
   honest app can promise past that point.

**Gate**: BatCave search → series → chapters → page images all load; a
force-stop + reopen still works within the cookie window; an interactive
challenge produces the readable error, no crash, no solve loop. Record
which step of the fix order made it pass.

## Status update — BatCave solved (2026-11-25)

BatCave now works end to end (search → series → chapters → 48 page images), verified on the
phone from a **cold cookie jar** and again after force-stop. What was needed, in the order
tried:

1. **HTTP/1.1 + bidirectional cookies failed.** With the shared CookieManager jar, live
   Set-Cookie writes, `cf-mitigated` detection, single-flight solve and a root-page solve, the
   retry was still refused; `HttpURLConnection` never got past Cloudflare.
2. **OkHttp (HTTP/2, browser header order) is what made it pass** — the justified dependency
   from the addendum. Release APK went from 1.22 MB to 1.41 MB (**+0.19 MB**, far less than the
   0.8–1 MB estimate because R8 shrinks it).
3. Two details beyond the addendum were required, both discovered empirically:
   - the working clearance here is the site's DLE `__guard_trust` cookie, set by the WebView a
     moment *after* the page finishes — the solve now waits up to 12 s for it before retrying;
   - a cold jar answers the first search with a **decoy 404** (no `cf-mitigated`), so a 404 is
     treated as retryable once: solve, then retry.

The first request of a process primes the jar with one WebView load of the site root. No
proxying of page content: HTML/JSON still come over OkHttp, exactly like Mihon.

## Release 1.8 (2026-11-25)

BatCave UI verified on the phone end to end: dock search → series page (12 chapters) →
continuous strip showing page 1/48. The earlier UI failure was a stale/pre-fix build; the
session logs showed `ok 78646 chars` on the same action. Release 1.8 (code 9) removes the
temporary stage logs, keeps OkHttp 4.12, and the release APK is 1.41 MB.
