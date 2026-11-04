# Online novels: LNReader research

**Status:** Research for a possible future feature, not an implementation or an approved design.

**Purpose and decision:** Keep local chapter-folder reading and add Free Web Novel as the **only** online source. A general plugin system, plugin marketplace, and other online sources are out of scope. Implementation and remaining UX details have not been approved yet.

**Inspected:** `novel.md.reader` at `0ff64de`, the local `/home/xzeronelysium/Documents/lnreader` checkout at `dc394e85`, the separate plugin repository at `5030252adcf6c2a8f1ce324763a22e46bcba4592`, and the installed Python packages `lightnovel-crawler` 4.14.0 and `lncrawl-scraper` 1.7.0. References describe those revisions and may move later. Selected live source operations were tested on this PC; no Android integration has been implemented or tested.

## Selected source: Free Web Novel

The user selected **Free Web Novel** as the sole online source.

LNReader's v3.0.0 manifest identifies its entry as `FWN.com`, site `https://freewebnovel.com/`, English, plugin version `2.3.9` when inspected; the manifest is on a moving branch and can change.[1]

Its source configuration in the plugin repository is part of the `readnovelfull` multisource template and specifies search at `search`, chapter listing at `api/chapterlist.php` with an `aid` parameter, and image referer handling.[5][6]

Current versions are available on the repository's moving `master` branch.[2][3] These are observations from source, not a verified contract or a guarantee that the endpoints remain usable.

The site's home page and one novel page were reachable through web extraction; the sample novel page exposed only chapters 1–40 in the returned page, so complete chapter enumeration must be checked rather than assumed.[7][8]

A prior LNReader issue reported the same 40-chapter symptom and was closed after a referenced pagination fix; that historical report does not establish current behavior.[4] The later lncrawl investigation below tested individual search, chapter-list and chapter retrieval operations, but not an Android search-to-reading flow or LNReader plugin execution.

For the future, prefer a **single native Kotlin integration** modeled on the source's observed behavior, rather than running an LNReader plugin host. The upstream template and its Free Web Novel-specific configuration should be inspected again at implementation time: source layout, pagination, request behavior and text ordering can change. Copying upstream code or assets requires license compliance. Do not assume source access, content rights, downloads, anti-bot restrictions, or a particular HTML rendering strategy have already been resolved.

## Installed lncrawl: direct-reading research

Installed CLI: `/home/xzeronelysium/.local/bin/lncrawl`, `lightnovel-crawler` **4.14.0**, with `lncrawl-scraper` **1.7.0**. The relevant installed source files are under `/home/xzeronelysium/.local/lib/python3.13/site-packages/`; paths below are relative to that directory. This is a Python program running on the PC, not an Android/Kotlin library.

### Observed code path

1. `sources/en/f/freewebnovel.py:11-14` selects `https://freewebnovel.com/`. The shared template `lncrawl/templates/freewebnovel.py:12-18` supplies HTML selectors and sets a rate-limit value of 2. Its search implementation POSTs `searchkey` to `/search/` (`:30-35`).
2. The inherited `SoupTemplate.read_novel` fetches a novel page, reads metadata, and calls `parse_toc` (`lncrawl/core/template.py:53-89,139-145,204-208`). The site-specific crawler extracts `totalPage`/`pageSize` from `chapterPagination`, defaulting to option count and page size 40 (`sources/en/f/freewebnovel.py:16-30`).
3. It submits POST requests to the novel URL with `ajax=chapters`, `page` and `pageSize`, expects a JSON `html` fragment, and extracts chapter anchors (`sources/en/f/freewebnovel.py:32-71`; `scraper/session.py:720-753`). This is a different observed route from the LNReader configuration's `api/chapterlist.php`; either route's future behavior must be tested, not assumed.
4. Despite its name, `download_chapter` first retrieves **one** chapter URL, extracts its HTML content and puts it in `chapter.body` (`lncrawl/templates/freewebnovel.py:40-62`; `lncrawl/core/models.py:113-134`). Writing a downloaded book is a separate workflow; the same fetch-then-render shape can inform an on-demand reader without downloading the whole novel.
5. The template expects `.m-read`, applies Unicode NFKC, uses page style selectors and a cleaner, and removes images (`lncrawl/templates/freewebnovel.py:17,20-28,37-62`). These are implementation-specific transformations, not a guarantee of faithful text: verify ordering, formatting and content preservation on real chapters instead of blindly copying them.

### Live, limited test on this PC

Using the installed `FreewebnovelCrawler` class and its `lncrawl-scraper` session in an isolated scratch data directory, with browser solver, proxy and archive disabled:

