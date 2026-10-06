# Kino ⇄ CloudStream bridge protocol (v2)

Apache-2.0 (full text in `LICENSE`, next to this file; the rest of the bridge repository is GPL-3.0). This folder is copied verbatim into both repos; a change in either place is a protocol change: bump
`CsWire.PROTOCOL_VERSION`. Both sides require the exact version (no fallback): v2 replaced v1 before either shipped.
Exception: an optional key added to a bridge **result**, which older Kino builds never read (they ignore unknown keys), and
an optional bridge component Kino starts only after learning it exists, are additive within v2 and marked "(additive)"
below. Kino treats an absent additive key as its stated default.

- Every JSON object carries no secrets; repo URLs never appear (Kino sends `.cs3` bytes, not URLs).
- Absent optional values are left out of the JSON, never sent as `null`, unless a field below says `|null`.
- Ids (`callId`, `pluginId`, plugin `id`): 1..64 chars of letters, digits, `-`, `_`, `.`. Every op's `pluginId` except
  `listPlugins` starts with `cloudstream-` (Kino mints them); anything else is `bad_request`.

## hello

`hello(v, host)` → `{"protocolVersion","bridgeVersionCode","upstreamCommit","kotlinVersion","plugins":[{"id","sha256"}]}`:
every plugin the bridge holds, with the sha256 (64 lowercase hex) of the `.cs3` it loaded (`CsWire.HelloPlugin`).
Kino reconciles on each hello: it removes ids it has no install or pending preview for, and re-sends a `.cs3` whose
sha256 differs from the installed one. A malformed entry makes the whole hello a protocol error.
Mismatch: the bridge answers `{"error":{"code":"protocol"},"protocolVersion":2}`; Kino offers a bridge update.
A caller that is not Kino gets `refused`; unreadable plugin storage gets `unavailable` (never an empty list).

## call, event, done, doneStream

`call(op)`: `{"v":2,"op","callId","pluginId","deadlineMs","args":{}}`; `deadlineMs` is 1..600000. A `callId` still
running is refused (`bad_request`). `cancel(callId)` ends a running op with `cancelled`; the deadline ends it with
`timeout`.

Exactly one of `IReply.done(json)` / `IReply.doneStream(pfd)` ends each call, after every `event` of that call.

- A result of at most `CsWire.MAX_INLINE_RESULT_CHARS` (100 000) chars goes in `done`.
- A longer one goes through `doneStream`: the bridge creates a reliable pipe (`ParcelFileDescriptor.createReliablePipe()`),
  hands Kino the read end and writes the same JSON `done` would carry, UTF-8, into the write end, from its own
  threads (never the binder thread). Complete: the write end is closed with an OK status. Failed: `closeWithError`.
  Kino reads with `CsWire.readStreamedResult`: EOF without the OK status (closeWithError, a dead bridge) or bytes that
  are not UTF-8 is `bridge_stream_broken`.
- A streamed result is at most `CsWire.MAX_STREAM_RESULT_BYTES` (8 MiB) of UTF-8. Over it the bridge answers `done`
  with `too_large` instead (a `load` answer is first rebuilt leaner, below).
- The bridge writes within the op's deadline, and at least 5 s and at most 60 s after `doneStream` whatever is left of
  it: a reader that does not read by then gets the stream closed with an error. Kino reads at once, off its binder
  threads, and closes the read end at its own deadline.
- At most 2 streamed results are in flight. A large result that arrives while both stream slots are busy gets `done`
  with `streams_busy` (`CsWire.Err.STREAMS_BUSY`) and no `doneStream`. Kino retries that op once after one of its
  streams ends, or runs at most 2 large-result ops at a time.
- `done` itself: when Binder refuses it (an exhausted oneway buffer is reported as `DeadObjectException` on a live
  binder), the bridge sends one short `too_large` instead. The same single fallback follows a refused `doneStream`.
