"use client";

import React from "react";
import { IKVideo, ImageKitProvider } from "imagekitio-next";
import config from "@/lib/config";

/**
 * Safely parse a video URL.
 */
const parseVideoUrl = (value: string): URL | null => {
  try {
    return new URL(value);
  } catch {
    return null;
  }
};

/**
 * Restrict video playback to trusted ImageKit hosts.
 */
const isTrustedImageKitHost = (hostname: string): boolean => {
  return hostname === "imagekit.io" || hostname.endsWith(".imagekit.io");
};

/**
 * Render a book trailer or fallback placeholder.
 */
const BookVideo = ({ videoUrl }: { videoUrl: string }) => {
  if (videoUrl.startsWith("data:video/")) {
    return (
      <video src={videoUrl} controls={true} className="w-full rounded-lg" />
    );
  }

  const parsedUrl = parseVideoUrl(videoUrl);
  const path = parsedUrl?.pathname.toLowerCase() ?? "";
  const hostname = parsedUrl?.hostname.toLowerCase() ?? "";

  // Check if the URL is actually a video file or a trusted ImageKit video URL.
  const isVideoFile =
    Boolean(parsedUrl) &&
    (path.endsWith(".mp4") ||
      path.endsWith(".webm") ||
      path.endsWith(".ogg") ||
      path.endsWith(".avi") ||
      path.endsWith(".mov") ||
      path.includes("/video/") ||
      (isTrustedImageKitHost(hostname) && path.includes("/books/videos/")));

  // If it's not a video file, show a placeholder
  if (!isVideoFile) {
    return (
      <div className="flex h-48 w-full items-center justify-center rounded-lg bg-gray-100 sm:h-64">
        <p className="text-sm text-gray-500 sm:text-base">No video available</p>
      </div>
    );
  }

  // For ImageKit URLs in videos folder, try to play them as videos
  // even if they have .png extension (they might be misnamed video files)
  if (
    isTrustedImageKitHost(hostname) &&
    path.includes("/books/videos/") &&
    config.env.imagekit.publicKey &&
    config.env.imagekit.urlEndpoint
  ) {
    return (
      <ImageKitProvider
        publicKey={config.env.imagekit.publicKey}
        urlEndpoint={config.env.imagekit.urlEndpoint}
      >
        <IKVideo
          src={videoUrl}
          controls={true}
          className="h-auto w-full max-w-full rounded-lg"
        />
      </ImageKitProvider>
    );
  }

  return <video src={videoUrl} controls={true} className="w-full rounded-lg" />;
};
export default BookVideo;
