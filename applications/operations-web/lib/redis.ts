import Redis from "ioredis";
import { config } from "./config";

let client: Redis | undefined;

type RedisErrorDetails = {
  name: string;
  code: string;
};

type RedisErrorReporter = (message: string, details: RedisErrorDetails) => void;

export function attachRedisErrorHandler(
  redisClient: Redis,
  report: RedisErrorReporter = (message, details) => console.error(message, details),
) {
  let lastFailure: string | undefined;
  redisClient.on("error", (error) => {
    const code = "code" in error && typeof error.code === "string"
      ? error.code
      : "UNKNOWN";
    const failure = `${error.name}:${code}`;
    if (failure === lastFailure) return;
    lastFailure = failure;
    report("Operations Web Redis connection unavailable", {
      name: error.name,
      code,
    });
  });
  redisClient.on("ready", () => {
    lastFailure = undefined;
  });
}

export function redis(): Redis {
  if (!client) {
    client = new Redis(config.redisUrl, { lazyConnect: false, maxRetriesPerRequest: 1 });
    attachRedisErrorHandler(client);
  }
  return client;
}
