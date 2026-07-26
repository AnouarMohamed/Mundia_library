/**
 * FileUpload Component
 * 
 * Uploads images and videos through the server-mediated upload endpoint.
 * 
 * Features:
 * - Client-side validation for file size and type.
 * - Dynamic preview for both images and videos.
 * - Specialized "upload intents" for compartmentalized storage (e.g., student IDs vs book covers).
 */

"use client";

import type { ChangeEvent } from "react";
import { useRef, useState, useEffect } from "react";
import { showToast } from "@/lib/toast";
import { cn } from "@/lib/utils";

/**
 * Maps the storage folder and file type to a specific security intent.
 * This intent is verified server-side before issuing an upload signature.
 */
const getUploadIntent = (type: Props["type"], folder: string) => {
  if (folder === "ids") return "signup-card";
  if (folder === "books/covers" && type === "image") return "book-cover";
  if (folder === "books/videos" && type === "video") return "book-video";
  return null;
};

/**
 * Props for the FileUpload component.
 */
interface Props {
  /** The type of media being uploaded. */
  type: "image" | "video";
  /** Standard HTML file acceptance string (e.g., "image/*"). */
  accept: string;
  /** UI text shown when no file is selected. */
  placeholder: string;
  /** Destination folder in the cloud storage. */
  folder: string;
  /** Color theme for the upload button. */
  variant: "dark" | "light";
  /** Callback triggered when a file is successfully uploaded/selected. */
  onFileChange: (filePath: string) => void;
  /** Controlled value (full URL or path) from the parent form. */
  value?: string;
}

const FileUpload = ({
  type,
  accept,
  placeholder,
  folder,
  variant,
  onFileChange,
  value,
}: Props) => {
  const uploadRef = useRef<HTMLInputElement | null>(null);
  
  // Local state for path and progress
  const [file, setFile] = useState<{ filePath: string | null }>({
    filePath: value ?? null,
  });
  const [progress, setProgress] = useState(0);
  
  const uploadIntent = getUploadIntent(type, folder);

  /** 
   * CRITICAL: Sync value prop with internal state.
   * Ensures the preview updates if the form is reset or initialized with SSR data.
   */
  useEffect(() => {
    if (value !== undefined && value !== file.filePath) {
      setFile({ filePath: value ?? null });
    }
  }, [value, file.filePath]);

  const styles = {
    button:
      variant === "dark"
        ? "bg-dark-300"
        : "bg-light-600 border-gray-100 border",
    placeholder: variant === "dark" ? "text-light-100" : "text-slate-500",
    text: variant === "dark" ? "text-light-100" : "text-dark-400",
  };

  /** Generic error handler for all upload scenarios. */
  const onError = (error: unknown): void => {
    void error;
    showToast.error(
      `${type === "image" ? "Image" : "Video"} Upload Failed`,
      `Your ${type} could not be uploaded. Please try again.`,
    );
  };

  /**
   * Client-side safety checks for file size before attempting upload.
   * Limits: 20MB for images, 50MB for videos.
   */
  const onValidate = (file: File): boolean => {
    if (type === "image") {
      if (file.size > 20 * 1024 * 1024) {
        showToast.error("File Too Large", "Images must be smaller than 20MB.");
        return false;
      }
    } else if (type === "video") {
      if (file.size > 50 * 1024 * 1024) {
        showToast.error("File Too Large", "Videos must be smaller than 50MB.");
        return false;
      }
    }
    return true;
  };

  /**
   * Sends the file to the authenticated server upload boundary. The server
   * independently checks authorization, byte length, magic bytes and images by
   * decoding/re-encoding them before storage.
   */
  const onFileSelected = async (event: ChangeEvent<HTMLInputElement>) => {
    const selectedFile = event.target.files?.[0];

    if (!selectedFile || !onValidate(selectedFile) || !uploadIntent) return;

    setProgress(10);
    try {
      const body = new FormData();
      body.set("intent", uploadIntent);
      body.set("file", selectedFile);

      const response = await fetch("/api/uploads", {
        method: "POST",
        body,
      });

      if (!response.ok) {
        throw new Error(`Upload rejected with status ${response.status}`);
      }

      const result = (await response.json()) as { url?: unknown };
      if (typeof result.url !== "string" || !result.url.startsWith("https://")) {
        throw new Error("Upload response did not contain a secure URL");
      }

      setFile({ filePath: result.url });
      onFileChange(result.url);
      setProgress(100);
      showToast.success(
        `${type === "image" ? "Image" : "Video"} Uploaded`,
        "The server verified and stored your file.",
      );
    } catch (error) {
      setProgress(0);
      onError(error);
    } finally {
      event.target.value = "";
    }
  };

  /** Renders the styled upload button. */
  const uploadButton = (
    <button
      className={cn("upload-btn", styles.button)}
      onClick={(e) => {
        e.preventDefault();
        uploadRef.current?.click();
      }}
    >
      <div className="flex flex-col items-center gap-1">
        <div className="flex items-center gap-1.5">
          <img
            src="/icons/upload.svg"
            alt="upload-icon"
            width={20}
            height={20}
            className="size-4 shrink-0 object-contain sm:size-5"
          />
          <p className={cn("text-sm sm:text-base", styles.placeholder)}>
            {placeholder}
          </p>
        </div>

        {file.filePath && (
          <p className={cn("upload-filename break-all text-[10px] sm:text-xs", styles.text)}>
            {file.filePath}
          </p>
        )}
      </div>
    </button>
  );

  /** Renders a simple progress bar during upload. */
  const progressIndicator = (
    <>
      {progress > 0 && progress !== 100 && (
        <div className="w-full rounded-full bg-green-200">
          <div
            className="progress text-[7px] sm:text-[8px]"
            style={{ width: `${progress}%` }}
          >
            {progress}%
          </div>
        </div>
      )}
    </>
  );

  /** Renders the media preview based on the current file path. */
  const preview = file.filePath ? (
    <>
      {type === "image" ? (
        <img
          src={file.filePath}
          alt="Uploaded preview"
          width={500}
          height={300}
          className="h-auto w-full max-w-full rounded-lg"
        />
      ) : type === "video" ? (
        <video
          src={file.filePath}
          controls={true}
          className="h-64 w-full rounded-lg sm:h-96"
        />
      ) : null}
    </>
  ) : null;

  return (
    <>
      <input
        ref={uploadRef}
        type="file"
        accept={accept}
        className="hidden"
        onChange={(event) => {
          void onFileSelected(event);
        }}
      />
      {uploadButton}
      {progressIndicator}
      {preview}
    </>
  );
};

export default FileUpload;
