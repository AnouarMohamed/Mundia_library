import assert from "node:assert/strict";
import { createHash, randomUUID } from "node:crypto";

import { and, eq } from "drizzle-orm";

import { closeDb, db } from "@/database/drizzle";
import { rateLimitBuckets } from "@/database/schema";
import { applyPostgresRateLimit } from "@/lib/security/postgres-rate-limit";

const scope = "verification";
const identifier = randomUUID();
const identifierHash = createHash("sha256")
  .update(identifier)
  .digest("hex");
const limit = 30;
const contenders = 100;

const main = async () => {
  let failure: unknown;

  try {
    const outcomes = await Promise.all(
      Array.from({ length: contenders }, () =>
        applyPostgresRateLimit({
          scope,
          identifier,
          limit,
          windowSeconds: 60,
        }),
      ),
    );

    assert.equal(
      outcomes.filter((outcome) => outcome.success).length,
      limit,
      "the distributed budget must admit exactly its configured limit",
    );
    assert.equal(
      outcomes.filter((outcome) => !outcome.success).length,
      contenders - limit,
      "every request over budget must be rejected",
    );

    const [bucket] = await db
      .select({ requestCount: rateLimitBuckets.requestCount })
      .from(rateLimitBuckets)
      .where(
        and(
          eq(rateLimitBuckets.scope, scope),
          eq(rateLimitBuckets.identifierHash, identifierHash),
        ),
      )
      .limit(1);
    assert.equal(
      bucket?.requestCount,
      limit + 1,
      "rejected traffic must not grow the durable counter without bound",
    );

    console.log(
      `Rate-limit invariant passed: ${contenders} concurrent requests admitted exactly ${limit} and capped durable state at ${limit + 1}.`,
    );
  } catch (error) {
    failure = error;
  } finally {
    try {
      await db
        .delete(rateLimitBuckets)
        .where(
          and(
            eq(rateLimitBuckets.scope, scope),
            eq(rateLimitBuckets.identifierHash, identifierHash),
          ),
        );
    } catch (cleanupError) {
      failure = failure
        ? new AggregateError(
            [failure, cleanupError],
            "Rate-limit verification and cleanup both failed",
          )
        : cleanupError;
    }
    await closeDb();
  }

  if (failure) throw failure;
};

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
