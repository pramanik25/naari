import Link from "next/link";
import { query } from "@/lib/db";
import { fmtCoords, mapsUrl } from "@/lib/format";
import Badge from "@/components/Badge";
import When from "@/components/When";

export const dynamic = "force-dynamic";

export default async function HelpersPage() {
  const helpers = await query(`
    SELECT h.user_id, h.lat, h.lng, h.updated_at,
           u.name,
           h.updated_at > now() - interval '24 hours' AS fresh,
           (SELECT count(*) FROM incident_helpers ih WHERE ih.helper_id = h.user_id) AS times_alerted,
           (SELECT count(*) FROM incident_helpers ih
             WHERE ih.helper_id = h.user_id AND ih.responded_at IS NOT NULL)          AS times_responded
      FROM helpers h
      JOIN users u ON u.id = h.user_id
     ORDER BY h.updated_at DESC
     LIMIT 200
  `);

  return (
    <>
      <h1>Helpers</h1>
      <p className="subtitle">
        Opt-in volunteers who receive nearby SOS alerts. Locations older than 24 h are ignored by fan-out.
      </p>

      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Helper</th>
              <th>Status</th>
              <th>Location</th>
              <th>Updated</th>
              <th>Times alerted</th>
              <th>Times responded</th>
            </tr>
          </thead>
          <tbody>
            {helpers.length === 0 && (
              <tr>
                <td colSpan={6} className="empty">
                  No helpers registered.
                </td>
              </tr>
            )}
            {helpers.map((h) => (
              <tr key={h.user_id}>
                <td>
                  <Link href={`/users/${h.user_id}`}>{h.name || "Unnamed"}</Link>
                </td>
                <td>
                  {h.fresh ? <Badge value="online" kind="completed" /> : <Badge value="stale" kind="neutral" />}
                </td>
                <td>
                  <a href={mapsUrl(h.lat, h.lng)} target="_blank">
                    {fmtCoords(h.lat, h.lng)}
                  </a>
                </td>
                <td>
                  <When value={h.updated_at} />
                </td>
                <td>{h.times_alerted}</td>
                <td>{h.times_responded}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}
