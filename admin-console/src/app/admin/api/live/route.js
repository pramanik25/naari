import { NextResponse } from "next/server";
import { query } from "@/lib/db";
import { trackUrl } from "@/lib/format";

// pg needs the Node runtime; never cache a live feed.
export const runtime = "nodejs";
export const dynamic = "force-dynamic";

/**
 * Active, user-initiated sessions only.
 *
 * Location reaches the server exclusively while a woman has an SOS/duress incident running — a
 * session she started, with the phone showing its mandatory foreground-service notification. This
 * endpoint surfaces those live sessions for the situation room; it is deliberately NOT a way to
 * read any user's location on demand. An ended, non-duress incident drops off immediately, and
 * anything older than 24 h is excluded so the board stays to what is genuinely happening now.
 */
const ACTIVE_SESSIONS_SQL = `
  SELECT i.id, i.user_id, u.name AS user_name,
         i.source, i.silent, i.duress, i.status,
         i.started_at, i.last_lat, i.last_lng, i.last_accuracy, i.last_at, i.battery, i.track_token,
         (SELECT count(*)::int FROM incident_helpers ih WHERE ih.incident_id = i.id)                AS helpers_alerted,
         (SELECT count(*)::int FROM incident_helpers ih WHERE ih.incident_id = i.id
            AND ih.responded_at IS NOT NULL)                                                        AS responders
    FROM incidents i
    JOIN users u ON u.id = i.user_id
   WHERE (i.status = 'active' OR i.duress)
     AND i.started_at > now() - interval '24 hours'
   ORDER BY i.duress DESC, COALESCE(i.last_at, i.started_at) DESC
   LIMIT 200
`;

const ms = (v) => (v ? new Date(v).getTime() : null);
const num = (v) => (v == null ? null : Number(v));

export async function GET() {
  try {
    const rows = await query(ACTIVE_SESSIONS_SQL);
    const sessions = rows.map((r) => ({
      id: r.id,
      userId: r.user_id,
      userName: r.user_name || "Unnamed",
      source: r.source,
      silent: r.silent,
      duress: r.duress,
      status: r.status,
      startedAt: ms(r.started_at),
      lastAt: ms(r.last_at),
      lat: num(r.last_lat),
      lng: num(r.last_lng),
      accuracy: num(r.last_accuracy),
      battery: num(r.battery),
      helpersAlerted: r.helpers_alerted ?? 0,
      responders: r.responders ?? 0,
      trackUrl: trackUrl(r.track_token),
    }));
    return NextResponse.json(
      { ok: true, serverTime: Date.now(), sessions },
      { headers: { "Cache-Control": "no-store" } }
    );
  } catch (err) {
    console.error("live feed query failed", err);
    return NextResponse.json({ ok: false, error: "query_failed" }, { status: 500 });
  }
}
