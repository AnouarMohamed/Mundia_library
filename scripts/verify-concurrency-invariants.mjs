import assert from "node:assert/strict";
import { randomInt, randomUUID } from "node:crypto";
import pg from "pg";

const { Pool } = pg;

const connectionString = process.env.DATABASE_URL;
if (!connectionString) {
  throw new Error(
    "DATABASE_URL is required; this verifier never selects a database implicitly.",
  );
}

const concurrency = 32;
const initialCopies = 8;
const runId = randomUUID();
const fixture = {
  adminId: randomUUID(),
  borrowerId: randomUUID(),
  bookId: randomUUID(),
  borrowId: randomUUID(),
};

const firstUniversityId = randomInt(100_000_000, 1_900_000_000);
let secondUniversityId = randomInt(100_000_000, 1_900_000_000);
while (secondUniversityId === firstUniversityId) {
  secondUniversityId = randomInt(100_000_000, 1_900_000_000);
}

const pool = new Pool({
  connectionString,
  application_name: "verify-concurrency-invariants",
  max: concurrency,
  connectionTimeoutMillis: 10_000,
  statement_timeout: 30_000,
});

const rollbackQuietly = async (client) => {
  try {
    await client.query("rollback");
  } catch {
    // Preserve the original failure. Cleanup below uses a fresh transaction.
  }
};

const fulfilledValues = (results, label) => {
  const errors = results
    .filter((result) => result.status === "rejected")
    .map((result) => result.reason);

  if (errors.length > 0) {
    throw new AggregateError(errors, `${label} failed`);
  }

  return results.map((result) => result.value);
};

const insertFixtures = async () => {
  const client = await pool.connect();

  try {
    await client.query("begin");
    await client.query(
      `insert into users (
         id,
         full_name,
         email,
         university_id,
         password,
         university_card,
         status,
         role
       )
       values
         ($1, $2, $3, $4, $5, $6, 'APPROVED', 'USER'),
         ($7, $8, $9, $10, $11, $12, 'APPROVED', 'ADMIN')`,
      [
        fixture.borrowerId,
        "Concurrency Verifier Borrower",
        `concurrency-borrower-${runId}@example.invalid`,
        firstUniversityId,
        "not-a-login-credential",
        `fixture://${runId}/borrower`,
        fixture.adminId,
        "Concurrency Verifier Admin",
        `concurrency-admin-${runId}@example.invalid`,
        secondUniversityId,
        "not-a-login-credential",
        `fixture://${runId}/admin`,
      ],
    );
    await client.query(
      `insert into books (
         id,
         title,
         author,
         genre,
         rating,
         cover_url,
         cover_color,
         description,
         total_copies,
         available_copies,
         video_url,
         summary
       )
       values ($1, $2, $3, $4, $5, $6, $7, $8, $9, $9, $10, $11)`,
      [
        fixture.bookId,
        `Concurrency Invariant Fixture ${runId}`,
        "Invariant Verifier",
        "TEST",
        5,
        `fixture://${runId}/cover`,
        "#000000",
        "Ephemeral fixture used only for PostgreSQL concurrency verification.",
        initialCopies,
        `fixture://${runId}/video`,
        "Ephemeral concurrency fixture",
      ],
    );
    await client.query(
      `insert into borrow_records (
         id,
         user_id,
         book_id,
         status,
         due_date,
         return_date
       )
       values ($1, $2, $3, 'PENDING', null, null)`,
      [fixture.borrowId, fixture.borrowerId, fixture.bookId],
    );
    await client.query("commit");
  } catch (error) {
    await rollbackQuietly(client);
    throw error;
  } finally {
    client.release();
  }
};

