/**
 * Session Provider Wrapper
 * 
 * A client-side wrapper for the NextAuth SessionProvider.
 * This component allows using useSession() and other session-related hooks in client components
 * by providing the necessary context at the root of the application.
 * 
 * @module app/SessionProviderWrapper
 */

"use client";
import { SessionProvider } from "next-auth/react";
import { ReactNode } from "react";

/**
 * Client-side component that wraps its children with the NextAuth SessionProvider.
 * 
 * @param {Object} props - Component properties
 * @param {ReactNode} props.children - Components that need access to session context
 */
const SessionProviderWrapper = ({ children }: { children: ReactNode }) => {
  return <SessionProvider>{children}</SessionProvider>;
};

export default SessionProviderWrapper;
