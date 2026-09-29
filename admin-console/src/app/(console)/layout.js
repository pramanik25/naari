import Link from "next/link";

const NAV = [
  { href: "/", label: "Dashboard" },
  { href: "/users", label: "Users" },
  { href: "/incidents", label: "Incidents" },
  { href: "/checkins", label: "Check-ins" },
  { href: "/helpers", label: "Helpers" },
];

export default function ConsoleLayout({ children }) {
  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="brand">
          Naari <span>Shakti</span>
        </div>
        {NAV.map((item) => (
          <Link key={item.href} href={item.href} className="nav-link">
            {item.label}
          </Link>
        ))}
        <form method="POST" action="/api/logout">
          <button type="submit" className="ghost" style={{ width: "100%" }}>
            Sign out
          </button>
        </form>
      </aside>
      <main className="main">{children}</main>
    </div>
  );
}
