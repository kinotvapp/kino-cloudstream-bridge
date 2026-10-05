# Kino ⇄ CloudStream bridge protocol (v1)

Apache-2.0. This folder is copied verbatim into both repos; a change in either place is a protocol
change: bump `CsWire.PROTOCOL_VERSION`, keep the old version answering until Kino stops sending it.

- Every JSON object carries no secrets; repo URLs never appear (Kino sends `.cs3` bytes, not URLs).
- `hello(v, host)` → `{"protocolVersion","bridgeVersionCode","upstreamCommit","kotlinVersion","plugins":[{"id","sha256"}]}`:
  every plugin the bridge holds, with the sha256 (64 lowercase hex) of the `.cs3` it loaded (`CsWire.HelloPlugin`).
  Kino reconciles on each hello: it removes ids it has no install or pending preview for, and re-sends a `.cs3` whose
  sha256 differs from the installed one. A malformed entry makes the whole hello a protocol error.
  Mismatch: the bridge answers `{"error":{"code":"protocol"}}`; Kino offers a bridge update.
- `call(op)`: `{"v":1,"op","callId","pluginId","deadlineMs","args":{}}`. `done` exactly once. Ops:
  `installPlugin{sha256,fileName}` → `{name,version,providers:[Provider],missingSymbols:[String]}`;
  `removePlugin{}` → `{ok}`; `listPlugins{}` → `{plugins:[{id,sha256}]}`;
  `mainPage{provider,index,page}` → `{rows:[{name,items:[Item]}],hasNext}`;
  `search{provider,query}` → `{items:[Item]}`;
  `load{provider,url}` → `{title:Title,episodes:[Episode]}`;
  `loadLinks{provider,data}` → events `link`/`subtitle`, then `{returned:Boolean,links:Int}`.
- Provider `{name,mainUrl,lang,tvTypes:[String],hasMainPage,mainPages:[{name,data}]}`;
  Item `{name,url,type,poster,posterHeaders,year,quality,provider}`;
  Title `{name,url,type,plot,poster,background,year,tags,rating,durationMin,kind}`;
  Episode `{data,season,episode,name,poster,description,date,dubStatus}`;
  link `{type:"link",url,name,source,referer,quality,linkType:"VIDEO|M3U8|DASH",headers:{}}`;
  subtitle `{type:"subtitle",lang,url,headers:{}}`.
- `IHost.request` meta: `{"callId","pluginId","method","url","headers":[[n,v]],"followRedirects","timeoutMs","hasBody"}`.
- Errors: `{"error":{"code","message"}}`, codes in `CsWire.Err`.
- Caps: request body 1 MB, response body 5 MB, 250 requests per op, 6 in flight per plugin.

## Distribution (Kino's OTA manifest)

Kino offers the bridge APK through its own `latest.json` (the one `OtaManifest` parses, served by GitHub Releases
tag `ota`, the npm `static-asset-pack` mirror and archive.org), as an optional top-level `bridge` object. Clients
that predate it ignore unknown keys; it never lives in a separate document at a URL older clients read.

```json
"bridge": { "package": "com.kino.csbridge", "versionCode": 3, "versionName": "1.0.2", "minProtocol": 1, "maxProtocol": 1,
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
