import { queryOne } from "@/lib/db";
import { isRedacted, signedEvidenceUrl } from "@/lib/evidence";

// pg needs the Node runtime.
export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const PASS_HEADERS = ["content-type", "content-length", "content-range", "accept-ranges"];

/**
 * Streams one evidence file (photo / audio / video) to a logged-in admin (middleware guards
 * /admin/*). The files live on the API server's disk, so this signs a short-lived tracking URL
 * server-side and relays it; the signed URL never reaches the browser. The backend still enforces
 * its rules: verified files only, and nothing once the incident ended more than 24 h ago.
 * Range is forwarded so audio/video can seek.
 */
export async function GET(request, { params }) {
  const { id } = await params;
  if (!UUID_RE.test(id)) return new Response("not found", { status: 404 });

  try {
    const row = await queryOne(
      `SELECT e.id, i.track_token, i.ended_at
         FROM evidence e JOIN incidents i ON i.id = e.incident_id
        WHERE e.id = $1 AND e.verified`,
      [id]
    );
    if (!row || isRedacted(row)) return new Response("not found", { status: 404 });

    const url = signedEvidenceUrl(row.track_token, row.id);
    if (!url) return new Response("SIGNING_SECRET / PUBLIC_BASE_URL not configured", { status: 503 });

    const headers = { "accept-encoding": "identity" };
    const range = request.headers.get("range");
    if (range) headers.range = range;
    const upstream = await fetch(url, { headers, cache: "no-store" });

    const out = new Headers({ "Cache-Control": "private, max-age=600", "X-Content-Type-Options": "nosniff" });
    for (const h of PASS_HEADERS) {
      const v = upstream.headers.get(h);
      if (v) out.set(h, v);
    }
    return new Response(upstream.ok ? upstream.body : null, { status: upstream.status, headers: out });
  } catch (err) {
    console.error("evidence relay failed", err);
    return new Response("upstream error", { status: 502 });
  }
}
