import type { Metadata } from "next";
import "./globals.css";
import { Toaster } from "@/components/ui/toaster";
import QueryProvider from "@/components/QueryProvider";

import localFont from "next/font/local";
import { ReactNode } from "react";
import SessionProviderWrapper from "./SessionProviderWrapper";

const ibmPlexSans = localFont({
  src: [
    { path: "/fonts/IBMPlexSans-Regular.ttf", weight: "400", style: "normal" },
    { path: "/fonts/IBMPlexSans-Medium.ttf", weight: "500", style: "normal" },
    { path: "/fonts/IBMPlexSans-SemiBold.ttf", weight: "600", style: "normal" },
    { path: "/fonts/IBMPlexSans-Bold.ttf", weight: "700", style: "normal" },
  ],
});

const bebasNeue = localFont({
  src: [
    { path: "/fonts/BebasNeue-Regular.ttf", weight: "400", style: "normal" },
  ],
  variable: "--bebas-neue",
});

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
    apple: "/favicon.ico",
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

const RootLayout = ({ children }: { children: ReactNode }) => {
  return (
    <html lang="en" suppressHydrationWarning>
      <SessionProviderWrapper>
        <body
          className={`${ibmPlexSans.className} ${bebasNeue.variable} professional-ui min-h-screen bg-background text-foreground antialiased`}
          suppressHydrationWarning
        >
          <QueryProvider>
            {children}
            <Toaster />
          </QueryProvider>
        </body>
      </SessionProviderWrapper>
    </html>
  );
};

export default RootLayout;
