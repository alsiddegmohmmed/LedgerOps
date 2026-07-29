import { spawn, spawnSync } from "node:child_process";
import { existsSync } from "node:fs";
import { resolve } from "node:path";
import globalTeardown from "./global-teardown.mjs";

const appDirectory = process.cwd();
const repositoryDirectory = resolve(appDirectory, "../..");
const composeFile = resolve(appDirectory, "tests/e2e/compose.slice-1.yml");
const runtimeFile = resolve(appDirectory, "tests/e2e/.e2e-runtime.json");
const portsFile = resolve(appDirectory, "tests/e2e/.e2e-ports.json");
const docker = process.env.DOCKER_BIN ?? "docker";
const projectPrefix = "ledgerops-slice1-e2e-";

function run(program, args, options = {}) {
  const result = spawnSync(program, args, {
    cwd: repositoryDirectory,
    env: process.env,
    encoding: "utf8",
    ...options,
  });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`${program} ${args.join(" ")} exited with ${result.status}\n${result.stderr ?? ""}`);
  return result.stdout ?? "";
}

function parseComposeList(output) {
  if (!output.trim()) return [];
  try {
    const parsed = JSON.parse(output);
    return Array.isArray(parsed) ? parsed : [parsed];
  } catch {
    return output.split("\n").filter(Boolean).map((line) => JSON.parse(line));
  }
}

function removeAbandonedProjects() {
  const projects = parseComposeList(run(docker, ["compose", "ls", "--all", "--format", "json"]));
  for (const project of projects.filter((entry) => entry.Name?.startsWith(projectPrefix))) {
    run(docker, ["compose", "-p", project.Name, "-f", composeFile, "down", "-v", "--remove-orphans"]);
  }
}

function assertNoDockerResources() {
  const projects = parseComposeList(run(docker, ["compose", "ls", "--all", "--format", "json"]));
  const staleProjects = projects.filter((entry) => entry.Name?.startsWith(projectPrefix));
  const containers = run(docker, ["ps", "-aq", "--filter", `name=${projectPrefix}`]).trim();
  const volumes = run(docker, ["volume", "ls", "-q", "--filter", `name=${projectPrefix}`]).trim();
  if (staleProjects.length || containers || volumes) {
    throw new Error(`Startup-failure cleanup left resources: projects=${staleProjects.map((entry) => entry.Name).join(",")} containers=${containers} volumes=${volumes}`);
  }
}

function assertNoApplicationProcesses() {
  const processes = run("ps", ["-axo", "pid=,command="]);
  const leftovers = processes.split("\n").filter((line) =>
    line.includes("bootRun --no-daemon") || line.includes("next/dist/bin/next dev"));
  if (leftovers.length) throw new Error(`E2E application processes remain: ${leftovers.join(" | ")}`);
}

removeAbandonedProjects();
const allocatedPorts = JSON.parse(run(process.execPath, [resolve(appDirectory, "tests/e2e/allocate-ports.mjs")], { cwd: appDirectory }).trim());
const startup = spawn(process.execPath, [resolve(appDirectory, "tests/e2e/test-app.mjs")], {
  cwd: appDirectory,
  env: {
    ...process.env,
    ...Object.fromEntries(Object.entries(allocatedPorts).map(([name, port]) => [`E2E_${name.toUpperCase()}_PORT`, String(port)])),
    E2E_FORCE_STARTUP_FAILURE: "true",
  },
  stdio: "inherit",
});
const exitCode = await new Promise((resolvePromise, reject) => {
  startup.on("error", reject);
  startup.on("exit", (code) => resolvePromise(code ?? 1));
});
if (exitCode === 0) throw new Error("Forced E2E startup failure unexpectedly succeeded");

let teardownError;
try {
  globalTeardown();
} catch (error) {
  teardownError = error;
}
if (teardownError) throw teardownError;
if (existsSync(runtimeFile) || existsSync(portsFile)) throw new Error("Startup-failure cleanup left a runtime or port manifest");
for (const generated of ["playwright-report", "test-results"]) {
  if (existsSync(resolve(appDirectory, generated))) throw new Error(`Startup-failure verification found generated output: ${generated}`);
}
assertNoDockerResources();
assertNoApplicationProcesses();
console.log("Verified forced early-startup failure cleanup");