- The crawler's own search for `Cultivation Online` returned **zero results**. Its legacy POST `/search/` returned HTTP 200, but no matching title or selected search results. This does not prove that the book is absent.
- A separate GET to `/search?keyword=Cultivation+Online&page=1` returned HTML with the `Cultivation Online` link. This request used the installed scraper session but was **not** the crawler's own search method; this is a candidate to verify further, not a proven stable API.
- The book page was HTTP 200; lncrawl parsed **68** chapter pages of size **40**. Rather than issue all 68 requests, two representative requests returned 40 chapter links on page 1 and 8 on page 68, ending at **Chapter 2688**. The last response reported a total of **2,688** chapters; intermediate pages and full list ordering were not tested.
- Calling the installed `download_chapter` for Chapter 1 returned a nonempty HTML body (15,711 characters). It was kept in memory; no novel or chapter was saved as a download. Content completeness and order were not independently verified.

The earlier simple Python HTTP request received 403; the lncrawl-scraper session succeeded for these particular requests. The installed scraper supports multiple transport and recovery mechanisms (`lncrawl/services/scraper.py:92-153,155-201`; `scraper/config.py:75-130`), so success here does not establish that a standard Ktor request on Android will succeed. We deliberately did not exercise the browser challenge solver, proxies, or archive. Handle access restrictions openly and do not assume they can or should be bypassed.

**Licensing:** installed `lightnovel-crawler` package metadata declares `GPL-3.0-or-later`; `lncrawl-scraper` is separately distributed under Apache 2.0. Inspect license obligations before reusing or translating source code and before distributing any derivative; this is not a legal conclusion. Source observations are research, not approval to copy implementation. Site terms and content rights remain separate.

## Comparison and recommended principles

The user wants an independently created solution informed by **how** both projects work—not either application's code, plugin system, runtime or downloader. The recommendation below is an engineering proposal, not an implemented or approved feature.

| Concern | lncrawl 4.14.0 | LNReader Free Web Novel plugin at `5030252a` | Recommendation for our single source |
| --- | --- | --- | --- |
| Search | POSTs `searchkey` to `/search/`; returned no results in our bounded test. Its installed source index marks this source `can_search: false`, and the CLI filters on that flag (`lncrawl/commands/search/search.py:72-77`). | Builds a GET request to `/search` with `keyword` and `page` (`readnovelfull/template.ts:993-1045`; Free Web Novel configuration).[5][6] | Follow the **observed GET search behavior** as the initial candidate, not lncrawl's stale search. The direct GET worked with the PC scraper; the LNReader plugin and Android client have not been tested end-to-end by us. |
| Chapter list | Requests each JSON page from the novel URL; its parallel resolution emits results by completion, and it assigns chapter ids as they arrive (`sources/en/f/freewebnovel.py:32-65`; `lncrawl/core/taskman.py:194-203`; `lncrawl/core/template.py:241-255`). | The FWN configuration uses a separate chapter-list endpoint; the shared template also contains a separate paginated JSON strategy for other configured sources (`template.ts:212-296,722-826`).[5][6] | Start with the **site's paginated list response** we tested. Retrieve pages lazily or sequentially, retain the page number and order the results by source page and position, not network completion. Treat missing/duplicate pages and mismatched counts as incomplete; never silently claim success. |
| Chapter reading | Fetches one chapter, extracts an HTML body in memory, and separately supports export. | Fetches one chapter and sanitizes it for its reader; it can fall back from a downloaded copy to online fetch (`src/screens/reader/hooks/useChapter.ts:163-213`). | Adopt the shared principle: **fetch just the chapter the user opens**, convert supported content into our existing Compose reading blocks, render it, and save reading position. No whole-book download or export is required. |
| Rendering | Performs source-specific cleanup and normalization (`lncrawl/templates/freewebnovel.py:20-62`). | Parses chapter HTML; FWN-specific generated code includes a content-order correction. LNReader then sanitizes and renders HTML in a WebView (`template.ts:831-990`; `src/screens/reader/utils/sanitizeChapterText.ts:42-110`).[5][6] | Treat HTML as untrusted data. Preserve paragraph order, whitespace, punctuation and emphasis; use a minimal, tested HTML-to-Compose conversion. Do not execute site scripts. Test ordering and edge cases rather than adopting either project's cleanup blindly. |
| State / progress | Primarily a crawler/export workflow. | Tracks chapter identity separately from ordering (`src/database/schema/chapter.ts:10-31`). | Keep local books and SAF behavior unchanged. Save online novel/chapter identity using stable remote paths and save scroll position; remap progress by chapter identity when a list refresh changes positions. |

