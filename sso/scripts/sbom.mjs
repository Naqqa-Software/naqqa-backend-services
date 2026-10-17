#!/usr/bin/env node
import { execFileSync } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync, mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const ssoDir = resolve(here, '..');
const authDir = resolve(ssoDir, '..', 'auth');
const outDir = join(ssoDir, 'dist-sbom');
const RUNTIME = new Set(['compile', 'runtime', 'provided']);

function pomCoords(dir) {
  const xml = readFileSync(join(dir, 'pom.xml'), 'utf8').replace(/<parent>[\s\S]*?<\/parent>/, '');
  const pick = (t) => (xml.match(new RegExp(`<${t}>([^<]+)</${t}>`)) || [])[1];
  const desc = (xml.match(/<description>([^<]*)<\/description>/) || [])[1];
  return { group: pick('groupId') || 'com.naqqa', artifact: pick('artifactId'), version: pick('version'), name: pick('name'), description: desc };
}

function parseDependencyList(text) {
  const out = [];
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('The following') || line.startsWith('none')) continue;
    const m = line.match(/^([^:\s]+):([^:\s]+):([^:\s]+):(?:([^:\s]+):)?([^:\s]+):([a-z]+)(?:\s+--\s+module.*)?(.*)$/);
    if (!m) continue;
    const [, group, artifact, type, classifier, version, scope, rest] = m;
    const lic = rest && rest.match(/\(([^)]*)\)/);
    out.push({ group, artifact, type, classifier, version, scope, licenses: lic ? lic[1] : null });
  }
  return out;
}

function runList(dir, outFile) {
  const env = { ...process.env };
  if (!env.JAVA_HOME && process.env.HOME) {
    const cand = join(process.env.HOME, '.local/jdk/jdk-17.0.20.1+1');
    if (existsSync(cand)) env.JAVA_HOME = cand;
  }
  execFileSync(join(dir, 'mvnw'), ['-q', '-B', 'dependency:list', `-DoutputFile=${outFile}`, '-DappendOutput=false'], { cwd: dir, env, stdio: ['ignore', 'inherit', 'inherit'] });
  return readFileSync(outFile, 'utf8');
}

function findJar(dir, c) {
  const name = `${c.artifact}-${c.version}.jar`;
  const roots = [join(dir, 'target')];
  try {
    for (const d of readdirSync(dir)) if (d.startsWith('target')) roots.push(join(dir, d));
  } catch { }
  for (const r of roots) {
    const p = join(r, name);
    if (existsSync(p)) return p;
  }
  return null;
}

function sha256(file) {
  return createHash('sha256').update(readFileSync(file)).digest('hex');
}

function purl(d) {
  const q = [];
  if (d.type && d.type !== 'jar') q.push(`type=${d.type}`);
  if (d.classifier) q.push(`classifier=${d.classifier}`);
  return `pkg:maven/${d.group}/${d.artifact}@${d.version}${q.length ? '?' + q.join('&') : ''}`;
}

function licenseEntries(s) {
  if (!s) return undefined;
  return s.split(/\s*[;,]\s*/).filter(Boolean).map((name) => (/^[A-Za-z0-9.+-]+$/.test(name) ? { license: { id: name } } : { license: { name } }));
}

function build(dir, coords, deps, jar) {
  const mainRef = purl({ ...coords, group: coords.group, type: 'jar' });
  const main = {
    type: 'library', 'bom-ref': mainRef, group: coords.group, name: coords.artifact, version: coords.version, purl: mainRef,
    ...(coords.description ? { description: coords.description } : {}),
    ...(jar ? { hashes: [{ alg: 'SHA-256', content: sha256(jar) }] } : {}),
  };
  const components = deps.map((d) => {
    const ref = purl(d);
    const lic = licenseEntries(d.licenses);
    return { type: 'library', 'bom-ref': ref, group: d.group, name: d.artifact, version: d.version, scope: RUNTIME.has(d.scope) && d.scope !== 'provided' ? 'required' : 'excluded', purl: ref, ...(lic ? { licenses: lic } : {}), properties: [{ name: 'maven:scope', value: d.scope }] };
  });
  return {
    bomFormat: 'CycloneDX', specVersion: '1.5', serialNumber: `urn:uuid:${randomUUID()}`, version: 1,
    metadata: { timestamp: new Date().toISOString(), tools: { components: [{ type: 'application', name: 'naqqa-sbom', version: '1.0.0' }] }, component: main },
    components,
    dependencies: [{ ref: mainRef, dependsOn: components.filter((c) => c.scope === 'required').map((c) => c['bom-ref']) }, ...components.map((c) => ({ ref: c['bom-ref'], dependsOn: [] }))],
  };
}

mkdirSync(outDir, { recursive: true });
const tmp = mkdtempSync(join(tmpdir(), 'naqqa-sbom-'));
let failed = false;
for (const [dir, file] of [[ssoDir, 'naqqa-sso.cdx.json'], [authDir, 'naqqa-auth.cdx.json']]) {
  const coords = pomCoords(dir);
  const deps = parseDependencyList(runList(dir, join(tmp, `${coords.artifact}.txt`)));
  const jar = findJar(dir, coords);
  const bom = build(dir, coords, deps, jar);
  writeFileSync(join(outDir, file), JSON.stringify(bom, null, 2) + '\n');
  const runtime = deps.filter((d) => RUNTIME.has(d.scope));
  console.log(`${file}: ${deps.length} components (${runtime.length} runtime), jar sha256 ${jar ? 'yes' : 'missing (build the jar first)'}`);
  if (coords.artifact === 'naqqa-sso' && runtime.length > 0) {
    console.error(`FAIL: naqqa-sso must have zero runtime components, found: ${runtime.map(purl).join(', ')}`);
    failed = true;
  }
}
process.exit(failed ? 1 : 0);
