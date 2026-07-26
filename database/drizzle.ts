/**
 * Database Connection Module
 *
 * This module initializes and exports the Drizzle ORM instance used throughout the application.
 * It features a hybrid connection strategy to support both local development and serverless production:
 *
 * All environments use the transaction-capable node-postgres driver.
 *
 * The previous hostname-based selection used Drizzle's Neon HTTP driver for every
 * remote PostgreSQL URL. That driver does not support interactive transactions,
 * which made circulation approval and return operations fail in production.
 *
 * Hosted deployments must provide a pooled PostgreSQL URL (for example, a Neon
 * pooled endpoint or PgBouncer) and tune DATABASE_POOL_MAX for the instance size.
 * The singleton prevents connection exhaustion during Next.js HMR.
 */

import config from "@/lib/config";
import { drizzle as drizzleNodePostgres } from "drizzle-orm/node-postgres";
import { Pool } from "pg";
import * as schema from "@/database/schema";

/** Message shown when DATABASE_URL is missing. */
const missingDatabaseUrlMessage =
  "No database connection string was provided. Please check your DATABASE_URL environment variable.";

/** Creates a standard error for missing database configuration. */
const createMissingDatabaseUrlError = () =>
  new Error(missingDatabaseUrlMessage);

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

type Client = Pool;

/**
 * Creates a transaction-capable PostgreSQL connection pool.
 * @param databaseUrl - The connection string.
 */
const createClient = (databaseUrl: string): Client => {
  parseDatabaseUrl(databaseUrl);

  const boundedMilliseconds = (
    value: string | undefined,
    fallback: number,
  ) => {
    const parsed = Number.parseInt(value ?? "", 10);
    return Number.isFinite(parsed)
      ? Math.min(120_000, Math.max(100, parsed))
      : fallback;
  };
  const configuredPoolMax = Number.parseInt(
    process.env.DATABASE_POOL_MAX ?? "10",
    10,
  );
  const max = Number.isFinite(configuredPoolMax)
    ? Math.min(100, Math.max(1, configuredPoolMax))
    : 10;
  const statementTimeout = boundedMilliseconds(
    process.env.DATABASE_STATEMENT_TIMEOUT_MS,
    15_000,
  );
  const queryTimeout = boundedMilliseconds(
    process.env.DATABASE_QUERY_TIMEOUT_MS,
    20_000,
  );
  const idleTransactionTimeout = boundedMilliseconds(
    process.env.DATABASE_IDLE_TRANSACTION_TIMEOUT_MS,
    15_000,
  );

  return new Pool({
    connectionString: databaseUrl,
    max,
    connectionTimeoutMillis: 5_000,
    idleTimeoutMillis: 30_000,
    statement_timeout: statementTimeout,
    query_timeout: queryTimeout,
    idle_in_transaction_session_timeout: idleTransactionTimeout,
    allowExitOnIdle: process.env.NODE_ENV !== "production",
  });
};

type Db = ReturnType<typeof drizzleNodePostgres<typeof schema>>;

/**
 * Initializes the Drizzle ORM instance with the provided client.
 * @param client - The node-postgres pool.
 */
const createDb = (client: Client): Db =>
  drizzleNodePostgres(client, { schema });

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
  var __libraryClient: Client | undefined;
  var __libraryDb: Db | undefined;
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
    : createDb(client)
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
  if (client) {
    await client.end();
  }
};

export { closeDb, db };
