import Link from "next/link";
import { notFound } from "next/navigation";
import { hasPushColumn, query, queryOne } from "@/lib/db";
import { fmtCoords, mapsUrl, trackUrl } from "@/lib/format";
import Badge from "@/components/Badge";
import When from "@/components/When";

export const dynamic = "force-dynamic";

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export default async function UserDetailPage({ params }) {
  const { id } = await params;
  if (!UUID_RE.test(id)) notFound();

  const user = await queryOne(
    `SELECT id, name, guardian_code, whatsapp_enabled, created_at FROM users WHERE id = $1`,
    [id]
  );
  if (!user) notFound();

  const pushCol = await hasPushColumn();
  const [
    devices,
    guardians,
    wards,
    helper,
    waContacts,
    incidents,
    checkins,
    notifications,
  ] = await Promise.all([
    query(
      `SELECT device_name, created_at,
              ${pushCol ? "push_token IS NOT NULL" : "NULL::boolean"} AS has_push
         FROM device_tokens WHERE user_id = $1 ORDER BY created_at DESC`,
      [id]
    ),
    query(
      `SELECT u.id, u.name, g.linked_at FROM guardian_links g
         JOIN users u ON u.id = g.guardian_id WHERE g.ward_id = $1 ORDER BY g.linked_at DESC`,
      [id]
    ),
    query(
      `SELECT u.id, u.name, g.linked_at FROM guardian_links g
         JOIN users u ON u.id = g.ward_id WHERE g.guardian_id = $1 ORDER BY g.linked_at DESC`,
      [id]
    ),
    queryOne(`SELECT lat, lng, updated_at FROM helpers WHERE user_id = $1`, [id]),
    query(
      `SELECT number, name, opted_in, opted_in_at, opted_out_at, position
         FROM whatsapp_contacts WHERE user_id = $1 ORDER BY position, created_at`,
      [id]
    ),
    query(
      `SELECT i.id, i.source, i.silent, i.status, i.duress, i.started_at, i.ended_at,
              i.user_initiated_end, i.battery, i.track_token, i.last_lat, i.last_lng,
              (SELECT count(*) FROM evidence e WHERE e.incident_id = i.id) AS evidence_count,
              (SELECT count(*) FROM incident_helpers ih WHERE ih.incident_id = i.id) AS helpers_alerted
         FROM incidents i WHERE i.user_id = $1 ORDER BY i.started_at DESC LIMIT 50`,
      [id]
    ),
    query(
      `SELECT id, status, deadline, note, contacts, escalated_at, incident_id, created_at
         FROM checkins WHERE user_id = $1 ORDER BY created_at DESC LIMIT 50`,
      [id]
    ),
    query(
      `SELECT n.id, n.type, n.created_at, n.acked_at, n.incident_id
         FROM notifications n WHERE n.recipient_id = $1 ORDER BY n.created_at DESC LIMIT 50`,
      [id]
    ),
  ]);

  return (
    <>
      <p className="dim" style={{ margin: "0 0 6px" }}>
        <Link href="/admin/users">← Users</Link>
      </p>
      <h1>{user.name || "Unnamed user"}</h1>
      <p className="subtitle mono">{user.id}</p>

      <div className="cards">
        <div className="card">
          <h3>Profile</h3>
          <dl className="kv">
            <dt>Guardian code</dt>
            <dd className="mono">{user.guardian_code || "—"}</dd>
            <dt>Joined</dt>
            <dd>
              <When value={user.created_at} />
            </dd>
            <dt>WhatsApp alerts</dt>
            <dd>{user.whatsapp_enabled ? "enabled" : "disabled"}</dd>
            <dt>Helper</dt>
            <dd>
              {helper ? (
                <>
                  <a href={mapsUrl(helper.lat, helper.lng)} target="_blank">
                    {fmtCoords(helper.lat, helper.lng)}
                  </a>{" "}
                  <span className="dim">
                    · updated <When value={helper.updated_at} relative={false} />
                  </span>
                </>
              ) : (
                "not a helper"
              )}
            </dd>
          </dl>
        </div>

        <div className="card">
          <h3>Devices ({devices.length})</h3>
          {devices.length === 0 && <p className="dim">No devices registered.</p>}
          {devices.map((d, i) => (
            <p key={i} style={{ margin: "4px 0" }}>
              {d.device_name || "Unknown device"}{" "}
              {d.has_push == null ? null : d.has_push ? (
                <Badge value="push" kind="info" />
              ) : (
                <Badge value="no push" kind="neutral" />
              )}
              <br />
              <span className="dim">
                registered <When value={d.created_at} relative={false} />
              </span>
            </p>
          ))}
        </div>

        <div className="card">
          <h3>Guardians — get her alerts ({guardians.length})</h3>
          {guardians.length === 0 && <p className="dim">No guardians linked.</p>}
          {guardians.map((g) => (
            <p key={g.id} style={{ margin: "4px 0" }}>
              <Link href={`/admin/users/${g.id}`}>{g.name || "Unnamed"}</Link>{" "}
              <span className="dim">
                since <When value={g.linked_at} relative={false} />
              </span>
            </p>
          ))}
        </div>

        <div className="card">
          <h3>Guarding — she gets their alerts ({wards.length})</h3>
          {wards.length === 0 && <p className="dim">Not guarding anyone.</p>}
          {wards.map((g) => (
            <p key={g.id} style={{ margin: "4px 0" }}>
              <Link href={`/admin/users/${g.id}`}>{g.name || "Unnamed"}</Link>{" "}
              <span className="dim">
                since <When value={g.linked_at} relative={false} />
              </span>
            </p>
          ))}
        </div>

        {waContacts.length > 0 && (
          <div className="card">
            <h3>WhatsApp contacts ({waContacts.length})</h3>
            {waContacts.map((c) => (
              <p key={c.number} style={{ margin: "4px 0" }}>
                <span className="mono">{c.number}</span> {c.name && `· ${c.name}`}{" "}
                {c.opted_in ? (
                  <Badge value="opted in" kind="completed" />
                ) : c.opted_out_at ? (
                  <Badge value="opted out" kind="duress" />
                ) : (
                  <Badge value="pending" kind="warn" />
                )}
              </p>
            ))}
          </div>
        )}
      </div>

      <h2>Incidents ({incidents.length})</h2>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Started</th>
              <th>Source</th>
              <th>Status</th>
              <th>Ended</th>
              <th>Evidence</th>
              <th>Helpers alerted</th>
              <th>Battery</th>
              <th>Last location</th>
              <th>Track</th>
            </tr>
          </thead>
          <tbody>
            {incidents.length === 0 && (
              <tr>
                <td colSpan={9} className="empty">
                  No incidents.
                </td>
              </tr>
            )}
            {incidents.map((i) => (
              <tr key={i.id}>
                <td>
                  <Link href={`/admin/incidents/${i.id}`}>
                    <When value={i.started_at} />
                  </Link>
                </td>
                <td>
                  {i.source}
                  {i.silent && <span className="dim"> (silent)</span>}
                </td>
                <td>
                  <Badge value={i.status} /> {i.duress && <Badge value="duress" />}
                </td>
                <td>
                  <When value={i.ended_at} relative={false} />
                  {i.ended_at && (
                    <div className="dim">{i.user_initiated_end ? "by user" : "auto/other"}</div>
                  )}
                </td>
                <td>{i.evidence_count}</td>
                <td>{i.helpers_alerted}</td>
                <td>{i.battery != null ? `${i.battery}%` : "—"}</td>
                <td>
                  {i.last_lat != null ? (
                    <a href={mapsUrl(i.last_lat, i.last_lng)} target="_blank">
                      {fmtCoords(i.last_lat, i.last_lng)}
                    </a>
                  ) : (
                    "—"
                  )}
                </td>
                <td>
                  {trackUrl(i.track_token) ? (
                    <a href={trackUrl(i.track_token)} target="_blank">
                      open
                    </a>
                  ) : (
                    "—"
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <h2>Check-ins ({checkins.length})</h2>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Created</th>
              <th>Status</th>
              <th>Deadline</th>
              <th>Note</th>
              <th>SMS contacts</th>
              <th>Escalated</th>
            </tr>
          </thead>
          <tbody>
            {checkins.length === 0 && (
              <tr>
                <td colSpan={6} className="empty">
                  No check-ins.
                </td>
              </tr>
            )}
            {checkins.map((c) => (
              <tr key={c.id}>
                <td>
                  <When value={c.created_at} />
                </td>
                <td>
                  <Badge value={c.status} />
                </td>
                <td>
                  <When value={c.deadline} relative={false} />
                </td>
                <td className="wrap">{c.note || "—"}</td>
                <td className="mono">
                  {Array.isArray(c.contacts) && c.contacts.length > 0 ? c.contacts.join(", ") : "—"}
                </td>
                <td>
                  {c.incident_id ? (
                    <Link href={`/admin/incidents/${c.incident_id}`}>incident →</Link>
                  ) : (
                    "—"
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <h2>Alerts received (last 50)</h2>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>When</th>
              <th>Type</th>
              <th>Incident</th>
              <th>Acknowledged</th>
            </tr>
          </thead>
          <tbody>
            {notifications.length === 0 && (
              <tr>
                <td colSpan={4} className="empty">
                  No alerts received.
                </td>
              </tr>
            )}
            {notifications.map((ntf) => (
              <tr key={ntf.id}>
                <td>
                  <When value={ntf.created_at} />
                </td>
                <td>
                  <Badge value={ntf.type} kind="info" />
                </td>
                <td>
                  {ntf.incident_id ? (
                    <Link href={`/admin/incidents/${ntf.incident_id}`}>view →</Link>
                  ) : (
                    "—"
                  )}
                </td>
                <td>
                  {ntf.acked_at ? <When value={ntf.acked_at} relative={false} /> : <Badge value="unacked" kind="warn" />}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}
