import { describe, expect, it } from "vitest";
import fc from "fast-check";
import { getApiErrorMessage } from "./apiError";

const fallbackStatusText = "Bad Gateway";

const expectedMessage = (
  message: string | undefined,
  error: string | undefined
) => {
  if (message?.trim()) return message.trim();
  if (error?.trim()) return error.trim();

  return fallbackStatusText;
};

describe("getApiErrorMessage", () => {
  it("prefers valid message and error payloads", async () => {
    await fc.assert(
      fc.asyncProperty(
        fc.option(fc.string(), { nil: undefined }),
        fc.option(fc.string(), { nil: undefined }),
        async (message, error) => {
          const response = new Response(JSON.stringify({ message, error }), {
            status: 502,
            statusText: fallbackStatusText,
          });

          await expect(getApiErrorMessage(response)).resolves.toBe(
            expectedMessage(message, error)
          );
        }
      )
    );
  });

  it("falls back to status text for non-json responses", async () => {
    const response = new Response("upstream failed", {
      status: 502,
      statusText: fallbackStatusText,
    });

    await expect(getApiErrorMessage(response)).resolves.toBe(
      fallbackStatusText
    );
  });

  it("falls back to status code when status text is empty", async () => {
    const response = new Response("upstream failed", {
      status: 502,
    });

    await expect(getApiErrorMessage(response)).resolves.toBe(
      "Request failed with status 502"
    );
  });
});
