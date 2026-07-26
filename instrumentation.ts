/**
 * Run fail-closed configuration validation when the Node.js server starts.
 * Importing lazily avoids loading Node-only configuration in an Edge runtime.
 */
export async function register() {
  if (process.env.NEXT_RUNTIME === "nodejs") {
    try {
      await import("@/lib/config");
    } catch (error) {
      console.error(
        "Fatal startup configuration error:",
        error instanceof Error ? error.message : "unknown configuration error",
      );
      process.exit(1);
    }
  }
}
