import Nav from "@/components/Nav";

export default function ConsoleLayout({ children }) {
  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="brand">
          <span className="brand-mark">NS</span>
          <div className="brand-copy">
            <strong>Naari Shakti</strong>
            <small>SAFETY OPERATIONS</small>
          </div>
        </div>
        <div className="nav-caption">Workspace</div>
        <Nav />
        <div className="sidebar-foot">
          <div className="access-note">
            <strong>Read-only access</strong>
            <span>Operational records are viewed securely.</span>
          </div>
          <form method="POST" action="/admin/api/logout">
            <button type="submit" className="ghost">
              Sign out
            </button>
          </form>
        </div>
      </aside>
      <div className="workspace">
        <header className="topbar">
          <div className="crumbs">
            <span>Naari Shakti</span>
            <span aria-hidden="true">/</span>
            <strong>Operations</strong>
          </div>
          <span className="access-pill">READ-ONLY CONSOLE</span>
        </header>
        <main className="main">{children}</main>
      </div>
    </div>
  );
}
