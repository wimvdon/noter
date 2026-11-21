# Testing

## Automatic (CI, on every pull request and push to `main`)

| Check | Command | Catches |
|---|---|---|
| Release script tests | `python3 -m unittest discover -s scripts/tests` | Version maths, keystore-secret decoding |
| Sign-in helper tests | `node --test worker/test/noter-sign-in.test.mjs` | The helper's code exchange, sealing, refresh and what it refuses (with a stand-in for Notion) |
| Unit tests | `./gradlew testDebugUnitTest` | Logic bugs in plain-Kotlin classes (`app/src/test/`) |
| Lint | `./gradlew lintDebug` | Android API misuse, missing translations, accessibility, hard-coded text, … (errors fail the build) |
| Debug build | `./gradlew assembleDebug` | Compile errors; produces the APK to test on the phone |
| Release build | `./gradlew assembleRelease` | Problems caused by R8 minification / resource shrinking |

Reports (test results, lint HTML) are attached to every CI run as the `reports` artifact, also
when the run fails.

Run all of it locally before pushing:
```sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
python3 -m unittest discover -s scripts/tests
node --test worker/test/noter-sign-in.test.mjs
```

### What the unit tests cover
| Test | Covers |
|---|---|
| `logic/FileUploadsTest`, `data/NotesRepositoryTest` (photos and files), `PropertyCodecTest`, `BlockCodecTest` (file uploads) | The size limit (the workspace's, at most 20 MB, an empty file refused), image or file block by kind, names given an extension, where a new file block goes (after a line and what is nested in it, into the same parent, at the end) and that a line not in Notion yet can't be pointed at; the upload's requests (JSON, then multipart with the file), a file too big refused before anything is sent, the block added after its line here and in Notion's copy, a files property written back with the files Notion just sent plus the upload (or without the removed one), the header image set and removed |
| `ui/common/SheetGuardsTest` | A scroll or fling in a sheet's list never pulls the sheet down; a swipe from the top does; with the keyboard open or the sheet still moving a close only closes the keyboard |
| `logic/ContentMergeTest` | Typing while a newer version comes from Notion: the edits go on top of it, keeping blocks added or changed in Notion meanwhile |
| `data/NotesRepositoryTest` (pull-to-refresh) | Pull-to-refresh asks Notion again for the people and linked pages kept here, before they would expire |
| `data/NotesRepositoryTest` (views), `data/api/NotionRoutesTest` | A data source's Notion views read by its database, in that list's order (one Notion won't show left out, those of other data sources not offered), a view's notes asked with its filter and sorts (only titles back) and not asked again within a minute; a failed request kept as the views' status; a change reaching Notion noted, so the view is asked again; the view shown kept per data source; a request about a view or a data source's views goes to that workspace's token |
| `data/NotesRepositoryTest` (lost answers) | A page Notion made without answering isn't made again; one it didn't make is made on the next try |
| `data/NotesRepositoryTest` (stopped refresh), `data/api/NotionClientTest` | A refresh stopped halfway (the screen closed) doesn't block the next one; an answer cut off halfway counts as offline instead of crashing |
| `logic/LauncherShortcutsTest` | Which pinned databases and notes become launcher shortcuts, in which order, and what a shortcut may open |
| `logic/HomeLevelsTest` | Which pages are Home's top level, and which subpages and databases inside them the search missed |
| `logic/DatabaseEditTest` | What a database's new name or icon (an emoji or a custom emoji) sends to Notion, and which rows, pins and kept names change |
| `data/notion/IconCodecTest`, `data/sync/OutboxIconTest` | Reading icons and the workspace's custom emojis, the icon body sent for an emoji, a custom emoji (by ID) or none, and icon changes queued before custom emojis existed still reading |
| `data/api/CustomEmojiClientTest`, `data/NotesRepositoryTest` (custom emojis) | `GET /custom_emojis` read page by page with the right workspace's token, a page's icon set to a custom emoji by ID, emojis kept a day and used when Notion can't be asked |
| `logic/SchemaEditsTest` | Edit database's properties: what renaming, removing and adding sends to Notion (by ID, unique names, blank names), what adding, removing, recolouring and regrouping options sends (existing ones by ID, new names unique, status groups), and how the kept filters, sort, cards and note layout follow |
| `logic/StatusDefaultsTest` | A status' default option: found with its colour, shown for an empty status (display only), never for other types |
| `logic/SubpageTitlesTest` | A page's subpage and database titles follow Notion, and only those blocks change |
| `logic/WorkspaceWaysTest` | Which tokens Advanced offers, in which order, and which is chosen first |
| `logic/SealedBoxTest`, `logic/SignInsTest` | Connect to Notion: opening what the sign-in helper sealed (a sample sealed by the helper itself), the state and authorize link, reading Notion's token answer |
| `logic/TokenRefresherTest`, `data/api/RenewKeyClientTest` (in `MultiWorkspaceClientTest.kt`) | Refreshing a refused Connect to Notion token once, one refresh at a time, keeping its workspace's ID; pasted tokens are never refreshed |
| `logic/WorkspaceTokensPlaceTest` (in `WorkspaceTokensTest.kt`) | Each Notion workspace once: where a new token goes (main, after the others, in place of the same workspace's token), that a way that sees less never replaces one that sees more, the clean-up of duplicates from before, and sorting (the top one becomes the main token) |
| `data/notion/TokenCodecTest`, `data/api/NotionRoutesTest` | Notion's workspace ID for every kind of token; a page found by two tokens listed once |
| `logic/DocumentEditorTest` | Enter, Backspace, Markdown shortcuts (Notion's), dividers, code blocks, numbering; nesting (indent, outdent, Enter and Backspace in nested blocks, what may move) |
| `data/notion/BlockCodecTest` | Reading and writing every editable block type; images, files, links and linked pages read-only; table rows with IDs, cells keep formatting and mentions and a row is written whole |
| `data/model/NotionFilesTest`, `logic/ReadOnlyBlocksTest` | Expiring links to files Notion hosts; file names and links of read-only blocks |
| `data/notion/PropertyCodecTest` | Reading and writing page properties; a status' default option and the group of each status option; Notion's buttons left out of schemas, pages and stored copies, button blocks hidden |
| `data/notion/InlineMarkdownTest` | Bold, italic, strikethrough, code and links ⇄ Notion rich text; user, page, date, template and emoji mentions and equations written back unchanged, colour and underline kept, link previews as links, code blocks unaffected |
| `data/notion/InlineTokensTest` | Editing around mentions (typing before, after or inside one, deleting part of one removes it, two side by side), colours kept when their text changes, pasted marker characters dropped, splitting lines |
| `data/sync/ContentDiffTest` | The block changes sent to Notion for an edit, also for nested blocks (new blocks in their parent, moved blocks added again) |
| `data/sync/BlockMatchTest` | New blocks keep the IDs Notion gave them, and blocks Notion added without answering are recognised, so nothing is added twice |
| `data/sync/OutboxTableRowTest` | Waiting table row edits: a newer edit of a row replaces the older one, renamed with a new note |
| `logic/TableCellsTest` | Table cell editing: which tables are editable, only the edited cell changes, short rows filled up, waiting edits put on downloaded content, an old cached table still reads |
| `data/sync/OutboxTest` | The queue of unsent changes (merging, renaming new notes; a restore after Undo: an unsent trash is taken back, one being sent gets a restore behind it) |
| `data/sync/SyncPolicyTest` | When to refresh, and merging refreshed notes |
| `data/sync/SaveTimingTest` | How soon edits are saved while typing (within Notion's rate limit) |
| `data/sync/SourceKeysTest` | Keys saved before data sources renamed to the data source (setups, pinned databases, order, layouts, waiting new notes) |
| `data/notion/CommentCodecTest`, `logic/CommentThreadsTest`, `data/NotesRepositoryCommentsTest` | Comments: Notion's comment read (author, time, rich text as Markdown, block or page parent), a new comment names the page and a reply only the discussion, edit and delete go to that comment and update what is kept, every page of comments is read, a 403 becomes the "comments not allowed" message (only for comments), comments grouped by discussion with the page's first, own comments recognised by the token's user ID, author names from the cached users, block quotes, mentions not edited, comments never remembered as workspace objects |
| `data/NotesRepositoryTest` | The repository against a fake Notion server (`FakeNotion`, the JDK's own HTTP server): a setup saved before data sources moves to its data source, every database with a copy read at start and a pinned page without one downloaded, a waiting new subpage of a page's notebook from before still made inside the page, Home's list of databases and pages kept on the phone, setups kept at once and databases pinned and unpinned, pages made inside a page and a refused top-level page removed, new notes stay open while Notion creates them, nothing is sent for a note in the trash, table rows with IDs, an edited table row sent (also after going offline), an old cached table downloaded again, expired image links, pages from Browse and linked pages, new to-dos added once (also after a lost answer), nested blocks downloaded and added to their parent, columns downloaded and new lines added to their column, the database a data source is in for links to Notion |
| `data/SyncStressTest` | Random editing (typing, Enter, ticking, changing kinds, indenting, outdenting, deleting) while a slow fake Notion that keeps the blocks, nested ones too, answers: Notion ends with exactly the note on the phone, also when it can't insert at the start |
| `data/store/CacheStoreTest` | On-phone copies survive a restart |
| `logic/NoteOrderingTest`, `logic/ChipFilterTest` | Pins (Home's pinned notes of every database), custom order, filters (checkbox, date ranges, relations, created by, formulas, numbers in order of their value) |
| `data/notion/TokenCodecTest`, `logic/NoteListsTest` | The token's kind, owner and profile picture; pinned databases in the order they were dragged to |
| `logic/BlockSelectionTest`, `logic/RevealTest` | Selecting lines of a note (what can be selected, the Markdown that is copied) and how far a new line scrolls into view above the keyboard |
| `logic/ReadingTextTest` | The note as plain text for Select text: list markers, numbers, ticked boxes, Markdown marks removed (not in code), blocks the editor only shows left out |
| `logic/ChoiceOrderTest` | A picker's order: selected first when it opens, then nothing moves while ticking; new choices at the end |
| `data/api/NotionErrorsTest` | Which errors the screens say in the user's language |
| `logic/NewOptionsTest` | When the picker of a select or multi-select offers to add what was typed (not for an existing name in any case, nor with a comma) |
| `logic/SchemaOptionsTest` | An option a note has (added on the phone or in Notion) joins its property's options once, so every picker shows it |
| `logic/QuickActionsTest` | Quick actions (#119): what each kind of property can do (set, add, remove, replace, empty, toggle, increase, decrease, move), the new values of every kind (options, people with "me", pages, text, numbers, links, checkboxes, dates today ± days or moved, also with a time), changes in order, only what changes, what can't change skipped, quick actions of 2.6.14 read as changes, editing a change, saving and Undo after delete |
| `logic/QuickFiltersTest` | A view's quick filters as chips: named like Notion's pills (the value of a set condition, none for a value that isn't a word), which ones get a chip (a condition, or a value that can be picked; gone properties and the title are left out), what a chip without a value can be set to, chips the view no longer has dropped, the combined filter body |
| `logic/NotionViewsTest`, `data/notion/ViewCodecTest` | Notion's views: which ones a database offers, which one it opens on (Notion's first view, then the last used, the first shown when that one is gone), the filter, the quick filters that are on and sorts sent to Notion (sorts by property name, quick filters added without nesting deeper), a view's notes kept per combination of quick filters, notes made since a view was asked shown first, a view's visible properties and cover read from its configuration (leniently; older copies load), the view a note's property order follows, the view each database shows |
| `logic/DatabaseSetupsTest` | A database's notes as its Notion view lists them, pinned ones first (a note the phone doesn't have yet left out); the property cards show when none were chosen; a setup found with or without dashes |
| `logic/LegacyNotebooksTest` | Notebooks from before the redesign: each database keeps its notebook's setup and is pinned in the order of the tabs (also a notebook of several data sources), a page's notebook becomes a pinned page, the first notebook of a database wins, a setup made since then stays, old filter properties |
| `logic/ColumnsTest` | Columns: how far their blocks are indented, and that blocks stay in their column |
| `data/notion/BlockCodecTest` (shares) | Images and bookmarks from a share written as links; a refused shared page tried with less |
| `data/TrashTest` | Undo after trashing (nothing is sent, the note returns in its place, pinned; after a sent trash a restore is sent; edits that were waiting are sent again), the trash search's request body and the place names, restoring a page (`in_trash: false`, shown again in its database, Home asked again for a page, errors thrown) |
| `data/notion/TrashCodecTest` | The pages in Notion's trash: title, icon, and the database or page each was in |
| `logic/TrashListTest` | The Trash list split by workspace, a restored page leaving it |
| `data/notion/WorkspaceCodecTest` | The list of databases and pages from Notion's search, data sources (grouped by their database; one the database doesn't list is left out) and the data source a page belongs to; the page each page or database is in, a data source placed where its database is |
| `data/icons/IconCatalogTest` | Reading the icon file, icon search, old icon keys, and that the bundled file has every suggested icon |
| `logic/PageTreeTest` | Home's tree of databases and pages: the top level (what isn't in another page of the list) and the level below, most recently edited first (a page counting what is inside it), IDs with or without dashes, hidden entries left out (a hidden top entry with what is inside it) |
| `logic/HiddenOnHomeTest` | Hiding a database or page from Home's list (#108): kept once with its name, newest first, shown again; IDs with or without dashes |
| `logic/NewDatabasesTest` | A new database's request: where it goes, its title, the title property and its properties in order, several of a kind numbered as Notion does, each name once, status, typed select options, Tasks and Projects starters (`database_type`, no schema), inline only inside a page |
| `logic/SharedTextTest`, `logic/SharedNotesTest`, `logic/WebArticlesTest` | Sharing to the app: the title, text and link of a share; the new note's title and blocks; a web page's content read in order without scripts and menus, links made whole, the description when there is nothing to read |
| `logic/MoveTargetsTest` | Move to…: where a note, page or database can go (not itself, where it is, what is inside it; only its own workspace; the top of the workspace not with a connection), searching by words, the new parent sent to Notion, Home's list after a move |
| `logic/NoteTemplatesTest` | Page templates: the default one for a new note, a chosen one by ID, none for an empty note; the request with the time zone; no empty title next to a template; typed lines after the template's content, never twice; Notion's list of templates; a queued note keeps its template (and old queues still read) |
| `data/NotesRepositoryTest` (templates) | The create request has the template and no content, the templates are listed with the schema, a refused template makes an empty note, shared notes use none, the template's content and values are downloaded after it was applied, typed lines are held back and added after it (also when it never comes) |
| `logic/NoteTargetsTest` | Where + on Home offers a new note: the pinned databases, then the others on Home's list, each data source of a database of several; a part per workspace, with a page at the top unless the token is a connection's |
| `logic/WorkspacesTest` | A token's short ID, the workspaces' names, which workspace a page or database is in, lists split by workspace |
| `logic/SearchResultsTest` | Search's one list: phone notes and Notion's results mixed, each page once, best matches first; filters by kind and workspace |
| `logic/PropertyLayoutsTest` | Which properties are shown, collapsed and on the card, and in which order; header image and content on cards; cards per database setup, starting from what its cards showed before; cards following the Notion view (its properties, cover, custom choices kept); a note's property order following the view until arranged |
| `logic/NotePreviewsTest` | Which previews are downloaded, and the preview text |
| `logic/NotionDatesTest` | Dates as Notion sends them |
| `BackupRulesTest` | Only the settings file is backed up |

### Writing unit tests
- Every logic change gets a test. Put logic in plain Kotlin classes (no Android types) and test
  them with JUnit in `app/src/test/kotlin/`, mirroring the package. See `DocumentEditorTest`.
- Test names describe behaviour in words: `` fun `tap stops at the maximum`() ``.
- No device tests (instrumented/UI tests) are set up: they need an emulator, which makes CI slow and
  flaky. Add them only if a decision in `decisions.md` says so.

## Manual (on the phone)

What automation can't check: how it looks and feels on a real device. For each pull request (before or after it is merged: merging only waits for green CI),
install the debug APK (see README, "Testing a pull request") and follow the steps in the pull request
description. In addition, check every now and then (always before a release):

- [ ] The app starts, and the changed screen looks right.
- [ ] **Dark mode**: switch the phone's dark theme on/off. Text stays readable.
- [ ] **Rotation** (if auto-rotate is on): the screen still works and keeps its state.
- [ ] **Offline**: turn on flight mode, edit a note, turn it off. The cloud icon in the top bar
      disappears and the change shows up in Notion.
- [ ] **Notion round trip**: edit a note in Notion, pull down to refresh the overview. The card's
      preview and the note show the change.
- [ ] **Language**: Settings → System → Languages → App languages → this app → Nederlands.
      All text is translated.
- [ ] **Large text**: Settings → Display → Font size at maximum. Nothing is cut off.
- [ ] **Back** gesture/button behaves as expected.
- [ ] After a release: install the release APK over the previous release. It updates without
      losing data.
- [ ] **Backup** (once the app stores settings): change a setting, Settings → search "Backup" →
      Back up now, uninstall, install the same APK again. The setting is back.

## Reading a crash from a release build
Release builds are minified, so stack traces show short names like `a.b.c`. Download
`<app>-<version>-mapping.txt` from the matching GitHub Release and decode the trace with R8's
`retrace` (Android SDK `cmdline-tools/latest/bin/retrace`):
```sh
retrace <app>-<version>-mapping.txt stacktrace.txt
```
Or ask Claude Code to do it: give it the stack trace and the version.