- `event`s stay inline (`done`'s budget is shared with them): per op at most 200 `link` and 100 `subtitle` events,
  each at most 16 384 chars, 100 000 chars in all; past any of these, events are dropped and not counted.

## Ops

| op | args | result |
|---|---|---|
| `installPlugin` | `{sha256,fileName}` + the `.cs3` as `payload` (≤ 20 MiB) | `{name,version,providers:[Provider],missingSymbols:[String],hasSettings}` (≤ 100 symbols, each ≤ 300 chars; non-empty = not stored; `hasSettings` below) |
| `removePlugin` | `{}` | `{ok:true}` |
| `listPlugins` | `{}` | `{plugins:[{id,sha256}]}` |
| `mainPage` | `{provider,index,page}` (`page` ≥ 1) | `{rows:[{name,items:[Item]}],hasNext}` |
| `search` | `{provider,query}` | `{items:[Item]}` |
| `load` | `{provider,url}` | `{title:Title,episodes:[Episode]}` |
| `loadLinks` | `{provider,data}` | events `link` / `subtitle`, then `{returned:Boolean,links:Int}` (`links` = link events Kino got) |

- `hasSettings` (additive, Boolean, absent = `false`): the plugin set CloudStream's `openSettings` in its `load()`, so
  it has a settings screen of its own ("Plugin settings" below). `false` whenever `missingSymbols` is non-empty. Kino
  keeps it with the install; every `installPlugin` answer (a re-send after `hello` included) refreshes it.
- `provider` (in args and in `Item.provider`) is the index of the provider in the plugin's `installPlugin.providers`
  list. `index` is the index in that provider's `mainPages`. Integral JSON numbers only.
- Each provider op runs at most the provider's own timeout hint (CloudStream's 2 min default, clamped to 5 s..8 min),
  inside the op's deadline. A plugin with 3 tasks stuck in blocking code answers `timeout` with message `busy` at once.

### Shapes

- Provider `{name,mainUrl,lang,tvTypes:[String],hasMainPage,mainPages:[{name,data}]}`; `tvTypes` are CloudStream
  `TvType` names, never `Torrent`.
- Item `{name,url,type,poster,posterHeaders,year,quality,provider}`:
  `type` a `TvType` name; `poster` an URL; `posterHeaders` `{name:value}` (≤ 64); `year` Int;
  `quality` the CloudStream `SearchQuality` **name** (e.g. `"HD"`); `provider` the provider index (above).
  At most 200 items per list, 30 rows, and items stop being added once the items of one answer pass 100 000 chars.
- Title `{name,url,type,plot,poster,background,year,tags,rating,durationMin,kind}`:
  `rating` a Double 0..10 (`Score.toDouble(10)`); `durationMin` Int minutes; `tags` ≤ 20 strings of ≤ 100 chars;
  `plot` ≤ 2000 chars; `kind` which CloudStream response class it was: `"movie"` (`MovieLoadResponse`), `"live"`
  (`LiveStreamLoadResponse`), `"anime"` (`AnimeLoadResponse`) or `"series"` (any other).
- Episode `{data,season,episode,name,poster,description,date,dubStatus}`:
  `date` epoch milliseconds (Long); `dubStatus` a `DubStatus` name for anime; `description` ≤ 2000 chars.
  At most 5000 episodes; `data` ≤ 16 KiB (an episode with longer data is left out, never truncated). A movie or live
  title is a one-episode list of its data. Anime: dubbed first, then subbed, then the rest.
  A `load` answer over 8 MiB is rebuilt leaner: no episode `poster`/`description`, then no `name` either; still
  over, it is `too_large`.
- link event `{type:"link",url,name,source,referer,quality,linkType:"VIDEO|M3U8|DASH",headers:{}}`: `quality` the
  CloudStream **Int** (e.g. 1080; `Qualities.Unknown` is -1); `headers` ≤ 20, each value ≤ 4096 chars, ≤ 8192 in all.
- subtitle event `{type:"subtitle",lang,url,headers:{}}`.
- Every URL the bridge sends is ≤ 8192 chars, absolute https with a host, no userinfo; peer-to-peer (Torrent type,
  `magnet:`, `.torrent`) items, episodes and links are dropped. Names are cut to 500 chars.

