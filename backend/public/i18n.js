/* Naari Shakti tracking page strings. Add a language by adding a dictionary with the same keys;
   missing keys fall back to English. The language comes from navigator.languages (or ?lang=xx). */
(function (w) {
  'use strict';

  var dict = {
    en: {
      doc_title: 'Naari Shakti · Live safety link',
      brand: 'Naari Shakti',
      eyebrow_live: 'Live safety link',
      loading: 'Loading live location…',
      someone: 'Someone',
      title_active: '{name} needs help',
      title_duress: '{name} may be in danger',
      title_ended: '{name} is safe now',
      title_checkin: '{name} missed a safety check-in',
      status_active: 'SOS active · live location is being shared',
      status_duress: 'She may have been forced to cancel. Treat this as an emergency.',
      status_ended: 'This alert has ended.',
      label_started: 'Started',
      label_battery: 'Battery',
      label_updated: 'Last update',
      label_helpers: 'Nearby helpers',
      helpers_value: '{responders} on the way · {notified} alerted',
      no_location: 'Waiting for the first location…',
      location_hidden: 'Location is no longer shared for this alert.',
      map_unavailable: 'The map could not be loaded. Use the Google Maps button instead.',
      accuracy: 'accurate to about {m} m',
      open_maps: 'Open in Google Maps',
      call_112: 'Call 112',
      evidence_title: 'Verified evidence',
      evidence_empty: 'No verified evidence yet.',
      verified_badge: 'verified ✓ SHA-256',
      kind_photo: 'Photo',
      kind_audio: 'Audio',
      kind_video: 'Video',
      captured: 'Captured {ago}',
      offline: 'Connection lost · retrying…',
      rate_limited: 'Too many refreshes · waiting a moment…',
      not_found: 'This tracking link is not valid.',
      live_note: 'Updates every 10 seconds.',
      stopped_note: 'Live updates have stopped.',
      privacy: 'This link is private. Share it only with people who can help.',
      never: '—'
    },
    hi: {
      doc_title: 'नारी शक्ति · लाइव सुरक्षा लिंक',
      brand: 'नारी शक्ति',
      eyebrow_live: 'लाइव सुरक्षा लिंक',
      loading: 'लाइव लोकेशन लोड हो रही है…',
      someone: 'कोई',
      title_active: '{name} को मदद चाहिए',
      title_duress: '{name} खतरे में हो सकती हैं',
      title_ended: '{name} अब सुरक्षित हैं',
      title_checkin: '{name} ने सुरक्षा चेक-इन नहीं किया',
      status_active: 'SOS सक्रिय · लाइव लोकेशन साझा हो रही है',
      status_duress: 'हो सकता है उन्हें अलर्ट रद्द करने के लिए मजबूर किया गया हो। इसे आपातकाल मानें।',
      status_ended: 'यह अलर्ट समाप्त हो गया है।',
      label_started: 'शुरू हुआ',
      label_battery: 'बैटरी',
      label_updated: 'आखिरी अपडेट',
      label_helpers: 'आस-पास के मददगार',
      helpers_value: '{responders} रास्ते में · {notified} को सूचना',
      no_location: 'पहली लोकेशन का इंतज़ार है…',
      location_hidden: 'इस अलर्ट की लोकेशन अब साझा नहीं की जाती।',
      map_unavailable: 'नक्शा लोड नहीं हो सका। Google Maps बटन का उपयोग करें।',
      accuracy: 'लगभग {m} मीटर तक सटीक',
      open_maps: 'Google Maps में खोलें',
      call_112: '112 पर कॉल करें',
      evidence_title: 'सत्यापित सबूत',
      evidence_empty: 'अभी तक कोई सत्यापित सबूत नहीं है।',
      verified_badge: 'सत्यापित ✓ SHA-256',
      kind_photo: 'फ़ोटो',
      kind_audio: 'ऑडियो',
      kind_video: 'वीडियो',
      captured: 'रिकॉर्ड {ago}',
      offline: 'कनेक्शन टूट गया · फिर से कोशिश हो रही है…',
      rate_limited: 'बहुत ज़्यादा रिफ्रेश · थोड़ा रुकें…',
      not_found: 'यह ट्रैकिंग लिंक मान्य नहीं है।',
      live_note: 'हर 10 सेकंड में अपडेट होता है।',
      stopped_note: 'लाइव अपडेट बंद हो गए हैं।',
      privacy: 'यह लिंक निजी है। इसे केवल उन्हीं लोगों से साझा करें जो मदद कर सकते हैं।',
      never: '—'
    }
  };

  function pick() {
    var forced = null;
    try { forced = new URLSearchParams(w.location.search).get('lang'); } catch (e) { /* old browser */ }
    var wanted = [];
    if (forced) wanted.push(forced);
    var nav = w.navigator || {};
    if (nav.languages && nav.languages.length) wanted = wanted.concat(Array.prototype.slice.call(nav.languages));
    else if (nav.language) wanted.push(nav.language);
    for (var i = 0; i < wanted.length; i++) {
      var base = String(wanted[i]).toLowerCase().split('-')[0];
      if (dict[base]) return base;
    }
    return 'en';
  }

  var lang = pick();

  function t(key, vars) {
    var s = (dict[lang] && dict[lang][key]) || dict.en[key] || key;
    return s.replace(/\{(\w+)\}/g, function (m, k) {
      return vars && vars[k] !== undefined && vars[k] !== null ? String(vars[k]) : m;
    });
  }

  /** Fills every [data-i18n] element's text from the dictionary. */
  function apply(root) {
    var els = (root || w.document).querySelectorAll('[data-i18n]');
    for (var i = 0; i < els.length; i++) els[i].textContent = t(els[i].getAttribute('data-i18n'));
  }

  w.NaariI18n = { lang: lang, t: t, apply: apply, languages: Object.keys(dict) };
})(window);
