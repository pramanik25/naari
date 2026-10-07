import { NextResponse } from "next/server";
import { SESSION_COOKIE, checkPassword, createSessionToken } from "@/lib/auth";

export async function POST(request) {
  const form = await request.formData();
  const password = form.get("password") ?? "";

  if (!checkPassword(password)) {
    return NextResponse.redirect(new URL("/admin/login?error=1", request.url), 303);
  }

  const res = NextResponse.redirect(new URL("/admin", request.url), 303);
  const proto =
    request.headers.get("x-forwarded-proto") ?? new URL(request.url).protocol.replace(":", "");
  res.cookies.set(SESSION_COOKIE, createSessionToken(), {
    httpOnly: true,
    sameSite: "lax",
    secure: proto === "https", // http only happens on localhost
    path: "/admin",
    maxAge: 7 * 24 * 60 * 60,
  });
  return res;
}
