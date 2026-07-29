import { existsSync, readdirSync, readFileSync, rmSync, statSync, unlinkSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { resolve } from "node:path";
import { terminateProcessSync } from "./process-lifecycle.mjs";

const appDirectory = process.cwd();
const repositoryDirectory = resolve(appDirectory, "../..");
const composeFile = resolve(appDirectory, "tests/e2e/compose.slice-1.yml");
const runtimeFile = resolve(appDirectory, "tests/e2e/.e2e-runtime.json");
const portsFile = resolve(appDirectory, "tests/e2e/.e2e-ports.json");
const docker = process.env.DOCKER_BIN ?? "docker";
const playwrightOutputPaths = [
  resolve(appDirectory, "test-results"),
  resolve(appDirectory, "playwright-report"),
];

function run(program, args) {
  const result = spawnSync(program, args, { cwd: repositoryDirectory, env: process.env, stdio: "inherit" });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`${program} ${args.join(" ")} exited with ${result.status}`);
}

function outputSummary(directory, entries = [], depth = 0) {
  if (entries.length >= 40 || depth > 3 || !existsSync(directory)) return entries;
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const path = resolve(directory, entry.name);
    entries.push(`${path} (${entry.isDirectory() ? "directory" : `${statSync(path).size} bytes`})`);
    if (entry.isDirectory()) outputSummary(path, entries, depth + 1);
    if (entries.length >= 40) break;
  }
  return entries;
}

function cleanupPlaywrightOutput() {
  const existing = playwrightOutputPaths.flatMap((path) => outputSummary(path));
  if (existing.length) console.error(`Cleaning Playwright output (${existing.length} entries):\n${existing.join("\n")}`);
  for (const path of playwrightOutputPaths) rmSync(path, { recursive: true, force: true });
  const remaining = playwrightOutputPaths.filter((path) => existsSync(path));
  if (remaining.length) throw new Error(`E2E teardown left Playwright output: ${remaining.join(", ")}`);
}

process.once("exit", () => {
  try {
    cleanupPlaywrightOutput();
  } catch (error) {
    console.error(`E2E exit cleanup failed: ${error.message}`);
    process.exitCode = 1;
  }
});

export default function globalTeardown() {
  let runtime;
  let failure;
  try {
    try { runtime = JSON.parse(readFileSync(runtimeFile, "utf8")); } catch (error) {
      if (error.code !== "ENOENT") throw error;
    }
    if (runtime) {
      terminateProcessSync(runtime.next);
      terminateProcessSync(runtime.core);
      run(docker, ["compose", "-p", runtime.projectName, "-f", composeFile, "down", "-v", "--remove-orphans"]);
      let containers = "";
      let volumes = "";
      for (let attempt = 0; attempt < 30; attempt += 1) {
        containers = spawnSync(docker, ["ps", "-aq", "--filter", `label=com.docker.compose.project=${runtime.projectName}`], { encoding: "utf8" }).stdout.trim();
        volumes = spawnSync(docker, ["volume", "ls", "-q", "--filter", `label=com.docker.compose.project=${runtime.projectName}`], { encoding: "utf8" }).stdout.trim();
        if (!containers && !volumes) break;
        spawnSync("sleep", ["1"]);
      }
      if (containers || volumes) throw new Error(`E2E cleanup left resources: containers=${containers} volumes=${volumes}`);
    }
  } catch (error) {
    failure = error;
  } finally {
    for (const file of [runtimeFile, portsFile]) {
      try { unlinkSync(file); } catch (error) { if (error.code !== "ENOENT" && !failure) failure = error; }
      if (existsSync(file) && !failure) failure = new Error(`E2E teardown left generated state: ${file}`);
    }
    try {
      cleanupPlaywrightOutput();
    } catch (error) {
      failure = failure ? new Error(`${failure.message}; ${error.message}`) : error;
    }
  }
  if (failure) throw failure;
}
