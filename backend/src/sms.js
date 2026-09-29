'use strict';

const PHONE_RE = /^\+?[0-9]{6,16}$/;

/**
 * Minimal Twilio Messages API client over fetch (no SDK). `fetchImpl` is injectable for tests.
 * send() resolves to { sid } or throws; callers decide whether a failure matters.
 */
function createSms({ config, fetchImpl = globalThis.fetch, log } = {}) {
  const twilio = config && config.twilio;
  return {
    enabled: Boolean(twilio),
    async send(to, text) {
      if (!twilio) throw new Error('sms not configured');
      if (typeof to !== 'string' || !PHONE_RE.test(to)) throw new Error('invalid phone number');
      const url = `https://api.twilio.com/2010-04-01/Accounts/${encodeURIComponent(twilio.accountSid)}/Messages.json`;
      const auth = Buffer.from(`${twilio.accountSid}:${twilio.authToken}`).toString('base64');
      const res = await fetchImpl(url, {
        method: 'POST',
        headers: {
          Authorization: `Basic ${auth}`,
          'Content-Type': 'application/x-www-form-urlencoded',
        },
        body: new URLSearchParams({ To: to, From: twilio.from, Body: text }).toString(),
        signal: AbortSignal.timeout(10_000),
      });
      if (!res.ok) {
        let detail = '';
        try { detail = (await res.json()).message || ''; } catch (_) { /* ignore */ }
        throw new Error(`twilio ${res.status} ${detail}`.trim());
      }
      let sid = null;
      try { sid = (await res.json()).sid || null; } catch (_) { /* ignore */ }
      if (log) log.info({ sid }, 'sms sent');
      return { sid };
    },
  };
}

module.exports = { createSms, PHONE_RE };
