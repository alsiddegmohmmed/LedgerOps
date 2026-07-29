import { spawnSync } from "node:child_process";

const GRACEFUL_TIMEOUT_MS = 10_000;
const FORCE_TIMEOUT_MS = 5_000;
const POLL_MS = 100;

function isErrno(error, code) {
  return error && typeof error === "object" && error.code === code;
}

function processField(pid, field) {
  const result = spawnSync("ps", ["-p", String(pid), "-o", `${field}=`], {
    encoding: "utf8",
  });
  if (result.error) throw result.error;
  if (result.status !== 0) return null;
  return result.stdout.trim() || null;
}

function readProcessIdentity(pid) {
  try {
    process.kill(pid, 0);
  } catch (error) {
    if (isErrno(error, "ESRCH")) return null;
    throw error;
  }
  const startTime = processField(pid, "lstart");
  const command = processField(pid, "command");
  const processGroupId = processField(pid, "pgid");
  if (!startTime || !command) return null;
  return {
    pid,
    startTime,
    command,
    processGroupId: processGroupId ? Number(processGroupId) : null,
  };
}

export function captureProcessIdentity(pid, expectedCommand) {
  const identity = readProcessIdentity(pid);
  if (!identity) throw new Error(`Unable to capture identity for E2E PID ${pid}`);
  if (!identity.command.includes(expectedCommand)) {
    throw new Error(`Refusing to track PID ${pid}: unexpected command ${identity.command}`);
  }
  return { ...identity, expectedCommand };
}

function assertIdentity(identity) {
  const current = readProcessIdentity(identity.pid);
  if (!current) return false;
  if (current.startTime !== identity.startTime) {
    throw new Error(`Refusing to terminate PID ${identity.pid}: process start identity changed`);
  }
  if (identity.processGroupId !== null && current.processGroupId !== identity.processGroupId) {
    throw new Error(`Refusing to terminate PID ${identity.pid}: process-group identity changed`);
  }
  if (!current.command.includes(identity.expectedCommand)) {
    throw new Error(`Refusing to terminate PID ${identity.pid}: unexpected command ${current.command}`);
  }
  return true;
}

function signal(identity, signalName) {
  if (!assertIdentity(identity)) return false;
  const target = identity.processGroupId === null ? identity.pid : -identity.processGroupId;
  try {
    process.kill(target, signalName);
    return true;
  } catch (error) {
    if (!isErrno(error, "ESRCH")) throw error;
    if (target === identity.pid) return false;
    try {
      process.kill(identity.pid, signalName);
      return true;
    } catch (directError) {
      if (isErrno(directError, "ESRCH")) return false;
      throw directError;
    }
  }
}

function waitForExit(pid, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      process.kill(pid, 0);
    } catch (error) {
      if (isErrno(error, "ESRCH")) return true;
      throw error;
    }
    const sleep = spawnSync("sleep", [String(POLL_MS / 1000)]);
    if (sleep.error) throw sleep.error;
    if (sleep.status !== 0) throw new Error(`Process-exit wait failed with ${sleep.status}`);
  }
  return false;
}

async function waitForExitAsync(pid, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      process.kill(pid, 0);
    } catch (error) {
      if (isErrno(error, "ESRCH")) return true;
      throw error;
    }
    await new Promise((resolve) => setTimeout(resolve, POLL_MS));
  }
  return false;
}

export function terminateProcessSync(identity) {
  if (!identity || !assertIdentity(identity)) return;
  signal(identity, "SIGTERM");
  if (waitForExit(identity.pid, GRACEFUL_TIMEOUT_MS)) return;
  signal(identity, "SIGKILL");
  if (!waitForExit(identity.pid, FORCE_TIMEOUT_MS)) {
    throw new Error(`Process PID ${identity.pid} remained alive after forced termination`);
  }
}

export async function terminateProcess(identity) {
  if (!identity || !assertIdentity(identity)) return;
  signal(identity, "SIGTERM");
  if (await waitForExitAsync(identity.pid, GRACEFUL_TIMEOUT_MS)) return;
  signal(identity, "SIGKILL");
  if (!await waitForExitAsync(identity.pid, FORCE_TIMEOUT_MS)) {
    throw new Error(`Process PID ${identity.pid} remained alive after forced termination`);
  }
}
