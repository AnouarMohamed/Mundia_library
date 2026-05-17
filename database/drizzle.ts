/**
 * Database Connection Module
 *
 * This module initializes and exports the Drizzle ORM instance used throughout the application.
 * It features a hybrid connection strategy to support both local development and serverless production:
 *
 * 1. Local/Docker Development: Uses 'pg' (node-postgres) for standard TCP connections to local PostgreSQL instances.
 * 2. Serverless/Production (Neon): Uses '@neondatabase/serverless' for HTTP-based connections, which are 
 *    more resilient in serverless environments (e.g., Vercel) where connection pooling is critical.
 *
 * It also implements a singleton pattern using the global object in development to prevent 
 * exhausting database connections during Next.js Hot Module Replacement (HMR).
 */

import config from "@/lib/config";
import { neon } from "@neondatabase/serverless";
import { drizzle as drizzleNeon } from "drizzle-orm/neon-http";
import { drizzle as drizzleNodePostgres } from "drizzle-orm/node-postgres";
import { Pool } from "pg";
import * as schema from "@/database/schema";

/** Message shown when DATABASE_URL is missing. */
const missingDatabaseUrlMessage =
  "No database connection string was provided. Please check your DATABASE_URL environment variable.";

/** Creates a standard error for missing database configuration. */
const createMissingDatabaseUrlError = () =>
  new Error(missingDatabaseUrlMessage);

/** List of hostnames that should trigger the use of Node-Postgres instead of Neon HTTP. */
const nodePostgresHosts = new Set([
  "localhost",
  "127.0.0.1",
  "0.0.0.0",
  "::1",
  "db",
  "postgres",
]);

/**
 * Validates and parses the database connection string.
 * @param databaseUrl - The connection string to parse.
 * @throws Error if the URL is invalid or uses an unsupported protocol.
 */
const parseDatabaseUrl = (databaseUrl: string) => {
  try {
    const parsedUrl = new URL(databaseUrl);

    if (!["postgres:", "postgresql:"].includes(parsedUrl.protocol)) {
      throw new Error("DATABASE_URL must use a PostgreSQL connection string.");
    }

    return parsedUrl;
  } catch {
    throw new Error("DATABASE_URL must be a valid PostgreSQL connection string.");
  }
};

type NeonClient = ReturnType<typeof neon>;
type PgClient = Pool;
type Client = NeonClient | PgClient;

/**
 * Determines whether to use the Node-Postgres driver based on the hostname.
 * @param databaseUrl - The connection string.
 * @returns True if the host is local or a known Docker service name.
 */
const shouldUseNodePostgres = (databaseUrl: string) =>
  nodePostgresHosts.has(parseDatabaseUrl(databaseUrl).hostname);

/**
 * Creates the appropriate database client (Neon HTTP or PG Pool).
 * @param databaseUrl - The connection string.
 */
const createClient = (databaseUrl: string): Client => {
  if (shouldUseNodePostgres(databaseUrl)) {
    return new Pool({ connectionString: databaseUrl });
  }

  return neon(databaseUrl);
};

/** Wrapper for Neon HTTP drizzle initialization. */
const createNeonDb = (client: NeonClient) =>
  drizzleNeon(client, { schema });

type Db = ReturnType<typeof createNeonDb>;

/**
 * Initializes the Drizzle ORM instance with the provided client.
 * @param client - The database client.
 * @param databaseUrl - The connection string (used to determine driver type).
 */
const createDb = (client: Client, databaseUrl: string): Db => {
  if (shouldUseNodePostgres(databaseUrl)) {
    return drizzleNodePostgres(client as PgClient, { schema }) as unknown as Db;
  }

  return createNeonDb(client as NeonClient);
};

/**
 * Fallback proxy that throws an error when any database property is accessed.
 * Used when DATABASE_URL is missing to provide a clear error message at runtime.
 */
const createMissingDatabase = () =>
  new Proxy(
    {},
    {
      get(_target, property) {
        if (property === "then") {
          return undefined;
        }

        throw createMissingDatabaseUrlError();
      },
    }
  ) as Db;

declare global {
  // eslint-disable-next-line no-var
  var __libraryClient: Client | undefined;
  // eslint-disable-next-line no-var
  var __libraryDb: Db | undefined;
  // eslint-disable-next-line no-var
  var __libraryDbUrl: string | undefined;
}

/**
 * Global cache to persist connections across HMR in development.
 */
const globalForDb = globalThis as typeof globalThis & {
  __libraryClient?: Client;
  __libraryDb?: Db;
  __libraryDbUrl?: string;
};

const databaseUrl = config.env.databaseUrl;
const cachedConnectionMatches = globalForDb.__libraryDbUrl === databaseUrl;

/** Initialized client instance. */
const client = databaseUrl
  ? cachedConnectionMatches && globalForDb.__libraryClient
    ? globalForDb.__libraryClient
    : createClient(databaseUrl)
  : undefined;

/** Main Drizzle ORM instance. */
const db = client
  ? cachedConnectionMatches && globalForDb.__libraryDb
    ? globalForDb.__libraryDb
    : createDb(client, databaseUrl)
  : createMissingDatabase();

// In development, cache the connection in the global object.
if (process.env.NODE_ENV !== "production" && client) {
  globalForDb.__libraryClient = client;
  globalForDb.__libraryDb = db;
  globalForDb.__libraryDbUrl = databaseUrl;
}

/**
 * Safely closes the database connection.
 * Primary used in scripts or long-running processes to clean up Pool resources.
 */
const closeDb = async () => {
  if (client instanceof Pool) {
    await client.end();
  }
};

export { closeDb, db };
