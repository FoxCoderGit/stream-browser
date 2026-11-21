/* SPDX-License-Identifier: MPL-2.0
 * Appended to GeckoViewStartup.sys.mjs by tools/prepare_gecko.py.
 * Privileged engine code, not a WebExtension or page-injected script.
 */
const streamDispatcher = () => ChromeUtils.importESModule(
  "resource://gre/modules/Messaging.sys.mjs").EventDispatcher.instance;
const streamSelections = new WeakMap();

export function publishStreamSelection(window, selection) {
  const top = window.browsingContext.top.currentWindowGlobal;
  if (!top || !selection || !Number.isSafeInteger(selection.revision)) return;
  const selectedFrame = streamSelections.get(top);
  if (selection.activating === true) {
    streamSelections.set(top, window);
  } else if (selectedFrame !== window) {
    return;
  }
  const number = value => typeof value === "number" && Number.isFinite(value) ? value : 0;
  const source = typeof selection.source === "string" ? selection.source.slice(0, 16384) : "";
  streamDispatcher().sendRequest("Stream:Selection", {
    page: top.documentURI?.spec ?? "",
    frame: String(window.innerWindowId),
    source,
    revision: String(selection.revision),
    title: (top.documentTitle ?? "").slice(0, 240),
    width: number(selection.width),
    height: number(selection.height),
    duration: number(selection.duration),
    audio: selection.audio === true,
  });
}

if (Services.appinfo.processType === Services.appinfo.PROCESS_TYPE_DEFAULT) {
  JSWINDOWACTORS.StreamSelection = {
    parent: { esModuleURI: "resource://gre/modules/StreamSelectionParent.sys.mjs" },
    child: {
      esModuleURI: "resource://gre/modules/StreamSelectionChild.sys.mjs",
      events: Object.fromEntries(
        ["play", "playing", "emptied", "loadstart", "loadedmetadata", "resize"]
          .map(type => [type, { capture: true, mozSystemGroup: true }])
      ),
    },
    allFrames: true,
    matches: ["http://*/*", "https://*/*"],
    safeForUntrustedWebProcess: true,
  };
  const snapshots = new Map();
  const mediaPattern = /(?:\.m3u8|\.mpd|\.(?:mp4|webm|mkv|mov|mp3|m4a|ogg|wav|flac))(?:$|[?#])/i;
  const dispatcher = streamDispatcher;
  const observer = {
    observe(subject) {
      try {
        const channel = subject.QueryInterface(Ci.nsIHttpChannel);
        if (!/^https?:/.test(channel.URI.spec) || channel.responseStatus >= 400 ||
            channel.requestMethod !== "GET") return;
        const headers = {};
        channel.visitRequestHeaders({visitHeader(name, value) {
          if (["user-agent", "referer", "origin", "authorization"].includes(name.toLowerCase()))
            headers[name.toLowerCase()] = value;
        }});
        const url = channel.URI.spec;
        const original = channel.originalURI.spec;
        const frame = String(channel.loadInfo.innerWindowID);
        const page = channel.loadInfo.browsingContext?.top?.currentWindowGlobal?.documentURI?.spec
          || headers.referer || url;
        const mime = channel.contentType || "";
        const kind = /mpegurl/i.test(mime) || /\.m3u8(?:$|[?#])/i.test(url) ? "HLS"
          : /dash\+xml/i.test(mime) || /\.mpd(?:$|[?#])/i.test(url) ? "DASH"
          : /^(?:video|audio)\//i.test(mime) || mediaPattern.test(url) ? "file" : "";
        const snapshot = {url, page, mime: channel.contentType || "",
          headers, attrs: channel.loadInfo.originAttributes};
        if (kind || channel.loadInfo.externalContentPolicyType === Ci.nsIContentPolicy.TYPE_DOCUMENT) {
          const key = frame + "\n" + url;
          for (const entry of [key, url]) {
            snapshots.delete(entry);
            snapshots.set(entry, snapshot);
          }
          while (snapshots.size > 1024) snapshots.delete(snapshots.keys().next().value);
        }
        if (kind && frame !== "0") {
          dispatcher().sendRequest("Stream:Response", {url, original, page, frame, mime});
        }
      } catch (_) { /* Non-HTTP or destroyed channels are not candidates. */ }
    },
  };
  for (const topic of ["http-on-examine-response", "http-on-examine-cached-response", "http-on-examine-merged-response"])
    Services.obs.addObserver(observer, topic);
  dispatcher().registerListener({onEvent(event, data, callback) {
    if (event === "Stream:Ping") { callback.onSuccess({version: 2}); return; }
    try {
      const snapshot = snapshots.get(data.frame ? data.frame + "\n" + data.url : data.url);
      if (!snapshot) { callback.onError("Контекст запроса устарел. Обновите страницу."); return; }
      const cookies = [];
      const seen = new Set();
      for (const url of [snapshot.url, snapshot.page]) {
        const uri = Services.io.newURI(url);
        for (const cookie of Services.cookies.getCookiesFromHost(uri.host, snapshot.attrs)) {
          const key = cookie.host + "\t" + cookie.path + "\t" + cookie.name;
          if (seen.has(key)) continue;
          seen.add(key);
          cookies.push({domain: cookie.host, path: cookie.path, name: cookie.name,
            value: cookie.value, secure: cookie.isSecure, httpOnly: cookie.isHttpOnly,
            expires: cookie.isSession ? 0 : cookie.expiry});
        }
      }
      callback.onSuccess({json: JSON.stringify({url: snapshot.url, page: snapshot.page,
        mime: snapshot.mime, headers: snapshot.headers, cookies})});
    } catch (_) { callback.onError("Не удалось прочитать контекст сессии."); }
  }}, ["Stream:Resolve", "Stream:Ping"]);
}
