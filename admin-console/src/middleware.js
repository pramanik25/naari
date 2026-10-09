import { NextResponse } from "next/server";

const SESSION_COOKIE = "ns_admin";

// Mirrors src/lib/auth.js using Web Crypto (middleware runs on the edge runtime).
async function isValidSession(token) {
  if (!token) return false;
  const dot = token.indexOf(".");
  if (dot < 1) return false;
  const exp = token.slice(0, dot);
  const sig = token.slice(dot + 1);
  if (!/^\d+$/.test(exp) || Number(exp) < Date.now()) return false;

  const secret =
    process.env.ADMIN_SESSION_SECRET ||
    `ns-admin-session:${process.env.ADMIN_PASSWORD || ""}`;
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"]
  );
  const mac = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(exp));
  const expected = Array.from(new Uint8Array(mac))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
  if (sig.length !== expected.length) return false;
  let diff = 0;
  for (let i = 0; i < expected.length; i++) diff |= sig.charCodeAt(i) ^ expected.charCodeAt(i);
  return diff === 0;
}

export async function middleware(request) {
  const { pathname } = request.nextUrl;
  if (pathname === "/admin/login" || pathname.startsWith("/admin/api/login")) {
    return NextResponse.next();
  }
  const ok = await isValidSession(request.cookies.get(SESSION_COOKIE)?.value);
  if (!ok) {
    const url = request.nextUrl.clone();
    url.pathname = "/admin/login";
    url.search = "";
    return NextResponse.redirect(url);
  }
  return NextResponse.next();
}

// Only the console is guarded; the public site (/, /privacy, /download) is open to everyone.
export const config = {
  matcher: ["/admin", "/admin/:path*"],
};
