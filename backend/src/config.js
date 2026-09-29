'use strict';

const crypto = require('crypto');
const path = require('path');

function intEnv(env, name, def, min, max) {
  const raw = env[name];
  if (raw === undefined || raw === '') return def;
  const n = Number(raw);
  if (!Number.isInteger(n) || n < min || n > max) {
    throw new Error(`${name} must be an integer between ${min} and ${max}`);
  }
  return n;
}

function parseTrustProxy(raw) {
  if (raw === undefined || raw === '' || raw === 'false' || raw === '0') return false;
  if (raw === 'true') return 1; // one reverse proxy hop in front of us
  if (/^\d+$/.test(raw)) return Number(raw);
  return raw; // e.g. "loopback, 10.0.0.0/8"
}

/**
 * Builds the runtime configuration from an env object (process.env by default).
 * Tests pass their own env so nothing leaks between suites.
 */
function loadConfig(env = process.env, log = null) {
  const databaseUrl = env.DATABASE_URL;
  if (!databaseUrl) throw new Error('DATABASE_URL is required');

  let signingSecret = env.SIGNING_SECRET;
  let signingSecretEphemeral = false;
  if (!signingSecret) {
    signingSecret = crypto.randomBytes(32).toString('hex');
    signingSecretEphemeral = true;
  } else if (signingSecret.length < 16) {
    throw new Error('SIGNING_SECRET must be at least 16 characters');
  }

  const publicBaseUrl = (env.PUBLIC_BASE_URL || 'http://127.0.0.1:8080').replace(/\/+$/, '');

  const twilio = env.TWILIO_ACCOUNT_SID && env.TWILIO_AUTH_TOKEN && env.TWILIO_FROM
    ? { accountSid: env.TWILIO_ACCOUNT_SID, authToken: env.TWILIO_AUTH_TOKEN, from: env.TWILIO_FROM }
    : null;

  // WhatsApp Business Cloud API: dormant unless both the token and the sender id are set.
  const whatsapp = env.WHATSAPP_TOKEN && env.WHATSAPP_PHONE_NUMBER_ID
    ? {
      token: env.WHATSAPP_TOKEN,
      phoneNumberId: env.WHATSAPP_PHONE_NUMBER_ID,
      verifyToken: env.WHATSAPP_VERIFY_TOKEN || null,
      appSecret: env.WHATSAPP_APP_SECRET || null,
      apiVersion: env.WHATSAPP_API_VERSION || 'v20.0',
      businessNumber: env.WHATSAPP_BUSINESS_NUMBER || null, // else looked up from the Graph API
      communityLink: env.WHATSAPP_COMMUNITY_LINK || null, // public community channel link
      templates: {
        sos: env.WHATSAPP_TEMPLATE_SOS || 'naari_sos_alert',
        sosText: env.WHATSAPP_TEMPLATE_SOS_TEXT || 'naari_sos_alert_text',
        evidence: env.WHATSAPP_TEMPLATE_EVIDENCE || 'naari_sos_evidence',
        safe: env.WHATSAPP_TEMPLATE_SAFE || 'naari_sos_safe',
      },
      lang: env.WHATSAPP_TEMPLATE_LANG || 'en',
      fallbackDelayMs: 20_000, // no location after this long -> text-only SOS
      retryBaseMs: 1_000, // backoff 1 s, 2 s, 4 s
    }
    : null;

  const config = {
    databaseUrl,
    port: intEnv(env, 'PORT', 8080, 0, 65535),
    publicBaseUrl,
    evidenceDir: path.resolve(env.EVIDENCE_DIR || './data/evidence'),
    signingSecret,
    signingSecretEphemeral,
    maxEvidenceBytes: intEnv(env, 'MAX_EVIDENCE_BYTES', 52428800, 1, 1024 * 1024 * 1024 * 4),
    // Free-tier storage: evidence (photos/audio/video) is deleted this long after the incident
    // ends. 0 = keep forever (set this once storage is upgraded).
    evidenceRetentionHours: intEnv(env, 'EVIDENCE_RETENTION_HOURS', 24, 0, 24 * 365),
    twilio,
    whatsapp,
    trustProxy: parseTrustProxy(env.TRUST_PROXY),
    logLevel: env.LOG_LEVEL || 'info',
    sweepIntervalMs: 30_000,
  };

  if (whatsapp && !whatsapp.appSecret && log) {
    log.warn('WHATSAPP_APP_SECRET not set: webhook posts are not authenticated (anyone could forge JOIN/STOP)');
  }
  if (signingSecretEphemeral && log) {
    log.warn('SIGNING_SECRET not set: using a random per-boot key; evidence links break on restart and across instances');
  }
  return config;
}

module.exports = { loadConfig };
