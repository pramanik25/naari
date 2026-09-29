const TZ = process.env.ADMIN_TZ || "Asia/Kolkata";

export function fmtDateTime(value) {
  if (!value) return "—";
  const d = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(d.getTime())) return "—";
  return new Intl.DateTimeFormat("en-IN", {
    timeZone: TZ,
    day: "2-digit",
    month: "short",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    hour12: true,
  }).format(d);
}

export function timeAgo(value) {
  if (!value) return "—";
  const d = value instanceof Date ? value : new Date(value);
  const ms = Date.now() - d.getTime();
  if (Number.isNaN(ms)) return "—";
  if (ms < 0) {
    return `in ${duration(-ms)}`;
  }
  return `${duration(ms)} ago`;
}

export function duration(ms) {
  const s = Math.floor(ms / 1000);
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m`;
  const h = Math.floor(m / 60);
  if (h < 48) return `${h}h ${m % 60}m`;
  const days = Math.floor(h / 24);
  return `${days}d`;
}

export function trackUrl(token) {
  const base = process.env.PUBLIC_BASE_URL || "";
  return token && base ? `${base}/t/${token}` : null;
}

export function mapsUrl(lat, lng) {
  if (lat == null || lng == null) return null;
  return `https://www.google.com/maps?q=${lat},${lng}`;
}

export function fmtCoords(lat, lng) {
  if (lat == null || lng == null) return "—";
  return `${Number(lat).toFixed(5)}, ${Number(lng).toFixed(5)}`;
}

export function fmtBytes(n) {
  if (n == null) return "—";
  const kb = 1024;
  if (n < kb) return `${n} B`;
  if (n < kb * kb) return `${(n / kb).toFixed(1)} KB`;
  return `${(n / kb / kb).toFixed(1)} MB`;
}
