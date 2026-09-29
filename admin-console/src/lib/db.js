import { Pool } from "pg";

// One pool per server process (survives Next.js dev hot reloads via globalThis).
const globalForDb = globalThis;

export const pool =
  globalForDb.__nsAdminPool ??
  new Pool({
    connectionString: process.env.DATABASE_URL,
    max: 5,
    idleTimeoutMillis: 30_000,
  });

if (!globalForDb.__nsAdminPool) globalForDb.__nsAdminPool = pool;

export async function query(text, params) {
  const res = await pool.query(text, params);
  return res.rows;
}

export async function queryOne(text, params) {
  const rows = await query(text, params);
  return rows[0] ?? null;
}

// Migration 005 adds device_tokens.push_token; the live DB may not have it yet.
export async function hasPushColumn() {
  if (globalForDb.__nsAdminHasPush === undefined) {
    const row = await queryOne(
      `SELECT 1 AS ok FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'device_tokens' AND column_name = 'push_token'`
    );
    globalForDb.__nsAdminHasPush = Boolean(row);
  }
  return globalForDb.__nsAdminHasPush;
}
