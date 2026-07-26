/**
 * Authentication Layout
 * 
 * Layout for authentication-related pages (sign-in, sign-up).
 * It provides a split-screen design with a form on one side and a brand illustration on the other.
 * This layout also prevents authenticated users from accessing auth pages by redirecting them.
 * 
 * @module app/(auth)/layout
 */

import { ReactNode } from "react";
import { redirect } from "next/navigation";
import { getSession } from "@/lib/session";

/**
 * Authentication layout component.
 * 
 * Performs a server-side check for an existing session.
 * If a session exists, the user is redirected to the home page.
 * 
 * @param {Object} props - Component properties
 * @param {ReactNode} props.children - Auth form content (sign-in or sign-up)
 */
const Layout = async ({ children }: { children: ReactNode }) => {
  // Check for an existing session to prevent authenticated users from re-authenticating.
  const session = await getSession();

  if (session) {
    // Redirect to home if already logged in.
    redirect("/");
  }

  return (
    <main className="grid min-h-screen lg:grid-cols-2">
      {/* Left side: Authentication form container */}
      <section className="flex items-center justify-center bg-white px-6 py-12">
        <div className="mx-auto w-full max-w-sm">
          {/* Brand Logo */}
          <img
            src="/images/mundia-logo.png"
            alt="Mundiapolis"
            width={200}
            height={67}
            className="mb-10 h-auto w-[180px] sm:w-[200px]"
          />
          {/* Auth form (children) */}
          {children}
        </div>
      </section>

      {/* Right side: Brand illustration and campus image (hidden on mobile) */}
      <section className="relative hidden lg:block">
        <img
          src="/images/mundiapolis-campus-optimized.jpg"
          alt="Université Mundiapolis campus"
          className="size-full object-cover"
          style={{ objectPosition: "74% center" }}
        />
        {/* Overlay gradient for text readability */}
        <div className="absolute inset-0 bg-gradient-to-t from-black/60 via-transparent to-transparent" />
        {/* Brand slogan */}
        <div className="absolute bottom-8 left-8 right-8 z-10">
          <p className="text-lg font-serif text-white sm:text-2xl">Mundiatheque</p>
          <p className="mt-1 text-sm text-white/80">Ouvrez un livre, ouvrez votre esprit.</p>
        </div>
      </section>
    </main>
  );
};

export default Layout;
