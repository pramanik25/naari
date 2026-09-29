import Link from "next/link";
import { query } from "@/lib/db";
import { fmtCoords, mapsUrl } from "@/lib/format";
import Badge from "@/components/Badge";
import When from "@/components/When";
import Pager from "@/components/Pager";

export const dynamic = "force-dynamic";
const PAGE_SIZE = 25;

const STATUSES = ["active", "completed", "cancelled", "overdue"];

export default async function CheckinsPage({ searchParams }) {
  const sp = await searchParams;
  const status = STATUSES.includes(sp?.status) ? sp.status : "";
  const page = Math.max(1, Number(sp?.page) || 1);

  const params = [];
  let where = "";
  if (status) {
    params.push(status);
    where = `WHERE c.status = $1`;
  }
  params.push(PAGE_SIZE + 1, (page - 1) * PAGE_SIZE);

  const rows = await query(
    `
    SELECT c.id, c.status, c.deadline, c.note, c.contacts, c.last_lat, c.last_lng, c.last_at,
           c.incident_id, c.escalated_at, c.created_at,
           u.id AS user_id, u.name AS user_name
      FROM checkins c
      JOIN users u ON u.id = c.user_id
      ${where}
     ORDER BY c.created_at DESC
     LIMIT $${params.length - 1} OFFSET $${params.length}
    `,
    params
  );

  const hasMore = rows.length > PAGE_SIZE;
  const checkins = rows.slice(0, PAGE_SIZE);

  return (
    <>
      <h1>Check-ins</h1>
      <p className="subtitle">Timed “I’m safe” checks. Overdue ones auto-create an incident.</p>

      <form className="toolbar" method="GET">
        <select name="status" defaultValue={status}>
          <option value="">All statuses</option>
          {STATUSES.map((s) => (
            <option key={s} value={s}>
              {s}
            </option>
          ))}
        </select>
        <button type="submit">Filter</button>
        {status && (
          <Link className="btn ghost" href="/checkins">
            Clear
          </Link>
        )}
      </form>

      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Created</th>
              <th>User</th>
              <th>Status</th>
              <th>Deadline</th>
              <th>Note</th>
              <th>SMS contacts</th>
              <th>Last location</th>
              <th>Escalated</th>
            </tr>
          </thead>
          <tbody>
            {checkins.length === 0 && (
              <tr>
                <td colSpan={8} className="empty">
                  No check-ins found.
                </td>
              </tr>
            )}
            {checkins.map((c) => (
              <tr key={c.id}>
                <td>
                  <When value={c.created_at} />
                </td>
                <td>
                  <Link href={`/users/${c.user_id}`}>{c.user_name || "Unnamed"}</Link>
                </td>
                <td>
                  <Badge value={c.status} />
                </td>
                <td>
                  <When value={c.deadline} />
                </td>
                <td className="wrap">{c.note || "—"}</td>
                <td className="mono">
                  {Array.isArray(c.contacts) && c.contacts.length > 0 ? c.contacts.join(", ") : "—"}
                </td>
                <td>
                  {c.last_lat != null ? (
                    <a href={mapsUrl(c.last_lat, c.last_lng)} target="_blank">
                      {fmtCoords(c.last_lat, c.last_lng)}
                    </a>
                  ) : (
                    "—"
                  )}
                </td>
                <td>
                  {c.incident_id ? (
                    <Link href={`/incidents/${c.incident_id}`}>incident →</Link>
                  ) : (
                    "—"
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <Pager page={page} hasMore={hasMore} basePath="/checkins" params={status ? { status } : {}} />
    </>
  );
}
