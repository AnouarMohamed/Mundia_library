"use client";
import { SessionProvider } from "next-auth/react";
import { ReactNode } from "react";

/**
 * Client-side wrapper for NextAuth session context.
 */
const SessionProviderWrapper = ({ children }: { children: ReactNode }) => {
  return <SessionProvider>{children}</SessionProvider>;
};

export default SessionProviderWrapper;
