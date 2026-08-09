import { EventEmitter } from "node:events";
import { describe, expect, it, vi } from "vitest";
import type Redis from "ioredis";
import { attachRedisErrorHandler } from "../lib/redis";

describe("Redis connection error handling", () => {
  it("handles and deduplicates connection failures until Redis becomes ready", () => {
    const events = new EventEmitter();
    const report = vi.fn();
    attachRedisErrorHandler(events as unknown as Redis, report);

    const refused = Object.assign(new Error("connect refused"), {
      code: "ECONNREFUSED",
    });
    events.emit("error", refused);
    events.emit("error", refused);

    expect(report).toHaveBeenCalledTimes(1);
    expect(report).toHaveBeenCalledWith(
      "Operations Web Redis connection unavailable",
      { name: "Error", code: "ECONNREFUSED" },
    );

    events.emit("ready");
    events.emit("error", refused);
    expect(report).toHaveBeenCalledTimes(2);
  });
});
