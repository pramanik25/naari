import fs from "node:fs";
import path from "node:path";
import { Readable } from "node:stream";
import { NextResponse } from "next/server";
import { APK_FILE_NAME, DEFAULT_APK_URL } from "@/lib/release";

export const dynamic = "force-dynamic";
export const runtime = "nodejs";

// GET /download — the one link the site (and anyone sharing the app) uses.
//   1. APK_URL set            -> redirect there (GitHub release asset, bucket, CDN…)
//   2. downloads/<apk> exists -> stream it from this server (supports resume)
//   3. otherwise              -> redirect to the latest GitHub release asset
export async function GET(request) {
  if (process.env.APK_URL) {
    return NextResponse.redirect(process.env.APK_URL, 302);
  }

  const file = path.join(process.cwd(), "downloads", APK_FILE_NAME);
  let stat;
  try {
    stat = await fs.promises.stat(file);
  } catch {
    return NextResponse.redirect(DEFAULT_APK_URL, 302);
  }

  const headers = {
    "Content-Type": "application/vnd.android.package-archive",
    "Content-Disposition": `attachment; filename="${APK_FILE_NAME}"`,
    "Accept-Ranges": "bytes",
    "Cache-Control": "no-cache",
    "X-Content-Type-Options": "nosniff",
  };

  // Phone download managers resume interrupted downloads with a Range request.
  const range = /^bytes=(\d*)-(\d*)$/.exec(request.headers.get("range") ?? "");
  if (range && (range[1] || range[2])) {
    let start = range[1] ? Number(range[1]) : Math.max(stat.size - Number(range[2]), 0);
    let end = range[1] && range[2] ? Math.min(Number(range[2]), stat.size - 1) : stat.size - 1;
    if (start > end || start >= stat.size) {
      return new Response(null, {
        status: 416,
        headers: { "Content-Range": `bytes */${stat.size}` },
      });
    }
    return new Response(Readable.toWeb(fs.createReadStream(file, { start, end })), {
      status: 206,
      headers: {
        ...headers,
        "Content-Range": `bytes ${start}-${end}/${stat.size}`,
        "Content-Length": String(end - start + 1),
      },
    });
  }

  return new Response(Readable.toWeb(fs.createReadStream(file)), {
    headers: { ...headers, "Content-Length": String(stat.size) },
  });
}
