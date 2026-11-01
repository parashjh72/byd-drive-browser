// Injected into every tab. Reports play/pause + song title to the app and exposes controls
// so the notification / steering-wheel buttons can drive the page.
(function () {
  if (window.__dbCtl) { window.__dbCtl.report(); return; }
  var S = new Set(), handlers = {}, last = '';

  function track(m) {
    if (!m || m.__dbt) return;
    m.__dbt = 1; S.add(m);
    ['play', 'playing', 'pause', 'ended', 'emptied'].forEach(function (e) { m.addEventListener(e, report); });
  }
  function all() {
    document.querySelectorAll('video,audio').forEach(track);
    return Array.from(S);
  }
  function isPlaying() { return all().some(function (m) { return !m.paused && !m.ended; }); }
  function meta() {
    var md = navigator.mediaSession && navigator.mediaSession.metadata;
    return { t: (md && md.title) || document.title || '', a: (md && md.artist) || location.hostname };
  }
  function report() {
    var p = isPlaying(), m = meta(), key = p + '|' + m.t + '|' + m.a;
    if (key === last) return;
    last = key;
    try { DriveMedia.state(p, m.t, m.a); } catch (e) {}
  }
  function click(sels) {
    for (var i = 0; i < sels.length; i++) {
      var b = document.querySelector(sels[i]);
      if (b) { b.click(); return true; }
    }
    return false;
  }
  function seek(sec) {
    all().forEach(function (m) { if (!m.paused) m.currentTime = Math.max(0, m.currentTime + sec); });
  }

  // Remember the page's own media-session handlers (YouTube, Spotify, YT Music set these).
  if (navigator.mediaSession && navigator.mediaSession.setActionHandler) {
    var orig = navigator.mediaSession.setActionHandler.bind(navigator.mediaSession);
    navigator.mediaSession.setActionHandler = function (action, fn) {
      handlers[action] = fn;
      return orig(action, fn);
    };
  }
  // Catch players created with `new Audio()` that are never attached to the page.
  var op = HTMLMediaElement.prototype.play;
  HTMLMediaElement.prototype.play = function () { track(this); return op.apply(this, arguments); };
  ['play', 'playing', 'pause', 'ended'].forEach(function (e) {
    document.addEventListener(e, function (ev) { track(ev.target); report(); }, true);
  });
  setInterval(report, 4000); // picks up song changes

  window.__dbCtl = {
    report: report,
    pause: function () {
      if (handlers.pause) { try { handlers.pause({ action: 'pause' }); } catch (e) {} }
      all().forEach(function (m) { if (!m.paused) { m.__dbwas = 1; m.pause(); } });
      setTimeout(report, 300);
    },
    play: function () {
      var ms = all(), was = ms.filter(function (m) { return m.__dbwas; });
      if (handlers.play) { try { handlers.play({ action: 'play' }); } catch (e) {} }
      else if (!ms.length) { click(['[data-testid="control-button-playpause"]', '.ytp-play-button', '#play-pause-button']); }
      (was.length ? was : ms.slice(0, 1)).forEach(function (m) {
        m.__dbwas = 0; var p = m.play(); if (p && p.catch) p.catch(function () {});
      });
      setTimeout(report, 300);
    },
    next: function () {
      if (handlers.nexttrack) { try { handlers.nexttrack({ action: 'nexttrack' }); return; } catch (e) {} }
      if (!click(['.ytp-next-button', 'ytmusic-player-bar .next-button', '[data-testid="control-button-skip-forward"]',
                  'button[aria-label^="Next" i]'])) seek(10);
    },
    prev: function () {
      if (handlers.previoustrack) { try { handlers.previoustrack({ action: 'previoustrack' }); return; } catch (e) {} }
      if (!click(['ytmusic-player-bar .previous-button', '[data-testid="control-button-skip-back"]',
                  'button[aria-label^="Previous" i]'])) seek(-10);
    }
  };
  report();
})();
