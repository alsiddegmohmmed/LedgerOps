import { spawn, spawnSync } from "node:child_process";
import { existsSync } from "node:fs";
import { resolve } from "node:path";

const appDirectory = process.cwd();
const docker = process.env.DOCKER_BIN ?? "docker";
const projectPrefix = "ledgerops-slice1-e2e-";
const generatedPaths = [
  "tests/e2e/.e2e-runtime.json",
  "tests/e2e/.e2e-ports.json",
  "test-results",
  "playwright-report",
];

function run(program, args) {
  const result = spawnSync(program, args, { cwd: appDirectory, env: process.env, encoding: "utf8" });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`${program} ${args.join(" ")} exited with ${result.status}`);
  return result.stdout ?? "";
}

const testRun = spawn(process.env.PNPM_BIN ?? "pnpm", ["exec", "playwright", "test"], {
  cwd: appDirectory,
  env: { ...process.env, E2E_FORCE_PLAYWRIGHT_FAILURE: "true" },
  stdio: "inherit",
});
const exitCode = await new Promise((resolvePromise, reject) => {
  testRun.on("error", reject);
  testRun.on("exit", (code) => resolvePromise(code ?? 1));
});
if (exitCode === 0) throw new Error("Forced Playwright test failure unexpectedly succeeded");

const compose = run(docker, ["compose", "ls", "--all", "--format", "json"]).trim();
if (compose.split("\n").some((line) => line.includes(projectPrefix))) throw new Error("Test-failure cleanup left a Compose project");
if (run(docker, ["ps", "-aq", "--filter", `name=${projectPrefix}`]).trim()) throw new Error("Test-failure cleanup left a container");
if (run(docker, ["volume", "ls", "-q", "--filter", `name=${projectPrefix}`]).trim()) throw new Error("Test-failure cleanup left a volume");
const processes = run("ps", ["-axo", "pid=,command="]).split("\n").filter((line) =>
  (line.includes("bootRun --no-daemon") || line.includes("next/dist/bin/next dev")) && !line.includes("verify-test-failure-cleanup"));
if (processes.length) throw new Error(`Test-failure cleanup left application processes: ${processes.join(" | ")}`);
const remaining = generatedPaths.filter((path) => existsSync(resolve(appDirectory, path)));
if (remaining.length) throw new Error(`Test-failure cleanup left generated state: ${remaining.join(", ")}`);
console.log("Verified intentional Playwright test-failure cleanup");
