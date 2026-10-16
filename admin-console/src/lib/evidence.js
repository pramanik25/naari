import crypto from "node:crypto";

// Mirrors backend/src/signing.js and backend/src/routes/track.js: the console signs the same
// 15-minute evidence URLs the public tracking page uses (server-side only, see
// app/admin/api/evidence/[id]/route.js), so the backend's own rules (verified files only,
// nothing served 24 h after the incident ends) still apply.
const URL_TTL_MS = 15 * 60 * 1000;
const URL_BUCKET_MS = 60 * 1000;
export const REDACT_AFTER_END_MS = 24 * 60 * 60 * 1000;

export function evidenceSigningConfigured() {
  return Boolean(process.env.SIGNING_SECRET && process.env.PUBLIC_BASE_URL);
}

export function isRedacted(incident, now = Date.now()) {
  return incident.ended_at != null && now - new Date(incident.ended_at).getTime() > REDACT_AFTER_END_MS;
}

/** Absolute signed URL for one evidence file, or null when signing is not configured. */
export function signedEvidenceUrl(token, evidenceId, now = Date.now()) {
  const secret = process.env.SIGNING_SECRET;
  const base = process.env.PUBLIC_BASE_URL;
  if (!secret || !base || !token) return null;
  const id = String(evidenceId).toLowerCase();
  const exp = Math.ceil((now + URL_TTL_MS) / URL_BUCKET_MS) * URL_BUCKET_MS;
  const sig = crypto.createHmac("sha256", secret).update(`${id}|${exp}`).digest("hex");
  return `${base}/api/v1/track/${token}/evidence/${id}?exp=${exp}&sig=${sig}`;
}
