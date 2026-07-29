import { spawn, spawnSync } from "node:child_process";
import { existsSync, unlinkSync, writeFileSync } from "node:fs";
import net from "node:net";
import { resolve } from "node:path";
import { captureProcessIdentity, terminateProcess, terminateProcessSync } from "./process-lifecycle.mjs";

const appDirectory = process.cwd();
const repositoryDirectory = resolve(appDirectory, "../..");
const composeFile = resolve(appDirectory, "tests/e2e/compose.slice-1.yml");
const docker = process.env.DOCKER_BIN ?? "docker";
const projectName = `ledgerops-slice1-e2e-${process.pid}-${Date.now()}`;
const runtimeFile = resolve(appDirectory, "tests/e2e/.e2e-runtime.json");
const keycloakSubject = "11111111-1111-4111-8111-111111111111";
const port = (name) => {
  const value = process.env[`E2E_${name.toUpperCase()}_PORT`];
  if (!value || !/^\d+$/.test(value)) throw new Error(`Missing allocated E2E_${name.toUpperCase()}_PORT`);
  return value;
};
const ports = {
  postgres: port("postgres"),
  redis: port("redis"),
  keycloak: port("keycloak"),
  core: port("core"),
  bff: port("bff"),
};
const keycloakBaseUrl = `http://127.0.0.1:${ports.keycloak}`;
const keycloakIssuer = `${keycloakBaseUrl}/realms/ledgerops`;
const redirectUri = `http://127.0.0.1:${ports.bff}/api/auth/callback`;

function command(program, args, options = {}) {
  return new Promise((resolvePromise, reject) => {
    const child = spawn(program, args, {
      cwd: options.cwd ?? repositoryDirectory,
      env: options.env ?? process.env,
      stdio: options.stdio ?? ["ignore", "pipe", "pipe"],
    });
    let output = "";
    child.stdout?.on("data", (chunk) => { output += chunk; });
    child.stderr?.on("data", (chunk) => { output += chunk; });
    child.on("error", reject);
    child.on("exit", (code, signal) => {
      if (code === 0) resolvePromise(output);
      else reject(new Error(`${program} ${args.join(" ")} exited with ${code ?? signal}\n${output}`));
    });
  });
}

async function waitFor(url, label, timeoutMs = 120000) {
  const deadline = Date.now() + timeoutMs;
  let lastError;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(url);
      if (response.ok) return;
      lastError = new Error(`${label} returned HTTP ${response.status}`);
    } catch (error) {
      lastError = error;
    }
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 1000));
  }
  throw new Error(`Timed out waiting for ${label}: ${lastError?.message ?? "unknown error"}`);
}

async function compose(args) {
  return command(docker, ["compose", "-p", projectName, "-f", composeFile, ...args]);
}

function parseComposeList(output) {
  try {
    const parsed = JSON.parse(output);
    return Array.isArray(parsed) ? parsed : [parsed];
  } catch {
    return output.split("\n").filter(Boolean).map((line) => JSON.parse(line));
  }
}

async function cleanupAbandonedProjects() {
  const projects = parseComposeList(await command(docker, ["compose", "ls", "--all", "--format", "json"]));
  for (const project of projects) {
    if (project.Name?.startsWith("ledgerops-slice1-e2e-")) {
      await command(docker, ["compose", "-p", project.Name, "-f", composeFile, "down", "-v", "--remove-orphans"]);
    }
  }
  const remaining = parseComposeList(await command(docker, ["compose", "ls", "--all", "--format", "json"]));
  const stale = remaining.filter((project) => project.Name?.startsWith("ledgerops-slice1-e2e-"));
  if (stale.length) throw new Error(`Abandoned E2E Compose projects remain: ${stale.map((project) => project.Name).join(", ")}`);
  const containers = await command(docker, ["ps", "-aq", "--filter", "name=ledgerops-slice1-e2e-"]);
  const volumes = await command(docker, ["volume", "ls", "-q", "--filter", "name=ledgerops-slice1-e2e-"]);
  if (containers.trim() || volumes.trim()) throw new Error(`Abandoned E2E resources remain: containers=${containers} volumes=${volumes}`);
}