## IHost.request

Meta: `{"callId","pluginId","method","url","headers":[[n,v]],"followRedirects","timeoutMs","hasBody"}`; `method` one of
GET POST PUT PATCH DELETE HEAD OPTIONS; `url` ≤ 8192 chars. Reply (`IHttpReply.response`, exactly once):
`{"status","message","headers":[[n,v]],"finalUrl"}` then the body into the sink pipe, or `{"error":{"code","message"}}`.
Caps: request body 1 MiB, response body 5 MiB, 250 requests per op, 6 in flight per plugin; only inside a live op.

## IHost.storage

Sync: `{"op":"get|set|remove|keys","pluginId","key","value"}` → `{"value":String|null}` | `{"ok":true}` | `{"keys":[]}` |
`{"error"}`. One entry (key + value) at most 262 144 chars.

## IHost.webView

The hidden-WebView page that CloudStream's `WebViewResolver` and `CloudflareKiller` would open themselves. Only during a
live op the person started; the reply comes through `IReply.done` only (never `event` or `doneStream`; Kino keeps it
within `MAX_INLINE_RESULT_CHARS`). `CsWire.webViewRequest` / `parseWebViewRequest` / `webViewReply` /
`parseWebViewReply` build and check both JSONs with the limits below (`CsWire.WebView`).

Request:

```
{ "callId", "pluginId",
  "url": "https://…",                 // start page, https, ≤ 8192 chars
  "timeoutMs": 18000,                 // required; clamped to 1000..25000
  "headers": {"name": "value"},       // first-load headers; Kino keeps ≤ 10 valid ones (token names, no Host,
                                      // Content-Length, Transfer-Encoding, Connection, Cookie; no CR/LF; ≤ 2048 chars)
  "match": "regex",                   // optional, 1..500 chars, compiled with IGNORE_CASE (send "(?-i)…" for case-sensitive)
  "alsoMatch": ["regex", …],          // optional, 1..10 patterns of 1..500 chars; only with captureAll
  "captureAll": true,                 // optional; requires match
  "waitForCookie": "cf_clearance",    // optional, a cookie name (HTTP token, ≤ 100 chars); combinable with match
  "returnCookiesOnTimeout": true }    // optional
```

At least one of `match` / `waitForCookie`. Unknown keys are ignored. Anything else wrong is `bad_request`.

When the page ends (`match` and `alsoMatch` are searched anywhere in the URL, `Matcher.find`, for every request the
WebView reports, the main frame and its redirects included):

- `match` alone: at the first request whose URL matches it (the primary match).
- `captureAll`: Kino collects every request whose URL matches `match` or any `alsoMatch` (deduplicated by URL, at most
  20) and ends once the primary match was seen and no new matching request came for 1 s (`SETTLE_MS`), or at 20.
- `waitForCookie`: once the WebView holds that cookie for the page's current host. With `match` (and `captureAll`),
  only when both conditions hold; with `waitForCookie` and `captureAll`, settle starts once both hold.
- Once the primary match was seen (and the cookie, with `waitForCookie`), the page ends successfully
  (`timedOut: false`) at settle or at `timeoutMs`, whichever comes first.
- Otherwise at `timeoutMs`: `{"error":{"code":"timeout"}}`, or, with `returnCookiesOnTimeout`, the reply below with
  what was captured so far, the current cookies and `"timedOut": true`.
- Kino answers within `timeoutMs` + 4 s (at most 3 s waiting for its one hidden page, then `busy`); the bridge stops
  waiting 2 s after that.

Reply:

```
{ "requests": [ {"url": "…", "method": "GET|POST|…", "headers": {"name": "value"}} ],
  "cookies": {"name": "value"},
  "userAgent": "…", "finalUrl": "https://…", "timedOut": false }
```

