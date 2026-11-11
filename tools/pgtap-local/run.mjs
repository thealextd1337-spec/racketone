// Dockerfreier Ersatz für `supabase db reset && supabase test db` auf Windows-Entwicklungsrechnern.
// Postgres 17 (embedded-postgres) + minimaler Supabase-Nachbau (supabase-stub.sql). Nur Vorprüfung, maßgeblich ist CI.
//
// Aufruf (Repo-Root, Git Bash):  node tools/pgtap-local/run.mjs [filter1,filter2]   (Filter auf den Dateinamen)
// Umgebung:
//   ADVANTLY_PGTAP_PORT=56170    Port. Windows reserviert u. a. 54522–55121 (netsh … show excludedportrange).
//   ADVANTLY_PGTAP_DATADIR=data  Datenordner relativ zu diesem Skript (parallele Läufe: eigener Port + Ordner).
//   RACES=1 | 181,510            Renntests wie in CI nach pgTAP (alle oder per Präfix), braucht `uv`.
//   ADVANTLY_EXPORT_TEST_DENO=<deno.exe>  für 200 --archive-roundtrip.
//   VERBOSE, NOTICES, LOG_PG     mehr Ausgabe.
// Keine Variable mit PG-Präfix setzen: Die Renntests lehnen jede PG*-Umgebung ab (der Runner bricht dann ab).
// LOCAL_SKIP braucht Storage/pg_cron/pg_net und läuft nur in CI.
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const supaDir = path.resolve(here, '../../supabase');
const filters = (process.argv[2] ?? '').split(',').filter(Boolean);
const dataDir = path.join(here, process.env.ADVANTLY_PGTAP_DATADIR ?? 'data');
const port = Number(process.env.ADVANTLY_PGTAP_PORT ?? 56170);
const db = 'advantly_pgtap';
const url = `postgresql://postgres:postgres@127.0.0.1:${port}/${db}`;
const LOCAL_SKIP = ['110_onboarding_cleanup.test.sql', '200_private_data_exports_platform.test.sql'];

const fail = (msg) => {
  console.error(msg);
  process.exit(2);
};
if (!Number.isInteger(port) || port < 1 || port > 65535) fail(`ADVANTLY_PGTAP_PORT ungültig: ${process.env.ADVANTLY_PGTAP_PORT}`);
const pgEnv = Object.keys(process.env).filter((k) => k.toUpperCase().startsWith('PG'));
if (pgEnv.length) fail(`PG*-Umgebungsvariablen entfernen (Renntests lehnen sie ab): ${pgEnv.join(', ')}`);
if (!fs.existsSync(path.join(here, 'node_modules/embedded-postgres'))) fail('Abhängigkeiten fehlen: cd tools/pgtap-local && npm ci');
const { default: EmbeddedPostgres } = await import('embedded-postgres');
const { default: pg } = await import('pg');

// Wie ci.yml „290 mit verfälschter Planner-Statistik“, „Consent-Gate und D2/D3-Lockrennen prüfen“ und „Export-Rennen …“.
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
  onLog: (m) => process.env.LOG_PG && console.log('PG', String(m).trim()),
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

// embedded-postgres bringt kein pgTAP mit: pgTAP-1.3.3-Dateien aus vendor/ einkopieren (Lizenz: vendor/LICENSE).
const pkgs = path.join(here, 'node_modules/@embedded-postgres');
for (const p of fs.readdirSync(pkgs)) {
  const ext = path.join(pkgs, p, 'native/share/extension');
  for (const f of fs.readdirSync(path.join(here, 'vendor'))) if (!fs.existsSync(path.join(ext, f))) fs.copyFileSync(path.join(here, 'vendor', f), path.join(ext, f));
}

let exitCode = 0;
let started = false;
try {
  if (!fs.existsSync(path.join(dataDir, 'PG_VERSION'))) await server.initialise();
  await server.start();
  started = true;
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
  let selected = 0;
  for (const f of files) {
    const name = path.basename(f);
    // 000-Setup legt die Test-Helfer an und läuft immer mit.
    const setup = name.startsWith('000');
    if (filters.length && !setup && !filters.some((x) => name.includes(x))) continue;
    if (!setup) selected++;
    if (LOCAL_SKIP.includes(name)) {
      console.log(`SKIP ${path.relative(testDir, f)} (nur in CI lauffähig)`);
      continue;
    }
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
    const plan = lines.find((l) => /^1\.\.\d+/.test(l));
    const ok = !err && plan && !lines.some((l) => /^not ok|^# Looks like|^# Failed/.test(l));
    if (!ok) failed++;
    console.log(`${ok ? 'PASS' : 'FAIL'} ${path.relative(testDir, f)} (${plan ?? 'kein Plan'})`);
    if (!ok || process.env.VERBOSE) {
      for (const l of lines) if (process.env.VERBOSE || /^not ok|^#/.test(l)) console.log('   ', l);
      if (err) console.log('    ERROR:', err.message, err.position ? '@' + err.position : '', err.where ?? '');
    }
  }
  if (filters.length && !selected) throw new Error(`Filter ${filters.join(',')} trifft keine Testdatei`);
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
  if (started) await server.stop();
}
process.exit(exitCode);