async function assertNoProjectResources(project) {
  const projects = parseComposeList(await command(docker, ["compose", "ls", "--all", "--format", "json"]));
  if (projects.some((entry) => entry.Name === project)) throw new Error(`E2E Compose project remains: ${project}`);
  const containers = await command(docker, ["ps", "-aq", "--filter", `label=com.docker.compose.project=${project}`]);
  const volumes = await command(docker, ["volume", "ls", "-q", "--filter", `label=com.docker.compose.project=${project}`]);
  if (containers.trim() || volumes.trim()) throw new Error(`E2E resources remain: containers=${containers} volumes=${volumes}`);
}

async function assertPortsAvailable() {
  for (const [name, value] of Object.entries(ports)) {
    const available = await new Promise((resolvePromise) => {
      const socket = net.createConnection({ host: "127.0.0.1", port: Number(value) });
      socket.once("connect", () => { socket.destroy(); resolvePromise(false); });
      socket.once("error", () => resolvePromise(true));
    });
    if (!available) throw new Error(`Allocated E2E ${name} port ${value} is already in use`);
  }
}

async function configureKeycloakClient() {
  const tokenResponse = await fetch(`${keycloakBaseUrl}/realms/master/protocol/openid-connect/token`, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "password", client_id: "admin-cli", username: "admin", password: "admin" }),
  });
  if (!tokenResponse.ok) throw new Error(`Keycloak admin token failed: ${await tokenResponse.text()}`);
  const adminToken = (await tokenResponse.json()).access_token;
  const clientsResponse = await fetch(`${keycloakBaseUrl}/admin/realms/ledgerops/clients?clientId=operations-web`, {
    headers: { authorization: `Bearer ${adminToken}` },
  });
  if (!clientsResponse.ok) throw new Error(`Keycloak client lookup failed: ${await clientsResponse.text()}`);
  const clients = await clientsResponse.json();
  if (clients.length !== 1) throw new Error("Expected exactly one Operations Web Keycloak client");
  const client = clients[0];
  const update = await fetch(`${keycloakBaseUrl}/admin/realms/ledgerops/clients/${client.id}`, {
    method: "PUT",
    headers: { authorization: `Bearer ${adminToken}`, "content-type": "application/json" },
    body: JSON.stringify({ ...client, redirectUris: [redirectUri], webOrigins: [`http://127.0.0.1:${ports.bff}`] }),
  });
  if (!update.ok) throw new Error(`Keycloak client update failed: ${await update.text()}`);
}

async function verifyTopology() {
  const rows = parseComposeList(await compose(["ps", "--format", "json"]));
  const services = new Set(rows.map((row) => row.Service));
  for (const expected of ["postgres", "redis", "keycloak"]) {
    if (!services.has(expected)) throw new Error(`Expected isolated Compose service ${expected}`);
  }
}

async function seedWithPsql() {
  const sql = `
INSERT INTO tenancy.tenants
  (id, name, default_currency, default_locale, status, version, created_at, updated_at)
VALUES
  ('00000000-0000-4000-8000-000000000001', 'Playwright Tenant', 'USD', 'en', 'ACTIVE', 0, now(), now()),
  ('00000000-0000-4000-8000-000000000002', 'Out of Scope Tenant', 'USD', 'en', 'ACTIVE', 0, now(), now());
INSERT INTO merchant.merchants
  (id, tenant_id, name, status, version, created_at, updated_at)
VALUES
  ('00000000-0000-4000-8000-000000000011', '00000000-0000-4000-8000-000000000001', 'Playwright Merchant', 'ACTIVE', 0, now(), now()),
  ('00000000-0000-4000-8000-000000000012', '00000000-0000-4000-8000-000000000002', 'Out of Scope Merchant', 'ACTIVE', 0, now(), now());
INSERT INTO identity.application_users
  (id, issuer, subject, status, version, created_at, updated_at)
VALUES ('10000000-0000-4000-8000-000000000001', '${keycloakIssuer}', '${keycloakSubject}', 'ACTIVE', 0, now(), now());
INSERT INTO identity.tenant_memberships
  (id, application_user_id, tenant_id, status, created_at, updated_at)
VALUES ('20000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000001', 'ACTIVE', now(), now());
INSERT INTO identity.tenant_role_assignments
  (id, membership_id, role, scope_mode)
VALUES ('30000000-0000-4000-8000-000000000001', '20000000-0000-4000-8000-000000000001', 'TENANT_ADMIN', 'TENANT_WIDE');
`;
  await new Promise((resolvePromise, reject) => {
    const child = spawn(docker, ["compose", "-p", projectName, "-f", composeFile, "exec", "-T", "postgres", "psql", "-v", "ON_ERROR_STOP=1", "-U", "ledgerops", "-d", "ledgerops"], {
      cwd: repositoryDirectory,
      stdio: ["pipe", "inherit", "inherit"],
    });
    child.stdin.end(sql);
    child.on("error", reject);
    child.on("exit", (code) => code === 0 ? resolvePromise() : reject(new Error(`psql exited with ${code}`)));
  });
}

