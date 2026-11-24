#!/usr/bin/env node
/**
 * One command for the whole dev stack (ADR-002):
 *
 *   1. build the Spring Boot backend jar — the SAME artifact production runs —
 *      but only when its sources are newer than the jar we already have;
 *   2. boot it on :8080 and wait for /actuator/health;
 *   3. start Vite on :5173 with /api/* proxied to it;
 *   4. shut both down together on Ctrl-C (or when either dies).
 *
 * Requires a JDK (21+): via JAVA_HOME or on PATH. Everything else is Node.
 *
 *   npm run dev
 */
import { spawn, spawnSync } from 'node:child_process'
import { existsSync, readdirSync, statSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const backend = join(root, 'backend')
const isWindows = process.platform === 'win32'

/* ------------------------------------------------------- JDK preflight */

function probeJava() {
  const result = spawnSync('java', ['-version'], { encoding: 'utf8' })
  if (result.error || result.status !== 0) return 0
  const text = `${result.stderr ?? ''}${result.stdout ?? ''}`
  const match = text.match(/version "(\d+)(?:\.(\d+))?/)
  if (!match) return 0
  return match[1] === '1' ? Number(match[2]) : Number(match[1])
}

if (!process.env.JAVA_HOME && probeJava() === 0) {
  console.error('\n[dev] No JDK found. Install Temurin JDK 21, then either:')
  console.error('[dev]   set JAVA_HOME="C:\\Program Files\\Eclipse Adoptium\\jdk-21.x.x.x"')
  console.error('[dev]   or put `java` on PATH, and run `npm run dev` again.\n')
  process.exit(1)
}
const javaMajor = process.env.JAVA_HOME ? 21 : probeJava()
if (javaMajor < 21) {
  console.error(`\n[dev] JDK 21+ required for Spring Boot 4 (found Java ${javaMajor}).\n`)
  process.exit(1)
}

/* ------------------------------------------- 1. backend jar (freshness) */

const targetDir = join(backend, 'target')
function existingJar() {
  if (!existsSync(targetDir)) return null
  const jars = readdirSync(targetDir)
    .filter((f) => f.endsWith('.jar') && !f.includes('-sources') && !f.includes('-javadoc'))
    .sort()
  return jars.length ? join(targetDir, jars[jars.length - 1]) : null
}

function newestSourceTime() {
  let newest = 0
  const walk = (dir) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const full = join(dir, entry.name)
      if (entry.isDirectory()) walk(full)
      else if (/\.(java|json|properties|xml|sql)$/.test(entry.name)) {
        newest = Math.max(newest, statSync(full).mtimeMs)
      }
    }
  }
  for (const dir of [join(backend, 'src'), join(backend, 'pom.xml')]) {
    if (statSync(dir).isDirectory()) walk(dir)
    else newest = Math.max(newest, statSync(dir).mtimeMs)
  }
  return newest
}

const jar = existingJar()
if (jar && statSync(jar).mtimeMs > newestSourceTime()) {
  console.log(`[dev] backend jar up to date → ${jar.split(/[\\/]/).pop()}`)
} else {
  console.log('[dev] building backend jar…')
  const mvn = isWindows ? 'mvnw.cmd' : join(backend, 'mvnw')
  const build = spawnSync(mvn, ['-q', 'package', '-DskipTests'], {
    cwd: backend,
    stdio: 'inherit',
    env: process.env,
    shell: isWindows,
  })
  if (build.status !== 0) {
    console.error('\n[dev] backend build failed — fix the errors above and retry.\n')
    process.exit(build.status ?? 1)
  }
}
const builtJar = existingJar()
if (!builtJar) {
  console.error('[dev] no jar produced in backend/target — unexpected, aborting.\n')
  process.exit(1)
}

/* ------------------------------------------------ 2+3. run both processes */

const children = []
const COLORS = { api: '\x1b[36m', web: '\x1b[35m' } // cyan, magenta

function prefix(child, name) {
  const tag = `${COLORS[name] ?? ''}[${name}]\x1b[0m `
  const pipe = (stream, out) => {
    let buffer = ''
    stream.setEncoding('utf8')
    stream.on('data', (chunk) => {
      buffer += chunk
      const lines = buffer.split(/\r?\n/)
      buffer = lines.pop() ?? ''
      for (const line of lines) out.write(tag + line + '\n')
    })
  }
  pipe(child.stdout, process.stdout)
  pipe(child.stderr, process.stderr)
}

function run(name, command, args, env = process.env) {
  const child = spawn(command, args, {
    cwd: root,
    stdio: ['ignore', 'pipe', 'pipe'],
    env,
    shell: false,
  })
  prefix(child, name)
  children.push(child)
  return child
}

let shuttingDown = false
function shutdown(code = 0) {
  if (shuttingDown) return
  shuttingDown = true
  for (const child of children) {
    if (child.exitCode === null && child.pid) {
      if (isWindows) {
        spawnSync('taskkill', ['/pid', String(child.pid), '/T', '/F'], { stdio: 'ignore' })
      } else {
        child.kill('SIGTERM')
      }
    }
  }
  process.exit(code)
}
process.on('SIGINT', () => shutdown(0))
process.on('SIGTERM', () => shutdown(0))

async function waitForHealth(url, timeoutMs = 60_000) {
  const started = Date.now()
  while (Date.now() - started < timeoutMs) {
    try {
      const res = await fetch(url, { signal: AbortSignal.timeout(1_500) })
      if (res.ok) return true
    } catch {
      /* not up yet */
    }
    await new Promise((resolve) => setTimeout(resolve, 400))
  }
  return false
}

const javaBin = process.env.JAVA_HOME
  ? join(process.env.JAVA_HOME, 'bin', isWindows ? 'java.exe' : 'java')
  : 'java'

// POST /api/reset is fail-closed in application.properties (a deployed build
// must not expose a data wipe). The local dev loop is exactly where reset is
// legitimate, so the API child — and only it — gets the opt-in. An explicit
// TWOFOLD_RESET_ENABLED=false in the environment still wins.
const apiEnv = {
  ...process.env,
  TWOFOLD_RESET_ENABLED: process.env.TWOFOLD_RESET_ENABLED ?? 'true',
}
const api = run('api', javaBin, ['-jar', builtJar], apiEnv)
api.on('exit', (code, signal) => {
  if (!shuttingDown) {
    console.error(`\n[dev] backend exited (${signal ?? code}) — stopping Vite too.`)
    shutdown(code ?? 1)
  }
})

const healthy = await waitForHealth('http://localhost:8080/actuator/health')
if (!healthy) {
  console.error('\n[dev] backend did not become healthy within 60s — see [api] logs above.')
  shutdown(1)
}
console.log('[dev] backend healthy on :8080 — starting Vite on :5173')

const viteBin = join(root, 'node_modules', 'vite', 'bin', 'vite.js')
const web = run('web', process.execPath, [viteBin])
web.on('exit', (code, signal) => {
  if (!shuttingDown) {
    console.error(`\n[dev] Vite exited (${signal ?? code}) — stopping backend too.`)
    shutdown(code ?? 0)
  }
})

console.log('[dev] open http://localhost:5173 — Ctrl-C stops both processes.')
