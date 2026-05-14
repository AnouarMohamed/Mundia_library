import config from "@/lib/config";
import { neon } from "@neondatabase/serverless";
import { drizzle as drizzleNeon } from "drizzle-orm/neon-http";
import { drizzle as drizzleNodePostgres } from "drizzle-orm/node-postgres";
import { Pool } from "pg";
import * as schema from "@/database/schema";

const missingDatabaseUrlMessage =
  "No database connection string was provided. Please check your DATABASE_URL environment variable.";

const createMissingDatabaseUrlError = () =>
  new Error(missingDatabaseUrlMessage);

const nodePostgresHosts = new Set([
  "localhost",
  "127.0.0.1",
  "0.0.0.0",
  "::1",
  "db",
  "postgres",
]);

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

const shouldUseNodePostgres = (databaseUrl: string) =>
  nodePostgresHosts.has(parseDatabaseUrl(databaseUrl).hostname);

const createClient = (databaseUrl: string): Client => {
  if (shouldUseNodePostgres(databaseUrl)) {
    return new Pool({ connectionString: databaseUrl });
  }

  return neon(databaseUrl);
};

const createNeonDb = (client: NeonClient) =>
  drizzleNeon(client, { schema });

type Db = ReturnType<typeof createNeonDb>;

const createDb = (client: Client, databaseUrl: string): Db => {
  if (shouldUseNodePostgres(databaseUrl)) {
    return drizzleNodePostgres(client as PgClient, { schema }) as unknown as Db;
  }

  return createNeonDb(client as NeonClient);
};

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

const globalForDb = globalThis as typeof globalThis & {
  __libraryClient?: Client;
  __libraryDb?: Db;
  __libraryDbUrl?: string;
};

const databaseUrl = config.env.databaseUrl;
const cachedConnectionMatches = globalForDb.__libraryDbUrl === databaseUrl;

const client = databaseUrl
  ? cachedConnectionMatches && globalForDb.__libraryClient
    ? globalForDb.__libraryClient
    : createClient(databaseUrl)
  : undefined;
const db = client
  ? cachedConnectionMatches && globalForDb.__libraryDb
    ? globalForDb.__libraryDb
    : createDb(client, databaseUrl)
  : createMissingDatabase();

if (process.env.NODE_ENV !== "production" && client) {
  globalForDb.__libraryClient = client;
  globalForDb.__libraryDb = db;
  globalForDb.__libraryDbUrl = databaseUrl;
}

const closeDb = async () => {
  if (client instanceof Pool) {
    await client.end();
  }
};

export { closeDb, db };
