// Dockerfreier Ersatz für `supabase db reset && supabase test db` auf Windows-Entwicklungsrechnern.
// Postgres 17 (embedded-postgres) + minimaler Supabase-Nachbau (supabase-stub.sql). Nur Vorprüfung, maßgeblich ist CI.
//
// Aufruf (Repo-Root):  node tools/pgtap-local/run.mjs [filter1,filter2]
// Umgebung:
//   PORT=56170        Port des Servers. Windows reserviert u. a. 54522–55121 (netsh … show excludedportrange).
//   DATADIR=data      Datenordner relativ zu diesem Skript (parallele Läufe: eigener PORT + DATADIR).
//   RACES=1 | 181,510 Renntests wie im CI-Schritt nach pgTAP (alle oder per Präfix).
//   ADVANTLY_EXPORT_TEST_DENO=<deno.exe>  für 200 --archive-roundtrip.
//   VERBOSE, NOTICES, PGLOG  mehr Ausgabe.
// Nie eine eigene Variable mit PG-Präfix setzen: Die Renntests lehnen jede PG*-Umgebung ab.
// Lokal erwartbar rot: 110 (pg_cron) und 200_private_data_exports_platform (Storage/pg_cron/pg_net).
import EmbeddedPostgres from 'embedded-postgres';
import pg from 'pg';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const supaDir = path.resolve(here, '../../supabase');
const filters = (process.argv[2] ?? '').split(',').filter(Boolean);
const dataDir = path.join(here, process.env.DATADIR ?? 'data');
const port = Number(process.env.PORT ?? 56170);
const db = 'advantly_pgtap';
const url = `postgresql://postgres:postgres@127.0.0.1:${port}/${db}`;

// Wie ci.yml „Consent-Gate und D2/D3-Lockrennen prüfen“ und „Export-Rennen …“.
const RACES = [
  ['290_staged_account_deletion_zero_stats.py', 'ADVANTLY_LIFECYCLE_TEST_DB_URL'],
  ['181_cloud_consent_gate_concurrency.py', 'ADVANTLY_CONSENT_TEST_DB_URL'],
  ['210_cloud_lifecycle_redactions_concurrency.py', 'ADVANTLY_LIFECYCLE_TEST_DB_URL'],
  ['220_redaction_write_guards_concurrency.py', 'ADVANTLY_LIFECYCLE_TEST_DB_URL'],
  ['250_late_redaction_manifest_concurrency.py', 'ADVANTLY_LIFECYCLE_TEST_DB_URL'],
  ['290_staged_account_deletion_concurrency.py', 'ADVANTLY_LIFECYCLE_TEST_DB_URL'],
  ['300_account_support_concurrency.py', 'ADVANTLY_ACCOUNT_TEST_DB_URL'],
  ['510_einladungen_concurrency.py', 'ADVANTLY_INVITES_TEST_DB_URL'],
  ['200_private_data_exports_concurrency.py', 'ADVANTLY_EXPORT_TEST_DB_URL', '--archive-roundtrip'],
];

const server = new EmbeddedPostgres({
  databaseDir: dataDir,
  user: 'postgres',
  password: 'postgres',
  port,
  persistent: true,
  onLog: (m) => process.env.PGLOG && console.log('PG', String(m).trim()),
  onError: (m) => console.log('PGERR', String(m).trim()),
});
const conn = (database) => {
  const c = new pg.Client({ host: '127.0.0.1', port, user: 'postgres', password: 'postgres', database });
  c.on('error', () => {});
  if (process.env.NOTICES) c.on('notice', (n) => console.log('  NOTICE', n.message));
  return c;
};

// Async starten: spawnSync blockiert die Event-Loop, die Postgres-Logpipe läuft voll und Backends hängen.
const race = (file, key, ...extra) =>
  new Promise((resolve, reject) => {
    const child = spawn('uv', ['run', path.join(supaDir, 'tests/database', file), '--confirm-disposable', ...extra], {
      env: { ...process.env, PYTHONUNBUFFERED: '1', PYTHONIOENCODING: 'utf-8', [key]: url },
      stdio: 'inherit',
    });
    child.on('error', reject);
    child.on('exit', (code) => resolve(code ?? 2));
  });