- `requests`: at most 20, in the order the page made them, except that the primary match, when one was seen, is first.
  When none was (only on a timeout reply), every entry is an `alsoMatch` hit (or none): readers check each URL against
  their own patterns. `method` as the WebView reported it (GET POST PUT PATCH DELETE HEAD OPTIONS; others dropped);
  `headers` the request's headers as the WebView saw them, ≤ 64, values ≤ 8192 chars, no CR/LF. `[]` for a cookie-only
  page. URLs ≤ 8192 chars, http or https (the bridge uses only https ones).
- `cookies`: the WebView's cookies for `finalUrl`'s host only (what `CookieManager.getCookie(finalUrl)` gives), read
  before Kino wipes the WebView: ≤ 64, name ≤ 256 chars, value 1..4096 chars, no `;`/CR/LF, names + values ≤ 16 384
  chars in all (`MAX_COOKIES_TOTAL_CHARS`; past it, cookies other than Cloudflare's `cf_*` / `__cf*` are dropped
  first). `{}` when none.
- `userAgent`: the WebView's user agent (the one Cloudflare ties `cf_clearance` to), ≤ 512 chars, `""` if unknown.
- `finalUrl`: the top page's last address, https. Kino answers `blocked` instead of sending any other `finalUrl`
  (`CsWire.webViewReply` does it), and a reply with one is malformed. `timedOut`: true only on a `returnCookiesOnTimeout` timeout reply.
- Error codes (`CsWire.Err`): `refused` (caller is not the bridge), `bad_request`, `not_allowed` (no live op the
  person started), `blocked` (host not allowed, not https, a private address, a navigation off https), `busy` (another
  hidden page is open), `timeout`, `browser_unavailable` (no WebView on the device), `unavailable` (Kino failed or
  stopped).

## Plugin settings (additive)

A plugin's own settings screen (CloudStream's `Plugin.openSettings`), shown by the bridge over Kino. Kino offers it
only for a plugin whose last `installPlugin` answer had `hasSettings: true`, and only on phones and tablets (never on
a television; the bridge refuses there anyway). Constants: `CsWire.Settings`.

- **Component**: activity `com.kino.csbridge.settings.PluginSettingsActivity` in package `com.kino.csbridge`
  (`CsWire.Settings.ACTIVITY`), started with an explicit component, no action, no data, from an Activity of Kino.
  It has no intent filter.
