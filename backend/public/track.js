/* Naari Shakti live tracking page. No inline scripts (CSP). Polls /api/v1/track/<token> every 10 s. */
(function () {
  'use strict';

  var POLL_MS = 10000;
  var I = window.NaariI18n || { lang: 'en', t: function (k) { return k; }, apply: function () {} };
  var t = I.t;
  var $ = function (id) { return document.getElementById(id); };

  document.documentElement.lang = I.lang;
  document.title = t('doc_title');
  I.apply();

  var parts = location.pathname.split('/').filter(Boolean);
  var token = parts[0] === 't' ? parts[1] : null;

  var state = {
    data: null,
    skew: 0, // serverTime - local clock
    timer: null,
    stopped: false,
    map: null,
    line: null,
    marker: null,
    halo: null,
    accCircle: null,
    follow: true,
    fitted: false,
    evidence: {} // evidenceId -> { el, url }
  };

  // ---------- formatting ----------
  var rtf = null;
  try { rtf = new Intl.RelativeTimeFormat(I.lang, { numeric: 'auto' }); } catch (e) { rtf = null; }

  function ago(ms) {
    if (ms == null) return t('never');
    var diff = Math.round((ms - (Date.now() + state.skew)) / 1000); // negative = past
    var abs = Math.abs(diff);
    var unit = 'second';
    var val = diff;
    if (abs >= 60 * 60 * 48) { unit = 'day'; val = Math.round(diff / 86400); }
    else if (abs >= 60 * 60) { unit = 'hour'; val = Math.round(diff / 3600); }
    else if (abs >= 60) { unit = 'minute'; val = Math.round(diff / 60); }
    else if (abs < 10) { val = 0; }
    if (rtf) return rtf.format(val, unit);
    return Math.abs(val) + ' ' + unit + (Math.abs(val) === 1 ? '' : 's') + ' ago';
  }

  function nameOf(d) {
    var n = d && typeof d.ownerName === 'string' ? d.ownerName.trim() : '';
    return n || t('someone');
  }

  // ---------- toast ----------
  function toast(key) {
    var el = $('toast');
    if (!key) { el.hidden = true; return; }
    el.textContent = t(key);
    el.hidden = false;
  }

  // ---------- map ----------
  function initMap() {
    if (state.map) return true;
    if (!window.L) {
      $('mapNote').textContent = t('map_unavailable');
      $('mapNote').hidden = false;
      $('map').hidden = true;
      return false;
    }
    var L = window.L;
    state.map = L.map('map', { zoomControl: true, attributionControl: true, worldCopyJump: true })
      .setView([22.5, 79], 4);
    // CARTO's free basemap: tile.openstreetmap.org 403-blocks production apps per its usage policy.
    var osmAttribution = '&copy; <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap</a> contributors &copy; <a href="https://carto.com/attributions" target="_blank" rel="noopener">CARTO</a>';
    var tiles = L.tileLayer('https://{s}.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}{r}.png', {
      subdomains: 'abcd',
      maxZoom: 19,
      attribution: osmAttribution
    }).addTo(state.map);
    var tileErrors = 0;
    tiles.on('tileerror', function () {
      // If CARTO is unreachable, fall back to the community-run German OSM server.
      if (++tileErrors !== 4) return;
      tiles.remove();
      L.tileLayer('https://tile.openstreetmap.de/{z}/{x}/{y}.png', {
        maxZoom: 18,
        attribution: '&copy; <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap</a> contributors'
      }).addTo(state.map);
    });
    state.map.on('dragstart', function () { state.follow = false; });
    return true;
  }

  function renderMap(d) {
    var note = $('mapNote');
    var accEl = $('accuracy');
    var loc = d.lastLocation;
    var redacted = d.endedAt != null && (d.serverTime || Date.now()) - d.endedAt > 24 * 3600 * 1000;
    if (!loc) {
      note.textContent = t(redacted ? 'location_hidden' : 'no_location');
      note.hidden = false;
      accEl.hidden = true;
      setMapsLink(null);
    } else {
      note.hidden = true;
      if (typeof loc.accuracy === 'number' && loc.accuracy > 0) {
        accEl.textContent = t('accuracy', { m: Math.round(loc.accuracy) });
        accEl.hidden = false;
      } else {
        accEl.hidden = true;
      }
      setMapsLink(loc);
    }
    if (!initMap()) return;
    var L = window.L;
    var latlngs = (d.path || []).map(function (p) { return [p.lat, p.lng]; });
    if (loc && (!latlngs.length || latlngs[latlngs.length - 1][0] !== loc.lat || latlngs[latlngs.length - 1][1] !== loc.lng)) {
      latlngs.push([loc.lat, loc.lng]);
    }
    if (!state.line) {
      state.line = L.polyline(latlngs, { color: '#8B7CFF', weight: 4, opacity: 0.9, lineJoin: 'round' }).addTo(state.map);
    } else {
      state.line.setLatLngs(latlngs);
    }
    if (!loc) {
      [state.marker, state.halo, state.accCircle].forEach(function (l) { if (l) state.map.removeLayer(l); });
      state.marker = state.halo = state.accCircle = null;
      return;
    }
    var here = [loc.lat, loc.lng];
    var live = d.status === 'active' || d.duress;
    if (!state.marker) {
      state.accCircle = L.circle(here, { radius: loc.accuracy || 0, color: '#FF4D7E', weight: 1, opacity: 0.4, fillOpacity: 0.08, interactive: false }).addTo(state.map);
      state.halo = L.circleMarker(here, { radius: 14, color: '#FF4D7E', weight: 2, fillOpacity: 0, className: 'halo', interactive: false }).addTo(state.map);
      state.marker = L.circleMarker(here, { radius: 8, color: '#F5F3FF', weight: 3, fillColor: '#FF4D7E', fillOpacity: 1 }).addTo(state.map);
    } else {
      state.marker.setLatLng(here);
      state.halo.setLatLng(here);
      state.accCircle.setLatLng(here).setRadius(loc.accuracy || 0);
    }
    state.halo.setStyle({ opacity: live ? 1 : 0 });
    if (!state.fitted) {
      state.fitted = true;
      if (latlngs.length > 1) state.map.fitBounds(L.latLngBounds(latlngs).pad(0.3), { maxZoom: 17 });
      else state.map.setView(here, 16);
    } else if (state.follow) {
      state.map.panTo(here, { animate: true });
    }
  }

  function setMapsLink(loc) {
    var a = $('gmaps');
    if (!loc) {
      a.removeAttribute('href');
      a.classList.add('disabled');
      a.setAttribute('aria-disabled', 'true');
      return;
    }
    a.href = 'https://www.google.com/maps/search/?api=1&query=' + encodeURIComponent(loc.lat + ',' + loc.lng);
    a.classList.remove('disabled');
    a.removeAttribute('aria-disabled');
  }

  // ---------- evidence ----------
  function mediaFor(item) {
    var ct = item.contentType || '';
    var el;
    if (ct.indexOf('image/') === 0) {
      el = document.createElement('img');
      el.loading = 'lazy';
      el.decoding = 'async';
      el.alt = t('kind_photo');
    } else if (ct.indexOf('video/') === 0) {
      el = document.createElement('video');
      el.controls = true;
      el.preload = 'metadata';
      el.playsInline = true;
      el.setAttribute('playsinline', '');
    } else {
      el = document.createElement('audio');
      el.controls = true;
      el.preload = 'none';
    }
    // Signed URLs expire after 15 min: on failure, retry once with the newest URL from the last poll.
    el.addEventListener('error', function () {
      var rec = state.evidence[item.evidenceId];
      if (!rec || rec.el.getAttribute('src') === rec.url) return;
      var at = rec.el.currentTime || 0;
      rec.el.src = rec.url;
      if (at && rec.el.tagName !== 'IMG') {
        rec.el.addEventListener('loadedmetadata', function once() {
          rec.el.removeEventListener('loadedmetadata', once);
          try { rec.el.currentTime = at; } catch (e) { /* ignore */ }
        });
      }
    });
    el.src = item.url;
    return el;
  }

  function renderEvidence(list) {
    var gallery = $('gallery');
    var seen = {};
    list = list || [];
    list.forEach(function (item, idx) {
      seen[item.evidenceId] = true;
      var rec = state.evidence[item.evidenceId];
      if (rec) { rec.url = item.url; rec.captured.textContent = t('captured', { ago: ago(item.capturedAt) }); return; }
      var fig = document.createElement('figure');
      fig.className = 'ev' + (item.kind === 'video' || item.kind === 'audio' ? ' wide' : '');
      var media = mediaFor(item);
      var cap = document.createElement('figcaption');
      var kind = document.createElement('span');
      kind.className = 'kind';
      kind.textContent = t('kind_' + item.kind);
      var when = document.createElement('span');
      when.textContent = t('captured', { ago: ago(item.capturedAt) });
      var badge = document.createElement('span');
      badge.className = 'badge';
      badge.textContent = t('verified_badge');
      cap.appendChild(kind);
      cap.appendChild(when);
      if (item.verified) cap.appendChild(badge);
      fig.appendChild(media);
      fig.appendChild(cap);
      // list is newest first; keep that order in the grid
      var before = gallery.children[idx] || null;
      gallery.insertBefore(fig, before);
      state.evidence[item.evidenceId] = { el: media, url: item.url, fig: fig, captured: when, capturedAt: item.capturedAt };
    });
    Object.keys(state.evidence).forEach(function (id) {
      if (!seen[id]) { gallery.removeChild(state.evidence[id].fig); delete state.evidence[id]; }
    });
    $('evidenceEmpty').hidden = list.length > 0;
  }

  // ---------- page ----------
  function stateName(d) {
    if (d.duress) return 'duress';
    return d.status === 'ended' ? 'ended' : 'active';
  }

  function renderClock() {
    var d = state.data;
    if (!d) return;
    $('started').textContent = ago(d.startedAt);
    $('updated').textContent = d.lastLocation ? ago(d.lastLocation.at) : t('never');
    Object.keys(state.evidence).forEach(function (id) {
      var rec = state.evidence[id];
      rec.captured.textContent = t('captured', { ago: ago(rec.capturedAt) });
    });
  }

  function render(d) {
    state.data = d;
    state.skew = typeof d.serverTime === 'number' ? d.serverTime - Date.now() : 0;
    var s = stateName(d);
    document.body.className = 'state-' + s;
    var name = nameOf(d);
    var titleKey = s === 'duress' ? 'title_duress'
      : s === 'ended' ? 'title_ended'
        : d.source === 'checkin' ? 'title_checkin' : 'title_active';
    $('title').textContent = t(titleKey, { name: name });
    $('bannerText').textContent = t('status_' + s);

    var bat = $('battery');
    if (typeof d.battery === 'number') {
      bat.textContent = d.battery + '%';
      bat.classList.toggle('low', d.battery <= 15);
    } else {
      bat.textContent = t('never');
    }
    $('helpers').textContent = t('helpers_value', { responders: d.respondersCount || 0, notified: d.helpersNotified || 0 });
    renderClock();
    renderMap(d);
    renderEvidence(d.evidence);
  }

  function stop() {
    state.stopped = true;
    clearTimeout(state.timer);
    $('liveNote').textContent = t('stopped_note');
  }

  function schedule(ms) {
    clearTimeout(state.timer);
    if (!state.stopped) state.timer = setTimeout(poll, ms);
  }

  function poll() {
    if (!token) { notFound(); return; }
    fetch('/api/v1/track/' + encodeURIComponent(token), { cache: 'no-store', credentials: 'omit' })
      .then(function (res) {
        if (res.status === 404) { notFound(); return null; }
        if (res.status === 429) { toast('rate_limited'); schedule(30000); return null; }
        if (!res.ok) throw new Error('HTTP ' + res.status);
        return res.json();
      })
      .then(function (d) {
        if (!d) return;
        toast(null);
        render(d);
        // Keep polling while she may still be in danger (duress stays live even after a forced cancel).
        if (d.status === 'ended' && !d.duress) stop(); else schedule(POLL_MS);
      })
      .catch(function () {
        toast('offline');
        schedule(POLL_MS);
      });
  }

  function notFound() {
    stop();
    document.body.className = 'state-notfound';
    $('title').textContent = t('not_found');
    $('bannerText').textContent = t('not_found');
    $('liveNote').hidden = true;
  }

  setInterval(renderClock, 15000);
  document.addEventListener('visibilitychange', function () {
    if (document.visibilityState === 'visible' && !state.stopped) schedule(0);
  });
  poll();
})();
