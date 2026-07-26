/** @type {import("next").NextConfig} */
const isProduction = process.env.NODE_ENV === "production";

const nextConfig = {
  output: "standalone",
  outputFileTracingRoot: process.cwd(),
  eslint: {
    // `npm run lint` is the enforced Oxlint gate. Avoid running a second,
    // framework-coupled legacy lint pass during the production build.
    ignoreDuringBuilds: true,
  },
  async headers() {
    const securityHeaders = [
      { key: "X-Frame-Options", value: "DENY" },
      { key: "X-Content-Type-Options", value: "nosniff" },
      { key: "X-DNS-Prefetch-Control", value: "off" },
      { key: "X-Download-Options", value: "noopen" },
      { key: "X-Permitted-Cross-Domain-Policies", value: "none" },
      { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
      { key: "Cross-Origin-Opener-Policy", value: "same-origin" },
      { key: "Cross-Origin-Resource-Policy", value: "same-origin" },
      { key: "Origin-Agent-Cluster", value: "?1" },
      {
        key: "Permissions-Policy",
        value: "camera=(), microphone=(), geolocation=(), payment=(), usb=()",
      },
    ];

    if (isProduction) {
      securityHeaders.push({
        key: "Strict-Transport-Security",
        value: "max-age=63072000; includeSubDomains; preload",
      });
    }

    return [
      {
        source: "/:path*",
        headers: securityHeaders,
      },
    ];
  },
  images: {
    remotePatterns: [
      {
        protocol: "https",
        hostname: "placehold.co",
      },
      {
        protocol: "https",
        hostname: "m.media-amazon.com",
      },
      {
        protocol: "https",
        hostname: "ik.imagekit.io",
        port: "",
      },
    ],
  },
  /*
  typescript: {
    ignoreBuildErrors: true,
  },
  */
};

export default nextConfig;
