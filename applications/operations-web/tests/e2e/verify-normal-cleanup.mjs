import { spawnSync } from "node:child_process";
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

const compose = run(docker, ["compose", "ls", "--all", "--format", "json"]).trim();
if (compose.split("\n").some((line) => line.includes(projectPrefix))) throw new Error("Normal-run cleanup left a Compose project");
if (run(docker, ["ps", "-aq", "--filter", `name=${projectPrefix}`]).trim()) throw new Error("Normal-run cleanup left a container");
if (run(docker, ["volume", "ls", "-q", "--filter", `name=${projectPrefix}`]).trim()) throw new Error("Normal-run cleanup left a volume");
const processes = run("ps", ["-axo", "pid=,command="]).split("\n").filter((line) =>
  (line.includes("bootRun --no-daemon") || line.includes("next/dist/bin/next dev")) && !line.includes("verify-normal-cleanup"));
if (processes.length) throw new Error(`Normal-run cleanup left application processes: ${processes.join(" | ")}`);
const remaining = generatedPaths.filter((path) => existsSync(resolve(appDirectory, path)));
if (remaining.length) throw new Error(`Normal-run cleanup left generated state: ${remaining.join(", ")}`);
console.log("Verified successful normal-run cleanup");
