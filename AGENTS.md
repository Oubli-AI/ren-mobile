# AGENTS.md — ren mobile

Guidance for any AI agent (or person) working in this repository. Read this first, then `android/README.md`.

ren mobile (app name **Ren**, `com.hiron.ren`; Emrys until 2026-11-12, Rivet before that) is the phone side of ren, the
browser built by Oubli AI (founder Tim Hunter, https://oubliai.com, tim@oubliai.com). It is a lightweight Android
browser with ren's search and an AI agent panel, and it still does everything Emrys did: texts, email drafts, calls,
apps, reminders and tasks on the phone through the owner's ElevenLabs agents. ren desktop is the Electron browser in
`ImRonAI/TheBrowser` (the owner's checkout is `RonBrowserRebuild`). Its face is Cato, the robot of catolabs.com,
recoloured to ren's volt (`android/web/src/robot/`).

The ElevenLabs agents still call themselves Emrys: their prompts, the webhook tool `call_as_emrys` and the VM's wire
names (`emrys-computer`, `EMRYS_*`, `/opt/emrys`) are unchanged, and the sections on them below say Emrys.

## ren mobile, 2026-11-12

What the overhaul added, on the branch `claude/project-thread-tnj6xo` (PR #14) and, for ren desktop,
`claude/ren-mcp-hub` in `ImRonAI/TheBrowser` (PR #3):

- **Design: Ink & Volt**, as ren desktop's Electron code implements it (`tailwind.config.ts` and `src/index.css` there
  are the truth; `docs/mobile-design.md` there describes an older palette and is not followed). Volt `#5543FF` in Light
  and `#8D98FE` in Dark, ink `#0B0C17`, colourless dark surfaces, flat surfaces with no glows, 10 dp controls, 12 dp
  cards. Bricolage Grotesque (display), Inter and JetBrains Mono, as variable fonts in `res/font/`. The logo is the
  "ren." logotype (`docs/design/ren-logotype.json`, copied from ren desktop's `logotype.ts`); `tools/build_icon.py`
  generates the launcher icon, the R notification icon and the wordmark from it. Settings › Browser and privacy ›
  Theme picks Light, Dark, Glass or System (2026-11-20). Glass is ren desktop's: the window is translucent over the
  wallpaper (`Theme.Ren.Glass`), blurred on Android 12+ where the phone allows cross-window blur, the app draws ren's
  glass tokens (`glassPalette`), and the bundled pages take ren's own classes (`lib/theme.ts`, `src/theme.ts`).
- **Robot: Cato**, unmodified in `android/web/src/cato/` (github.com/ImRonAI/cato-labs at `2a7a9b6`, hashes in
  `SHA256SUMS`). `scripts/gen-ren-robot.mjs` generates ren's robot from it into `src/robot/`: the same geometry, rig,
  moves and renderer, with Cato's blue changed to volt and an sRGB step added to the body shader (Cato's phone tier
  skips it, so its white shell draws grey without the bloom pass). The app drives it through `window.ren.*`
  (`src/robot/bridge.ts`, `ui/RobotView.kt`).
- **Browser** (`browser/Browser.kt`, `browser/Addresses.kt`, `browser/BrowserDns.kt`, `browser/ContentBlocker.kt`,
  `browser/SitePermissionsManager.kt`, `browser/BrowserHistory.kt`, `browser/Omnibox.kt`,
  `browser/RenCustomTabsService.kt`, `ui/BrowserScreen.kt`, `ui/BrowserSheets.kt`, `ui/BrowserHome.kt`): the app
  opens on it. Tabs are real WebViews, made on a tab's first navigation, with normal and private (WebView multi-profile)
  tabs, saved tab state, recently-closed tab reopen, desktop-site mode, find in page, pull-to-refresh (a pull that starts
  at the page's top reloads once the page reports being pulled past it, Chromium's root overscroll that Chrome's own
  pull-to-refresh starts from, so a pull inside a box that scrolls on its own, as ren's pages and an open spreadsheet
  do, scrolls the box; never while a page shows a full-screen dialog; `TabWebView`, 2026-11-23), context menus,
  print/PDF, downloads (including `blob:`/`data:`), uploads, fullscreen video, HTTP auth and JS dialogs, inline page
  error and TLS warning cards, and Custom Tabs. Tabs send WebView's user agent without its `; wv` and `Version/4.0`
  tokens (`Browser.browserUserAgent`, 2026-11-20), as DuckDuckGo's browser does, since Google refuses sign-in in an
  app's WebView. The address bar uses Mozilla's Public Suffix List to tell URLs from
  search phrases (ported from ren desktop's `navigable-url.ts` and `UrlBar.tsx`), shows omnibox suggestions from open
  tabs, bookmarks and history, strips tracking parameters, supports HTTPS-First, and routes searches through the chosen
  search engine (default Ren). **Secure DNS** (`BrowserDns.kt`) defaults to Cloudflare DoH with Quad9, AdGuard, Custom
  DoH and Automatic (system) modes via a loopback HTTP/`CONNECT` proxy (`ProxyController`, 15 s header timeout,
  unbounded relay sockets for long-lived SSE/WebSocket streams, and system DNS fallback on DoH transport failure while
  keeping NXDOMAIN/NODATA/SERVFAIL authoritative). **Shields** (`ContentBlocker.kt`) blocks third-party ad, tracker and
  fingerprinting hosts (with per-site toggle and badge count) and clears browsing data from Settings. **Site
  permissions** (`SitePermissionsManager.kt`) prompt per origin for camera, microphone, geolocation, protected media and
  sensors (remembered in Mozilla's `SitePermissionsStorage` for normal tabs, temporary in private tabs, and revocable in
  Settings). Home is `ren://home`: a switcher to ren desktop's destinations, the logotype, a search box ("Ask me
  anything. I dare you.", sized to fit the width), the five search levels and the favorite links. **Ask Ren** slides the
  agent panel (`HomeScreen.kt` under a header) over the browser.
- **Favorites** (2026-11-13, `browser/Favorites.kt`): one list, kept by the app (`Settings.favorites`), starting with
  ren desktop's `DEFAULT_FAVORITES` and added and normalized as its `favoritesStore.ts` does. Home shows them under
  the search box in one row that scrolls sideways (2026-11-15; long-press removes, Add opens a dialog); Discover shows the same list through ren's own store, which
  the page reads from and writes back to the app over `RenHost` (`{"type":"favorites"}`). An addition on either side
  shows on the other.
- **ren desktop's destinations** (2026-11-14, `ren://discover`, `ren://research` (2026-11-15), `ren://execute`, `ren://documents`,
  `ren://calendar`, `ren://vibe`, `ren://build`): ren desktop's `HomePage.tsx` on the tab the address names, with
  `OnboardingPage` for Personalize, `SignInPage` behind `#signin`, and the Projects and Documents pages App.tsx's
  `MainContent` draws for the addresses its tab store opens (`ron://projects`, `ron://project/<key>`,
  `ron://documents`), all unmodified in `src/ren`, and `DeepResearchPage` for Research. Every model picker reads Model Provider, Model, Reasoning Effort, Research Depth (owner, 2026-11-20): ren's `ModelSelector` (the agent panel, `ren://agent`, Research, Vibe's agent, issue chats) gets the phone's popover (`src/destinations/picker.tsx`, by a Vite alias for that importer alone), which adds the depth row the shell provides from ren's Deep Research store (`research-depth.tsx`), and `page.css` hides Research's own depth control; Settings › Agents ends with Research depth, the search level Home's chips set (`Settings.searchLevel`). `src/destinations/` mounts
  them under a switcher (ren desktop's `navigation/destinations.ts`: Home and the seven destinations); Home's switcher
  scrolls to the same seven. The page scrolls in the area under the switcher, as ren desktop's `BrowserLayout` scrolls a
  tab's content, since ren's `index.css` keeps the body from scrolling (2026-11-23: until then a finger couldn't scroll
  the Documents list or a document, seen on the emulator). A story opens in ren
  desktop's article reader (`ArticleReaderPage`, `ron://read`) in the same tab, and Back returns. Projects work
  as ren's guest workspace (`X-Ron-Workspace`, kept in SQLite on the VM) or signed in with Supabase (email and
  password, or ren's Google and GitHub buttons: the provider's page opens in a Custom Tab of another browser, since
  Google refuses sign-in in a WebView, and comes back to a loopback address the app listens on, `browser/OAuthLoopback.kt`,
  as ren desktop's comes back to `http://127.0.0.1:<port>/auth/callback`; ren's Supabase allows that redirect). The build needs `VITE_SUPABASE_URL` and
  `VITE_SUPABASE_ANON_KEY` in `android/web/.env.local` (gitignored; the values are ren desktop's `.env`).
  The pages are ren desktop at `0f6fa57` (`Oubli-AI/ren`, which was `ImRonAI/TheBrowser`, PR #8: main with deep
  research's formation graph and the Context Agent's `[phone]` turn, plus the account profile; Deep Research's pages and libs are from ren PR #14 (`bf91e1c`); see `src/ren/SOURCE.txt`, and `node scripts/sync-ren.mjs <checkout> .` copies
  them again), so the pages type-check with no errors. The app also sets the workspace token as the cookie `ren_token` on ren's
  origin (`Browser.backendCookie`; third-party cookies on for the bundled pages only), and Caddy lets a request with
  that cookie through to ren, so Build's live preview (an iframe) and ren's voice and teaching WebSockets, which can't
  carry a header, reach it (2026-11-20).
- **ren desktop's agent panel and what lays over its pages** (2026-11-20; owner: only ren mobile is built from now
  on, and every feature of ren desktop comes to it): `src/shell.tsx` puts ren's `AgentPanel` (threads, Memory, the
  specialist and project pickers, `render_ui` cards, voice, Teach Ren), `MusicSidePanel` (Live Music and batch music),
  `ScreenVisionOverlay` and `RepairBridge` over every bundled page, as BrowserLayout lays them over every tab, and
  follows ren's tab store as the phone's browser (`followTabs`: a web page or another of ren's pages opens in a new
  tab). `ren://agent` is the agent panel on a page of its own (Home's switcher, a sparkle). **Add to Ren context**
  (ren's context kinds, into the Context widget, which opens): the menu adds the page or a screenshot of it, a
  long-press adds a link or an image, and "Add to Ren context" in Android's text-selection menu adds the selection
  from any app (`ContextAddActivity`, `PROCESS_TEXT`); all of them only while the account is ready. **Split view**
  (the menu) shows two tabs at once, one above the other or side by side in landscape; touching a pane makes its tab
  the active one.
- **Context widget** (2026-11-15, `browser/ContextWidget.kt`, `device/AppUsage.kt`, `src/context/`): ren desktop's
  Context widget (`InterestsWidget`) and what feeds its Context Agent (`ContextBridge`), unmodified, on a bundled page
  in one WebView the app keeps for as long as it runs, laid over the browser under the agent panel. The page tells the
  app what it shows (`layout`: none, the orb, the orb with a finished run's notice, or the open panel) and the view
  takes only that much of the screen; Back closes the panel. It shows only for a ren account that is signed in and
  finished ren's interview (the pages report `{"type":"account"}` over `RenHost`; Settings › ren account shows it,
  opens `ren://discover#signin` or `#onboarding`, and signs out). As on the desktop it hears the pages the browser
  loads (`observeVisit`, `[browsing]`), and at most every three minutes, when the app comes to the front, what the
  user did on the phone: the apps they used, from usage access (Settings › What Ren can use › App usage,
  `PACKAGE_USAGE_STATS`), and what Ren did (the activity log's `device`, `approval` and `call` lines), as the Context
  Agent's `[phone]` turn (`reportPhone`, ren desktop PR #6). The record starts at sign-in. The account is ren
  desktop's: the same Supabase sign-in, and the VM's backend on ren desktop's storage (`PANEL_STORAGE: huggingface`,
  `HF_BUCKET_ID` `IAMRonHIT/RonBrowserRebuildBucket`). A user can start on either device (owner, 2026-11-15): the
  interview and the interest graph follow the account (ren desktop PR #8, `/context/profile` and
  `useAccountProfileSync`, mounted by the destinations and context pages as App.tsx mounts it), so an interview
  finished on the desktop isn't asked again on the phone, and the other way round; the interview waits until the
  account's copy has loaded. On the emulator
  (2026-11-15) the signed-out app showed no widget, Settings said "Signed in: No" from the widget page's report, and
  Sign in opened ren's sign-in page; signing in needs the owner's account, so the widget itself was not seen there.
- **Second brain for every agent** (2026-11-17, ren PR #25, owner: every agent that isn't a subagent has its own
  folder and the whole brain; subagents inherit their parent's): ren's search page and shopping agents, tool fixer,
  A2A Ren, teaching agents and OpenCode joined the agents that already had it. OpenCode reaches it through
  `ren-memory` in `deploy/vm/laptop/opencode.json` (`http://ren:8765/memory/mcp/`, its folder as `X-Ren-Agent`
  `opencode` and the user as `X-Ren-User` from `REN_USER_ID`, which the Context Agent sets when it starts OpenCode;
  without it the folder belongs to `default`),
  installed at `~/.config/opencode/opencode.json` in the shared home. Since 2026-11-21 (ren's brain rebuilt to
  Anthropic's design, owner: approve dreams first) every agent reads the whole brain and writes its own folder with
  Anthropic's memory tool (`memory`), gets the brain's index in its prompt instead of per-turn retrieval, keeps
  `search_memory` and `journal`, and stamps each fact with its date, agent and whether the user confirmed it. ren's
  API is the one dreamer: a dream is a pending version that changes nothing until the user approves it, shown as a
  notice over the Context widget's orb (`DreamNotice`). On the phone the notice takes the run notice's place (`layout`
  `notice`) and the review the whole screen (`panel`); Back closes it. The phone's review is its own
  (`src/context/DreamReview.tsx`, owner 2026-11-20: never ask to OK what isn't shown): every topic the dream would
  change with its whole text now and after (both from ren's `/context/dream`), the lines that change marked, and a
  checklist with a comment on each. Approve checked puts the checked topics in memory, each only if it still reads as
  it did when the dream ran; the unchecked ones go to the dreaming agent with their comments. The X dismisses the
  dream, so nothing it wrote reaches memory. The ElevenLabs text and voice
  agents (owner, 2026-11-17) reach the same server (ren PR #26, `core/memory_mcp.py`) as the MCP servers
  `ren-memory (text)` and `ren-memory (voice)` at `https://rivet.ron-ai.dev/memory/mcp/`, with Caddy's token
  (`X-Ren-Token`, the workspace secret `ren_vm_token`), `X-Ren-Agent` `elevenlabs-text` or `elevenlabs-voice`, and
  `X-Ren-User` from their `user_id`; each prompt ends with ren's `JOURNAL_DIRECTIVE` as it read on 2026-11-17 (their
  configuration is the owner's), and the server still serves `journal` for them.
- **The Context Agent hears the phone** (2026-11-19, ren PRs #31, #32 and #38, the pages synced from ren `a6213d7`): every
  notification another app posts goes to the page at once (`RenNotificationListener.posted`, the `notifications_list`
  row as ren's `PhoneNotification`), where ren's store gives the agent a `[notification]` turn: it journals it, keeps
  the person (`remember_person` in the brain's `people`, and the phone's own contacts through the new phone tool
  `contact_save`) and drafts a reply to a message from a person, which the widget shows with Send; Send asks
  `request_approval` first, then replies through `notification_reply` or `sms_send`. A search in the browser
  (`search`), the chats with Ren on the phone (`chat`, channel "Ren on the phone") and, in each `[phone]` report, the
  calls and texts since the last one (`calls_recent` and `sms_list` rows, `Messaging.calls` and `texts`) go with the
  apps and Ren's actions. Twice a day the agent reviews its journal and, with OpenCode, recommends projects, apps and
  agents. Contacts in Settings › What Ren can use asks for `WRITE_CONTACTS` too. Not run live (owner tests).
- **Apps the Context Agent builds** (2026-11-17, ren PR #29): when the computer is configured, the Context Agent
  suggests a web or Android app (`recommend_app`, the same card as its agents, labelled Web app or Android app); Build
  sends `[approve_app <id>]`, OpenCode builds it in `/config/workspace` (a web app deployed with Vercel, with its
  `icon.png`; an Android app with `./gradlew assembleDebug`) and `register_application` lists it. ren desktop's sidebar
  shows the web apps as icons that open them; Home on the phone shows every app (`browser/Applications.kt`, from ren's
  `/context/applications` for the account id the pages report): a web app opens in the tab, an Android app's APK comes
  from `/context/applications/<id>/apk` (the `ren` container mounts the shared home read-only) and Android's installer
  opens on it (`REQUEST_INSTALL_PACKAGES`, a FileProvider on the cache's `apps/`). The VM's `ren` service reaches the
  computer at `http://computer:8080/computer/mcp`. JDK 21, Gradle 8.14.3 and the Android SDK (platform 36, build-tools
  36.0.0) are in the shared home (`deploy/vm/toolbox/android.sh`, run once), on the computer image's `PATH`, with
  `JAVA_HOME` and `ANDROID_HOME` passed to Desktop Commander; the computer has 8 GB for the Gradle daemons.
- **Attachments in the agent panel** (2026-11-18, ren PR #30): as in ren desktop's composer, every paste in the
  composer (the text toolbar's Paste or Ctrl+V; `PasteAsAttachment` hands the field's clipboard read to
  `RenRuntime.attachPaste`) becomes a card above the field, named from its text (`core/PromptAttachments.kt`, a port
  of ren's `prompt-attachments.ts`), and Ask Ren in the Context widget (a saved item opened with its question, or a
  selection) puts its items in the composer as cards and opens the panel (`{"type":"ask"}` over `RenHost`,
  `ContextWidget.asked`). A message goes to the agent as the typed text, ren's instruction when a paste rides along,
  then one `<attachment>` block per card, written character for character as ren's `attachmentBlock`; the chat page
  draws those blocks as ren's cards again (`splitAttachments`, `SentAttachments`).
- **Default browser** (2026-11-13): `MainActivity` takes `VIEW` `http`/`https` links (BROWSABLE, DEFAULT) and
  `APP_BROWSER`, so Android offers Ren for the browser role; a link from another app opens in a new tab in front.
  Onboarding's step 4 and Settings › What Ren can use › Default browser ask for the role
  (`RoleManager.createRequestRoleIntent(ROLE_BROWSER)`); on the emulator Ren took the role and a link opened in it.
- **Ren as a search engine** (2026-11-20): other apps' web searches (`Intent.ACTION_WEB_SEARCH`, the query in
  `SearchManager.QUERY`) and "Search with Ren" in every app's text selection menu (`ACTION_PROCESS_TEXT`) open the
  query in a new tab with the chosen engine at the chosen level; Android's app picker makes Ren the default for web
  searches with "Always". Settings › What Ren can use shows "Default search engine" only on phones that have
  Android's search engine role (`android.app.role.SEARCH_ENGINE`, Android SDK 37.2, whose requirements neither the
  reference nor AOSP's `roles.xml` publish). An OpenSearch description isn't served: ren's search page is bundled in
  the app and its backend needs the workspace token, so no other browser can load it. The home-screen search bar
  (`widget_search.xml`, `RenWidgets.ACTION_SEARCH`) is the logotype, Home's "Ask me anything. I dare you." and the
  voice key: the bar opens Home with the address bar taking the typing, the logotype opens Home, the voice key talks
  to Ren.
- **Vibe's studio** (2026-11-20, owner: Google's realtime music and voice design, and the ElevenLabs tools, not the
  ElevenLabs agents, in Vibe; everything made is kept there; a designed voice can be Ren's voice): `ren://vibe` opens
  on the studio (`src/vibe/Vibe.tsx`), with ren desktop's Vibe agent (`SuperAgentInterface`) as its last tab. Live music
  is Lyria RealTime (`lyria-realtime-exp`) on ren's own `LiveMusicSession` over `/gemini/vibe/music`, played by ren's
  music sink; Song is Lyria 3.5 (ren's `lyria_generate`) or ElevenLabs Music; Sound is ElevenLabs sound effects; Speech
  is Gemini 3.8 Flash TTS or ElevenLabs `eleven_v3`; Voice designs a voice with Gemini's Voices API or ElevenLabs'
  text-to-voice (`deploy/vm/gemini/studio.py`, `/gemini/vibe/*`). The library is kept per user (the install id) under
  `/config/emrys-gemini/vibe` on the server, and the Strands agents on every provider have the same studio as tools
  (`vibe_create`, `vibe_voices`, `vibe_library`), so what they make shows in Vibe. The prompt is ren's `PromptInput`; the
  music, voices and library use ElevenLabs UI's Waveform, Orb, VoicePicker and AudioPlayer, unmodified in
  `src/elevenlabs` (hash-checked with ren's files). Any voice there can be Ren's voice (`Settings.renVoice`): Gemini
  Live talks in Google's 30 studio voices, and refuses designed ones (1007 "Replicated voice is not supported for this
  model", seen live 2026-11-20), so a designed or ElevenLabs voice reads the Strands text agent's replies aloud instead.
  Keys: `GOOGLE_API_KEY` and `ELEVENLABS_TOOLS_API_KEY` in `gemini.env`. Lyria 3.5 costs $0.08 a song with no free tier;
  `lyria-realtime-exp` is experimental and not on Google's pricing page (2026-11-20). Not run live past the Gemini
  voice list and one Gemini speech.
- **Squad** (2026-11-20, owner: Deep Research is one of many jobs; the user picks the outcome; the report starts as a
  ren Document and the PDF is printed from it; ren PR #48): `ren://research` is Squad. Its hero asks what to make
  (the backend's `Outcome`) before a run, and each outcome's team is Deep Research's formation
  with only its agents (`outcome_topology`). No team builds a PDF: the report's Document prints it, in Ink & Volt (ren PR #50).
  Report and Slide Deck are always offered; **Web App, Website and Mobile App** (2026-11-21, owner: a Strands agent
  whose job is driving OpenCode; ren PR #54) are offered while ren's workspace computer is configured
  (`available_outcomes`). Their team is the research swarm, then a build swarm: an App Builder that has OpenCode build
  it as the Context Agent does (`OPENCODE_BUILD`) and lists it with `register_application`, and an App QA that checks
  it and sends back what fails. The run keeps the app (`RunMeta.application`) and its page shows it in the report's
  place: a web app or website in a frame with a way into a browser tab, an Android app with Install. Install links to
  the app's APK on ren; the browser hands that link to Android's installer as Home's tile does (`Applications.apkId`,
  `Browser.installApplication`). A mobile app needs a signed-in ren account, whose Applications serve the APK; the
  picker says so. **DataGrid** (2026-11-21, ren PR #56) is always offered: the research swarm, then a grid swarm, a
  Grid Builder that writes the researched data as a spreadsheet (`grid.json`: a row per item, every result a formula,
  totals on a summary sheet over whole columns, every row's source) and a Grid QA that checks it with Python against
  the research and sends back what fails. The run's page shows the spreadsheet in the report's place and keeps it as
  a Document (see "Spreadsheets"). The rest of the forms are listed in
  https://claude.ai/artifact/KAuuP3nPvEgjMu1cLYPeRX. Not run live.
- **Spreadsheets (DataGrid)** (2026-11-21, owner: a DataGrid "which should have enriched features similar to excel or
  Google Sheets"; ren PR #56): a spreadsheet is a block in a page (the "/" menu's Spreadsheet), so Documents, issue
  descriptions and Squad's DataGrid outcome are one piece of code: ren's `GridBlock.tsx`, `components/grid/GridView.tsx`
  and `engine.ts`, unmodified in `src/ren`. A page shows the sheet's first 9 rows and **Open spreadsheet**, which lays
  Univer's sheets over the whole page (1.5.2, Apache-2.0, 29 `@univerjs/*` packages pinned exactly, with the plugins
  Univer ships for phones: formulas, number formats, filter, sort, conditional formatting, data validation, find and
  replace, tables and links); Done or Back closes it, and a page that can be edited keeps what it holds. Charts, pivot
  tables and Excel import are in Univer's paid packages (https://docs.univer.ai/server/license.md) and are not here.
  Agents read and write a spreadsheet as one ```` ```grid ```` fence of JSON (a title and sheets, each a name, rows
  and column formats; a formula is text starting with `=`). The open spreadsheet downloads the sheet in view as CSV,
  made in the page, and every sheet as Excel, written by ren (`POST /projects/grids/export`, XlsxWriter) with each
  formula and its result.
  - **Rows as an app** (2026-11-21, owner: "this should be like appsheet"; ren PR #60, `GridRows.tsx`, unmodified in
    `src/ren`): an open spreadsheet with rows shows a card per row, with the sheet one tab away (Cards, Sheet). After
    AppSheet's deck, detail and form views and its column types, a column is typed from its cells: email, phone,
    link, image, address (by its header), lat-long, date, time, price and percent (by the number format), yes or no,
    a few values that repeat as choices, and long text. A row shows every cell with what a tap starts: Email
    (`mailto:`), Call (`tel:`), Text (`sms:`), the link in a new tab and the place in Google Maps, with Edit, Copy
    and Delete. The form gives each column the input of its type; a new row is filled down from the row above, so
    it has its formulas and formats, and a cell that is a formula isn't asked for. Back leaves the form, then the
    row, then the spreadsheet. A total among a sheet's rows is a row like the others, as in AppSheet, which is why
    Squad's Grid Builder keeps totals on a summary sheet. Checked (2026-11-21) in Chromium at phone size on the
    built pages against ren's Projects code on loopback, light and dark: cards with pictures, a row's actions, an
    edit, a new row with the formula of the row above, a copy, a delete, a summary over whole columns, Back through
    every step, a sheet with only its header, and the saved page. On the emulator (2026-11-23, 0.5.11) a page with a
    spreadsheet filled by hand showed its cards, a row with its actions and the new-row form. Documents' header row
    is wider than a phone and widened the WebView's layout viewport, so the open spreadsheet ran under the browser's
    bottom bar with its Cards and Sheet tabs and Done out of sight; the page keeps the screen's width (`overflow-x:
    clip` on the root, `src/page.css`). And the bottom bar left the layout while the keyboard was up, so a tab grew
    by its height as the keyboard came and shrank by it once the keyboard went; Univer takes a shrink of 80 px or
    more for a keyboard and kept the taller height, its canvas over the tabs. The keyboard now rises over the bar and
    the tab ends at whichever is higher (`BrowserScreen`), so a tab only shrinks as the keyboard comes and grows as
    it goes. The Context widget's orb sat over a row's Text and Delete; a page tells the app when it shows an
    `aria-modal` dialog (`{"type":"modal"}` over `RenHost`, `src/host.ts`), and the orb or notice takes no room while
    the tab in front has one open (`Browser.modal`, `ContextOverlay`). The grey rail at the screen's right edge on the
    emulator is Android's accessibility button (the floating menu for the App operator), which the phone's own
    accessibility settings place, not the app.
  - **The other views** (2026-11-23, ren PR #61, `GridViews.tsx`, unmodified in `src/ren`): beside Cards and Sheet,
    the spreadsheet's bottom bar offers each of AppSheet's views the sheet in view has the data for: Chart (a bar
    chart of each column of choices, rows per choice, and of numbers, each row's highest first, with its table one
    tap away), Calendar (a month with the rows on each day, by the first column of dates, and the day's rows), Map
    (the rows with a place, by the first column of addresses or coordinates, and the one picked on Google Maps'
    keyless embed, as an Embed tab's map: one place at a time, since nothing geocodes the rest), Gallery (the rows'
    pictures) and Dashboard (all of those in one scroll, where there are two or more). A tap opens the row; Back
    returns to the view. The tabs size to their labels, so seven fit a 360 dp screen.
  - **In the APK:** the engine is a chunk of its own (1.75 MB compressed), loaded when a spreadsheet first calculates
    or opens. To stay under 20 MB, `vite.config.ts` leaves out what a phone never loads, 1.67 MB compressed: the
    hyphenation patterns of 77 languages, which Univer's layout loads for a cell laid out as a document whose text
    it reads as another language than English (it then logs "Invalid hyphenation pattern" and breaks lines without
    hyphens), and KaTeX's WOFF and TrueType fonts beside the WOFF2 a WebView takes. The app also keeps only English
    resources (`localeFilters`), which drops 0.88 MB of the libraries' translations. With all of it the release APK
    is 19.79 MB (2026-11-21).
  - **A file a page makes** (a spreadsheet as Excel or CSV, Print's PDF, HTML and Word, a chat, an attachment) is a
    link with a `download` name on a `blob:` or `data:` address. WebView's download listener gets the address without
    that name, and the app reads a blob when the user says Save, by when ren's pages have let go of it (a second after
    the click). So `src/host.ts` reads the file at the click and hands the app its name and content
    (`{"type":"download"}` over `RenHost`), and `Browser.savePageFile` offers it on the download sheet, from Ren, and
    saves it to Downloads.
  - **How much a page holds:** 2,000,000 characters, which is about 57,000 cells as an agent writes them and about
    19,000 once the sheet has been opened in a page that can be edited, which then also keeps Univer's workbook
    (styles, column widths, filters); past it the page says it is too large to save. A page an agent rewrites comes
    back without the workbook.
  - **Checked** (2026-11-21) in Chromium at phone size, on the built pages against ren's own Projects and Squad code
    on loopback, with the app's side of `RenHost` stood in: Squad offers DataGrid, a finished run shows its sheet, a
    sheet opens over the whole page from Documents and from a run, Back closes it, and CSV, Excel and Print's three
    files reach the app under their names. Not run on a phone: the download sheet and the saved file, Android's
    keyboard over a sheet, the Context widget's orb over an open sheet, and a DataGrid run with a live model.
- **Vault** (2026-11-20, owner: logins, cards, bank accounts and API tokens the agents use without seeing;
  `core/Vault.kt`, `mcp/VaultTools.kt`, `ui/VaultSettings.kt`): Settings › Vault keeps items on the phone, sealed in
  `files/vault.bin` with AES-GCM under an Android Keystore key (`KeystoreCipher`); opening an item takes biometrics or
  the screen lock. Each item has a kind (login, card, bank account, API token, other), fields, the domains it may go to
  (a domain covers its subdomains) and "Ask me before each use". It works the way strands_tools' `http_request` uses
  `auth_env_var` with `HTTP_REQUEST_TOKEN_CONFIG`: an agent names an item, and the phone resolves the value itself
  and sends it only to the item's sites. `vault_list` gives names, kinds, sites and field names; `vault_request` sends
  an https request from the phone with a field as a bearer, a header, a query parameter or basic auth, without
  following redirects (as Strands Shell injects credentials only on the original request); `browser_type` takes
  `item` and `field` and types the value on an https page of the item's sites, marking the field so `browser_read`
  never shows it and `browser_screenshot` refuses while it holds one. A use asks on the approval sheet unless the
  item is always allowed (the sheet's "Always allow" sets that). Every phone tool answer, as a client tool or over the
  hub, leaves with every vault value of five characters or more replaced by `[vault: item › field]`
  (`Vault.scrub`, digits found with spaces or dashes too). The tools are on the phone's MCP server, so every
  provider's agents reach them through the hub; `/hub/agents/mcp` serves `vault_*` beside `browser_*`. Nothing is
  synced or backed up (the backup rules exclude every file), so a lost phone loses the vault. Not run live.
- **Claude Code and Antigravity CLI, the main text agents** (2026-11-21, owner: their CLIs on the server, signed in
  with his accounts, all native tools kept, each a Strands model passed to `create_harness`, reasoning, tools and output
  streamed whole to the panel): Settings › Agents › Claude Code (the default) and Antigravity. `deploy/vm/gemini/cli_models.py`
  is a custom model provider per CLI, driving Claude Code through Anthropic's Claude Agent SDK (`claude-agent-sdk`
  0.2.187, which bundles Claude Code 2.1.330) and Antigravity as an Agent Client Protocol server (`agy acp`), one
  process per conversation and per subagent. The CLI keeps its own conversation, so the model is stateful
  (`capabilities.stateful`; Strands sends only the new turn and keeps the CLI's session id in `model_state`, saved with
  the session); `create_harness` gets `context_manager=None`, which a stateful model requires. The CLI's native tools
  (Claude Code's Bash, Read, Edit, WebSearch, Agent and the rest; Antigravity's run_command and the rest) run inside the
  CLI and reach the phone as `{"native_tool": …}` (Strands passes them on as raw `{"event": …}` chunks, as ren's xAI
  model does xAI's server-side tools); the agent's own tools (the hub's, ren's, the harness's) reach the CLI as one MCP
  server, `ren`, and a call there ends the model's message with a Strands `toolUse`, so Strands runs it and its result
  answers the CLI. For Claude Code `ren` is the SDK's in-process server (`create_sdk_mcp_server`); it runs with
  `permission_mode="bypassPermissions"`, summarized adaptive thinking, partial messages, and the agent's instructions
  appended to the `claude_code` system prompt preset; models are its aliases, efforts low to max (the SDK's
  `EffortLevel`); it signs in with `CLAUDE_CODE_OAUTH_TOKEN` (`claude setup-token`) in `gemini.env`. For Antigravity
  `ren` is `cli_bridge.py` (stdio, relaying to the loopback-only `/bridge/<token>`). Antigravity 1.5.13 (the release's `antigravity`
  binary as `agy`) runs as `agy acp` (ACP v1 over stdio), because its headless `-p` stream-json carries the model's
  thinking only as `thinking_tokens` (its headless docs; the verbosity setting, `high` showing "every tool call,
  command, and thought in full", is the terminal view's), while ACP streams the thinking as `agent_thought_chunk`, which
  the panel shows as the chain of thought, each tool call with its `rawInput` and `rawOutput`, and takes images in a
  prompt. Each process's session opens in its own folder with `.agents/mcp_config.json` (the `ren` tools loaded
  as Antigravity loads MCP tools by default, lazily through `call_mcp_tool`, and `timeoutSeconds: -1`), with `--add-dir /config/workspace` and `--dangerously-skip-permissions` ("Auto-approve all
  tool permission requests"); a later process resumes the conversation (`session/resume`). It has no system prompt
  flag, so a new conversation's first message starts with the instructions. The model picker lists the values of the
  model option ACP gives a new session, and the effort (low to max, its `--effort` levels) sets the session's
  `thought_level` option. It signs in with the owner's
  Google account (owner's choice, 2026-11-21, after Google's FAQ: wrapping `agy` "is not supported under consumer
  plans"), kept under `/config/.gemini`. Every step in the panel now opens to the tool's whole input and output, for
  every Strands provider. Checked offline (2026-11-21): mypy, and both models through `create_harness`, with
  the SDK's bundled Claude Code against a local stand-in for Anthropic's Messages API and a stand-in `agy` speaking
  ACP, with native tools streamed, a Strands tool called through `ren`, thinking streamed as reasoning, and a
  conversation resumed in a new process. Not run live: ACP's config option ids and values
  are read from the session, since Antigravity's docs name only the pickers (Model; Thinking level Low to Max).
  **In-app sign-in** (`deploy/vm/gemini/cli_auth.py`, `core/CliAuth.kt`, `Browser.openAuthTab`, Settings › Agents):
  selecting Claude Code or Antigravity in Settings › Agents immediately shows its sign-in status and a one-tap
  **Sign in to Claude Code** / **Sign in to Antigravity** button. `/gemini/cli-auth/start` runs the real CLI's OAuth
  login (`_bundled/claude auth login --claudeai` or `agy acp --login`) on a wide PTY and returns its authorize URL; the
  phone opens it in a Ren browser tab, automatically intercepts the callback redirect
  (`platform.claude.com/oauth/code/callback` or `antigravity.google/oauth-callback`), closes the tab, returns to
  Settings, and completes the exchange via `/gemini/cli-auth/complete` (converting Claude's callback query to
  `<code>#<state>` and passing Antigravity's callback URL directly), with Use Chrome / Paste code fallback and Sign out.
- **Connections** (2026-11-21, owner: Vercel, Netlify, GitHub, GitLab, Supabase and Bitbucket for every agent, with
  sign-in; `core/Connections.kt`, `ui/ConnectionsSettings.kt`, ren's `core/connections.py`): Settings › Connections
  lists the six. Each is the provider's official hosted MCP server: Vercel `https://mcp.vercel.com` (OAuth; Ren
  identifies itself with the Client ID Metadata Document `/connections/client.json`, vercel.com/changelog, 2026-11-02),
  Netlify `https://mcp.netlify.com/mcp` (OAuth with dynamic client registration, or a personal access token,
  netlify.com/auth.md), GitHub `https://api.githubcopilot.com/mcp/` (a personal access token: its OAuth has no dynamic
  client registration and needs a GitHub App of Ren's own, github-mcp-server `docs/host-integration.md`), GitLab
  `https://gitlab.com/api/v4/mcp` (OAuth with dynamic client registration, or a fine-grained token with the MCP
  permission), Supabase `https://mcp.supabase.com/mcp` (OAuth with dynamic client registration, or a personal access
  token) and Bitbucket, which has no MCP server of its own: its Bitbucket Cloud tools are on the Atlassian Rovo MCP
  server `https://mcp.atlassian.com/v2/mcp` (OAuth 2.1, or a service account's API key; the workspace must be linked to
  an Atlassian organization, and calls use the organization's Rovo credits). Sign in asks ren for the provider's page
  (`POST /connections/<name>/start`, the MCP SDK's OAuth client), opens it in a Custom Tab (`Browser.signInPage`), and
  the provider returns to ren's `/connections/callback` (Caddy lets it and `client.json` through without the token);
  Use a token keeps a token instead. ren keeps the tokens on the server (`RON_CONNECTIONS_DIR` on `ren_data`, mode 600,
  per user: the signed-in ren account, or the install), so long jobs run while the phone is away, and no agent sees
  them. ren serves the connected accounts per user at `/connections/mcp` (`X-Ren-User`, tools `vercel_*`, `github_*`
  and so on): ren's own agents (`connection_tools`), the phone's Strands agents on every provider (`ren-connections` in
  `servers(user_id)`, `deploy/vm/gemini/app.py`) and OpenCode (`ren-connections` in `opencode.json`). A tool its server
  doesn't mark read-only asks on the phone first: ren calls the phone's `request_approval` through the hub (`deploy` for
  Vercel and Netlify, `cloud_coding` for the rest, with the tool's name and its arguments as the details); "Always
  allow" on the sheet lets that kind go ahead from then on (`RenRuntime.approve`). Checked offline (ren's
  `test_connections.py`: a sign-in with dynamic client registration against a local OAuth MCP server, the proxy, an
  approved and a refused change, a token, disconnecting). Not run against the real providers.
- **Ren drives the browser in view**: while a turn uses a `browser_*` tool, the agent panel takes the lower half of
  the screen, so the page Ren works on stays visible above it, as ren desktop's panel sits beside its browser.
- **Reasoning**: the VM's `/gemini/text` sends Strands' `reasoningText` stream events as `{"reasoning": …}`, and
  asks Gemini for its thoughts (`include_thoughts`), which the harness doesn't; the panel adds them to the turn's
  chain of thought (`GeminiEvents`), as it does the ElevenLabs text agent's `agent_reasoning_response_part`.
- **One phone on the hub**: two devices can attach as `phone` (the owner's phone and a test emulator). The newest
  is used, and when it goes the hub falls back to the one still open. On 2026-11-13 the emulator's link made the
  agents read the emulator's 21 apps instead of the owner's phone; keep Remote tools off on test devices while the
  owner is using his phone.
- **Screen access** (2026-11-14): Android starts Ren's process in the background to bind the App operator
  (`ScreenOperator`) and the notification listener, after a reboot, an update or a reclaim. Starting `HubService` from
  `RenApp.onCreate` there threw `ForegroundServiceStartNotAllowedException` (Android 12+, "Background started FGS:
  Disallowed … uidState: CEM"), so the process died before the operator connected: Android listed it as enabled, not
  bound, and `device_status` said `app_operator: false` on the owner's phone (2026-11-13 21:20 and 11-14 08:00 UTC).
  `HubService` starts from `MainActivity.onStart` and from `HubService.Starter` (BOOT_COMPLETED, MY_PACKAGE_REPLACED);
  on the emulator an update and a reboot left the operator bound and the hub link running. Force-stopping Ren turns its
  accessibility service off in Android (seen on the emulator: `am force-stop` cleared `enabled_accessibility_services`).
  The agent panel shows "Ren can't see your screen" while the operator isn't connected, and its Set up opens
  `ScreenAccessSheet` (restricted settings on Android 13+, Notification access, App operator). The agents' hints point
  to Settings › What Ren can use; Settings › Permissions holds the agents' approval switches, not Android's.
- **Search**: `ren://search?q=…&level=…` is ren desktop's own results page. `android/web/src/ren/` holds
  `src/pages/SearchResultsPage.tsx` and everything it imports from ren desktop at `0f6fa57`, unmodified (hashes in
  `SHA256SUMS`, `npm run check:ren`), with ren desktop's Tailwind config and `index.css`. `src/search/` mounts it:
  the query and level come from the address, Dark follows the phone, a result opens in a new tab (`window.open`, which
  the browser turns into a tab), and `host.ts` adds the workspace token to the page's requests (as `X-Ren-Token`,
  since ren's Projects requests carry Supabase's bearer in `Authorization`). The page gets the
  token from the `RenHost` web message listener, which answers only the app's bundled origin
  (`https://appassets.androidplatform.net`). The backend is ren desktop's own backend on the VM, run as ren
  runs it (`python agent/api/main.py`; `deploy/vm/ren`, image built from `Oubli-AI/ren` at `4841e40` (ren main through PR #60, 2026-11-21, which shows a spreadsheet's rows as an app, after AppSheet; before it `50aef8f`, ren main through PR #56, which adds spreadsheets in pages and Squad's DataGrid outcome, the same files as `50b9414`, since PR #57 took three parts out and PR #59 put them back at the owner's word; before it `fa8e405`, ren main through PR #54, which adds Squad's Web App, Website and Mobile App outcomes, built by OpenCode; before it `760df19`, ren main through PR #53: PR #49 serves the user's connected accounts, PR #51 rebuilds the second brain to Anthropic's memory design with dreams approved first, and PR #53 keeps the dreams from before review as applied versions that undo; before it `4d3a4f3`, ren main through PR #50, which prints every Document in Ink & Volt: a cover with the logotype, title and date, Bricolage headings and a footer with page numbers; before it `eeccec3`, ren main through PR #48, 2026-11-20, which renames Deep Research to Squad and has the user pick the outcome, a report or a Slidev deck, before a run; before it `45ca0a5`, ren main through PR #47, which keeps a Deep Research run going when Hugging Face's bucket refuses a save with 429, as both runs on 2026-11-20 died of; before it `ab77c6f`, ren main through PR #46; before that `a6213d7`, ren main through PR #38, and before that `beaa580`, ren main with PRs #16 to #19, #21 to #29, on `2d24e4c`, which holds the pages' commit `0f6fa57`, the web tools (ren PR #11), the Context Agent with OpenCode (ren PR #12), the Search page off Brave (ren PR #13) and Deep Research as the Strands SDK documents it (ren PR #14: plain `Swarm`s and `GraphBuilder` graphs, the outer graph's `stream_async` events); PR #16 makes Mistral every agent's default and takes the harness's EnvironmentContext, which handed the agent ren's checkout path and AGENTS.md, off every user agent, owner 2026-11-17; PR #19 tells every swarm agent when it hands off, as the Strands swarm docs do; PR #22 sends every Mistral model its largest `max_tokens`: 262144 for Mistral Large 4, 131072 for the GLM and DeepSeek models Mistral hosts, the context window for the rest; PR #23 lets only the fact checker open sources and gives each Deep Research agent only the sections of its skill that fit its role; PR #24 gives Deep Research six levels after Perplexity's Agent API presets (fast, low, medium, high, xhigh and ultra, 5–8 up to 30–40 pages), a turn budget per agent with a wrap-up reminder over its last five turns, the Slidev skills on the deck agents, each run's report as a Document under a personal Deep Research page, and Print to PDF, HTML and Word on every Document; PR #27 lists and opens runs saved before the levels, mapping standard, deep and exhaustive to low, medium and ultra; PR #21 takes the overall time cap off the top-level graph, so only each swarm keeps `RON_DEEP_RESEARCH_EXECUTION_TIMEOUT` (3 h)) (scheduled specialist runs on, ren's default, since 2026-11-20), with electron-playwright-mcp (no remote: the VM's `ren/electron-playwright-mcp/` is a copy of its `pyproject.toml`, `README.md` and `src/electron_playwright_mcp` from the owner's checkout at `4612a9b`) and Playwright's Chromium for deep research's PDF, with the toolbox's Node 26.13.0 and pnpm for its Slidev deck, keys in
  `search.env`, Supabase's, `HF_TOKEN`, `PERPLEXITY_API_KEY` (its tool-repair fixer runs on Perplexity) and ren desktop's own providers (`RON_CHAT_PROVIDERS`, `TELNYX_API_KEY`, `GEMINI_API_KEY`, `MISTRAL_API_KEY`, `XAI_API_KEY`, 2026-11-16) in `ren.env`, so Deep Research picks ren desktop's default, Telnyx `moonshotai/Kimi-K3.1-Pro` (with only NIM it ran on `minimaxai/minimax-m3.1-flash`, which went silent mid-reply and failed the run); Deep Research's working files are on the `ren_data` volume (`RON_DEEP_RESEARCH_ARTIFACTS_DIR`, since a new container lost a run's report on 2026-11-17); its storage is ren desktop's Hugging Face bucket, see "Context
  widget"), at `https://rivet.ron-ai.dev/` behind the same token (Caddy sends every
  path the other services don't take to it, except ren's A2A endpoint). "Add search to Ren context" goes to the signed-in account's Context Agent
  (the mounts point ren's context store at the account, as `ContextBridge` does on the desktop), and "Search deeper"
  loads ren's deeper search in the tab.
- **Ren's hub** (`deploy/vm/hub/app.py`, FastMCP 3.4.0 and mcp 1.26.0, the versions ren desktop pins; the `hub`
  container at `https://rivet.ron-ai.dev/hub/` behind `EMRYS_GEMINI_TOKEN`): the one place the phone, ren desktop and
  the agents meet. A phone and a desktop are behind home routers, so each dials out
  (`wss://…/hub/attach/phone`, `/attach/desktop`, WebSocket subprotocol `mcp`) and serves its own MCP server on that
  socket; the hub is an MCP client there (`ProxyProvider` over the live session, `cache_ttl=0`) and an MCP server at
  `/hub/mcp` (Streamable HTTP). The phone's tools keep their names, ren desktop's are under `desktop_`, and
  `hub_peers` says which side is attached. `/hub/agents/mcp` is the same hub for the ElevenLabs agents, which already
  run the phone's tools as client tools: only the phone's `browser_*` tools, `desktop_*` and `hub_peers`.
  FastMCP 4.1.0 pulls mcp 2.3.0, which has no WebSocket transport, which is why the hub is on 3.4.0.
- **Work mode** (2026-11-20, owner: a work mode like Claude Code's, where Desktop Commander works on the user's own
  machine; mobile only, owner 2026-11-20): the user's computer is the hub's third side, `local`. `deploy/local/computer.py`
  runs on the computer (`REN_HUB_URL=wss://<host>/hub REN_HUB_TOKEN=<the hub's token> uv run deploy/local/computer.py`,
  needs uv and Node): Desktop Commander 0.3.1 over stdio with the user's environment, served through FastMCP's proxy on
  `/hub/attach/local`, dialing again after every drop. The hub lists its tools under `local_` on `/hub/mcp` and
  `/hub/agents/mcp`, and `hub_peers` says `local`. Every Desktop Commander schema carries `$schema` and
  `additionalProperties`, which Gemini Live refuses, so the connector serves them without those keys and voice gets the
  same 25 tools as text (the VM's own Desktop Commander, `emrys-computer`, still has them, so voice doesn't get its
  tools). The agent panel's Work pill (with a Strands provider; the ElevenLabs agents' tools are set in ElevenLabs)
  takes a folder (`Settings.workFolder`); the session opens with `work=<folder>`, and `deploy/vm/gemini/app.py` ends
  both agents' instructions with `work_prompt`: work there through `local_*` like a coding agent. Checked offline
  (2026-11-20): the hub's test, and the connector with the real Desktop Commander against the hub on loopback (25
  `local_` tools on both endpoints, `local_start_process` ran a command, every schema declarable on Gemini Live, and it
  attached again after the hub restarted). Not run live.
- **The phone on the hub** (`mcp/HubLink.kt`, `mcp/HubService.kt`, `mcp/PhoneMcpServer.kt`, `mcp/BrowserTools.kt`;
  Settings › Remote tools): a foreground service keeps the phone attached with the Kotlin MCP SDK 0.16.0
  (`Server.createSession(WebSocketClientTransport)`), proves the link with a ping every 20 s and retries from 2 s to
  60 s. It serves the 19 phone tools, `request_approval`, the seven searches and 13 browser tools (`browser_tabs`,
  `browser_open`, `browser_navigate`, `browser_back`, `browser_forward`, `browser_reload`, `browser_select`,
  `browser_close`, `browser_read`, `browser_click`, `browser_type`, `browser_scroll`, `browser_screenshot`) through the
  same handlers as the client tools. `HubLink.tools()` and `call()` make the phone an MCP client of the hub too. The
  old listener on the phone's Tailscale address is gone (the owner's Mac has no Tailscale, so nothing could reach it).
  `browser_click` taps the element's place on screen (`Browser.tap`), so the page sees a real gesture; a tab that
  isn't on screen gets the element's own `click()`.
- **ren desktop on the hub** (`agent/api/core/ren_mcp.py` in `ImRonAI/TheBrowser`): FastMCP server `ren-desktop` at
  `/mcp/` with the browser under `browser_` (a proxy over the `electron-playwright-mcp` server its own agents drive)
  and Ren's agent as `ask_ren`, `ren_threads`, `ren_thread` and `ren_cancel`. With `REN_HUB_URL` and `REN_HUB_TOKEN`
  set the backend attaches to the hub, and the panel agent's tool catalog gains the server `phone` (the hub's tools,
  prefixed `phone_`).
- **The agents on the laptop's desktop** (2026-11-13): the laptop's X server (Xvfb `:1`, run with `-ac` and MIT-SHM)
  is shared with the `computer` and `gemini` containers through the `x11` volume at `/tmp/.X11-unix`, the laptop's IPC
  namespace and its process namespace, with `DISPLAY=:1`. The computer image has the desktop tools the workspace's
  `AGENTS.md` names (`xdotool`, `xfce4-screenshooter`, `x11-utils`, Chromium and linuxserver's `wrapped-chromium`), so
  the Strands agents' shell and Desktop Commander drive the owner's desktop: from the `gemini` container,
  `wrapped-chromium https://example.com` opened on the laptop's screen and `xfce4-screenshooter` captured it.
- **The Strands agents on the hub** (`deploy/vm/gemini/app.py`): the server `ren-hub` (`http://hub:8300/mcp`,
  unprefixed) replaces the phone's Tailscale address, so Gemini, NIM and Mistral reach the phone and ren desktop
  whichever is attached.
- **Issue cards and Embed tabs** (2026-11-17, ren `Oubli-AI/ren` PR #18, copied unmodified into `src/ren`): issue
  cards are frosted glass with the status as a blurple tone, and the "/" menu's Embed tabs puts web pages, maps, Figma,
  Loom, Vimeo, Drive, YouTube, video, audio, PDFs and workflows in one block with a tab each, in issue descriptions and
  documents. Agents read and write it as one ```` ```embeds ```` fence with a `[Kind: title](url)` line per tab (a
  workflow tab is a nested `workflow` fence).
- **Web tools in place of Brave** (2026-11-17, ren `Oubli-AI/ren` PR #11): Brave's agent tools are off, not deleted.
  On the VM ren's `brave_tools()` is empty unless `RON_BRAVE_TOOLS=1`, and every Strands agent (text on every provider,
  Gemini Live) and ren's Deep Research get ren's `web_tools()` instead: strands_tools' Tavily (`TAVILY_API_KEY`), Exa
  (`EXA_API_KEY`) and Bright Data (`BRIGHTDATA_API_KEY`, zone `BRIGHTDATA_ZONE`, default `web_unlocker1`) tools and
  Olostep's hosted MCP server (`OLOSTEP_API_KEY`, tools `olostep_*`), each only where its key is set, beside Strands'
  vended `http_request`. The keys go in `gemini.env` (Strands agents) and `ren.env` (ren's agents and Deep Research);
  none were on the VM or the owner's Mac on 2026-11-17. On the phone the seven Brave search tools (client tools and on
  the hub) are served only when `braveSearchTools=true` in `android/ren.local.properties`
  (`BuildConfig.BRAVE_SEARCH_TOOLS`). The ElevenLabs agents get no new tools (their configuration is the owner's), and
  the search page in the browser still searches Brave.
- **Docker's disk on the VM** (2026-11-17, `deploy/vm/host/`): the VM's 49 GB disk filled twice (11-16 and 11-17, the
  second time killing a Deep Research run) when an image build ran beside the old images. The daemon uses the
  containerd image store, so images are in `/var/lib/containerd`, not `/var/lib/docker`. The `daemon.json` set on
  11-14 had only `defaultKeepStorage`, which Moby maps to BuildKit's `reservedSpace`: a floor the GC keeps, with no
  ceiling, so the build cache was never trimmed. The host files are installed as `/etc/docker/daemon.json` (build cache
  between 2 GB and 8 GB and at least 10 GB of disk left free, `live-restore` so a daemon restart leaves the containers
  running, container logs rotated at 3 × 20 MB), `/etc/systemd/system/docker-prune.{service,timer}` (every hour, every
  image no container uses and older than two hours goes, so each deploy's previous image is gone within the hour) and
  `/etc/systemd/journald.conf.d/size.conf` (the journal kept to 300 MB). `emrys-brain:14bcf2b` stays because the
  created, never-started container `emrys-brain-kept` holds it; a rollback image older than two hours does not stay,
  so rebuild the old commit instead. `builder.gc` and `log-opts` need `systemctl restart docker`, the rest
  `systemctl reload docker`. All of it was live on 2026-11-17 at 23:3x UTC, and the boot disk (`hyperdisk-balanced`)
  was grown online from 50 GB to 100 GB the same day (owner's OK; `growpart` from cloud-guest-utils, then
  `resize2fs`): 57 GB free afterwards.
- **ElevenLabs is out of credits (2026-11-12):** a typed question on the emulator opened a session with the text
  agent (tools registered, both MCP servers connected), and ElevenLabs closed it 8 s later with WebSocket code 1002,
  "This request exceeds your quota limit." The SDK reports any close but 1000 as `DisconnectionDetails.Error`, and the
  panel now shows it ("ElevenLabs ended the conversation: …") where it used to go back to Ready in silence. No
  ElevenLabs reply has been seen in ren mobile yet.
- **Live, 2026-11-12:** with the hub deployed, the emulator's app and ren desktop's backend (the branch above, run
  from a clone on the owner's Mac) both attached. A client on `/hub/mcp` listed 76 tools (40 from the phone, 36 from
  the desktop); `device_status` returned the emulator's real battery and network; the desktop's own Strands
  `MCPClient` (`hub_client_factory`) opened https://example.com in the phone's browser and read it back; and
  `/hub/agents/mcp` listed 50 tools. Through the hub, `browser_click` on example.com's link went to iana.org,
  `browser_back` returned to example.com and `browser_forward` to iana.org. Both sides re-attached by themselves each
  time the hub's container was replaced. The search page rendered in the app, and `deploy/vm/search/app.py`, run on
  the owner's Mac with the three keys `search.env` carries, answered one real search at level `fast`: five Brave
  sections (`web` 31, `news` 31, `videos` 50, `images` 50, `places` 2) and a Brave Answers answer with citations;
  the shopping agent on NIM was still reasoning when the 120 s the request was given ran out. Not run: a search from
  the app against the VM, ren desktop's browser tools through the hub (Electron wasn't running), anything on a
  physical phone.
- **The Strands agents live, 2026-11-12 and 13** (the VM, the emulator's app on Settings › Agents):
  - From the app's panel, Mistral read the phone's battery through the hub and, with ren's Brave tools, answered a web
    question in 24 s with its source; Gemini (`gemini-4-hydrogen`) opened example.com in the phone's browser and read
    its heading back; Gemini 3.8 Live answered a spoken question about the battery aloud with the real level.
  - 2026-11-13 05:5x UTC, with the token from a client on the Mac: `/hub/mcp` listed 40 tools, `hub_peers` said
    `{"phone":true,"desktop":false}` and `device_status` returned the emulator's battery; `/hub/agents/mcp` listed 14.
    "What is 2 + 2?" got "4" from Gemini in 2.4 s and from Mistral in 3.9 s, and nothing from NIM in 75 s. Spoken to
    `/gemini/voice` (macOS `say`, 16 kHz PCM), the same question got "That's four." with 24 kHz audio.
  - **The Gemini key is on the free tier**, which allows 20 `generateContent` requests a day per model
    (`GenerateRequestsPerDayPerProjectPerModel-FreeTier`, 429 with a `retryDelay`) and refuses `gemini-4-argon` and
    Google Search grounding, so `gemini.env` sets `gemini-4-hydrogen` and `GEMINI_WEB_SEARCH=0`. A harness turn makes
    several requests (the memory extraction is one), so the day's quota goes in a few web questions; the next ones
    ended in Google's 503 "high demand" after 38 s and in the 429 after 126 s. That is why a web
    question to Gemini text got no answer once the Brave tools were added. A billed key lifts it.
    2026-11-20 on the IONOS server, with `gemini-3.5-flash-lite` and `GEMINI_WEB_SEARCH=0`: a harness turn sends 223
    function declarations (about 142k tokens, 319k of the request's 559k characters are the `apify_` tools), and
    every turn got 429 or 503; the same request with no tools or with 50 answered at once.
  - **NIM's default `z-ai/glm-5.3-flash`** (ren's default, listed in NVIDIA's catalogue) returns nothing, from this
    VM and from ren desktop's own NIM code and venv on the owner's Mac; other NIM models on the same key answer in
    under a second, and Settings › Agents picks one. The VM's NIM default is `minimaxai/minimax-m3.1-flash` (owner,
    2026-11-13), set with ren's own `NIM_MODEL` in `compose.yaml`.
  - The phone's seven search tools (`web_search` and the rest, on the hub and as client tools) search Brave with the
    keys in Settings, baked from `braveSearchKey` and `braveAnswersKey` in `android/ren.local.properties`. Without them
    they answer "Search isn't set up yet: add the Brave key in Settings.", which Gemini Live got when it chose
    `web_search` for a spoken web question. The Strands agents also have ren's own `brave_*` tools on the VM's keys.

## How it works

The app connects straight to the owner's two ElevenLabs agents with the official ElevenLabs Android SDK (0.13.0) and
answers their client tools on the phone. Nothing sits in between: no backend, no gateway, no wrapper around the agents.

- **Session start** (`voice/RenAgent.kt`): `ConversationConfig(agentId, userId, dynamicVariables, textOnly, clientTools, …)`.
  - Voice agent `agent_2901m620nzwrev2r7ekqpydcxe25`, text agent `agent_9601m623qa72ffs9cyy3ezc0p6tb` (public agents,
    so `agentId` alone starts a session; no API key on the phone).
  - `userId` is a UUID made once per install (`Settings.userId`) and also sent as the `user_id` dynamic variable.
  - Eight `perm_*` dynamic variables (`perm_use_apps`, `perm_send_messages`, `perm_read_messages`, `perm_purchases`,
    `perm_install_apps`, `perm_cloud_coding`, `perm_deploy`, `perm_create_music`), each `granted` or `prompt`, from
    Settings › Permissions (`core/Approvals.kt`, `core/Settings.kt`). How the agents use them is set in their prompts,
    which the owner manages.
- **Client tools** (`AgentTools` in `voice/RenAgent.kt`, run by `device/DeviceTools.kt`), 19 phone tools with no gate
  on the phone: `sms_send`, `sms_list`, `contacts_search`, `contact_save`, `calls_recent`, `phone_call`,
  `calendar_list`, `calendar_create`, `notifications_list`, `notification_reply`, `reminder_create`, `reminders_list`,
  `reminder_cancel`, `email_draft`, `apps_list`, `app_open`, `device_status`, `screen_read`, `screen_act`.
- **`request_approval`** (client tool, `action_type` + `details`): shows the native approval sheet (`ui/ApprovalSheet.kt`)
  with what Emrys wants to do, an "Always allow" switch, Deny, and Approve behind biometrics or the screen lock. It
  answers `{"approved": …, "remember_decision": …}`; "Always allow" stores that `perm_<action>` as `granted` for later
  sessions. While Emrys is in the background the request is also a notification. The SDK ends every client tool call
  at 30 seconds, which withdraws an unanswered request (logged as expired).
- **Agents' own tools** (configured by the owner in the ElevenLabs dashboard, read 2026-10-31): both agents have the
  MCP servers `oubliai-telnyx` (https://oubli-catolabs.fastmcp.app/mcp) and `emrys-computer`
  (https://rivet.ron-ai.dev/computer/mcp, bearer-gated). The workspace also holds webhook tools that use the owner's
  ElevenLabs key.
- **Background tasks** (`deploy/vm/computer/hooks.py`, the `hooks` container at https://hooks.oubliai.com; deployed
  2026-11-03, live run the same day below):
  1. Either agent calls the async webhook tool `start_background_task`, and `POST /api/task` answers `started` at once.
     It takes `task` and `conversation_id` (from `system__conversation_id`), and `title`, `done_when` and the eight
     `perm_*` when the tool sends them; a missing `perm_*` asks first. The tool's description says Emrys phones the
     owner with the result itself, so the brief leaves calling back out. A conversation that ends with work it didn't
     hand off starts it through its post-call `background_task` data collection item instead.
  2. hooks.py records the job in `/config/workspace/jobs/<conversation>/<n>/job.json` and holds a text conversation with
     the Task agent "Rivet (Task)" (`agent_4901m6gdwby2fetbqddcpfkevvpp`): the text agent without the client tools or
     `start_background_task`, text only, 7200 s long. It speaks the documented WebSocket protocol rather than the SDK's
     `AsyncConversation`, whose `start_session` and `end_session` have no type annotations. The brief (`Title`, `Task`,
     `Done when`) is the first message, the `perm_*` are dynamic variables, the conversation id is the job's `session`,
     and the agent's replies go to `session.log`.
  3. The Task agent works through emrys-computer, oubliai-telnyx and the search webhooks, checks `done_when` itself and
     calls ElevenLabs' built-in `end_call` (attached 2026-11-04). Its post-call webhook, matched to the job by `session`, carries the report as data collection:
     `outcome` (ready, not_ready, needs_owner), `result`, `where`, `checked`, `blocker`, and `opening` (owner-approved
     2026-11-04), the line the callback starts with: why Emrys is calling back and how the task went. A session that ends without an
     outcome reports not_ready with ElevenLabs' termination reason; one that never starts reports why.
  4. Once the conversation the task came from has ended (its post-call webhook, or 600 s at most), the voice agent calls
     the owner from +17608769757 with the job's eight `perm_*` (its tools require them) and `original_request`, `source_summary`, `job_outcome`,
     `job_result`, `job_where`, `job_checked` and `job_blocker` (each `none` by default), the report's `opening` as its
     first message (empty for a session that never started, so it waits for the owner's hello), and its procedure
     "Report background task results"
     (`agtprc_2801m6ge2jbzebp8126nvrbc7520`) started: it checks the work with its own tools, then says whether it's
     ready. A refused call is placed once more after 60 s; every attempt's `success` and `message` are in `job.json`.
     A callback (`job_outcome` not `none`) never starts a task from its own post-call data, so a misread report can't
     loop.

  Live run, 2026-11-03: a request typed in Emrys on the emulator reached the text agent
  (`conv_4501m6grm404fpdsebhe55vknaba`), which called `start_background_task` with a title, `done_when` and all eight
  `perm_*`. The Task agent (`conv_3701m6grnh7mea2vxc8c6nhrqere`) wrote `/config/workspace/toolbox-versions.md` through
  emrys-computer in 22 s, every version matching the commands, and checked it. With no `end_call` it called
  `create_dub` with `name: "end_call"` (HTTP 400, nothing made), and the session stayed open until ElevenLabs ended it
  5414 s in ("Conversation timed out after the last agent message"). The post-call report (`ready`, with result, where
  and checked) reached hooks.py, which placed the callback at once because the text chat had ended. The callback
  (`conv_8601m6gxtz4jfberz3cr1wzkk4tm`) was answered, then ended after 7 s with error 1008 "Missing required dynamic
  variables in tools" for the eight `perm_*`, which the callback didn't send. hooks.py passes them on the callback (`58b5f83`,
  deployed the same day with `docker compose -p emrys build computer` and `up -d --no-deps hooks`, so only hooks was
  recreated; the previous file is `computer/hooks.py.before-callback-perms-20261103-211725`). After that deploy both
  endpoints answer 401 without their secrets and a task without `task` gets 422.

  Second live run, 2026-11-04 00:27 UTC, with the fix: a task typed in Emrys (text chat
  `conv_4901m6hz7jgsep0a8shh65chmjcc`) reached hooks in 20 s with all eight `perm_*`, and the Task agent
  (`conv_8101m6hz849rew8stvgpmjv42888`) wrote `/config/workspace/callback.md` (`date -u`, `uname -r`, both matching a
  fresh run) within seconds and reported ready, saying it had no way to end the session. Restarting the hooks container
  closed the session; its post-call report (`ready`) arrived within a second, and the callback waited for the text chat
  to end (about 9 minutes). Both callback attempts (`conv_2301m6hzt0xwe0qs274p47c6pvdn`,
  `conv_9501m6hzw9ynfmq8dedychk4eph3`) were refused with "[quota_exceeded] You've run out of credits", so the fix
  hasn't been heard on a call yet.

  Third live run, 2026-11-04 04:37 UTC, with `opening` (`2d36d67`): the same request plus "call me back when it is
  done" (text chat `conv_1501m6jdhvhke59trn18azx9xdmk`). The text agent wrote calling the owner back into the brief,
  since neither its prompt nor `start_background_task` says Emrys calls back with the report. The Task agent
  (`conv_7901m6jdk6tmfwjr59qwdcp5sbqd`) wrote and checked `callback.md` in 6 s, then, as its prompt allows a call the
  brief asks for, placed its own TeXML call from +17608769757 through oubliai-telnyx (`CreateTexmlCall`, queued 04:40)
  that read out the result, and ended its session with `end_call` at 287 s. The post-call report (`ready`) copied that
  call's script into `opening`, result and "Goodbye" included. hooks.py placed the callback when the text chat ended
  (`conv_3201m6je4b8kfeg8tx18xv5w76wx`, 04:47): it opened with that line, reached voicemail, and the voice agent ended
  it with `voicemail_detection` after 6 s without leaving a message.

  Fourth live run, 04:55 UTC, after the owner's OK to do whatever makes the calling agent know what it's doing:
  `start_background_task`'s description says Emrys phones the owner itself, and `opening` says to write the line
  fresh, why Emrys is calling and how it went, with no introduction or goodbye. The same request (text chat
  `conv_2001m6jejz48fjs97zykg7ykxfbq`) gave a brief ending "Do not call or text the owner, because reporting back
  happens automatically". The Task agent (`conv_6901m6jem6ewewwvy5xa0jv5g1a3`) wrote and checked `callback.md` and
  ended with `end_call` in 10 s, with no call of its own. The one callback (`conv_7201m6jet80sft0rjxqf9dzn2crw`,
  04:59, answered) opened "I'm calling back about writing the UTC date and kernel version to callback.md. That's all
  done and checked."; the owner hung up 10 s in, so nothing after the opening was tested.
- **Calls Emrys places** (owner-approved 2026-11-04): asked to call the owner back or to call someone, either agent
  hands the conversation to the voice agent on the phone with the webhook tool `call_as_emrys`
  (`tool_8901m6j86w2mfqa8ew5hkxvrgqs9`, on both agents). It posts to ElevenLabs' own
  `/v1/convai/sip-trunk/outbound-call` with the workspace secret `xi_api_key`: the voice agent calls from
  +17608769757 with the eight `perm_*`, `call_thread` (the conversation so far, `system__conversation_history`) and,
  written by the calling agent, `to_number` (the owner's number is in the tool's description, not in git),
  `call_to`, `call_reason` and the call's `first_message`, the line Emrys says when they pick up. The call starts the
  voice agent's procedure "Continue a conversation by phone" (`agtprc_0501m6j84vnafrcs0naakt8506da`), which reads
  those variables (placeholders `none`). Both prompts say Emrys can phone out, that a `sip_trunk` call is one Emrys
  placed when an active procedure says so, and when to use `call_as_emrys` rather than `phone_call`.

  The first message carries the reason because the voice agent runs Eleven Realtime v1 Max, whose quick replies
  didn't act on the started procedure. Live, 2026-11-04 03:11 UTC, with an empty first message
  (`conv_9301m6j8kcdde2ztjmercs82v6m9`): the thread, reason and `perm_*` all arrived, yet the first two replies were
  "I was wondering when you'd call" and "I don't actually have a reason for calling"; only the third, reasoned reply
  used the reason. On that call the agent also called `device_status`, which can't run on a phone call, and then said
  the phone was "plugged in and charging", which no tool returned. With the text agent's first message
  (`conv_7601m6j92ff3e849z3d8d95qe203`, 03:19 UTC) the call opened "Hey, calling you back about your Wi-Fi. Your
  phone's connected to Wi-Fi right now, and the battery's at 100 percent", the owner said thanks, and the agent ended
  the call with `end_call`. Neither text chat started a background task from its post-call data.
- **Transcript:** agent replies are shown without their expressive audio tags (`[warmly]`, `[surprised]`), which the
  voice still performs. `AgentTools.withoutAudioTags` uses the pattern from ElevenLabs' own widget (convai-widget-core
  `stripAudioTags`), so markdown links stay.
- **Strands providers: Claude Code, Antigravity, Gemini, NVIDIA NIM, Mistral, Telnyx and xAI** (Settings › Agents; Claude Code is the default, see "Claude Code and Antigravity CLI"): Emrys's agents
  in Strands on the workspace VM (`deploy/vm/gemini/app.py`, the `gemini` container at `wss://rivet.ron-ai.dev/gemini/`,
  behind Caddy's bearer `EMRYS_GEMINI_TOKEN`). The app's `voice/Assistant.kt` is the seam: `RenRuntime.agent` is
  `RenAgent` or `StrandsAgent`, and the screens use only its six members. Settings › Agents picks the provider, the text
  model from the provider's live catalogue (`GET /gemini/models?provider=`, searchable; none chosen is the VM's default)
  and the model's reasoning level (`GET /gemini/efforts`, with Auto first); the session's WebSocket carries `provider`,
  `model` and `effort`.
  - NIM and Mistral are ren's (`ImRonAI/TheBrowser` at `0c973a6`; `b112adf`'s reordering of tool results is not copied,
    because Strands 1.64.0's formatter already puts them first), copied into `providers.py`, `mistral_model.py`,
    `media_capabilities.py` and `context_windows.py`: NIM is Strands' `OpenAIModel` on `integrate.api.nvidia.com/v1`
    (ren's default `z-ai/glm-5.3-flash`; the VM's `NIM_MODEL` is `minimaxai/minimax-m3.1-flash`), Mistral is ren's `RonMistralModel` (owner, 2026-11-13: Strands' own `MistralModel` groups a streamed tool call's pieces by id, and only the first piece carries one, so every Mistral tool ran with `{}`; ren's subclass groups them by index; default `mistral-large-latest`), each passed to
    `create_harness(model=<Model>)`, with ren's per-model reasoning tables. `providers.py`'s header lists what the copies
    change for mypy and for Strands 1.64.0. Keys: `NVIDIA_NIM_API_KEY` and `MISTRAL_API_KEY` in `gemini.env`.
  - Telnyx (2026-11-13) is built the way ren builds NIM, since ren has no Telnyx provider: Telnyx Inference serves its
    open-weight models on an OpenAI-compatible API (https://developers.telnyx.com/docs/inference/getting-started.md),
    so the model is Strands' `OpenAIModel` on `https://api.telnyx.com/v2/ai/openai` with `TELNYX_API_KEY` (in
    `gemini.env`) as the bearer, passed to `create_harness(model=<Model>)`. The catalogue is `client.models.list()` there
    (`GET /v2/ai/openai/models`), kept to `task` `text-generation` (the same list holds embedding and audio models);
    its `context_length` is the harness's `context_window_limit`. The default is `zai-org/GLM-5.3-Flash`, the model
    Telnyx's catalogue marks Recommended (https://developers.telnyx.com/docs/inference/models.md). Telnyx takes
    `reasoning_effort` (none to max) but documents no values per model and answers an unsupported one with a 400, so
    its models offer Auto only, and `reasoning_content` streams as Strands' reasoning.
  - xAI (2026-11-13) is ren's, copied from `ImRonAI/TheBrowser` at `5a23a3d`: ren's `RonXAIModel` and `XAIStatePruner`
    (`agent/api/core/xai_model.py`, in the image's copy of ren at `/opt/ren`) on strands-xai 0.6.1 and xai-sdk 1.19.0,
    built as ren builds it in `providers.py`: xAI's server-side `web_search`, `x_search` and `code_execution` (and
    `collections_search` when `XAI_COLLECTION_IDS` is set), its citations included, and ren's per-model reasoning table.
    The default is ren's `grok-4.5` (`XAI_MODEL`), the catalogue is xai-sdk's `models.list_language_models()`, and the
    key is `XAI_API_KEY` in `gemini.env`. Every Strands provider, xAI and Gemini included, has ren's seven `brave_*`
    tools, as ren desktop gives them to all its providers (`build_core_tools`). Not run live (owner, 2026-11-13: the
    owner tests it).
  - Text (`/gemini/text`): the Strands harness, `create_harness(model="google/<text model>", effort=…)` for Gemini, with
    every built-in on (shell, read, write, edit, web_fetch, Google Search, programmatic_tool_caller, subagent, todos,
    sessions) plus python_repl, http_request, notebook, sleep, stop, handoff_to_user, swarm, graph and
    mcp_router. Gemini's default text model is gemini-3.5-flash-lite (owner, 2026-11-20; `GEMINI_MODEL` overrides it).
  - Voice (`/gemini/voice`): `BidiAgent` with `GoogleGeminiLiveModel` on gemini-3.8-live, voice Algieba, the same
    vended tools except swarm and graph (subagent and web_fetch run on the text model) and the bidi docs'
    `end_conversation`. Live, 2026-11-06 on the Gemini API: `enable_affective_dialog` is refused (1007 "Request
    contains an invalid argument"), and Strands 1.64.0 sends swarm's and graph's schemas as `additional_properties`,
    which Live refuses (1007 "Unknown name"); without both, a spoken "What is 2 + 2?" got "2 + 2 is 4." in
    24 kHz audio with both transcripts. The
    phone streams 16 kHz PCM and plays 24 kHz PCM (`voice/GeminiAudio.kt`), cleared on `bidi_barge_in`.
  - Both reach Ren's hub (`http://hub:8300/mcp`: the phone and ren desktop), emrys-computer and oubliai-telnyx
    (`${TELNYX_MCP_AUTHORIZATION}`), unprefixed so the names match the prompt, and use the ElevenLabs text and voice
    agents' own prompts, read when a conversation opens and filled like ElevenLabs' dynamic variables. The chat is
    built from Strands' events (`voice/GeminiEvents.kt`). `start_background_task`, `call_as_emrys`, Google Calendar
    and the ElevenLabs webhook tools have no Gemini counterpart yet.
  - Memory (2026-11-15, rebuilt 2026-11-21) is ren desktop's own, from the image's copy of ren
    (`agent/api/core/dreaming.py`), in place of the harness's built-in memory. Both agents read the user's second
    brain and write the folder `phone` with Anthropic's memory tool (`memory`), search it (`search_memory`, by meaning,
    on Mistral's `mistral-embed`) and keep a journal (`journal`), in the `ren`
    service's storage (ren desktop's Hugging Face bucket, `PANEL_STORAGE: huggingface`, `HF_TOKEN` in `gemini.env`),
    per `user_id`: the signed-in ren account's id (`StrandsAgent`'s `account`, from the Context widget's report), or
    the install's when signed out, so it is the brain ren's pages and the Context widget use (2026-11-20). The text
    agent's turns are journaled and the brain's index is injected before each turn (`dreaming.brain_memory`), as the
    SDK does both, with no model call; the voice agent, a `BidiAgent`, has the index in its prompt
    (`dreaming.index_block`) and the same tools (`dreaming.agent_memory_tools`). This service doesn't dream: ren's API
    dreams for every user, this service's journal included, and nothing reaches the brain until the user approves it
    (see "Second brain for every agent"). Both prompts end with ren's `MEMORY_PROTOCOL`, and the chat leaves `journal`
    calls out (`GeminiEvents`).
- **Phone MCP server** (`mcp/`, Settings › Remote tools): served on Ren's hub; see "The phone on the hub" above.
- **Release builds** keep the SDK's private fields (`android/app/proguard-rules.pro`). The SDK reads the voice
  conversation token into a private field with Gson, and its own keep rules cover only public members. Without the
  rule, R8 renames the field, the token comes back blank and voice fails with "Couldn't reach the ElevenLabs agent".
  R8's own `-dontwarn` rules cover Ktor's `java.lang.management` check, under the phone's MCP server.

## Status (2026-10-31)

| Area | State |
|---|---|
| App | **0.5.0** (versionCode 13, `com.hiron.emrys`). Everything is on `main` (PRs #5 to #8). The release build ships arm64-v8a only, with the WebRTC library (about 12 MB per ABI) and the dex files compressed (`useLegacyPackaging`), since a sideloaded APK isn't compressed on the way: 15.3 MB on 2026-11-13, under the project folder's 20 MB limit. |
| Verified | 109 unit and screen tests pass, Kotlin compiles with warnings as errors, lint has 0 errors. On the Android 15 emulator (2026-10-31) a typed question opened a session straight to the text agent (`conv_2801m68vtz0te2natfxrg5cyc6gp`): `user_id` and all eight `perm_*` arrived, the agent called `device_status`, the phone returned real data and the agent answered with the battery level. The arm64-only release APK, with the keep rule and the tag filter, installed on the emulator and opened a voice session with the voice agent ("Listening"; the agent's greeting appeared without its audio tags). |
| Not verified | Talking to the voice agent through a real microphone (the emulator runs without audio, so only the connection and the agent's greeting were checked); `request_approval` from a live agent (the handler is registered, but on 2026-10-31 the tool was not attached to either agent — attaching it is the owner's call); anything on a physical phone. |
| Attachments | Images and PDFs are not built: SDK 0.13.0 has no way to send files (`OutgoingEvent` has no multimodal message). The WebSocket protocol's `multimodal_message` with `file_input` exists only in the JS SDK and raw API. |
| Workspace VM | Google Cloud `instance-20261029-122759` (us-central1-a, c4-standard-8, Debian 13, 100 GB since 2026-11-17, static IP 136.113.136.145). `/opt/emrys` runs Caddy 2.11.7, the laptop (linuxserver webtop, https://desktop.rivet.ron-ai.dev), OpenVSCode (https://code.rivet.ron-ai.dev) and the `computer` container (Desktop Commander 0.3.1 behind FastMCP's proxy at `/computer/mcp`, reachable only with `EMRYS_COMPUTER_TOKEN`). The laptop (`deploy/vm/laptop/Dockerfile`, image `emrys-laptop:ls219`) and the computer image (`node:26.13.0-bookworm`) both run `deploy/vm/toolbox/install.sh`, so the owner's terminal, Desktop Commander and the hooks jobs share one set of pinned, checksum-checked developer tools: Node 26.13.0 LTS with npm/npx, pnpm, yarn, the Vercel CLI, TypeScript and tsx, Python 3 with pip, venv and pipx, uv/uvx, Bun, Deno, Go, gh from GitHub's apt repository, build tools and common CLIs. Rust (rustup 1.29.2, stable, minimal profile plus clippy and rustfmt) is in the shared home (`~/.rustup`, `~/.cargo`, on PATH via `~/.bashrc`, `~/.profile` and the computer image's `PATH`). OpenCode on the laptop runs Mistral `zai-glm-5-3` at `reasoningEffort: max`. It is OpenCode 2.2.5 (2026-11-01, from the v2 installer with the npm tarball's integrity checked; 1.19.5 is kept as `/config/.opencode/opencode-1.19.5.bak`), with the shared background service disabled (`opencode service set disabled true`, in `~/.config/opencode/service.json`) so every container's `opencode` runs a private server with its own environment. It reads the V1 `opencode.json` as is, except `whitelist`, which V2 ignores. Its MCP servers are context7, deepwiki, github (GitHub's remote server with the `ItzOubliAI` fine-grained token read from `~/.config/opencode/github-token`, mode 600, only on the VM), playwright (Chromium in `~/.cache/ms-playwright`), vercel and linear; the last two need a one-time OAuth sign-in from the laptop desktop (`opencode`, then `/mcps`). The owner signed in on 2026-11-01 (Vercel user `iamronai`, Linear team Cato-labs `CAT`), and the hooks jobs use those sign-ins from the shared home; the owner approved the Vercel team `team_dQkiAxSaM1x2NGxpqfymb7kT` (`tim-hi-roncoms-projects`) for that sign-in through `authorize_team_access` the same day, and `get_team`/`list_projects` for it succeed from the hooks container. The computer image puts `/config/.opencode/bin` and `/config/.cargo/bin` on `PATH`. A fresh private server lists no MCP servers for its first seconds, so `opencode mcp list` prints "No MCP servers configured" and `opencode mcp auth <name>` cannot find the server; against a server that has been up 15 s the list is complete, and a real `opencode run` attaches them. The brain is off the VM (deployed 2026-10-31 from `deploy/vm`: `docker compose -p emrys up -d --remove-orphans` removed `emrys-brain-1`); `https://rivet.ron-ai.dev/` answers 404, `/computer/mcp` 401 without the token and Desktop Commander's `initialize` with it. The previous files are kept as `compose.yaml.before-brain-removal-*` and `conf/Caddyfile.before-brain-removal-*`, and `data/`, `src/` and `brain.env` are still on disk. Reach it with `gcloud compute ssh instance-20261029-122759 --project fair-expanse-493212-h8 --zone us-central1-a --tunnel-through-iap`. Env files (`brain.env`, `laptop.env`, `computer.env`, `elevenlabs.env`, `hooks.env`, `code-token`) live only on the VM. The `hooks` container (`deploy/vm/computer/hooks.py`, https://hooks.oubliai.com, DNS on Vercel) serves the agents' async webhook tool `POST /api/task` (bearer from the ElevenLabs workspace secret `emrys_hooks_authorization`) and the post-call webhook `POST /api/post-call` (HMAC, ElevenLabs webhook "Emrys post-call" `cb517c50a8ab47818657c68e60d80ada`). Deployed 2026-11-01 with OpenCode doing each job (`opencode run --standalone --auto`) and a callback carrying `job_dir`; the Task agent version (see Background tasks) replaced it on 2026-11-03 (`docker compose -p emrys build computer`, then `up -d computer hooks`; the previous files are kept as `*.before-task-agent-20261103-104022`). After that deploy, `/api/task` and `/api/post-call` answer 401 without their secrets, a task without `task` gets 422, and `/computer/mcp` still answers `initialize` with its token. Both agents have the `start_background_task` tool and the post-call webhook; only the text agent has the `background_task` data collection item. Live run the same day: the text chat `conv_0701m6b181kpe78trarsr9jdbsx7` died on an LLM timeout, the post-call webhook started its task, and OpenCode built and deployed https://transilvanya-roses.vercel.app through the Vercel MCP (it exited 1 after its built-in web search was cancelled; a clean run exits 0). The callback reached the owner (`conv_1701m6b8m26me11ryhp498m9v4jq`) once the Telnyx connection's Caller ID Override was cleared. The 2026-11-01 version did not check `outbound_call`'s `success`; the current one records every attempt in `job.json`. |
| Telnyx outbound | ElevenLabs calls out through `sip.telnyx.com` as credential user `nursetimmy1988` with caller ID +17608769757. The connection's Caller ID Override must stay empty: when it held the owner's cell, Telnyx swapped that number in and refused every call with `403 Unverified origination number D51`. The phone number has "Store SIP messages" on, so `GET /v1/convai/conversations/<id>/sip-messages` shows the raw INVITE and Telnyx's reply. |
| Telnyx | The answering number +17608769757 forwards to a Telnyx call-answering assistant set up earlier by the brain. The live-call transcript, Take over and summaries went with the brain; the app keeps the number, mode and carrier forwarding codes in Settings. |

## Rules that always apply

- **Zero harness.** Connect the app to the ElevenLabs session and handle client tools. Don't write orchestration
  layers, backends between the phone and the agents, wrappers, mock runtimes, simulated paths or LLM pipes. A new
  on-device action is a new client tool.
- **The ElevenLabs agents' configuration belongs to the owner.** Never change their model, backups, prompt, tools, MCP
  servers, secrets or overrides, and never run sessions that override their settings. Reading it is fine.
- **Models are the owner's choice, in the dashboard.** The text agent and the Task agent run `claude-opus-5-5` (owner,
  2026-11-03: the task needs the most capable model). The voice agent is in Realtime mode on Eleven Realtime v1 Max
  (`realtime_model: eleven_realtime_v1_max`), which manages its LLM, so its stored `gpt-6-astra` isn't used (read
  2026-11-04). Claude Code, on the owner's Claude account, is the main text agent (owner, 2026-11-21). Don't add Claude or
  Anthropic models anywhere else in the product.
- **No live side effects without the owner's approval:** sends, calls, purchases, Telnyx writes, DNS changes, real
  email. Deploying committed changes to the workspace VM doesn't need a separate OK (owner, 2026-10-31).
- **Never commit secrets.** `android/ren.local.properties` (the baked answering number and the VM token) is gitignored; the VM's env
  files and `code-token` stay on the VM.
- **No fakes, stubs or simulations in the product.** Test the real feature in the real app (emulator or phone) before
  calling it done.
- **Branches:** branch from `main` and merge through PRs; don't push to `main`.
- **Match the surrounding code:** naming, comment density, evergreen comments with no "previously" or "now".
  Commits use conventional commit messages.
- **Validate before handing over an APK:** `./gradlew testDebugUnitTest lintDebug assembleRelease`, review the
  screenshots in `android/build/screens/`, check the APK (`apksigner verify`, `aapt2 dump badging`), and run one real
  conversation on the emulator.
- **APKs from a cloud session:** the owner's phone takes only APKs signed with his Mac's debug key (cert SHA-256
  `28d4b3…`). The build signs with `REN_DEBUG_KEYSTORE` (that keystore, base64) when it is set, and bakes
  `REN_GEMINI_TOKEN`, `REN_BRAVE_SEARCH_KEY`, `REN_BRAVE_ANSWERS_KEY` and `REN_ANSWERING_NUMBER` when
  `ren.local.properties` is absent; `VITE_SUPABASE_URL` and `VITE_SUPABASE_ANON_KEY` reach the web build from the
  environment. All seven are the owner's cloud environment variables, which he enters himself. A container has no
  Android SDK: install the command-line tools from dl.google.com, then `sdkmanager "platforms;android-36"
  "build-tools;36.0.0"`, and point `android/local.properties` at it (`sdk.dir=`). Check the APK's signer with
  `apksigner verify --print-certs` before handing it over.

## Repository map

```
android/   Kotlin/Compose app (minSdk 29, targetSdk 36): the browser, the agent panel, widgets, assistant overlay
  web/       the app's bundled pages, built by Vite into assets/ren/web by Gradle (webInstall, webBuild; needs Node):
             src/cato (Cato, unmodified), src/robot (ren's robot), src/ren (ren desktop's search page and agent panel
             components, unmodified), src/search (the phone's mount of the page), src/panel (the agent panel's chat)
deploy/vm/ the workspace VM: compose.yaml, Caddyfile, computer/ (Desktop Commander over HTTP), gemini/ (the Strands
           agents), hub/ (Ren's hub), ren/ (ren desktop's backend), laptop/ (OpenCode config, workspace AGENTS.md),
           host/ (the VM's Docker daemon, image prune timer and journald size)
docs/      design/ (REN_DESIGN.md: Ink & Volt on the phone; ren-logotype.json)
.github/   CI: android.yml (tests, lint, release build)
```

Commands:
```bash
cd android && python3 tools/build_icon.py --check
JAVA_HOME=<JDK 21> ./gradlew testDebugUnitTest lintDebug assembleRelease   # builds android/web first (npm ci, npm run build)
cd android/web && npm run build && npm test   # the pages alone: hash checks, tsc, Vite, then Playwright on the robot
uvx --python 3.12 --with fastmcp==4.1.0 --with elevenlabs==2.75.0 mypy --config-file deploy/vm/computer/mypy.ini   # the VM's Python
uvx --python 3.12 --with "strands-agents[bidi,bidi-google,gemini,mistral,openai,web-fetch]==1.64.0" --with strands-harness==0.4.0 --with mistralai==2.10.1 --with openai==2.50.0 --with fastapi==0.162.0 --with uvicorn==0.54.0 --with elevenlabs==2.75.0 --with claude-agent-sdk==0.2.187 mypy --config-file deploy/vm/gemini/mypy.ini
uvx --python 3.12 --with fastmcp==3.4.0 --with mcp==1.26.0 --with uvicorn==0.54.0 --with websockets==15.0.1 mypy --config-file deploy/vm/hub/mypy.ini
cd deploy/vm/hub && uvx --python 3.12 --with fastmcp==3.4.0 --with mcp==1.26.0 --with uvicorn==0.54.0 --with websockets==15.0.1 --with pytest --with anyio pytest test_hub.py
```

## Everything Emrys can do today

### Android app (`android/app/src/main/java/com/hiron/ren/`)
- **Runtime** (`core/RenRuntime.kt`): the state the screens render, the robot's command stream, the phone tools, the
  agent, and the approvals the agents ask for. No foreground service; the session lives while the app runs.
- **Interface (`ui/`)**: Ink & Volt (see "ren mobile" above). The kit is in `Theme.kt`, `Kit.kt`, `Keys.kt`,
  `Surfaces.kt`, `Chrome.kt` and `Meters.kt`; the brand mark is the "ren." logotype. The app opens on the browser
  (`BrowserScreen.kt`); the screens below are the agent panel and the pages its menu opens.
  - **Power-on screen** (`Onboarding.kt`): meet Emrys ("Set up" or "Not now"), the talk key, the default assistant,
    and phone access.
  - **Agent panel** (`HomeScreen.kt`, opened by Ask Ren over the whole screen): the status, the robot stage, a
    greeting, and the dock: quick keys (Catch me up, My day, Texts, Calls), the composer (text agent) and the voice
    disc (voice agent, asking for the microphone first). A conversation, typed or spoken, takes the whole panel: the
    chat fills it under a Conversation bar that opens Ren's stage over it like an accordion (owner, 2026-11-13).
  - **The chat** (`ChatView.kt`, `android/web/src/panel/`): the conversation the way ren desktop's agent panel
    (`AgentPanel.tsx`) draws it, on ren desktop's own components in `android/web/src/ren` (Conversation, Message,
    MessageResponse, Chain of Thought, Tool): the user's messages in a contained bubble, Ren's flat, its reasoning as a
    chain of thought split at its bold labels, a search as ren's web search card (what it searched for, its sources,
    its pictures) and any other tool as ren's tool card. The app sends the chat as JSON (`ChatPage.payload`,
    `src/panel/model.ts`).
  - **Approval sheet** (`ApprovalSheet.kt`): see `request_approval` above.
  - **Activity** (`ActivityScreen.kt`): pending reminders, then the activity log grouped by day.
  - **Settings** (`SettingsScreen.kt`): 1 Agents (provider, the Gemini text model, the talk key), 2 Permissions (a
    switch per `perm_*` action: "Always allowed" or "Asks you first", applied from the next conversation), 3 Call
    answering (number, mode, carrier codes), 4 What Emrys can use (phone permissions), 5 Remote tools (the phone's MCP
    server), 6 About.
- **Robot** (`ui/RobotView.kt`, `android/web/src/robot/`): the bundled page runs offline; Cato's moves follow what
  Ren is doing (`core/RobotDirector.kt`); the agent's speech moves the mouth (the SDK's audio alignment,
  `AgentTools.words`). The stage is in the agent panel; a page that fails says why with Retry. Run the emulator with
  `-gpu host`: the robot is WebGL.
- **Launcher** (`res/`, `widget/RenWidgets.kt`): adaptive and themed logotype icon, splash and notification icon;
  shortcuts Talk to Ren, Ask Ren, Catch me up, My day; a search-bar widget (4×1) that opens Ren's search and a
  panel widget (4×2) with the last reply and how many approvals wait.
- **Default assistant** (`voice/`): a `VoiceInteractionService` overlay over any app with speech-to-text and a keyboard
  fallback, sending to the text agent; locked out on a secured lock screen.
- **Notifications** (`service/Notifications.kt`): approvals ("Emrys needs your OK: …", cleared when the app comes to
  the front) and reminders.
- **Device access**: SMS, contacts, calendar, call log, notification listener (reply in any app), call screening (logs
  incoming calls), and the accessibility "app operator" that reads the screen and taps, types or scrolls.

## Remaining work

1. **Live checks on a phone:** a voice conversation, `request_approval` end to end (once the owner attaches the tool),
   each phone tool, the overlay, widgets and notifications.
2. **Image and PDF input:** blocked on the Android SDK (see Status). Don't build an upload path around it.
3. **VM leftovers:** `/opt/emrys/data`, `src/`, `brain.env` and the `emrys-brain:14bcf2b` image stay until the owner
   removes them.
4. **Background tasks:** a callback the owner talks through past its opening line; a session that never started
   still calls with an empty first message. Not checked since the 2026-11-03 config read: the voice agent's
   `background_task` item and `job_dir` on both agents.
5. **A stated result no tool returned:** on a phone call the voice agent called a phone tool that can't run there and
   then reported a result anyway (see Calls Emrys places). The prompt is the owner's.
6. **Owner's backlog** (from the approved plan): the ElevenLabs UI chat rebuild (threads, Orb, music players,
   simplified Settings), CloudFS, and triggers and webhooks.
7. **The Strands agents** (plan https://claude.ai/artifact/Ri9R22DZiVUhd2x2oW6CeL): they run on the Gemini API (AI
   Studio, owner, 2026-11-06), NIM, Mistral, Telnyx and xAI. The `gemini` and `search` images are built on the VM
   (`emrys-gemini:1.64.0`, `ren-api:4841e40`) and start once their env files are in `/opt/emrys`: `gemini.env`
   (`GOOGLE_API_KEY`, `NVIDIA_NIM_API_KEY`, `MISTRAL_API_KEY`, `TELNYX_API_KEY`, `XAI_API_KEY`; without `TELNYX_MCP_AUTHORIZATION` oubliai-telnyx
   doesn't load there), `search.env` (`BRAVE_SEARCH_API_KEY`, `BRAVE_ANSWERS_API_KEY`, `NVIDIA_NIM_API_KEY`) and
   `ren.env` (`SUPABASE_URL`, `SUPABASE_ANON_KEY`, `SUPABASE_SERVICE_ROLE_KEY`, `HF_TOKEN`, `PERPLEXITY_API_KEY`, `RON_CHAT_PROVIDERS` and ren desktop's provider keys).
   `EMRYS_GEMINI_TOKEN` is in `computer.env`, and the app's `geminiToken` (in `android/ren.local.properties`) is the
   same token. Live results are under "The Strands agents live" above; Gemini needs a billed key and NIM another
   model than its default. Next:
   `start_background_task` and `call_as_emrys` for them, and the hub's `/hub/agents/mcp` on the ElevenLabs text and
   voice agents (the owner asked for both agents to be connected; it needs the token as a workspace secret).
8. **ren mobile:** the agent panel's chat with a real conversation on a phone (built on ren desktop's components
   2026-11-13; with no agent calls allowed, only its page loading was checked); the Glass theme; the robot in ren desktop; an
   "Open on desktop" action that uses `HubLink.call`; Gemini Live as a Telnyx assistant (Call Control media streaming; ask
   before changing who answers +17608769757).
9. **Telnyx** (PR #13): checked by mypy and offline (the model, its window from the catalogue, the catalogue's filter);
   not run live. It needs `TELNYX_API_KEY` in the VM's `gemini.env` and the `gemini` image rebuilt, then one typed
   conversation from Settings › Agents › Telnyx with a tool call.
10. **Memory:** the `gemini` image is on ren `760df19` and the `ren` image on `4841e40`, which has the same memory code; both keep the brain in ren desktop's bucket
    under the signed-in account, so the phone's Strands agents and ren's pages share one brain; ren's API is its one
    dreamer and every dream waits for the user's approval. Brains the `gemini`
    service kept before 2026-11-20 under the install id stay in `/config/emrys-gemini/storage` and are not merged. Not
    run live: one conversation, one dream and one review from the phone's agents.

## Before launch (checklist)
1. **Release signing:** the APK is signed with the debug key (`android/app/build.gradle.kts`). Create a release
   keystore before any public distribution.
2. **ElevenLabs agents locked down (owner):** as of 2026-10-29 both agents had `enable_auth` off and webhook tools
   that use the owner's ElevenLabs key; public agents can be started by anyone with the agent id.
3. **The workspace VM's service account:** swap the Compute Engine default account for one with no roles.
4. **Secrets hygiene:** rotate the keys that were pasted into chat sessions.
5. **Store compliance (Play Store, if used):** SMS and call-log permissions need a permitted use case; Accessibility
   needs a disclosure. Sideloading avoids this.

## Key documents
- **ElevenLabs Agents:** https://elevenlabs.io/docs/eleven-agents/overview; Kotlin SDK
  https://elevenlabs.io/docs/eleven-agents/libraries/kotlin (source: github.com/elevenlabs/elevenlabs-android, tag
  v0.13.0); WebSocket API https://elevenlabs.io/docs/agents-platform/libraries/web-sockets; index
  https://elevenlabs.io/docs/llms.txt
- **ElevenLabs UI:** https://github.com/elevenlabs/ui
- **Telnyx MCP server:** https://github.com/ImRonAI/OubliTelnyxMCP, live at https://oubli-catolabs.fastmcp.app/mcp
- **Desktop Commander:** https://github.com/wonderwhy-er/DesktopCommanderMCP
- **Design:** `docs/design/REN_DESIGN.md`; ren desktop's `tailwind.config.ts`, `src/index.css` and `docs/brand.md`
  (`ImRonAI/TheBrowser`)
- **FastMCP:** https://gofastmcp.com (proxy providers, mounting, transforms); MCP Kotlin SDK
  github.com/modelcontextprotocol/kotlin-sdk (0.16.0)

## History
- **0.5.0, 2026-10-29/30:** Rivet renamed **Emrys** (Welsh "immortal", Merlin's true name; not a legal trademark
  clearance) with no compatibility layer, the Noir redesign, the new robot and the ElevenLabs stage layer, on top of
  upstream 0.4.5. A WebView with `WRAP_CONTENT` height collapses the robot page, so `RobotView` uses `MATCH_PARENT`.
- **2026-10-31:** the brain (Strands harness, gateway, search proxy, email, live calls, approval rules), the gateway
  client and service, brain search, recommended settings and the model picker were deleted; the app talks to the
  agents directly (`9ffab7a`). The computer container was added to the VM (`2810aae`).
- **2026-11-12:** Emrys became **ren mobile** (`com.hiron.ren`, with no compatibility layer): ren desktop's Ink & Volt
  design, Cato as the robot, a browser with ren desktop's search page, and Ren's hub joining the phone, ren desktop
  and the agents over MCP. The Matrix and Orb stage went with the old robot. Built web pages, `node_modules` and
  Playwright results left the repository; Gradle builds the pages.
