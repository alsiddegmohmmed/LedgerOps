import { spawn } from "node:child_process";
import { captureProcessIdentity, terminateProcess } from "./process-lifecycle.mjs";

const nodeCommand = process.execPath;

function start(source) {
  return spawn(nodeCommand, ["-e", source], { detached: true, stdio: "ignore" });
}

function waitForSpawn(child) {
  return new Promise((resolve, reject) => {
    child.once("error", reject);
    setTimeout(resolve, 100);
  });
}

function waitForExit(child) {
  if (child.exitCode !== null || child.signalCode !== null) return Promise.resolve();
  return new Promise((resolve, reject) => {
    child.once("error", reject);
    child.once("exit", resolve);
  });
}

async function normalShutdown() {
  const child = start("setInterval(() => {}, 1000)");
  await waitForSpawn(child);
  await terminateProcess(captureProcessIdentity(child.pid, nodeCommand));
  await waitForExit(child);
}

async function absentProcessProducesEsrch() {
  const child = start("");
  await new Promise((resolve) => child.once("exit", resolve));
  await terminateProcess({
    pid: child.pid,
    startTime: "process-exited-before-inspection",
    processGroupId: null,
    expectedCommand: nodeCommand,
  });
}

async function forcedShutdown() {
  const child = start("process.on('SIGTERM', () => {}); setInterval(() => {}, 1000)");
  await waitForSpawn(child);
  await terminateProcess(captureProcessIdentity(child.pid, nodeCommand));
  await waitForExit(child);
}

async function terminationFailurePropagates() {
  const child = start("setInterval(() => {}, 1000)");
  await waitForSpawn(child);
  try {
    await terminateProcess({ ...captureProcessIdentity(child.pid, nodeCommand), expectedCommand: "not-the-started-process" });
    throw new Error("Process ownership failure was not propagated");
  } catch (error) {
    if (!String(error.message).includes("Refusing to terminate")) throw error;
  } finally {
    await terminateProcess(captureProcessIdentity(child.pid, nodeCommand));
    await waitForExit(child);
  }
}

async function changedIdentityIsRejectedWithoutSignalling() {
  const child = start("setInterval(() => {}, 1000)");
  await waitForSpawn(child);
  const identity = captureProcessIdentity(child.pid, nodeCommand);
  try {
    await terminateProcess({ ...identity, startTime: "different-process-start-time" });
    throw new Error("Changed process identity was not rejected");
  } catch (error) {
    if (!String(error.message).includes("process start identity changed")) throw error;
  }
  if (child.exitCode !== null || child.signalCode !== null) throw new Error("Changed identity check signalled the child");
  try {
    process.kill(child.pid, 0);
  } catch (error) {
    throw new Error(`Changed identity check did not leave the child alive: ${error.message}`);
  }
  await terminateProcess(identity);
  await waitForExit(child);
}

await normalShutdown();
await absentProcessProducesEsrch();
await forcedShutdown();
await terminationFailurePropagates();
await changedIdentityIsRejectedWithoutSignalling();
console.log("Verified normal, ESRCH, forced, ownership-failure, and changed-identity process shutdown paths");