// embedded-postgres bringt kein pgTAP mit: generierte pgTAP-1.3.3-Dateien (BSD-Lizenz) aus vendor/ einkopieren.
const pkgs = path.join(here, 'node_modules/@embedded-postgres');
for (const p of fs.readdirSync(pkgs)) {
  const ext = path.join(pkgs, p, 'native/share/extension');
  for (const f of fs.readdirSync(path.join(here, 'vendor'))) if (!fs.existsSync(path.join(ext, f))) fs.copyFileSync(path.join(here, 'vendor', f), path.join(ext, f));
}

let exitCode = 0;
if (!fs.existsSync(path.join(dataDir, 'PG_VERSION'))) await server.initialise();
await server.start();
try {
  const admin = conn('postgres');
  await admin.connect();
  await admin.query(`drop database if exists ${db} with (force)`);
  await admin.query(`create database ${db} with template template0 encoding 'UTF8' locale 'C'`);
  await admin.end();

  const c = conn(db);
  await c.connect();
  await c.query(fs.readFileSync(path.join(here, 'supabase-stub.sql'), 'utf8'));
  await c.query(`alter database ${db} set search_path = "$user", public, extensions`);
  const migDir = path.join(supaDir, 'migrations');
  for (const f of fs.readdirSync(migDir).filter((f) => f.endsWith('.sql')).sort()) {
    try {
      await c.query(fs.readFileSync(path.join(migDir, f), 'utf8'));
    } catch (e) {
      console.log(`migration FAIL ${f}: ${e.message} ${e.position ? '@' + e.position : ''} ${e.where ?? ''}`);
      throw e;
    }
  }
  console.log('Migrationen ok');
  await c.query('create extension if not exists pgtap with schema extensions');
  await c.end();

  const testDir = path.join(supaDir, 'tests');
  const files = [];
  const walk = (d) => {
    for (const f of fs.readdirSync(d)) {
      const p = path.join(d, f);
      if (fs.statSync(p).isDirectory()) walk(p);
      else if (f.endsWith('.sql')) files.push(p);
    }
  };
  walk(testDir);
  files.sort();
  let failed = 0;
  for (const f of files) {
    // 000-Setup legt die Test-Helfer an und läuft immer mit.
    if (filters.length && !filters.some((x) => f.includes(x)) && !path.basename(f).startsWith('000')) continue;
    const t = conn(db);
    await t.connect();
    const lines = [];
    let err = null;
    try {
      // psql schickt Anweisungen einzeln (Autocommit). Ein Multi-Statement-Query liefe komplett in einer impliziten
      // Transaktion, daher den Teil vor `begin;` getrennt ausführen.
      const text = fs.readFileSync(f, 'utf8');
      const m = text.match(/^begin;\s*$/im);
      for (const chunk of m ? [text.slice(0, m.index), text.slice(m.index)] : [text]) {
        if (!chunk.trim()) continue;
        const res = await t.query(chunk);
        for (const r of [].concat(res))
          for (const row of r.rows ?? []) for (const v of Object.values(row)) if (typeof v === 'string') lines.push(...v.split('\n'));
      }
    } catch (e) {
      err = e;
    }
    await t.end().catch(() => {});
    const ok = !err && !lines.some((l) => /^not ok|^# Looks like|^# Failed/.test(l));
    if (!ok) failed++;
    console.log(`${ok ? 'PASS' : 'FAIL'} ${path.relative(testDir, f)} (${lines.find((l) => /^1\.\.\d+/.test(l)) ?? 'no plan'})`);
    if (!ok || process.env.VERBOSE) {
      for (const l of lines) if (process.env.VERBOSE || /^not ok|^#/.test(l)) console.log('   ', l);
      if (err) console.log('    ERROR:', err.message, err.position ? '@' + err.position : '', err.where ?? '');
    }
  }
  console.log(failed ? `\n${failed} Testdatei(en) fehlgeschlagen` : '\nAlle pgTAP-Tests grün');
  exitCode = failed ? 1 : 0;

  if (process.env.RACES) {
    const only = process.env.RACES === '1' ? null : process.env.RACES.split(',');
    for (const [file, key, ...extra] of RACES.filter(([f]) => !only || only.some((o) => f.startsWith(o)))) {
      const t0 = Date.now();
      console.log('START', file, extra.join(' '));
      const status = await race(file, key, ...extra);
      console.log('RACE', file, 'exit', status, `${Date.now() - t0} ms`);
      exitCode ||= status;
    }
  }
} catch (e) {
  console.error(e.message);
  exitCode = 2;
} finally {
  await server.stop();
}
process.exit(exitCode);
