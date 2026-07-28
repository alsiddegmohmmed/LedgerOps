import Redis from "ioredis";
import { config } from "./config";

let client: Redis | undefined;

export function redis(): Redis {
  client ??= new Redis(config.redisUrl, { lazyConnect: false, maxRetriesPerRequest: 1 });
  return client;
}
