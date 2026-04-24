import config from "@/lib/config";
import { drizzle } from "drizzle-orm/mysql2";
import mysql from "mysql2/promise";

const missingDatabaseUrlMessage =
  "No database connection string was provided. Please check your DATABASE_URL environment variable.";

const createMissingDatabaseUrlError = () =>
  new Error(missingDatabaseUrlMessage);

const createPool = () => {
  if (!config.env.databaseUrl) {
    throw createMissingDatabaseUrlError();
  }

  return mysql.createPool({
    uri: config.env.databaseUrl,
    connectionLimit: 10,
    waitForConnections: true,
    queueLimit: 0,
    enableKeepAlive: true,
  });
};

const createDb = (pool: mysql.Pool) =>
  drizzle({ client: pool, casing: "snake_case" });

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
  var __libraryPool: mysql.Pool | undefined;
  // eslint-disable-next-line no-var
  var __libraryDb: Db | undefined;
}

const globalForDb = globalThis as typeof globalThis & {
  __libraryPool?: mysql.Pool;
  __libraryDb?: Db;
};

const pool = config.env.databaseUrl
  ? globalForDb.__libraryPool ?? createPool()
  : undefined;
const db = pool
  ? globalForDb.__libraryDb ?? createDb(pool)
  : createMissingDatabase();

if (process.env.NODE_ENV !== "production" && pool) {
  globalForDb.__libraryPool = pool;
  globalForDb.__libraryDb = db;
}

export { db };