const cleanupFixtures = async () => {
  const client = await pool.connect();

  try {
    await client.query("begin");
    await client.query("delete from borrow_records where id = $1", [
      fixture.borrowId,
    ]);
    await client.query("delete from books where id = $1", [fixture.bookId]);
    await client.query("delete from users where id = any($1::uuid[])", [
      [fixture.borrowerId, fixture.adminId],
    ]);
    await client.query("commit");

    const cleanupState = await client.query(
      `select
         (select count(*)::int from borrow_records where id = $1) as borrow_count,
         (select count(*)::int from books where id = $2) as book_count,
         (
           select count(*)::int
             from users
            where id = any($3::uuid[])
         ) as user_count`,
      [
        fixture.borrowId,
        fixture.bookId,
        [fixture.borrowerId, fixture.adminId],
      ],
    );

    assert.deepEqual(cleanupState.rows[0], {
      borrow_count: 0,
      book_count: 0,
      user_count: 0,
    });
  } catch (error) {
    await rollbackQuietly(client);
    throw error;
  } finally {
    client.release();
  }
};

/**
 * Opens one transaction per contender and has every transaction observe the
 * same pre-transition state before releasing them to race. This creates the
 * strongest replay scenario without mocking PostgreSQL locking behavior.
 */
const runSynchronizedRace = async ({
  label,
  expectedSnapshotStatus,
  attempt,
}) => {
  const clients = [];

  try {
    for (let index = 0; index < concurrency; index += 1) {
      clients.push(await pool.connect());
    }

    fulfilledValues(
      await Promise.allSettled(
        clients.map((client) =>
          client.query("begin isolation level read committed"),
        ),
      ),
      `${label} transaction setup`,
    );

    const snapshots = fulfilledValues(
      await Promise.allSettled(
        clients.map((client) =>
          client.query(
            "select status from borrow_records where id = $1",
            [fixture.borrowId],
          ),
        ),
      ),
      `${label} synchronized read`,
    );

    for (const snapshot of snapshots) {
      assert.equal(snapshot.rowCount, 1, `${label}: fixture row disappeared`);
      assert.equal(
        snapshot.rows[0].status,
        expectedSnapshotStatus,
        `${label}: contenders did not observe the same initial state`,
      );
    }

    const outcomes = await Promise.allSettled(
      clients.map(async (client) => {
        try {
          const outcome = await attempt(client);
          await client.query(outcome.transitioned ? "commit" : "rollback");
          return outcome;
        } catch (error) {
          await rollbackQuietly(client);
          throw error;
        }
      }),
    );

    return fulfilledValues(outcomes, `${label} contenders`);
  } finally {
    await Promise.allSettled(clients.map(rollbackQuietly));
    for (const client of clients) {
      client.release();
    }
  }
};

const approveOnce = async (client) => {
  // Preserve the legacy action's ordering: reserve a physical copy, then win
  // the conditional request transition. A lost transition rolls the copy back.
  const reservedBook = await client.query(
    `update books
        set available_copies = available_copies - 1
      where id = $1
        and available_copies > 0
      returning available_copies`,
    [fixture.bookId],
  );
  assert.equal(
    reservedBook.rowCount,
    1,
    "approval contender could not reserve the isolated fixture inventory",
  );

  const transitionedBorrow = await client.query(
    `update borrow_records
        set status = 'BORROWED',
            due_date = current_date + 7,
            borrowed_by = $2,
            updated_by = $2,
            updated_at = now()
      where id = $1
        and status = 'PENDING'
      returning id`,
    [fixture.borrowId, fixture.adminId],
  );

  if (transitionedBorrow.rowCount === 0) {
    return { transitioned: false, reason: "status-already-transitioned" };
  }

  assert.equal(transitionedBorrow.rowCount, 1);
  return { transitioned: true, reason: "approved" };
};

