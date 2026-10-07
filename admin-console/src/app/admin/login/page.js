export const dynamic = "force-dynamic";

export default async function LoginPage({ searchParams }) {
  const sp = await searchParams;
  const failed = sp?.error === "1";
  return (
    <div className="login-wrap">
      <form className="login-card" method="POST" action="/api/login">
        <h1>
          Naari <span style={{ color: "var(--accent)" }}>Shakti</span> Admin
        </h1>
        {failed && <p className="error">Wrong password. Try again.</p>}
        <label htmlFor="password" className="dim">
          Admin password
        </label>
        <input
          id="password"
          name="password"
          type="password"
          autoFocus
          autoComplete="current-password"
          required
        />
        <button type="submit">Sign in</button>
      </form>
    </div>
  );
}
