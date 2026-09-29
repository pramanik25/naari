const CLASS_BY_VALUE = {
  active: "active",
  ended: "ended",
  completed: "completed",
  cancelled: "cancelled",
  overdue: "overdue",
  duress: "duress",
  pending: "warn",
  sent: "info",
  delivered: "info",
  read: "completed",
  failed: "duress",
};

export default function Badge({ value, kind }) {
  if (value == null || value === "") return <span className="dim">—</span>;
  const cls = kind || CLASS_BY_VALUE[String(value).toLowerCase()] || "neutral";
  return <span className={`badge ${cls}`}>{String(value)}</span>;
}