### Concrete evidence and traps

- `lncrawl`'s parallel ordering is a genuine risk: a local, no-network test submitted pages `[1, 2]` with page 2 finishing first, and `TaskManager.resolve` returned `[2, 1]`. Its chapter assignment follows arrival order. Do not treat completion order or filename sorting as reading order; preserve page order and the source-provided order within each page. `lncrawl/core/crawler.py:276-280,301-305` does not repair that ordering.
- A bounded PC probe of the same `Cultivation Online` chapter-list URL using **GET** returned page 1 (chapters 1–40) and page 2 (41–80), with server page and totals. The earlier lncrawl-style **POST** test also worked for first and last pages. A GET for page 69 returned HTTP 200 but **page 68** again; validate response page numbers and avoid looping or duplicating the final page. These tests did not fetch every page and do not prove a full 2,688-chapter list or universal behavior.
- The source page's `#indexListPage` reported 68 pages, 40 per page and 2,688 total in our probe. Current values and markup can change; do not hard-code a total or assume all chapter numbers are continuous.
- The upstream FWN config specifies `api/chapterlist.php` and `aid`, and the shared template chooses its form parameters in `template.ts:722-750`.[5][6] That is a different list route from lncrawl's tested paginated request; the FWN `chapterListPaginated` flag is not set in the inspected config. We have **not** verified LNReader's exact installed plugin or its endpoint end-to-end; do not describe its paginated helper as the FWN path.
- FWN's pinned upstream configuration includes a site-specific script for cases with `data-reader-order` and CSS order hints.[5] A bounded live probe of `Cultivation Online` chapter 1 found no such attributes or order styles on its article's direct children. Other chapters and future markup may differ. Add a fixture for such markup and verify intended order; never indiscriminately sort paragraphs or transform all Unicode.
- PC retrieval via the installed scraper succeeded for the test while a basic Python request previously returned 403. This does not prove Ktor/Android will work. First verify ordinary permitted requests on the target environment; show clear network/access errors instead of bypassing access controls or claiming success from extracted web pages.

### Suggested first implementation and verification

1. Add a **Free Web Novel** entry to the library, with search → select novel → chapter list → open one chapter → previous/next → resume. Keep local folder import, reading, refresh and deletion unchanged. No source picker, dynamic plugins, Python runtime, server, accounts or bulk downloads are necessary for the first slice; any small chapter cache or prefetch must be justified and is not required for an initial implementation.
2. Implement one small native site client with Ktor, explicit result/error types, cancellation, timeouts and restrained request pacing. Pick and verify an HTML parser; parse only expected elements and validate same-origin chapter links. A chapter page is transient reader input, not a permanent download. Full book offline use and background updates are outside this proposed first slice.
3. Represent local and online sources distinctly without changing or deleting existing SAF grants. Preserve the existing `library.json`, project ids and progress using a versioned migration; test old fixtures, interrupted writes, and failure recovery before any persisted format is replaced. Record online progress with chapter URL/path, not merely list index.
4. Validate actual search results, source metadata, representative early/middle/last list pages, stable page order, page mismatch, duplicate/missing rows, response totals and chapter body fidelity. Use synthetic fixtures for malformed HTML, markup ordering, entities and errors; do not rely on numbers in URLs alone. Verify 403/429/offline/empty-content states and retry behavior; do not treat an error page as chapter content.
5. Exercise end-to-end on an Android test build: search `Cultivation Online` → choose it → open early and late chapters → previous/next → leave and resume → refresh while preserving progress; verify local reading still works. Check build/test output and device results separately. Do not install or interact with a device unless requested. The user retains the decision on initial scope and UX.

## How LNReader does it

