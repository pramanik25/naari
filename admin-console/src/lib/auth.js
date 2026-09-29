import crypto from "node:crypto";

export const SESSION_COOKIE = "ns_admin";
const SESSION_DAYS = 7;

export function sessionSecret() {
  return (
    process.env.ADMIN_SESSION_SECRET ||
    `ns-admin-session:${process.env.ADMIN_PASSWORD || ""}`
  );
}

export function createSessionToken() {
  const exp = Date.now() + SESSION_DAYS * 24 * 60 * 60 * 1000;
  const sig = crypto
    .createHmac("sha256", sessionSecret())
    .update(String(exp))
    .digest("hex");
  return `${exp}.${sig}`;
}

export function checkPassword(candidate) {
  const expected = process.env.ADMIN_PASSWORD;
  if (!expected) return false; // unset password = console locked
  const a = Buffer.from(String(candidate));
  const b = Buffer.from(expected);
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}
