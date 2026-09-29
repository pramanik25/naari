'use strict';

const fs = require('fs');
const path = require('path');

const MIGRATIONS_DIR = path.join(__dirname, '..', 'migrations');
const LOCK_KEY = 725_401_993; // arbitrary constant: one migrator at a time across instances

/** Applies every migrations/*.sql not yet recorded in schema_migrations, in filename order. */
async function migrate(pool, log) {
  const client = await pool.connect();
  try {
    await client.query('SELECT pg_advisory_lock($1)', [LOCK_KEY]);
    await client.query(`CREATE TABLE IF NOT EXISTS schema_migrations (
      version text PRIMARY KEY,
      applied_at timestamptz NOT NULL DEFAULT now()
    )`);
    const done = new Set((await client.query('SELECT version FROM schema_migrations')).rows.map((r) => r.version));
    const files = fs.readdirSync(MIGRATIONS_DIR).filter((f) => /^\d+_.*\.sql$/.test(f)).sort();
    const applied = [];
    for (const file of files) {
      if (done.has(file)) continue;
      const sql = fs.readFileSync(path.join(MIGRATIONS_DIR, file), 'utf8');
      try {
        await client.query('BEGIN');
        await client.query(sql);
        await client.query('INSERT INTO schema_migrations (version) VALUES ($1)', [file]);
        await client.query('COMMIT');
      } catch (err) {
        await client.query('ROLLBACK').catch(() => {});
        err.message = `migration ${file} failed: ${err.message}`;
        throw err;
      }
      applied.push(file);
      if (log) log.info({ migration: file }, 'migration applied');
    }
    return applied;
  } finally {
    await client.query('SELECT pg_advisory_unlock($1)', [LOCK_KEY]).catch(() => {});
    client.release();
  }
}

module.exports = { migrate };

if (require.main === module) {
  require('dotenv').config({ quiet: true });
  const { createPool } = require('./db');
  const pool = createPool(process.env.DATABASE_URL);
  migrate(pool)
    .then((applied) => { console.log(applied.length ? `applied: ${applied.join(', ')}` : 'up to date'); })
    .catch((err) => { console.error(err.message); process.exitCode = 1; })
    .finally(() => pool.end());
}
