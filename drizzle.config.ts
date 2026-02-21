import { config } from "dotenv";

config({ path: ".env.local" });

const drizzleConfig = {
  schema: "./database/schema.ts",
  out: "./migrations/mysql",
  dialect: "mysql",
  dbCredentials: {
    url: process.env.DATABASE_URL!,
  },
};

export default drizzleConfig;
