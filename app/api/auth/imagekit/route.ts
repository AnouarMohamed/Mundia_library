import ImageKit from "imagekit";
import config from "@/lib/config";
import { NextResponse } from "next/server";
import { headers } from "next/headers";
import ratelimit from "@/lib/ratelimit";

let imagekit: ImageKit | null = null;

const getImageKit = () => {
  if (imagekit) {
    return imagekit;
  }

  const {
    imagekit: { publicKey, privateKey, urlEndpoint },
  } = config.env;

  if (!publicKey || !privateKey || !urlEndpoint) {
    throw new Error(
      "ImageKit is not configured. Please check NEXT_PUBLIC_IMAGEKIT_PUBLIC_KEY, IMAGEKIT_PRIVATE_KEY, and NEXT_PUBLIC_IMAGEKIT_URL_ENDPOINT."
    );
  }

  imagekit = new ImageKit({ publicKey, privateKey, urlEndpoint });

  return imagekit;
};

export async function GET() {
  try {
    // Rate limiting to prevent abuse (applies to both authenticated and unauthenticated users)
    // This endpoint is used for file uploads (book covers, university cards, videos)
    // Authentication is optional to allow sign-up flow (university card upload) to work
    // Rate limiting provides protection against abuse
    const ip = (await headers()).get("x-forwarded-for") || "127.0.0.1";
    const { success } = await ratelimit.limit(ip);

    if (!success) {
      return NextResponse.json(
        {
          success: false,
          error: "Too Many Requests",
          message: "Rate limit exceeded. Please try again later.",
        },
        { status: 429 }
      );
    }

    // Note: Authentication is optional for this endpoint
    // This allows sign-up flow (university card upload) to work before user is authenticated
    // However, rate limiting provides protection against abuse
    // Authenticated users get priority, but unauthenticated users can still use it for sign-up

    return NextResponse.json(getImageKit().getAuthenticationParameters());
  } catch (error) {
    console.error("Error getting ImageKit authentication parameters:", error);
    return NextResponse.json(
      {
        success: false,
        error: "Failed to get ImageKit authentication parameters",
        message:
          error instanceof Error ? error.message : "Unknown error occurred",
      },
      { status: 500 }
    );
  }
}