- **Permission**: the activity requires `com.arkiv.player.permission.CLOUDSTREAM_BRIDGE` (`CsWire.PERMISSION`, the
  bridge's signature-level permission, the same the service requires). Kino already holds it.
- **Start it for a result** (`registerForActivityResult(StartActivityForResult)` / `startActivityForResult`). The
  bridge also checks that the calling package, which Android fills in only for a start for a result, is Kino and is
  signed with the bridge's certificate; a plain `startActivity` is refused (`refused`).
- **Extra** `com.kino.csbridge.extra.PLUGIN_ID` (`CsWire.Settings.EXTRA_PLUGIN_ID`), String, required: the plugin's
  Kino id, the `pluginId` of its ops (`cloudstream-…`).
- **Before starting it**, Kino has a live binding to the bridge service with a successful `hello` (the screen reads and
  writes the plugin's values through `IHost.storage` of that binding) and keeps the binding until the result arrives.
- **While it is open**, the plugin's SharedPreferences / DataStore reads and writes made outside any op go to that
  plugin's storage (`IHost.storage` with its `pluginId`), whatever thread the plugin uses. A live op of another plugin
  keeps its own attribution. Plugin HTTP made from the screen is outside any op and fails (as for any request outside a
  call), so a screen that downloads something shows its own error.
- **The screen**: the activity has no window content of its own (transparent, dark theme, no animation); the plugin's
  dialog shows over Kino. It closes when no plugin dialog or fragment is left on top of it (dismissal, Back), when the
  person taps outside the dialog, or 1.5 s after `openSettings` returned without showing anything. It waits up to 3 s
  for the plugin's pending storage writes to reach Kino before finishing.
- **Result**: `RESULT_OK` when the screen ran and closed; Kino then reloads whatever it shows from the plugin (the
  plugin's next op sees the new values). `RESULT_CANCELED` with the String extra `com.kino.csbridge.extra.ERROR`
  (`CsWire.Settings.EXTRA_ERROR`):

  | code | meaning |
  |---|---|
  | `refused` | caller is not Kino (no calling package, another package, another signature) |
  | `bad_request` | extra missing, or not a valid `cloudstream-` id |
  | `unavailable` | no live Kino binding in the bridge (no `hello` yet) |
  | `unsupported_device` | the device is a television (`UI_MODE_TYPE_TELEVISION` or `FEATURE_LEANBACK`) |
  | `not_installed` | the bridge holds no such plugin |
  | `incompatible` | the plugin no longer links against this bridge, or crashed it while loading |
  | `no_settings` | the plugin has no settings screen (`openSettings` is null after `load()`) |
  | `timeout` | the plugin's `load()` (first use in the bridge process) took over 20 s, or its lock was busy |
  | `plugin_error` | the plugin's `load()` or `openSettings` threw (logged by the bridge as one line, class name only) |
  | `cancelled` | Android re-created the screen (the bridge process died while it was open); nothing was restored |

  A `RESULT_CANCELED` without the extra (the system's own cancel, a bridge without this activity) means "nothing ran".
  `ActivityNotFoundException` / `SecurityException` on start means the bridge predates this activity: Kino offers the
  bridge update.

## Errors

`{"error":{"code","message"}}` (message ≤ 300 chars, never a URL query), codes in `CsWire.Err`: `protocol`,
`bad_request`, `not_installed`, `incompatible` (a plugin linked against a symbol the bridge lacks), `timeout`, `cancelled`,
`plugin_error`, `network`, `refused`, `too_large`, `budget`, `unavailable`, `bridge_stream_broken` (read side of
`doneStream`), `streams_busy` (both stream slots busy), and, for `IHost.webView` only, `busy` plus the codes listed there.

## Distribution (Kino's OTA manifest)

Kino offers the bridge APK through its own `latest.json` (the one `OtaManifest` parses, served by GitHub Releases
tag `ota`, the npm `static-asset-pack` mirror and archive.org), as an optional top-level `bridge` object. Clients
that predate it ignore unknown keys; it never lives in a separate document at a URL older clients read.

```json
"bridge": { "package": "com.kino.csbridge", "versionCode": 3, "versionName": "1.0.2", "minProtocol": 2, "maxProtocol": 2,
            "sha256": "<64 lowercase hex>", "size": 14000000,
            "urls": ["https://github.com/kinotvapp/kino-light/releases/download/bridge-v1.0.2/kino-cloudstream.apk",
                     "https://cdn.jsdelivr.net/npm/static-asset-pack@1.0.10/ota/kino-cloudstream.apk"] }
```

- Kino ignores the whole entry (its own update unaffected) unless `package` is exactly `com.kino.csbridge`,
  `minProtocol..maxProtocol` contains its `CsWire.PROTOCOL_VERSION`, `versionCode` is a positive int, `sha256` matches
  `^[0-9a-f]{64}$`, `size` is not negative and every url is https on Kino's pinned OTA paths (`OtaSources.isAllowedApkUrl`:
  this repo's release assets, `static-asset-pack@<exact version>/ota/`, the `kino-app` archive.org item).
- The downloaded file must hash to `sha256`, and then be `com.kino.csbridge`, signed by exactly Kino's signer set, with
  exactly `versionCode`; anything else is deleted. Kino only opens the system installer, after the person asks.
- Publishing (`scripts/publish-release-github.sh --bridge <apk>`, outside git): check with `apksigner` that the APK's
  certificate digest equals Kino's, compute sha256 and size, upload it to a `bridge-v<versionName>` release and to the
  npm package's `ota/` folder at the exact version, and write this object into `latest.json`, keeping the current
  `bridge` object when only Kino is published.
