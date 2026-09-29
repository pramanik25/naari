'use strict';

const crypto = require('crypto');

const URL_TTL_MS = 15 * 60 * 1000;
const URL_BUCKET_MS = 60 * 1000; // round expiry so URLs stay stable between 10 s polls (browser cache)

/** HMAC-SHA256 over `evidenceId|exp`, hex. */
function signEvidence(secret, evidenceId, exp) {
  return crypto.createHmac('sha256', secret).update(`${evidenceId}|${exp}`).digest('hex');
}

/** Relative 15-minute signed URL (used by the tracking page, same origin). */
function signedEvidencePath(secret, token, evidenceId, now = Date.now()) {
  const exp = Math.ceil((now + URL_TTL_MS) / URL_BUCKET_MS) * URL_BUCKET_MS;
  return `/api/v1/track/${token}/evidence/${evidenceId}?exp=${exp}&sig=${signEvidence(secret, evidenceId, exp)}`;
}

/** Absolute signed URL built from PUBLIC_BASE_URL (used in notifications). */
function signedEvidenceUrl(config, token, evidenceId, now = Date.now()) {
  return `${config.publicBaseUrl}${signedEvidencePath(config.signingSecret, token, evidenceId, now)}`;
}

module.exports = { URL_TTL_MS, signEvidence, signedEvidencePath, signedEvidenceUrl };
