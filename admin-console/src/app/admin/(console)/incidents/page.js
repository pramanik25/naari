import Link from "next/link";
import { query } from "@/lib/db";
import { fmtCoords, mapsUrl, trackUrl } from "@/lib/format";
import Badge from "@/components/Badge";
import When from "@/components/When";
import Pager from "@/components/Pager";

export const dynamic = "force-dynamic";
const PAGE_SIZE = 25;

export default async function IncidentsPage({ searchParams }) {
  const sp = await searchParams;
  const status = ["active", "ended"].includes(sp?.status) ? sp.status : "";
  const duress = sp?.duress === "1";
  const q = (sp?.q || "").trim();
  const page = Math.max(1, Number(sp?.page) || 1);

  const where = [];
  const params = [];
  if (status) {
    params.push(status);
    where.push(`i.status = $${params.length}`);
  }
  if (duress) where.push(`i.duress`);
  if (q) {
    params.push(`%${q}%`);
    where.push(`(u.name ILIKE $${params.length} OR i.id::text ILIKE $${params.length} OR i.source ILIKE $${params.length})`);
  }
  params.push(PAGE_SIZE + 1, (page - 1) * PAGE_SIZE);

  const rows = await query(
    `
    SELECT i.id, i.source, i.silent, i.status, i.duress, i.started_at, i.ended_at,
           i.battery, i.contacts_count, i.last_lat, i.last_lng, i.last_at, i.track_token,
           u.id AS user_id, u.name AS user_name,
           (SELECT count(*) FROM evidence e WHERE e.incident_id = i.id) AS evidence_count,
           (SELECT count(*) FROM incident_helpers ih WHERE ih.incident_id = i.id) AS helpers_alerted,
           (SELECT count(*) FROM incident_helpers ih WHERE ih.incident_id = i.id AND ih.responded_at IS NOT NULL) AS responders
      FROM incidents i
      JOIN users u ON u.id = i.user_id
      ${where.length ? `WHERE ${where.join(" AND ")}` : ""}
     ORDER BY i.started_at DESC
     LIMIT $${params.length - 1} OFFSET $${params.length}
    `,
    params
  );

  const hasMore = rows.length > PAGE_SIZE;
  const incidents = rows.slice(0, PAGE_SIZE);
  const filterParams = {};
  if (status) filterParams.status = status;
  if (duress) filterParams.duress = "1";
  if (q) filterParams.q = q;

  return (
    <>
      <h1>Incidents</h1>
      <p className="subtitle">Every fired SOS, newest first.</p>

      <form className="toolbar" method="GET">
        <input type="search" name="q" placeholder="Search user, source or id…" defaultValue={q} style={{ width: 260 }} />
        <select name="status" defaultValue={status}>
          <option value="">All statuses</option>
          <option value="active">Active</option>
          <option value="ended">Ended</option>
        </select>
        <label className="dim" style={{ display: "flex", alignItems: "center", gap: 6 }}>
          <input type="checkbox" name="duress" value="1" defaultChecked={duress} /> duress only
        </label>
        <button type="submit">Filter</button>
        {(q || status || duress) && (
          <Link className="btn ghost" href="/admin/incidents">
            Clear
          </Link>
        )}
      </form>

      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Started</th>
              <th>User</th>
              <th>Source</th>
              <th>Status</th>
              <th>Duration</th>
              <th>Evidence</th>
              <th>Helpers</th>
              <th>Battery</th>
              <th>Last location</th>
              <th>Track</th>
            </tr>
          </thead>
          <tbody>
            {incidents.length === 0 && (
              <tr>
                <td colSpan={10} className="empty">
                  No incidents found.
                </td>
              </tr>
            )}
            {incidents.map((i) => {
              const end = i.ended_at ? new Date(i.ended_at) : new Date();
              const durMin = Math.max(0, Math.round((end - new Date(i.started_at)) / 60000));
              return (
                <tr key={i.id}>
                  <td>
                    <Link href={`/admin/incidents/${i.id}`}>
                      <When value={i.started_at} />
                    </Link>
                  </td>
                  <td>
                    <Link href={`/admin/users/${i.user_id}`}>{i.user_name || "Unnamed"}</Link>
                  </td>
                  <td>
                    {i.source}
                    {i.silent && <span className="dim"> (silent)</span>}
                  </td>
                  <td>
                    <Badge value={i.status} /> {i.duress && <Badge value="duress" />}
                  </td>
                  <td>{durMin < 60 ? `${durMin}m` : `${Math.floor(durMin / 60)}h ${durMin % 60}m`}</td>
                  <td>{i.evidence_count}</td>
                  <td>
                    {i.helpers_alerted}
                    {Number(i.responders) > 0 && <span className="dim"> · {i.responders} responding</span>}
                  </td>
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
              );
            })}
          </tbody>
        </table>
      </div>

      <Pager page={page} hasMore={hasMore} basePath="/admin/incidents" params={filterParams} />
    </>
  );
}
