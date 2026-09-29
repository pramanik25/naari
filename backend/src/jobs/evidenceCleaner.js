'use strict';

const path = require('path');
const fsp = require('fs/promises');

const MAX_PER_RUN = 500;
/** Evidence on an incident that never ended is still removed after this long. */
const STALE_ACTIVE_HOURS = 7 * 24;

/**
 * Frees storage on the free tier (Neon + Render): deletes evidence files and their rows once an
 * incident has been over for `config.evidenceRetentionHours` (the tracking page stops serving
 * evidence 24 h after the end anyway, so nothing visible is lost). Evidence on incidents that
 * never ended is removed after 7 days. Set EVIDENCE_RETENTION_HOURS=0 to keep everything once
 * storage is upgraded.
 */
function createEvidenceCleaner({ pool, config, log, intervalMs = 60 * 60_000 }) {
  let timer = null;
  let running = null;

  async function sweep() {
    if (!config.evidenceRetentionHours) return { deleted: 0 };
    const { rows } = await pool.query(
      `SELECT e.id, e.file_path FROM evidence e JOIN incidents i ON i.id = e.incident_id
        WHERE (i.status = 'ended' AND i.ended_at < now() - make_interval(hours => $1))
           OR e.received_at < now() - make_interval(hours => $2)
        LIMIT ${MAX_PER_RUN}`,
      [config.evidenceRetentionHours, STALE_ACTIVE_HOURS]);
    let deleted = 0;
    for (const ev of rows) {
      // File first: if the unlink fails the row stays and the next sweep retries.
      const file = path.join(config.evidenceDir, ...String(ev.file_path).split('/'));
      try {
        await fsp.unlink(file);
      } catch (err) {
        if (err.code !== 'ENOENT') {
          log.warn({ err, evidenceId: ev.id }, 'evidence file unlink failed');
          continue;
        }
      }
      await pool.query('DELETE FROM evidence WHERE id = $1', [ev.id]);
      deleted++;
    }
    if (deleted) log.info({ deleted }, 'old evidence deleted (free-tier retention)');
    return { deleted };
  }

  /** One sweep; concurrent calls share the in-flight run. */
  function runOnce() {
    if (!running) running = sweep().finally(() => { running = null; });
    return running;
  }

  return {
    runOnce,
    start() {
      if (timer) return;
      runOnce().catch((err) => log.error({ err }, 'evidence clean failed'));
      timer = setInterval(() => { runOnce().catch((err) => log.error({ err }, 'evidence clean failed')); }, intervalMs);
      timer.unref();
    },
    async stop() {
      if (timer) clearInterval(timer);
      timer = null;
      if (running) await running.catch(() => {});
    },
  };
}

module.exports = { createEvidenceCleaner, STALE_ACTIVE_HOURS };
