import { describe, it, expect, vi, beforeEach } from "vitest";
import { getCachedData, setCachedData } from "./redis-cache";
import redis from "@/database/redis";

// Mock Redis
vi.mock("@/database/redis", () => ({
  default: {
    get: vi.fn(),
    set: vi.fn(),
    del: vi.fn(),
    keys: vi.fn(),
  },
}));

describe("Redis Cache Service", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  describe("getCachedData", () => {
    it("should return null if key does not exist", async () => {
      (redis.get as any).mockResolvedValue(null);

      const result = await getCachedData("test-key");

      expect(result.data).toBeNull();
      expect(result.isStale).toBe(false);
    });

    it("should return fresh data if within TTL", async () => {
      const now = Date.now();
      const mockData = { 
        data: { name: "Test" }, 
        expiresAt: now + 10000, 
        staleAt: now + 20000 
      };
      (redis.get as any).mockResolvedValue(mockData);

      const result = await getCachedData("test-key");

      expect(result.data).toEqual(mockData.data);
      expect(result.isStale).toBe(false);
    });

    it("should return stale data if beyond TTL but within SWR", async () => {
      const now = Date.now();
      const mockData = { 
        data: { name: "Stale Test" }, 
        expiresAt: now - 5000, 
        staleAt: now + 5000 
      };
      (redis.get as any).mockResolvedValue(mockData);

      const result = await getCachedData("test-key");

      expect(result.data).toEqual(mockData.data);
      expect(result.isStale).toBe(true);
    });

    it("should return null if beyond SWR period", async () => {
      const now = Date.now();
      const mockData = { 
        data: { name: "Expired Test" }, 
        expiresAt: now - 20000, 
        staleAt: now - 10000 
      };
      (redis.get as any).mockResolvedValue(mockData);

      const result = await getCachedData("test-key");

      expect(result.data).toBeNull();
      expect(result.isStale).toBe(false);
    });
  });

  describe("setCachedData", () => {
    it("should set data with correct expiration logic", async () => {
      await setCachedData("test-key", { foo: "bar" }, { ttl: 60, swr: 300 });

      expect(redis.set).toHaveBeenCalledWith(
        "test-key",
        expect.objectContaining({
          data: { foo: "bar" },
          expiresAt: expect.any(Number),
          staleAt: expect.any(Number),
        }),
        { ex: 360 } // ttl + swr
      );
    });
  });
});