async function verifySeedWithPsql() {
  const sql = `
SELECT
  EXISTS (SELECT 1 FROM tenancy.tenants WHERE id = '00000000-0000-4000-8000-000000000002')
  AND EXISTS (SELECT 1 FROM merchant.merchants WHERE id = '00000000-0000-4000-8000-000000000012' AND tenant_id = '00000000-0000-4000-8000-000000000002')
  AND NOT EXISTS (SELECT 1 FROM identity.tenant_memberships WHERE application_user_id = '10000000-0000-4000-8000-000000000001' AND tenant_id = '00000000-0000-4000-8000-000000000002')
  AND NOT EXISTS (SELECT 1 FROM tenancy.tenants WHERE id = '00000000-0000-4000-8000-000000000099');
`;
  const output = await new Promise((resolvePromise, reject) => {
    const child = spawn(docker, ["compose", "-p", projectName, "-f", composeFile, "exec", "-T", "postgres", "psql", "-tA", "-v", "ON_ERROR_STOP=1", "-U", "ledgerops", "-d", "ledgerops"], {
      cwd: repositoryDirectory,
      stdio: ["pipe", "pipe", "pipe"],
    });
    let stdout = "";
    let stderr = "";
    child.stdout.on("data", (chunk) => { stdout += chunk; });
    child.stderr.on("data", (chunk) => { stderr += chunk; });
    child.stdin.end(sql);
    child.on("error", reject);
    child.on("exit", (code) => code === 0 ? resolvePromise(stdout.trim()) : reject(new Error(`psql verification exited with ${code}: ${stderr}`)));
  });
  if (output !== "t") throw new Error("PostgreSQL seed verification did not prove the existing unauthorized Tenant, Merchant, missing membership, and nonexistent UUID cases");
}

let core;
let next;
let coreIdentity;
let nextIdentity;
let stopping = false;
let cleanupComplete = false;
function writeRuntime() {
  writeFileSync(runtimeFile, JSON.stringify({ projectName, core: coreIdentity, next: nextIdentity }));
}
function cleanupSynchronously() {
  terminateProcessSync(nextIdentity);
  terminateProcessSync(coreIdentity);
  const result = spawnSync(docker, ["compose", "-p", projectName, "-f", composeFile, "down", "-v", "--remove-orphans"], {
    cwd: repositoryDirectory,
    env: process.env,
    stdio: "inherit",
  });
  if (result.status !== 0) {
    process.stderr.write(`Synchronous E2E cleanup failed with ${result.status}\n`);
    process.exitCode = 1;
  }
}
process.on("exit", () => {
  if (!cleanupComplete) cleanupSynchronously();
});
async function stop() {
  if (stopping) return;
  stopping = true;
  await terminateProcess(nextIdentity);
  await terminateProcess(coreIdentity);
  await compose(["down", "-v", "--remove-orphans"]);
  let containers = "";
  let volumes = "";
  for (let attempt = 0; attempt < 30; attempt += 1) {
    containers = await command(docker, ["ps", "-aq", "--filter", `label=com.docker.compose.project=${projectName}`]);
    volumes = await command(docker, ["volume", "ls", "-q", "--filter", `label=com.docker.compose.project=${projectName}`]);
    if (!containers.trim() && !volumes.trim()) break;
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 1000));
  }
  if (containers.trim() || volumes.trim()) throw new Error(`E2E cleanup left resources: containers=${containers} volumes=${volumes}`);
  await assertNoProjectResources(projectName);
  try { unlinkSync(resolve(appDirectory, "tests/e2e/.e2e-ports.json")); } catch (error) {
    if (error.code !== "ENOENT") throw error;
  }
  try { unlinkSync(runtimeFile); } catch (error) {
    if (error.code !== "ENOENT") throw error;
  }
  if (existsSync(resolve(appDirectory, "tests/e2e/.e2e-ports.json")) || existsSync(runtimeFile)) {
    throw new Error("E2E teardown left runtime or port manifests");
  }
  cleanupComplete = true;
}

