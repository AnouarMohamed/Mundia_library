import React from "react";

/**
 * Friendly throttling page for rate-limited users.
 */
const Page = () => {
  return (
    <main className="root-container flex min-h-screen flex-col items-center justify-center px-4">
      <h1 className="font-serif text-3xl font-normal text-light-100 sm:text-5xl">
        Too many requests
      </h1>
      <p className="mt-2 max-w-xl text-center text-sm text-light-400 sm:mt-3 sm:text-base">
        We&apos;ve paused access for a moment. Please wait briefly, then try
        again.
      </p>
    </main>
  );
};
export default Page;
