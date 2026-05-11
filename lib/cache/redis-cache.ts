/**
 * Redis Cache Service
 * 
 * Provides an advanced caching layer for the application using Upstash Redis.
 * Implements Stale-While-Revalidate (SWR) patterns to ensure high performance
 * and availability even when the cache is being refreshed.
 * 
 * Features:
 * - TTL (Time To Live) support.
 * - SWR (Stale-While-Revalidate) logic.
 * - Automatic JSON serialization/deserialization.
 * - Error resilience (fails gracefully if Redis is unavailable).
 */

import redis from "@/database/redis";

export interface CacheOptions {
  ttl?: number; // Time to live in seconds
  swr?: number; // Stale-while-revalidate period in seconds
}

const DEFAULT_TTL = 3600; // 1 hour
const DEFAULT_SWR = 86400; // 24 hours

/**
 * Get a value from the cache with SWR logic.
 * 
 * Logic:
 * 1. If the key exists and is fresh (within TTL), return it.
 * 2. If the key exists but is stale (beyond TTL but within SWR), return stale data
 *    and trigger a revalidation (by returning a flag).
 * 3. If the key doesn't exist or is beyond SWR, return null.
 */
export async function getCachedData<T>(key: string): Promise<{ data: T | null; isStale: boolean }> {
  try {
    const cached = await redis.get<{ data: T; expiresAt: number; staleAt: number }>(key);
    
    if (!cached) {
      return { data: null, isStale: false };
    }

    const now = Date.now();
    
    if (now < cached.expiresAt) {
      // Data is fresh
      return { data: cached.data, isStale: false };
    } else if (now < cached.staleAt) {
      // Data is stale but within SWR period
      return { data: cached.data, isStale: true };
    } else {
      // Data is too old
      return { data: null, isStale: false };
    }
  } catch (error) {
    console.error(`Redis get error for key ${key}:`, error);
    return { data: null, isStale: false };
  }
}

/**
 * Set data in the cache with TTL and SWR periods.
 */
export async function setCachedData<T>(key: string, data: T, options: CacheOptions = {}): Promise<void> {
  const ttl = options.ttl || DEFAULT_TTL;
  const swr = options.swr || DEFAULT_SWR;
  
  const now = Date.now();
  const expiresAt = now + ttl * 1000;
  const staleAt = now + (ttl + swr) * 1000;

  try {
    // We store the data with the maximum possible lifetime (TTL + SWR)
    await redis.set(key, { data, expiresAt, staleAt }, { ex: ttl + swr });
  } catch (error) {
    console.error(`Redis set error for key ${key}:`, error);
  }
}

/**
 * Invalidate a cache key.
 */
export async function invalidateCache(key: string): Promise<void> {
  try {
    await redis.del(key);
  } catch (error) {
    console.error(`Redis del error for key ${key}:`, error);
  }
}

/**
 * Clear multiple keys using a pattern.
 * Use sparingly on large keyspaces.
 */
export async function invalidatePattern(pattern: string): Promise<void> {
  try {
    const keys = await redis.keys(pattern);
    if (keys.length > 0) {
      await redis.del(...keys);
    }
  } catch (error) {
    console.error(`Redis keys/del error for pattern ${pattern}:`, error);
  }
}