const returnOnce = async (client) => {
  // Preserve the legacy action's return ordering: win BORROWED -> RETURNED
  // before restoring inventory, so replayed returns cannot inflate stock.
  const transitionedBorrow = await client.query(
    `update borrow_records
        set status = 'RETURNED',
            return_date = current_date,
            returned_by = $2,
            borrowed_by = coalesce(borrowed_by, $2),
            fine_amount = '0.00',
            updated_by = $2,
            updated_at = now()
      where id = $1
        and status = 'BORROWED'
      returning book_id`,
    [fixture.borrowId, fixture.adminId],
  );

  if (transitionedBorrow.rowCount === 0) {
    return { transitioned: false, reason: "status-already-transitioned" };
  }

  assert.equal(transitionedBorrow.rowCount, 1);
  const restoredBook = await client.query(
    `update books
        set available_copies = available_copies + 1
      where id = $1
      returning available_copies, total_copies`,
    [fixture.bookId],
  );
  assert.equal(
    restoredBook.rowCount,
    1,
    "return winner could not restore the isolated fixture inventory",
  );

  return { transitioned: true, reason: "returned" };
};

const readInvariantState = async () => {
  const state = await pool.query(
    `select
       borrow_records.status,
       borrow_records.due_date,
       borrow_records.return_date,
       books.available_copies,
       books.total_copies
     from borrow_records
     inner join books on books.id = borrow_records.book_id
     where borrow_records.id = $1`,
    [fixture.borrowId],
  );

  assert.equal(state.rowCount, 1, "isolated fixture state is missing");
  return state.rows[0];
};

const assertExactlyOneWinner = (outcomes, label) => {
  assert.equal(outcomes.length, concurrency);
  assert.equal(
    outcomes.filter((outcome) => outcome.transitioned).length,
    1,
    `${label} must commit exactly one state transition`,
  );
  assert.equal(
    outcomes.filter(
      (outcome) =>
        !outcome.transitioned &&
        outcome.reason === "status-already-transitioned",
    ).length,
    concurrency - 1,
    `${label} must reject every replay through the conditional status update`,
  );
};

let failure;

try {
  await insertFixtures();

  const approvalOutcomes = await runSynchronizedRace({
    label: "concurrent approval",
    expectedSnapshotStatus: "PENDING",
    attempt: approveOnce,
  });
  assertExactlyOneWinner(approvalOutcomes, "approval");

  const approvedState = await readInvariantState();
  assert.equal(approvedState.status, "BORROWED");
  assert.notEqual(approvedState.due_date, null);
  assert.equal(approvedState.return_date, null);
  assert.equal(approvedState.total_copies, initialCopies);
  assert.equal(
    approvedState.available_copies,
    initialCopies - 1,
    "exactly one approved transition must consume exactly one copy",
  );

  const returnOutcomes = await runSynchronizedRace({
    label: "concurrent return",
    expectedSnapshotStatus: "BORROWED",
    attempt: returnOnce,
  });
  assertExactlyOneWinner(returnOutcomes, "return");

  const returnedState = await readInvariantState();
  assert.equal(returnedState.status, "RETURNED");
  assert.notEqual(returnedState.return_date, null);
  assert.equal(returnedState.total_copies, initialCopies);
  assert.equal(
    returnedState.available_copies,
    initialCopies,
    "exactly one return transition must restore exactly one copy",
  );

  console.log(
    `Concurrency invariants passed: ${concurrency} synchronized approvals and ${concurrency} synchronized returns each produced exactly one winner; inventory moved ${initialCopies} -> ${initialCopies - 1} -> ${initialCopies}.`,
  );
} catch (error) {
  failure = error;
} finally {
  try {
    await cleanupFixtures();
    console.log(`Concurrency verifier fixtures cleaned up (${runId}).`);
  } catch (cleanupError) {
    failure = failure
      ? new AggregateError(
          [failure, cleanupError],
          "Verification failed and fixture cleanup also failed",
        )
      : cleanupError;
  }

  try {
    await pool.end();
  } catch (poolError) {
    failure = failure
      ? new AggregateError(
          [failure, poolError],
          "Verification failed and the PostgreSQL pool did not close cleanly",
        )
      : poolError;
  }
}

if (failure) {
  throw failure;
}
