"use client";

import { useEffect } from "react";
import Link from "next/link";

const RootError = ({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) => {
  useEffect(() => {
    console.error("Student page failed to render", error);
  }, [error]);

  return (
    <section className="mx-auto w-full max-w-xl rounded-lg border border-[var(--mundia-line)] bg-[var(--mundia-surface)] px-5 py-8 text-center">
      <p className="text-sm font-semibold uppercase tracking-wide text-[var(--mundia-gold-strong)]">
        Page unavailable
      </p>
      <h1 className="mt-2 font-serif text-3xl text-[var(--mundia-ink)]">
        We could not load this page
      </h1>
      <p className="mt-3 text-sm leading-6 text-[var(--mundia-muted)]">
        Your account is safe. Check your connection and try again.
      </p>
      <div className="mt-6 flex flex-col gap-2 sm:flex-row sm:justify-center">
        <button
          type="button"
          onClick={reset}
          className="min-h-12 rounded-lg bg-[var(--mundia-navy)] px-6 text-sm font-semibold text-white"
        >
          Try again
        </button>
        <Link
          href="/library"
          className="inline-flex min-h-12 items-center justify-center rounded-lg border border-[var(--mundia-line)] px-6 text-sm font-semibold text-[var(--mundia-ink)]"
        >
          Return to library
        </Link>
      </div>
    </section>
  );
};

export default RootError;
