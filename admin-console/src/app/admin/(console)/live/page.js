"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import Badge from "@/components/Badge";
import { fmtCoords, fmtDateTime, mapsUrl, timeAgo } from "@/lib/format";

const REFRESH_MS = 10_000;
// A session whose last fix is older than this is probably paused/offline, not moving.
const STALE_MS = 3 * 60_000;

export default function LivePage() {
  const [sessions, setSessions] = useState(null); // null = first load
  const [error, setError] = useState(null);
  const [updatedAt, setUpdatedAt] = useState(null);
  const [live, setLive] = useState(true);
  const [now, setNow] = useState(() => Date.now());
  const timer = useRef(null);

  const load = useCallback(async () => {
    try {
      const res = await fetch("/admin/api/live", { cache: "no-store" });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = await res.json();
      if (!data.ok) throw new Error(data.error || "query_failed");
      setSessions(data.sessions);
      setUpdatedAt(data.serverTime);
      setError(null);
    } catch (e) {
      setError(e.message || "Failed to load");
    }
  }, []);

  // Poll while "live" is on. Also re-tick "now" each second so the age labels stay honest.
  useEffect(() => {
    load();
    if (!live) return undefined;
    timer.current = setInterval(load, REFRESH_MS);
    return () => clearInterval(timer.current);
  }, [live, load]);

  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, []);

  const duressCount = sessions?.filter((s) => s.duress).length ?? 0;
  const activeCount = sessions?.length ?? 0;

  return (
    <>
      <div className="page-heading">
        <p className="eyebrow">Situation room</p>
        <h1>Live map</h1>
        <p className="subtitle">
          Women with an SOS running right now. Locations appear only while she has an active
          session; this is not an on-demand tracker.
        </p>
      </div>

      <div
        className="dim"
        style={{
          display: "flex",
          alignItems: "center",
          gap: 12,
          flexWrap: "wrap",
          margin: "0 0 14px",
        }}
      >
        <button
          type="button"
          onClick={() => setLive((v) => !v)}
          className={`badge ${live ? "active" : "neutral"}`}
          style={{ cursor: "pointer", border: "none" }}
          aria-pressed={live}
        >
          {live ? "● Live — auto-refreshing" : "⏸ Paused"}
        </button>
        <button
          type="button"
          onClick={load}
          className="badge info"
          style={{ cursor: "pointer", border: "none" }}
        >
          Refresh now
        </button>
        {updatedAt && <span>Updated {timeAgo(updatedAt)}</span>}
        {error && <span className="badge duress">Error: {error}</span>}
      </div>

      <div className="stats">
        <div className={`stat ${activeCount > 0 ? "alert" : "good"}`}>
          <div className="num">{activeCount}</div>
          <div className="label">Active SOS sessions</div>
        </div>
        <div className={`stat ${duressCount > 0 ? "alert" : ""}`}>
          <div className="num">{duressCount}</div>
          <div className="label">Under duress</div>
        </div>
      </div>

      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>User</th>
              <th>Source</th>
              <th>Started</th>
              <th>Last fix</th>
              <th>Location</th>
              <th>Battery</th>
              <th>Helpers</th>
              <th>Live track</th>
            </tr>
          </thead>
          <tbody>
            {sessions === null && (
              <tr>
                <td colSpan={8} className="empty">
                  Loading…
                </td>
              </tr>
            )}
            {sessions !== null && sessions.length === 0 && (
              <tr>
                <td colSpan={8} className="empty">
                  No active SOS sessions right now.
                </td>
              </tr>
            )}
            {sessions?.map((s) => {
              const stale = s.lastAt == null || now - s.lastAt > STALE_MS;
              return (
                <tr key={s.id}>
                  <td>
                    <Link href={`/admin/users/${s.userId}`}>{s.userName}</Link>
                    {s.duress && (
                      <>
                        {" "}
                        <Badge value="duress" />
                      </>
                    )}
                  </td>
                  <td>
                    {s.source}
                    {s.silent && <span className="dim"> (silent)</span>}
                  </td>
                  <td title={s.startedAt ? fmtDateTime(s.startedAt) : undefined}>
                    {s.startedAt ? timeAgo(s.startedAt) : "—"}
                  </td>
                  <td>
                    {s.lastAt ? (
                      <span
                        className={stale ? "badge warn" : "badge completed"}
                        title={fmtDateTime(s.lastAt)}
                      >
                        {timeAgo(s.lastAt)}
                      </span>
                    ) : (
                      <span className="dim">no fix yet</span>
                    )}
                  </td>
                  <td>
                    {s.lat != null && mapsUrl(s.lat, s.lng) ? (
                      <a href={mapsUrl(s.lat, s.lng)} target="_blank" rel="noreferrer">
                        {fmtCoords(s.lat, s.lng)}
                      </a>
                    ) : (
                      "—"
                    )}
                    {s.accuracy != null && (
                      <span className="dim"> · ±{Math.round(s.accuracy)}m</span>
                    )}
                  </td>
                  <td>{s.battery != null ? `${s.battery}%` : "—"}</td>
                  <td>
                    {s.helpersAlerted}
                    {s.responders > 0 && (
                      <span className="dim"> · {s.responders} responding</span>
                    )}
                  </td>
                  <td>
                    {s.trackUrl ? (
                      <a href={s.trackUrl} target="_blank" rel="noreferrer">
                        open map →
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
    </>
  );
}
