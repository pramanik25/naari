import Link from "next/link";

export default function Pager({ page, hasMore, basePath, params = {} }) {
  const makeHref = (p) => {
    const sp = new URLSearchParams(params);
    if (p > 1) sp.set("page", String(p));
    else sp.delete("page");
    const qs = sp.toString();
    return qs ? `${basePath}?${qs}` : basePath;
  };
  if (page <= 1 && !hasMore) return null;
  return (
    <div className="pager">
      {page > 1 ? (
        <Link className="btn ghost" href={makeHref(page - 1)}>
          ← Prev
        </Link>
      ) : (
        <span />
      )}
      <span>Page {page}</span>
      {hasMore && (
        <Link className="btn ghost" href={makeHref(page + 1)}>
          Next →
        </Link>
      )}
    </div>
  );
}
