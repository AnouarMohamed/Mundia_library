import { ReactNode } from "react";
// import Image from "next/image";
import { redirect } from "next/navigation";
import { getSession } from "@/lib/session";

/**
 * Auth layout that blocks signed-in users from auth routes.
 */
const Layout = async ({ children }: { children: ReactNode }) => {
  const session = await getSession();

  // Redirect authenticated users back to home.
  if (session) {
    redirect("/");
  }

  return (
    <main className="auth-container">
      <div className="auth-noise" aria-hidden />
      <section className="auth-form">
        <div className="auth-shell">
          <div className="flex flex-col gap-4">
            <div className="flex flex-row items-center gap-2.5 sm:gap-3">
              <img
                src="/icons/logo.svg"
                alt="logo"
                width={37}
                height={37}
                className="auth-logo size-7 sm:size-[37px]"
              />
              <div>
                <h1 className="font-serif text-3xl font-normal text-white sm:text-4xl">
                  Mundiapolis Library
                </h1>
                <p className="text-[11px] text-light-200/80 sm:text-xs">
                  Student access portal
                </p>
              </div>
            </div>

            <div className="auth-chip-row">
              <span className="auth-chip">Curated Catalog</span>
              <span className="auth-chip">Frictionless Borrowing</span>
              <span className="auth-chip">Academic Ready</span>
            </div>

            <p className="text-sm text-light-100/80 sm:text-base">
              Borrow books, track requests, and manage your academic reading in
              one refined workspace.
            </p>
          </div>

          <div className="auth-box fade-in-up">{children}</div>

          <div className="auth-footnote">
            Session traffic is encrypted and rate-limited for safer access.
          </div>
        </div>
      </section>

      <section className="auth-illustration">
        <img
          src="/images/mundiapolis-campus-optimized.jpg"
          alt="Universite Mundiapolis campus"
          height={1000}
          width={1000}
          className="auth-illustration-image"
        />
        <div className="auth-illustration-overlay">
          <p className="text-[11px] text-light-200/75">Student study access</p>
          <h2 className="mt-2 font-serif text-4xl font-normal text-white sm:text-5xl">
            Built for focused study
          </h2>
          <p className="mt-2 text-sm text-light-100/80 sm:text-base">
            Discover stronger recommendations, streamlined approvals, and clear
            reading history at a glance.
          </p>

          <div className="auth-stat-grid">
            <article className="auth-stat-card">
              <p>Avg Session</p>
              <h3>14m</h3>
            </article>
            <article className="auth-stat-card">
              <p>Catalog Reach</p>
              <h3>10k+</h3>
            </article>
            <article className="auth-stat-card">
              <p>Uptime</p>
              <h3>99.9%</h3>
            </article>
          </div>
          <p className="auth-photo-credit">
            Campus photo:{" "}
            <a
              href="https://commons.wikimedia.org/wiki/File:Campus-mundiapolis.jpg"
              target="_blank"
              rel="noopener noreferrer"
            >
              Wikimedia Commons (CC BY-SA 4.0)
            </a>
          </p>
        </div>
      </section>
    </main>
  );
};

export default Layout;