LNReader is an Android React Native/TypeScript app. Source scrapers are maintained separately from the reader app, in the community [lnreader-plugins repository](https://github.com/lnreader/lnreader-plugins). Its published manifest lists plugins, with metadata such as ID, site, language, version, and a URL to compiled JavaScript (`src/plugins/types/index.ts:96-109` in LNReader). Enabled repository URLs are read from its database, and their JSON lists are fetched and combined (`src/plugins/pluginManager.ts:178-195`; `src/database/queries/RepositoryQueries.ts:9-17`).

A typical online flow is:

1. Choose or install a source plugin. The manager downloads its compiled JavaScript and optional custom JS/CSS, stores it, and initializes it (`src/plugins/pluginManager.ts:126-158`).
2. Browse or search a source via `popularNovels` and `searchNovels` (`src/plugins/types/index.ts:118-130`).
3. Select a result; `parseNovel` returns metadata and chapter references, with an optional `parsePage` for paged chapter lists (`src/plugins/types/index.ts:49-89,118-130`; `src/services/plugin/fetch.ts:20-30,50-69`).
4. Open a chapter: `parseChapter` fetches its content. The [plugin API documentation](https://github.com/lnreader/lnreader-plugins/blob/master/docs/docs.md#pluginbaseparsechapter) specifies an HTML string, not Markdown (`src/services/plugin/fetch.ts:32-39`). LNReader sanitizes chapter HTML and uses a WebView for its reader (`src/screens/reader/hooks/useChapter.ts:180-213`; `src/screens/reader/utils/sanitizeChapterText.ts:42-110`; `src/screens/reader/components/WebViewReader.tsx:455-463`).
5. For offline reading, downloaded chapters are stored as HTML and images. The reader first tries the stored file and falls back to fetching from the plugin (`src/services/download/downloadChapter.ts:21-98`; `src/screens/reader/hooks/useChapter.ts:163-177`). Downloads are an explicit feature of LNReader, not proof that every website permits or supports offline use.

Novel identity in LNReader is `(pluginId, path)` and chapter identity is `(novelId, path)`; it records chapter position and progress separately (`src/database/schema/novel.ts:9-32`; `src/database/schema/chapter.ts:10-31`). That distinction matters when sources reorder or add chapters.

### Plugin compatibility and trust

LNReader evaluates downloaded plugin code with JavaScript `Function(...)` and supplies a `require` shim for a specific set of host libraries including Cheerio, network helpers, storage, filters and other utilities (`src/plugins/pluginManager.ts:31-64`). Plugins may depend on those libraries, headers, cookies, settings, web storage, pagination, custom scripts or other app behavior. The [plugin documentation](https://github.com/lnreader/lnreader-plugins/blob/master/docs/docs.md) and [quick start](https://github.com/lnreader/lnreader-plugins/blob/master/docs/quickstart.md) describe the contract and source authoring process.

A manifest alone is **not** enough to run LNReader plugins in our Kotlin app. Full compatibility would require a JavaScript runtime, the expected host APIs/libraries and an explicit security/trust model. The observed loader evaluates remote code; that does not establish that plugins are sandboxed. We should not execute arbitrary downloaded code or assume compatibility without a separate design and security review.

Security and reliability details worth carrying into that review:

- The inspected install path fetches the bundle and evaluates it without an apparent integrity or signature check (`src/plugins/pluginManager.ts:126-132`). LNReader's Android build also enables cleartext traffic (`app.json:43-47`). Our implementation should not assume that a repository URL makes code trustworthy.
- The reader's WebView enables JavaScript and file access (`src/screens/reader/components/WebViewReader.tsx:455-463`). Sanitizing chapter HTML is useful but is not a substitute for a separate plugin execution and WebView security review.
- LNReader's `fetchText` returns an empty string on failure (`src/plugins/helpers/fetch.ts:76-99`), which can obscure the difference between a failed request and an empty chapter. Our UI should distinguish these cases.

## Our app today

- It is a small native Kotlin/Jetpack Compose Android app, currently designed for local folders. `Project` requires a `treeUri`, and `Chapter` holds a file URI; chapter titles and Markdown detection are filename-based (`app/src/main/java/com/mdreader/data/Models.kt:3-46`).
- Folder import and refresh use Android's Storage Access Framework, list supported local files and keep them in place (`app/src/main/java/com/mdreader/data/Import.kt:14-41`; `app/src/main/java/com/mdreader/data/ChapterSource.kt:9-17,42-85`; `app/src/main/java/com/mdreader/ui/MDReaderApp.kt:120-141`).
- `LibraryStore` keeps projects and progress in `filesDir/library.json`; it currently deduplicates projects by folder URI, persists file URIs, and saves progress by chapter index (`app/src/main/java/com/mdreader/data/LibraryStore.kt:12-18,32-46,79-85,87-178`). It is not yet an online-source store.
- The reader directly calls `ChapterSource.read` with a content URI and displays a folder-specific failure message. It then renders Markdown or plain text into Compose blocks (`app/src/main/java/com/mdreader/ui/ReaderScreen.kt:91-115`; `app/src/main/java/com/mdreader/md/Markdown.kt:12-41`). Online HTML needs a deliberate conversion/rendering strategy; treating it as Markdown or plain text directly is not equivalent.
- The checked-in Android manifest declares no internet permission (`app/src/main/AndroidManifest.xml:1-23`), and the app module declares no dedicated networking or HTML parser dependency (`app/build.gradle.kts:54-69`).
- The current JSON load wraps all projects in one `runCatching`; a malformed or incompatible entry can clear the in-memory library (`app/src/main/java/com/mdreader/data/LibraryStore.kt:92-134`). Chapter-list UI keys also use the chapter URI (`app/src/main/java/com/mdreader/ui/ChapterListSheet.kt:123-132`). Both must be considered when evolving the model.

## Proposed future implementation

Implement browse/search, novel details, chapter listing and chapter content for **Free Web Novel only**, keeping local folders intact. A native integration fits the existing Kotlin/Compose app and avoids executing third-party plugin code; it does not imply LNReader plugin compatibility. Use Ktor if/when a network client is needed, consistent with the preferred Kotlin stack; select an HTML parser only after testing real source pages. There is no need to build source selection or a general plugin architecture.

### Potential shape of a small first slice

Keep the existing local path working and distinguish local from Free Web Novel chapter loading. For the online path, expose browse/search → novel details/chapter list → add to library → read a chapter → resume. On-demand access is a possible first slice; offline downloads, background updates, images and authentication remain scope decisions. Preserve the app's existing look and Compose reader where practical.

Before implementation, explicitly address:

- **Identity and migration:** Differentiate local and online novels; use stable source/novel/chapter identifiers rather than folder URI or chapter index alone. Preserve existing `library.json`, local permissions, books and progress. Define fallback when a chapter disappears or identifiers change; test migration with existing data and refreshes that insert/reorder chapters.
- **HTML/content:** Decide how supported HTML becomes safe readable Compose blocks, including entities, paragraphs, emphasis, breaks, links and (if in scope) images. Do not run site scripts. Handle missing/empty or malformed chapter content visibly.
- **Network behavior:** Request internet permission; run network I/O off the UI thread; handle timeouts, cancellation, redirects, unavailable sites, offline state, pagination, retries and rate limits. Never silently report an error as a successful empty chapter.
- **Access and rights:** Use permitted public content or supported/authenticated access; check the source's terms and copyright. Do not bypass paywalls, DRM, access controls or anti-bot challenges. Avoid aggressive scraping and caching content where not permitted.
- **Validation:** Use repeatable fixture tests for parsing, identity/order and persistence migration, plus a real end-to-end test against each chosen source (search → list → read → resume), including error/offline cases. Live sites may change or be unavailable; record what actually worked.

## Decisions still needed

- Is offline reading needed initially, or is on-demand reading enough? Are chapter images needed?
- How should local and online books appear together in the library, and what metadata is important?
- What should happen if an online chapter is removed, reorders, requires login, or becomes temporarily unavailable?

## Source references

- Local LNReader checkout: `/home/xzeronelysium/Documents/lnreader` (app source; MIT license in `LICENSE`). If actual code is reused, retain required copyright and license notices and review the license of each separately sourced plugin/dependency.
- [LNReader plugin repository](https://github.com/lnreader/lnreader-plugins), [plugin API](https://github.com/lnreader/lnreader-plugins/blob/master/docs/docs.md), and [plugin testing guidance](https://github.com/lnreader/lnreader-plugins/blob/master/docs/testing.md). The plugin repository itself is separate from the local app checkout; individual site terms and content rights also apply.
- This app's current overview: `README.md` and historical v1 `PLAN.md`; code cited above is the authority for the inspected behavior.

## Sources

[1] https://raw.githubusercontent.com/LNReader/lnreader-plugins/plugins/v3.0.0/.dist/plugins.min.json — LNReader plugin manifest (v3.0.0)
[2] https://raw.githubusercontent.com/lnreader/lnreader-plugins/master/plugins/multisrc/readnovelfull/template.ts — ReadNovelFull multisource template
[3] https://raw.githubusercontent.com/lnreader/lnreader-plugins/master/plugins/multisrc/readnovelfull/sources.json — ReadNovelFull source configuration
[4] https://github.com/lnreader/lnreader-plugins/issues/2279 — LNReader plugin issue 2279
[5] https://raw.githubusercontent.com/lnreader/lnreader-plugins/5030252adcf6c2a8f1ce324763a22e46bcba4592/plugins/multisrc/readnovelfull/sources.json — Pinned Free Web Novel source configuration
[6] https://raw.githubusercontent.com/lnreader/lnreader-plugins/5030252adcf6c2a8f1ce324763a22e46bcba4592/plugins/multisrc/readnovelfull/template.ts — Pinned ReadNovelFull source adapter template
[7] https://freewebnovel.com — Free Web Novel home page
[8] https://freewebnovel.com/novel/lord-of-the-truth — Example novel page