process.on("SIGINT", () => void stop().then(() => process.exit(130)).catch((error) => { console.error(error); process.exit(1); }));
process.on("SIGTERM", () => void stop().then(() => process.exit(143)).catch((error) => { console.error(error); process.exit(1); }));

try {
  const startupCommand = process.env.E2E_FORCE_STARTUP_FAILURE === "true"
    ? `ledgerops-slice1-invalid-docker-${process.pid}`
    : docker;
  await command(startupCommand, ["version"]);
  writeRuntime();
  await cleanupAbandonedProjects();
  await assertPortsAvailable();
  await compose(["up", "-d", "--wait", "--wait-timeout", "120"]);
  await verifyTopology();
  await waitFor(`${keycloakIssuer}/.well-known/openid-configuration`, "Keycloak");
  await configureKeycloakClient();
  core = spawn("./gradlew", ["bootRun", "--no-daemon"], {
    cwd: repositoryDirectory,
    detached: true,
    stdio: "inherit",
    env: {
      ...process.env,
      SERVER_PORT: ports.core,
      SPRING_DATASOURCE_URL: `jdbc:postgresql://127.0.0.1:${ports.postgres}/ledgerops`,
      SPRING_DATASOURCE_USERNAME: "ledgerops",
      SPRING_DATASOURCE_PASSWORD: "local-ledgerops",
      KAFKA_BOOTSTRAP_SERVERS: "127.0.0.1:9093",
      LEDGEROPS_OBSERVABILITY_KAFKA_LAG_ENABLED: "false",
      LEDGEROPS_MESSAGING_PUBLISHER_ENABLED: "false",
      LEDGEROPS_PROVIDER_COMMAND_CONSUMER_ENABLED: "false",
      LEDGEROPS_PAYMENT_RESULT_CONSUMER_ENABLED: "false",
      LEDGEROPS_PAYMENT_RETRY_CONSUMER_ENABLED: "false",
      LEDGEROPS_PROVIDER_EXECUTION_ENABLED: "false",
      LEDGEROPS_PROVIDER_WEBHOOK_ENABLED: "false",
      LEDGEROPS_PROVIDER_WEBHOOK_PROCESSING_ENABLED: "false",
      LEDGEROPS_IDENTITY_JWT_ISSUER: keycloakIssuer,
      LEDGEROPS_IDENTITY_JWT_AUDIENCE: "operations-web",
      LEDGEROPS_IDENTITY_JWT_JWK_SET_URI: `${keycloakIssuer}/protocol/openid-connect/certs`,
    },
  });
  coreIdentity = captureProcessIdentity(core.pid, "bootRun --no-daemon");
  writeRuntime();
  await waitFor(`http://127.0.0.1:${ports.core}/actuator/health`, "LedgerOps Core");
  await seedWithPsql();
  await verifySeedWithPsql();
  next = spawn(process.execPath, ["node_modules/next/dist/bin/next", "dev", "-p", ports.bff], {
    cwd: appDirectory,
    detached: true,
    stdio: "inherit",
    env: {
      ...process.env,
      KEYCLOAK_ISSUER: keycloakIssuer,
      KEYCLOAK_CLIENT_ID: "operations-web",
      KEYCLOAK_REDIRECT_URI: redirectUri,
      REDIS_URL: `redis://127.0.0.1:${ports.redis}`,
      CORE_BASE_URL: `http://127.0.0.1:${ports.core}`,
      BFF_ORIGIN: `http://127.0.0.1:${ports.bff}`,
    },
  });
  nextIdentity = captureProcessIdentity(next.pid, "next/dist/bin/next");
  writeRuntime();
  await waitFor(`http://127.0.0.1:${ports.bff}`, "Operations Web");
  const exitCode = await new Promise((resolvePromise) => next.on("exit", (code) => resolvePromise(code ?? 1)));
  await stop();
  process.exit(exitCode);
} catch (error) {
  console.error(error);
  await stop();
  process.exit(1);
}
