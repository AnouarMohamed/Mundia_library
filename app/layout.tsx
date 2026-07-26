/**
 * Root Layout
 * 
 * This is the top-level layout for the entire application.
 * It configures global fonts, default metadata, and wraps the application with essential providers.
 * 
 * @module app/layout
 */

import type { Metadata } from "next";
import "./globals.css";
import { Toaster } from "@/components/ui/toaster";
import QueryProvider from "@/components/QueryProvider";

import localFont from "next/font/local";
import { connection } from "next/server";
import { ReactNode } from "react";
import SessionProviderWrapper from "./SessionProviderWrapper";

// Local fonts for consistent typography across the app.
// IBM Plex Sans for primary body text and UI elements.
const ibmPlexSans = localFont({
  src: [
    { path: "/fonts/IBMPlexSans-Regular.ttf", weight: "400", style: "normal" },
    { path: "/fonts/IBMPlexSans-Medium.ttf", weight: "500", style: "normal" },
    { path: "/fonts/IBMPlexSans-SemiBold.ttf", weight: "600", style: "normal" },
    { path: "/fonts/IBMPlexSans-Bold.ttf", weight: "700", style: "normal" },
  ],
});

// Bebas Neue for expressive headings and brand elements.
const bebasNeue = localFont({
  src: [
    { path: "/fonts/BebasNeue-Regular.ttf", weight: "400", style: "normal" },
  ],
  variable: "--bebas-neue",
});

/**
 * Default metadata used across the application for SEO and social sharing.
 * Configures title, description, keywords, and OpenGraph properties.
 */
export const metadata: Metadata = {
  metadataBase: new URL(
    process.env.NEXT_PUBLIC_PROD_API_ENDPOINT || "http://localhost:3000"
  ),
  title: "Mundiapolis Library | University Library Management",
  description:
    "Mundiapolis University Library - A modern library management solution for borrowing, tracking, and discovering books. Your gateway to knowledge at Mundiapolis.",
  authors: [
    {
      name: "Arnob Mahmud",
      url: "https://arnob-mahmud.vercel.app/",
    },
    { name: "arnob_t78@yahoo.com" },
  ],
  keywords: [
    "Mundiapolis",
    "library",
    "university library",
    "book borrowing",
    "library management",
    "student portal",
    "Arnob Mahmud",
    "Next.js",
    "TypeScript",
    "Drizzle ORM",
    "ImageKit",
    "Upstash",
    "Resend",
  ],
  icons: {
    icon: "/favicon.ico",
    shortcut: "/favicon.ico",
    apple: "/images/mundiapolis-mark.png",
  },
  openGraph: {
    title: "Mundiapolis Library | University Library Management",
    description:
      "Mundiapolis University Library - A modern library management solution for borrowing, tracking, and discovering books. Your gateway to knowledge at Mundiapolis.",
    url: "https://arnob-mahmud.vercel.app/",
    siteName: "Mundiapolis Library",
    images: [
      {
        url: "/images/auth-illustration.png",
        width: 1200,
        height: 630,
        alt: "Mundiapolis Library App",
      },
    ],
    locale: "en_US",
    type: "website",
  },
};

/**
 * Root layout component that serves as the entry point for the UI.
 * 
 * Provides:
 * - Session context via SessionProviderWrapper
 * - Global CSS and custom font classes
 * - Query management via QueryProvider (React Query)
 * - Global toast notifications via Toaster
 * 
 * @param {Object} props - Component properties
 * @param {ReactNode} props.children - Child components/pages to render
 */
const RootLayout = async ({ children }: { children: ReactNode }) => {
  // A fresh CSP nonce is generated for every document request. Waiting for the
  // request here prevents static HTML from being emitted without that nonce.
  await connection();

  return (
    <html lang="en" suppressHydrationWarning>
      <SessionProviderWrapper>
        <body
          className={`${ibmPlexSans.className} ${bebasNeue.variable} professional-ui min-h-screen bg-background text-foreground antialiased`}
          suppressHydrationWarning
        >
          <QueryProvider>
            {/* The application content is rendered here */}
            {children}
            {/* Global toast notifications */}
            <Toaster />
          </QueryProvider>
        </body>
      </SessionProviderWrapper>
    </html>
  );
};

export default RootLayout;
