/**
 * BookVideo Component
 *
 * Handles the playback of book trailers and promotional videos.
 * Supports direct URLs, Data-URIs, and optimized ImageKit video delivery.
 *
 * @author Mundia Library Team
 * @version 1.0.0
 */

"use client";

import React from "react";
import { ImageKitProvider, Video as IKVideo } from "@imagekit/next";
import config from "@/lib/config";

/**
 * Safely parses a string into a URL object.
 *
 * @param {string} value - The potential URL string
 * @returns {URL | null} The URL object or null if invalid
 */
const parseVideoUrl = (value: string): URL | null => {
  try {
    return new URL(value);
  } catch {
    return null;
  }
};

/**
 * Validates if a hostname belongs to a trusted ImageKit endpoint.
 *
 * @param {string} hostname - Hostname to check
 * @returns {boolean} True if host is trusted
 */
const isTrustedImageKitHost = (hostname: string): boolean => {
  return hostname === "imagekit.io" || hostname.endsWith(".imagekit.io");
};

/**
 * BookVideo
 *
 * Renders a video player for a book. Includes logic for host validation
 * and fallback display when no valid video is found.
 *
 * @param {Object} props - Component properties
 * @param {string} props.videoUrl - The URL of the video to play
 * @returns {JSX.Element} The rendered video player or placeholder
 */
const BookVideo = ({ videoUrl }: { videoUrl: string }) => {
  // Support for direct data URIs (e.g. for small embedded clips)
  if (videoUrl.startsWith("data:video/")) {
    return (
      <video src={videoUrl} controls={true} className="w-full rounded-lg" />
    );
  }

  const parsedUrl = parseVideoUrl(videoUrl);
  const path = parsedUrl?.pathname.toLowerCase() ?? "";
  const hostname = parsedUrl?.hostname.toLowerCase() ?? "";

  // Strategy: Determine if the URL points to a valid video asset
  const isVideoFile =
    Boolean(parsedUrl) &&
    (path.endsWith(".mp4") ||
      path.endsWith(".webm") ||
      path.endsWith(".ogg") ||
      path.endsWith(".avi") ||
      path.endsWith(".mov") ||
      path.includes("/video/") ||
      (isTrustedImageKitHost(hostname) && path.includes("/books/videos/")));

  // Fallback: Show a friendly message if the video asset is missing or invalid
  if (!isVideoFile) {
    return (
      <div className="flex h-48 w-full items-center justify-center rounded-lg bg-gray-100 sm:h-64">
        <p className="text-sm text-gray-500 sm:text-base">No video available</p>
      </div>
    );
  }

  /**
   * ImageKit Strategy:
   * For ImageKit URLs in the designated videos folder, use the specialized IKVideo
   * component to leverage transformation and optimization features.
   */
  if (
    isTrustedImageKitHost(hostname) &&
    path.includes("/books/videos/") &&
    config.env.imagekit.urlEndpoint
  ) {
    return (
      <ImageKitProvider urlEndpoint={config.env.imagekit.urlEndpoint}>
        <IKVideo
          src={videoUrl}
          controls={true}
          className="h-auto w-full max-w-full rounded-lg"
        />
      </ImageKitProvider>
    );
  }

  // Standard Strategy: Use native HTML5 video player for other valid video URLs
  return <video src={videoUrl} controls={true} className="w-full rounded-lg" />;
};

export default BookVideo;
