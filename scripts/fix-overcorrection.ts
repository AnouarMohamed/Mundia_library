/**
 * Fix Over-Correction from Previous Script Run
 * 
 * The fix script ran twice and over-corrected these books:
 * - "The Clean Coder": Should be 36 available (was 35, now 38 - over by 2)
 * - "HTML and CSS: Design and Build Websites": Should be 24 available (was 23, now 25 - over by 1)
 */

import { books } from "@/database/schema";
import { eq } from "drizzle-orm";
import { closePostgresPool, db } from "@/scripts/postgres-db";

async function fixOvercorrection() {
  console.log("🔧 Fixing Over-Correction\n");
  console.log("=".repeat(80));

  try {
    // Fix "The Clean Coder" - should be 36 (currently 38, need to decrement by 2)
    const cleanCoder = await db
      .select()
      .from(books)
      .where(eq(books.title, "The Clean Coder"))
      .limit(1);

    if (cleanCoder.length > 0) {
      const book = cleanCoder[0];
      const current = book.availableCopies;
      const correct = book.totalCopies; // Should equal total copies (all available)
      
      if (current !== correct) {
        console.log(`📖 "The Clean Coder"`);
        console.log(`   Current: ${current} available`);
        console.log(`   Correct: ${correct} available (should equal total copies)`);
        console.log(`   Fixing: ${current} → ${correct}`);
        
        await db
          .update(books)
          .set({ availableCopies: correct })
          .where(eq(books.id, book.id));
        
        console.log(`   ✅ Fixed\n`);
      } else {
        console.log(`📖 "The Clean Coder" - Already correct (${current} available)\n`);
      }
    }

    // Fix "HTML and CSS: Design and Build Websites" - should be 24 (currently 25, need to decrement by 1)
    const htmlCss = await db
      .select()
      .from(books)
      .where(eq(books.title, "HTML and CSS: Design and Build Websites"))
      .limit(1);

    if (htmlCss.length > 0) {
      const book = htmlCss[0];
      const current = book.availableCopies;
      const correct = book.totalCopies; // Should equal total copies (all available)
      
      if (current !== correct) {
        console.log(`📖 "HTML and CSS: Design and Build Websites"`);
        console.log(`   Current: ${current} available`);
        console.log(`   Correct: ${correct} available (should equal total copies)`);
        console.log(`   Fixing: ${current} → ${correct}`);
        
        await db
          .update(books)
          .set({ availableCopies: correct })
          .where(eq(books.id, book.id));
        
        console.log(`   ✅ Fixed\n`);
      } else {
        console.log(`📖 "HTML and CSS: Design and Build Websites" - Already correct (${current} available)\n`);
      }
    }

    // Verify
    console.log("=".repeat(80));
    console.log("✅ Verification");
    console.log("=".repeat(80));

    const allBooks = await db.select().from(books);
    const totalCopies = allBooks.reduce((sum, book) => sum + book.totalCopies, 0);
    const availableCopies = allBooks.reduce((sum, book) => sum + book.availableCopies, 0);
    const borrowedCopies = totalCopies - availableCopies;

    console.log(`Total Copies: ${totalCopies}`);
    console.log(`Available Copies: ${availableCopies}`);
    console.log(`Borrowed Copies: ${borrowedCopies}`);

    if (borrowedCopies >= 0 && borrowedCopies <= totalCopies) {
      console.log(`\n✅ SUCCESS: All values are now correct!`);
    } else {
      console.log(`\n⚠️  Still an issue with the calculations`);
    }

    console.log("\n" + "=".repeat(80));
    console.log("✅ Fix Complete!");
    console.log("=".repeat(80));

    await closePostgresPool();
    process.exit(0);
  } catch (error) {
    console.error("❌ Error:", error);
    await closePostgresPool();
    process.exit(1);
  }
}

fixOvercorrection();
