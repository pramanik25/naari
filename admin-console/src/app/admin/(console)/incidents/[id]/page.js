import Link from "next/link";
import { notFound } from "next/navigation";
import { query, queryOne } from "@/lib/db";
import { fmtBytes, fmtCoords, mapsUrl, trackUrl } from "@/lib/format";
import Badge from "@/components/Badge";
import When from "@/components/When";

export const dynamic = "force-dynamic";

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function osmEmbed(lat, lng) {
  const d = 0.008;
  const bbox = [lng - d, lat - d, lng + d, lat + d].join("%2C");
  return `https://www.openstreetmap.org/export/embed.html?bbox=${bbox}&layer=mapnik&marker=${lat}%2C${lng}`;
}

export default async function IncidentDetailPage({ params }) {
  const { id } = await params;
  if (!UUID_RE.test(id)) notFound();

  const incident = await queryOne(
    `SELECT i.*, u.name AS user_name FROM incidents i JOIN users u ON u.id = i.user_id WHERE i.id = $1`,
    [id]
  );
  if (!incident) notFound();

  const [locations, evidence, helpers, notifications, waMessages, checkin] = await Promise.all([
    query(
      `SELECT lat, lng, accuracy, at FROM incident_locations
        WHERE incident_id = $1 ORDER BY at DESC LIMIT 100`,
      [id]
    ),
    query(
      `SELECT id, kind, content_type, size, verified, captured_at, received_at
         FROM evidence WHERE incident_id = $1 ORDER BY received_at DESC`,
      [id]
    ),
    query(
      `SELECT ih.*, u.name FROM incident_helpers ih JOIN users u ON u.id = ih.helper_id
        WHERE ih.incident_id = $1 ORDER BY ih.alerted_at`,
      [id]
    ),
    query(
      `SELECT n.id, n.type, n.created_at, n.acked_at, u.id AS recipient_id, u.name AS recipient_name
         FROM notifications n JOIN users u ON u.id = n.recipient_id
        WHERE n.incident_id = $1 ORDER BY n.created_at DESC LIMIT 100`,
      [id]
    ),
    query(
      `SELECT to_number, kind, status, attempts, error, created_at
         FROM whatsapp_messages WHERE incident_id = $1 ORDER BY created_at DESC`,
      [id]
    ),
    queryOne(`SELECT id, note, deadline, status FROM checkins WHERE incident_id = $1`, [id]),
  ]);

  const track = trackUrl(incident.track_token);

  return (
    <>
      <p className="dim" style={{ margin: "0 0 6px" }}>
        <Link href="/admin/incidents">← Incidents</Link>
      </p>
      <h1>
        SOS by <Link href={`/admin/users/${incident.user_id}`}>{incident.user_name || "Unnamed"}</Link>{" "}
        <Badge value={incident.status} /> {incident.duress && <Badge value="duress" />}
      </h1>
      <p className="subtitle mono">{incident.id}</p>

      <div className="cards">
        <div className="card">
          <h3>Incident</h3>
          <dl className="kv">
            <dt>Source</dt>
            <dd>
              {incident.source}
              {incident.silent && " (silent)"}
            </dd>
            <dt>Started</dt>
            <dd>
              <When value={incident.started_at} />
            </dd>
            <dt>Ended</dt>
            <dd>
              {incident.ended_at ? (
                <>
                  <When value={incident.ended_at} />{" "}
                  <span className="dim">({incident.user_initiated_end ? "by user" : "auto/other"})</span>
                </>
              ) : (
                "still active"
              )}
            </dd>
            <dt>Duress</dt>
            <dd>{incident.duress ? <When value={incident.duress_at} /> : "no"}</dd>
            <dt>SMS contacts</dt>
            <dd>{incident.contacts_count}</dd>
            <dt>Battery</dt>
            <dd>{incident.battery != null ? `${incident.battery}%` : "—"}</dd>
            <dt>Message</dt>
            <dd>{incident.message || "—"}</dd>
            <dt>Broadcast</dt>
            <dd>{incident.broadcast ? "helpers nearby alerted" : "off"}</dd>
            <dt>Tracking page</dt>
            <dd>
              {track ? (
                <a href={track} target="_blank">
                  {track}
                </a>
              ) : (
                "—"
              )}
            </dd>
          </dl>
        </div>

        <div className="card">
          <h3>Last known location</h3>
          {incident.last_lat != null ? (
            <>
              <p style={{ marginTop: 0 }}>
                <a href={mapsUrl(incident.last_lat, incident.last_lng)} target="_blank">
                  {fmtCoords(incident.last_lat, incident.last_lng)}
                </a>{" "}
                <span className="dim">
                  ±{incident.last_accuracy != null ? Math.round(incident.last_accuracy) : "?"} m ·{" "}
                  <When value={incident.last_at} relative={false} />
                </span>
              </p>
              <iframe
                className="map-frame"
                src={osmEmbed(Number(incident.last_lat), Number(incident.last_lng))}
                loading="lazy"
              />
            </>
          ) : (
            <p className="dim">No location received yet.</p>
          )}
        </div>
      </div>

      {checkin && (
        <p style={{ marginTop: 14 }}>
          Escalated from check-in <Badge value={checkin.status} />
          {checkin.note && <> — “{checkin.note}”</>} (deadline <When value={checkin.deadline} relative={false} />)
        </p>
      )}

      <h2>Location trail (latest {locations.length})</h2>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Time</th>
              <th>Coordinates</th>
              <th>Accuracy</th>
            </tr>
          </thead>
          <tbody>
            {locations.length === 0 && (
              <tr>
                <td colSpan={3} className="empty">
                  No location points.
                </td>
              </tr>
            )}
            {locations.map((p, i) => (
              <tr key={i}>
                <td>
                  <When value={p.at} />
                </td>
                <td>
                  <a href={mapsUrl(p.lat, p.lng)} target="_blank">
                    {fmtCoords(p.lat, p.lng)}
                  </a>
                </td>
                <td>{p.accuracy != null ? `±${Math.round(p.accuracy)} m` : "—"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <h2>Evidence ({evidence.length})</h2>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Captured</th>
              <th>Kind</th>
              <th>Type</th>
              <th>Size</th>
              <th>Hash verified</th>
              <th>Received</th>
            </tr>
          </thead>
          <tbody>
            {evidence.length === 0 && (
              <tr>
                <td colSpan={6} className="empty">
                  No evidence uploaded.
                </td>
              </tr>
            )}
            {evidence.map((e) => (
              <tr key={e.id}>
                <td>
                  <When value={e.captured_at} />
                </td>
                <td>{e.kind}</td>
                <td className="mono">{e.content_type}</td>
                <td>{fmtBytes(Number(e.size))}</td>
                <td>{e.verified ? <Badge value="verified" kind="completed" /> : <Badge value="mismatch" kind="duress" />}</td>
                <td>
                  <When value={e.received_at} relative={false} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <h2>Nearby helpers alerted ({helpers.length})</h2>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Helper</th>
              <th>Distance</th>
              <th>Radius</th>
              <th>Alerted</th>
              <th>Responded</th>
            </tr>
          </thead>
          <tbody>
            {helpers.length === 0 && (
              <tr>
                <td colSpan={5} className="empty">
                  No helpers were alerted.
                </td>
              </tr>
            )}
            {helpers.map((h) => (
              <tr key={h.helper_id}>
                <td>
                  <Link href={`/admin/users/${h.helper_id}`}>{h.name || "Unnamed"}</Link>
                </td>
                <td>{h.distance_m} m</td>
                <td>{h.radius_km} km</td>
                <td>
                  <When value={h.alerted_at} />
                </td>
                <td>
                  {h.responded_at ? (
                    <>
                      <Badge value="responding" kind="completed" /> <When value={h.responded_at} relative={false} />
                      {h.respond_lat != null && (
                        <>
                          {" "}
                          <a href={mapsUrl(h.respond_lat, h.respond_lng)} target="_blank">
                            from {fmtCoords(h.respond_lat, h.respond_lng)}
                          </a>
                        </>
                      )}
                    </>
                  ) : (
                    <span className="dim">no response</span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <h2>Alerts fanned out ({notifications.length})</h2>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>When</th>
              <th>Type</th>
              <th>Recipient</th>
              <th>Acknowledged</th>
            </tr>
          </thead>
          <tbody>
            {notifications.length === 0 && (
              <tr>
                <td colSpan={4} className="empty">
                  No alerts recorded.
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
                  <Link href={`/admin/users/${ntf.recipient_id}`}>{ntf.recipient_name || "Unnamed"}</Link>
                </td>
                <td>
                  {ntf.acked_at ? <When value={ntf.acked_at} relative={false} /> : <Badge value="unacked" kind="warn" />}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {waMessages.length > 0 && (
        <>
          <h2>WhatsApp messages ({waMessages.length})</h2>
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>When</th>
                  <th>To</th>
                  <th>Kind</th>
                  <th>Status</th>
                  <th>Attempts</th>
                  <th>Error</th>
                </tr>
              </thead>
              <tbody>
                {waMessages.map((m, i) => (
                  <tr key={i}>
                    <td>
                      <When value={m.created_at} />
                    </td>
                    <td className="mono">{m.to_number}</td>
                    <td>{m.kind}</td>
                    <td>
                      <Badge value={m.status} />
                    </td>
                    <td>{m.attempts}</td>
                    <td className="wrap dim">{m.error || "—"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </>
  );
}
