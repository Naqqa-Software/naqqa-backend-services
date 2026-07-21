/*!
 * naqqa-analytics tracker — tiny, privacy-aware, dependency-free.
 * Usage (any site / any entity):
 *   <script defer src="https://BACKEND/api/public/analytics/tracker.js"
 *           data-property="naqqasoftware.co.uk" data-entity-type="blog"></script>
 * Optional: data-entity-id, data-endpoint (override backend), data-path.
 * SPA: call window.naqqaAnalytics.track({entityType, entityId, path, title}) on route change.
 */
(function () {
  "use strict";
  var script =
    document.currentScript ||
    document.querySelector('script[src*="analytics/tracker.js"]') ||
    document.querySelector("script[data-property]");
  if (!script) return;
  var cfg = script.dataset || {};

  var base = (function () {
    if (cfg.endpoint) return cfg.endpoint.replace(/\/$/, "");
    try { return new URL(script.src).origin + "/api/public/analytics"; }
    catch (e) { return "/api/public/analytics"; }
  })();
  var property = cfg.property || location.hostname;
  var defaultType = cfg.entityType || "page";

  // ── stable ids (GA-style first-party client id: cookie + localStorage) ────────
  function uid() {
    try { return crypto.randomUUID(); }
    catch (e) { return Date.now().toString(36) + Math.random().toString(36).slice(2); }
  }
  function cookie(name, value, days) {
    try {
      if (value === undefined) {
        var m = document.cookie.match(new RegExp("(?:^|; )" + name + "=([^;]*)"));
        return m ? decodeURIComponent(m[1]) : null;
      }
      var exp = new Date(Date.now() + days * 864e5).toUTCString();
      document.cookie = name + "=" + encodeURIComponent(value) + "; expires=" + exp +
        "; path=/; SameSite=Lax";
    } catch (e) { return null; }
  }
  var isNewVisitor = false;
  function visitorId() {
    var v;
    try { v = cookie("nqa_uid") || localStorage.getItem("nqa_vid"); } catch (e) {}
    if (!v) { v = uid(); isNewVisitor = true; }
    try { cookie("nqa_uid", v, 730); localStorage.setItem("nqa_vid", v); } catch (e) {}
    return v;
  }
  function sessionId() {
    var now = Date.now(), TTL = 30 * 60 * 1000;
    try {
      var s = JSON.parse(sessionStorage.getItem("nqa_sid") || "null");
      if (!s || now - s.t > TTL) s = { id: uid(), t: now };
      s.t = now;
      sessionStorage.setItem("nqa_sid", JSON.stringify(s));
      return s.id;
    } catch (e) { return uid(); }
  }

  function qp(name) {
    try { return new URLSearchParams(location.search).get(name) || undefined; }
    catch (e) { return undefined; }
  }

  function send(payload) {
    var url = base + "/collect";
    var body = JSON.stringify(payload);
    try {
      if (navigator.sendBeacon) {
        navigator.sendBeacon(url, new Blob([body], { type: "application/json" }));
        return;
      }
    } catch (e) {}
    try {
      fetch(url, { method: "POST", headers: { "Content-Type": "application/json" }, body: body, keepalive: true });
    } catch (e) {}
  }

  function baseEvent(over) {
    over = over || {};
    return {
      property: property,
      entityType: over.entityType || defaultType,
      entityId: over.entityId || cfg.entityId || location.pathname,
      path: over.path || cfg.path || location.pathname + location.search,
      title: over.title || document.title,
      visitorId: visitorId(),
      sessionId: sessionId(),
      newVisitor: isNewVisitor,
      referrer: document.referrer || "",
      language: navigator.language,
      screen: (window.screen ? window.screen.width + "x" + window.screen.height : ""),
      viewport: (window.innerWidth ? window.innerWidth + "x" + window.innerHeight : ""),
      utmSource: qp("utm_source"),
      utmMedium: qp("utm_medium"),
      utmCampaign: qp("utm_campaign"),
      utmTerm: qp("utm_term"),
      utmContent: qp("utm_content")
    };
  }

  // ── engagement (time-on-page) ────────────────────────────────────────────────
  var visibleMs = 0, lastTick = Date.now(), current = null, sentEngagement = false;
  function accrue() {
    var now = Date.now();
    if (document.visibilityState === "visible") visibleMs += now - lastTick;
    lastTick = now;
  }
  function flush() {
    accrue();
    if (sentEngagement || visibleMs < 1000 || !current) return;
    sentEngagement = true;
    var e = baseEvent(current);
    e.eventType = "engagement";
    e.durationMs = Math.round(visibleMs);
    send(e);
  }

  function pageview(over) {
    // finalize previous page's engagement (SPA nav) then start fresh.
    flush();
    visibleMs = 0; lastTick = Date.now(); sentEngagement = false;
    current = over || {};
    var e = baseEvent(current);
    e.eventType = "pageview";
    send(e);
  }

  document.addEventListener("visibilitychange", function () {
    accrue();
    if (document.visibilityState === "hidden") flush();
  });
  window.addEventListener("pagehide", flush);
  window.addEventListener("beforeunload", flush);

  // custom event: naqqaAnalytics.event("cta_click", { entityId: "...", ... })
  function event(name, over) {
    var e = baseEvent(over || {});
    e.eventType = "event";
    e.eventName = name;
    send(e);
  }

  window.naqqaAnalytics = { track: pageview, event: event, send: send };

  // initial pageview
  pageview();
})();
