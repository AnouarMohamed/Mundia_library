import config from "@/lib/config";
import { neon } from "@neondatabase/serverless";
import { drizzle } from "drizzle-orm/neon-http";
import * as schema from "@/database/schema";

const missingDatabaseUrlMessage =
  "No database connection string was provided. Please check your DATABASE_URL environment variable.";

const createMissingDatabaseUrlError = () =>
  new Error(missingDatabaseUrlMessage);

const createClient = () => {
  if (!config.env.databaseUrl) {
    throw createMissingDatabaseUrlError();
  }

  return neon(config.env.databaseUrl);
};

type Client = ReturnType<typeof createClient>;

const createDb = (client: Client) =>
  drizzle({ client, schema });

type Db = ReturnType<typeof createDb>;

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
}

const globalForDb = globalThis as typeof globalThis & {
  __libraryClient?: Client;
  __libraryDb?: Db;
};

const client = config.env.databaseUrl
  ? globalForDb.__libraryClient ?? createClient()
  : undefined;
const db = client
  ? globalForDb.__libraryDb ?? createDb(client)
  : createMissingDatabase();

if (process.env.NODE_ENV !== "production" && client) {
  globalForDb.__libraryClient = client;
  globalForDb.__libraryDb = db;
}

export { db };
