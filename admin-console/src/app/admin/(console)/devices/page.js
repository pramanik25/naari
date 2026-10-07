import Link from "next/link";
import { query, queryOne } from "@/lib/db";
import Badge from "@/components/Badge";
import When from "@/components/When";

export const dynamic = "force-dynamic";

const DORMANT_DAYS = 30;

export default async function DevicesPage() {
  const [stats, perDay, recent, activity] = await Promise.all([
    queryOne(`
      SELECT
        (SELECT count(*) FROM device_tokens)                                              AS devices_total,
        (SELECT count(*) FROM device_tokens WHERE created_at > now() - interval '7 days') AS installs_7d,
        (SELECT count(*) FROM device_tokens WHERE created_at > now() - interval '30 days') AS installs_30d,
        (SELECT count(*) FROM users WHERE created_at > now() - interval '30 days')        AS users_30d
    `),
    query(`
      SELECT date_trunc('day', created_at)::date AS day, count(*) AS devices
        FROM device_tokens
       WHERE created_at > now() - interval '30 days'
       GROUP BY 1 ORDER BY 1 DESC
    `),
    query(`
      SELECT d.device_name, d.created_at, u.id AS user_id, u.name AS user_name
        FROM device_tokens d JOIN users u ON u.id = d.user_id
       ORDER BY d.created_at DESC LIMIT 50
    `),
    query(`
      SELECT u.id, u.name, u.created_at,
             (SELECT count(*) FROM device_tokens d WHERE d.user_id = u.id) AS devices,
             GREATEST(
               u.created_at,
               COALESCE((SELECT max(d.created_at) FROM device_tokens d WHERE d.user_id = u.id), 'epoch'::timestamptz),
               COALESCE((SELECT max(i.updated_at) FROM incidents i    WHERE i.user_id = u.id), 'epoch'::timestamptz),
               COALESCE((SELECT max(c.updated_at) FROM checkins c     WHERE c.user_id = u.id), 'epoch'::timestamptz),
               COALESCE((SELECT max(h.updated_at) FROM helpers h      WHERE h.user_id = u.id), 'epoch'::timestamptz)
             ) AS last_seen
        FROM users u
       ORDER BY last_seen DESC
       LIMIT 200
    `),
  ]);

  const n = (v) => Number(v ?? 0);
  const dormantCutoff = Date.now() - DORMANT_DAYS * 24 * 60 * 60 * 1000;
  const dormant = activity.filter((u) => new Date(u.last_seen).getTime() < dormantCutoff);
  const maxPerDay = Math.max(1, ...perDay.map((d) => Number(d.devices)));

  return (
    <>
      <h1>Devices &amp; installs</h1>
      <p className="subtitle">
        A device registration ≈ one install (a reinstall registers a brand-new user, since the
        auth token is lost). Uninstalls are never reported by the app, so “dormant” below is the
        closest signal — for exact install/uninstall counts use Google Play Console or Firebase
        Analytics.
      </p>

      <div className="stats">
        <div className="stat">
          <div className="num">{n(stats.devices_total)}</div>
          <div className="label">Devices registered (all time)</div>
        </div>
        <div className="stat good">
          <div className="num">{n(stats.installs_7d)}</div>
          <div className="label">New devices last 7 days</div>
        </div>
        <div className="stat">
          <div className="num">{n(stats.installs_30d)}</div>
          <div className="label">New devices last 30 days · {n(stats.users_30d)} new users</div>
        </div>
        <div className={`stat ${dormant.length > 0 ? "alert" : ""}`}>
          <div className="num">{dormant.length}</div>
          <div className="label">Dormant &gt;{DORMANT_DAYS} days (possibly uninstalled)</div>
        </div>
      </div>

      <h2>Installs per day (last 30 days)</h2>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Day</th>
              <th>New devices</th>
              <th style={{ width: "50%" }}></th>
            </tr>
          </thead>
          <tbody>
            {perDay.length === 0 && (
              <tr>
                <td colSpan={3} className="empty">
                  No device registrations in the last 30 days.
                </td>
              </tr>
            )}
            {perDay.map((d) => (
              <tr key={d.day}>
                <td>
                  {new Intl.DateTimeFormat("en-IN", {
                    timeZone: process.env.ADMIN_TZ || "Asia/Kolkata",
                    day: "2-digit",
                    month: "short",
                  }).format(new Date(d.day))}
                </td>
                <td>{d.devices}</td>
                <td>
                  <div
                    style={{
                      height: 10,
                      width: `${(Number(d.devices) / maxPerDay) * 100}%`,
                      minWidth: 4,
                      borderRadius: 4,
                      background: "var(--accent)",
                      opacity: 0.8,
                    }}
                  />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <h2>Recent device registrations</h2>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Registered</th>
              <th>Device</th>
              <th>User</th>
            </tr>
          </thead>
          <tbody>
            {recent.length === 0 && (
              <tr>
                <td colSpan={3} className="empty">
                  No devices registered yet.
                </td>
              </tr>
            )}
            {recent.map((d, i) => (
              <tr key={i}>
                <td>
                  <When value={d.created_at} />
                </td>
                <td>{d.device_name || "Unknown device"}</td>
                <td>
                  <Link href={`/users/${d.user_id}`}>{d.user_name || "Unnamed"}</Link>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <h2>User activity — last seen</h2>
      <p className="dim" style={{ marginTop: -4 }}>
        Last seen = newest of: device registered, SOS activity, check-in activity, helper location
        update. Users quiet for over {DORMANT_DAYS} days may have uninstalled or switched phones.
      </p>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>User</th>
              <th>Joined</th>
              <th>Devices</th>
              <th>Last seen</th>
              <th>Status</th>
            </tr>
          </thead>
          <tbody>
            {activity.length === 0 && (
              <tr>
                <td colSpan={5} className="empty">
                  No users yet.
                </td>
              </tr>
            )}
            {activity.map((u) => {
              const isDormant = new Date(u.last_seen).getTime() < dormantCutoff;
              return (
                <tr key={u.id}>
                  <td>
                    <Link href={`/users/${u.id}`}>{u.name || "Unnamed"}</Link>
                  </td>
                  <td>
                    <When value={u.created_at} relative={false} />
                  </td>
                  <td>{u.devices}</td>
                  <td>
                    <When value={u.last_seen} />
                  </td>
                  <td>
                    {isDormant ? (
                      <Badge value="dormant" kind="warn" />
                    ) : (
                      <Badge value="active" kind="completed" />
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </>
  );
}
