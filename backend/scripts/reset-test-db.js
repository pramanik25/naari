'use strict';

// Recreates the public schema of the TEST database and applies all migrations.
// Refuses to run against a database whose name does not end in "_test".

const { createPool } = require('../src/db');
const { migrate } = require('../src/migrate');

const url = process.env.TEST_DATABASE_URL || 'postgres://naari@127.0.0.1:55432/naarishakti_test';

(async () => {
  const dbName = new URL(url).pathname.replace(/^\//, '');
  if (!dbName.endsWith('_test')) throw new Error(`refusing to reset non-test database "${dbName}"`);
  const pool = createPool(url);
  try {
    await pool.query('DROP SCHEMA IF EXISTS public CASCADE');
    await pool.query('CREATE SCHEMA public');
    const applied = await migrate(pool);
    console.log(`test database ${dbName} reset; migrations: ${applied.join(', ')}`);
  } finally {
    await pool.end();
  }
})().catch((err) => {
  console.error(err.message);
  process.exit(1);
});
