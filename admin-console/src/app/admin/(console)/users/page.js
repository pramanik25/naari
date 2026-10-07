import Link from "next/link";
import { query } from "@/lib/db";
import When from "@/components/When";
import Pager from "@/components/Pager";

export const dynamic = "force-dynamic";
const PAGE_SIZE = 25;

export default async function UsersPage({ searchParams }) {
  const sp = await searchParams;
  const q = (sp?.q || "").trim();
  const page = Math.max(1, Number(sp?.page) || 1);

  const params = [];
  let where = "";
  if (q) {
    params.push(`%${q}%`);
    where = `WHERE u.name ILIKE $1 OR u.guardian_code ILIKE $1 OR u.id::text ILIKE $1`;
  }
  params.push(PAGE_SIZE + 1, (page - 1) * PAGE_SIZE);

  const rows = await query(
    `
    SELECT u.id, u.name, u.guardian_code, u.created_at, u.whatsapp_enabled,
           (SELECT count(*) FROM device_tokens d WHERE d.user_id = u.id)                    AS devices,
           (SELECT count(*) FROM guardian_links g WHERE g.ward_id = u.id)                   AS guardians,
           (SELECT count(*) FROM guardian_links g WHERE g.guardian_id = u.id)               AS wards,
           (SELECT count(*) FROM incidents i WHERE i.user_id = u.id)                        AS incidents,
           (SELECT max(i.started_at) FROM incidents i WHERE i.user_id = u.id)               AS last_incident_at,
           (SELECT count(*) FROM checkins c WHERE c.user_id = u.id)                         AS checkins,
           EXISTS (SELECT 1 FROM helpers h WHERE h.user_id = u.id
                     AND h.updated_at > now() - interval '24 hours')                        AS helper_active
      FROM users u
      ${where}
     ORDER BY u.created_at DESC
     LIMIT $${params.length - 1} OFFSET $${params.length}
    `,
    params
  );

  const hasMore = rows.length > PAGE_SIZE;
  const users = rows.slice(0, PAGE_SIZE);

  return (
    <>
      <h1>Users</h1>
      <p className="subtitle">Everyone registered on the app. Click a user for full details.</p>

      <form className="toolbar" method="GET">
        <input
          type="search"
          name="q"
          placeholder="Search name, guardian code or user id…"
          defaultValue={q}
          style={{ width: 320 }}
        />
        <button type="submit">Search</button>
        {q && (
          <Link className="btn ghost" href="/users">
            Clear
          </Link>
        )}
      </form>

      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Name</th>
              <th>Guardian code</th>
              <th>Joined</th>
              <th>Devices</th>
              <th>Guardians</th>
              <th>Guarding</th>
              <th>Incidents</th>
              <th>Last SOS</th>
              <th>Check-ins</th>
              <th>Helper</th>
              <th>WhatsApp</th>
            </tr>
          </thead>
          <tbody>
            {users.length === 0 && (
              <tr>
                <td colSpan={11} className="empty">
                  No users found.
                </td>
              </tr>
            )}
            {users.map((u) => (
              <tr key={u.id}>
                <td>
                  <Link href={`/users/${u.id}`}>{u.name || "Unnamed"}</Link>
                  <div className="dim mono">{u.id.slice(0, 8)}…</div>
                </td>
                <td className="mono">{u.guardian_code || "—"}</td>
                <td>
                  <When value={u.created_at} />
                </td>
                <td>{u.devices}</td>
                <td>{u.guardians}</td>
                <td>{u.wards}</td>
                <td>{u.incidents}</td>
                <td>
                  <When value={u.last_incident_at} />
                </td>
                <td>{u.checkins}</td>
                <td>{u.helper_active ? "🟢 online" : <span className="dim">—</span>}</td>
                <td>{u.whatsapp_enabled ? "✓" : <span className="dim">—</span>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <Pager page={page} hasMore={hasMore} basePath="/users" params={q ? { q } : {}} />
    </>
  );
}
