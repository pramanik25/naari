"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

const NAV = [
  { href: "/", label: "Dashboard", icon: "grid" },
  { href: "/users", label: "Users", icon: "users" },
  { href: "/devices", label: "Devices & installs", icon: "device" },
  { href: "/incidents", label: "Incidents", icon: "alert" },
  { href: "/checkins", label: "Check-ins", icon: "check" },
  { href: "/helpers", label: "Helpers", icon: "hands" },
];

const PATHS = {
  grid: "M4 4h6v6H4V4zm10 0h6v6h-6V4zM4 14h6v6H4v-6zm10 0h6v6h-6v-6z",
  users:
    "M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8zm7 0a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM2 20c0-3.3 3.1-6 7-6s7 2.7 7 6M17 14c2.8.2 5 2.5 5 6",
  device: "M7 3h10a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2zm3 16h4",
  alert: "M12 3l9 16H3l9-16zm0 6v5m0 3h.01",
  check: "M4 12l5 5L20 6",
  hands: "M12 21c4-2 8-5 8-10a4 4 0 0 0-8-1 4 4 0 0 0-8 1c0 5 4 8 8 10z",
};

function Icon({ name }) {
  return (
    <svg
      width="17"
      height="17"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d={PATHS[name]} />
    </svg>
  );
}

export default function Nav() {
  const pathname = usePathname();
  return (
    <nav className="nav-list" aria-label="Console navigation">
      {NAV.map((item) => {
        const active =
          item.href === "/" ? pathname === "/" : pathname.startsWith(item.href);
        return (
          <Link
            key={item.href}
            href={item.href}
            className={`nav-link${active ? " active" : ""}`}
            aria-current={active ? "page" : undefined}
          >
            <Icon name={item.icon} />
            <span>{item.label}</span>
          </Link>
        );
      })}
    </nav>
  );
}
