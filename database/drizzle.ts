import config from "@/lib/config";
import { drizzle } from "drizzle-orm/mysql2";
import mysql from "mysql2/promise";

if (!config.env.databaseUrl) {
  throw new Error(
    "No database connection string was provided. Please check your DATABASE_URL environment variable."
  );
}

const createPool = () =>
  mysql.createPool({
    uri: config.env.databaseUrl,
    connectionLimit: 10,
    waitForConnections: true,
    queueLimit: 0,
    enableKeepAlive: true,
  });

const createDb = (pool: mysql.Pool) =>
  drizzle({ client: pool, casing: "snake_case" });

type Db = ReturnType<typeof createDb>;

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

const pool = globalForDb.__libraryPool ?? createPool();
const db = globalForDb.__libraryDb ?? createDb(pool);

if (process.env.NODE_ENV !== "production") {
  globalForDb.__libraryPool = pool;
  globalForDb.__libraryDb = db;
}

export { db };
