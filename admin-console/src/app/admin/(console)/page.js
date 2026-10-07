import Link from "next/link";
import { hasPushColumn, query, queryOne } from "@/lib/db";
import Badge from "@/components/Badge";
import When from "@/components/When";

export const dynamic = "force-dynamic";

export default async function DashboardPage() {
  const pushCol = await hasPushColumn();
  const [stats, feed] = await Promise.all([
    queryOne(`
      SELECT
        (SELECT count(*) FROM users)                                                       AS users_total,
        (SELECT count(*) FROM users WHERE created_at > now() - interval '7 days')          AS users_week,
        (SELECT count(*) FROM incidents WHERE status = 'active')                           AS incidents_active,
        (SELECT count(*) FROM incidents WHERE duress AND status = 'active')                AS duress_active,
        (SELECT count(*) FROM incidents WHERE started_at > now() - interval '24 hours')    AS incidents_24h,
        (SELECT count(*) FROM incidents)                                                   AS incidents_total,
        (SELECT count(*) FROM checkins  WHERE status = 'active')                           AS checkins_active,
        (SELECT count(*) FROM checkins  WHERE status = 'overdue')                          AS checkins_overdue,
        (SELECT count(*) FROM helpers   WHERE updated_at > now() - interval '24 hours')    AS helpers_active,
        (SELECT count(*) FROM device_tokens)                                               AS devices_total,
        ${pushCol ? "(SELECT count(*) FROM device_tokens WHERE push_token IS NOT NULL)" : "NULL"} AS devices_push,
        (SELECT count(*) FROM notifications WHERE created_at > now() - interval '24 hours') AS notif_24h,
        (SELECT count(*) FROM evidence WHERE received_at > now() - interval '7 days')      AS evidence_week
    `),
    query(`
      (SELECT 'incident'::text AS kind, i.id::text AS ref, u.id::text AS user_id, u.name,
              ('SOS via ' || i.source || CASE WHEN i.silent THEN ' (silent)' ELSE '' END
               || CASE WHEN i.duress THEN ' — DURESS' ELSE '' END) AS detail,
              i.status AS status, i.started_at AS at
         FROM incidents i JOIN users u ON u.id = i.user_id
        ORDER BY i.started_at DESC LIMIT 12)
      UNION ALL
      (SELECT 'checkin', c.id::text, u.id::text, u.name,
              ('Check-in' || CASE WHEN c.note <> '' THEN ': ' || c.note ELSE '' END),
              c.status, c.created_at
         FROM checkins c JOIN users u ON u.id = c.user_id
        ORDER BY c.created_at DESC LIMIT 12)
      UNION ALL
      (SELECT 'signup', u.id::text, u.id::text, u.name, 'New user registered', NULL, u.created_at
         FROM users u
        ORDER BY u.created_at DESC LIMIT 12)
      ORDER BY at DESC
      LIMIT 25
    `),
  ]);

  const n = (v) => Number(v ?? 0);

  return (
    <>
      <div className="page-heading">
        <p className="eyebrow">Situation room</p>
        <h1>Operations overview</h1>
        <p className="subtitle">Current safety activity across the platform.</p>
      </div>

      <div className="stats">
        <div className="stat">
          <div className="num">{n(stats.users_total)}</div>
          <div className="label">Users total · +{n(stats.users_week)} this week</div>
        </div>
        <div className={`stat ${n(stats.incidents_active) > 0 ? "alert" : "good"}`}>
          <div className="num">{n(stats.incidents_active)}</div>
          <div className="label">Active SOS incidents</div>
        </div>
        <div className={`stat ${n(stats.duress_active) > 0 ? "alert" : ""}`}>
          <div className="num">{n(stats.duress_active)}</div>
          <div className="label">Active duress</div>
        </div>
        <div className="stat">
          <div className="num">{n(stats.incidents_24h)}</div>
          <div className="label">Incidents last 24 h · {n(stats.incidents_total)} total</div>
        </div>
        <div className={`stat ${n(stats.checkins_overdue) > 0 ? "alert" : ""}`}>
          <div className="num">{n(stats.checkins_overdue)}</div>
          <div className="label">Overdue check-ins · {n(stats.checkins_active)} active</div>
        </div>
        <div className="stat good">
          <div className="num">{n(stats.helpers_active)}</div>
          <div className="label">Helpers online (24 h)</div>
        </div>
        <div className="stat">
          <div className="num">{n(stats.devices_total)}</div>
          <div className="label">
            Devices{stats.devices_push != null && ` · ${n(stats.devices_push)} with push`}
          </div>
        </div>
        <div className="stat">
          <div className="num">{n(stats.notif_24h)}</div>
          <div className="label">Alerts sent last 24 h</div>
        </div>
      </div>

      <h2>Recent activity</h2>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>When</th>
              <th>User</th>
              <th>Event</th>
              <th>Status</th>
            </tr>
          </thead>
          <tbody>
            {feed.length === 0 && (
              <tr>
                <td colSpan={4} className="empty">
                  No activity yet.
                </td>
              </tr>
            )}
            {feed.map((row) => (
              <tr key={`${row.kind}-${row.ref}-${row.at}`}>
                <td>
                  <When value={row.at} />
                </td>
                <td>
                  <Link href={`/users/${row.user_id}`}>{row.name || "Unnamed"}</Link>
                </td>
                <td className="wrap">
                  {row.kind === "incident" ? (
                    <Link href={`/incidents/${row.ref}`}>{row.detail}</Link>
                  ) : (
                    row.detail
                  )}
                </td>
                <td>
                  <Badge value={row.status} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}
