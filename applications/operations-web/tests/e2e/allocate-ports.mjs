import net from "node:net";
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";

function freePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      server.close(() => resolve(address.port));
    });
  });
}

const runtimeFile = resolve("tests/e2e/.e2e-ports.json");
const names = ["postgres", "redis", "keycloak", "core", "bff"];

let ports;
if (existsSync(runtimeFile)) {
  try {
    const existing = JSON.parse(readFileSync(runtimeFile, "utf8"));
    if (names.every((name) => Number.isInteger(existing[name]) && existing[name] > 0)) ports = existing;
  } catch {
    ports = undefined;
  }
}
if (!ports) {
  ports = {};
  for (const name of names) ports[name] = await freePort();
  writeFileSync(runtimeFile, JSON.stringify(ports));
}
process.stdout.write(JSON.stringify(ports));
