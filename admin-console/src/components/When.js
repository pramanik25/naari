import { fmtDateTime, timeAgo } from "@/lib/format";

// Absolute timestamp with a relative hint, e.g. "29 Sep 2026, 10:04 am · 2h ago".
export default function When({ value, relative = true }) {
  if (!value) return <span className="dim">—</span>;
  return (
    <span title={new Date(value).toISOString()}>
      {fmtDateTime(value)}
      {relative && <span className="dim"> · {timeAgo(value)}</span>}
    </span>
  );
}
