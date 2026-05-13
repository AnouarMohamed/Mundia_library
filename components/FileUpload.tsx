"use client";

import { IKImage, ImageKitProvider, IKUpload, IKVideo } from "imagekitio-next";
import config from "@/lib/config";
import type { ChangeEvent } from "react";
import { useRef, useState, useEffect } from "react";
// import Image from "next/image";
import { showToast } from "@/lib/toast";
import { cn } from "@/lib/utils";

const {
  env: {
    imagekit: { publicKey, urlEndpoint },
  },
} = config;
const isImageKitConfigured = Boolean(publicKey && urlEndpoint);

const authenticator = async () => {
  try {
    const response = await fetch("/api/auth/imagekit");

    if (!response.ok) {
      const errorText = await response.text();

      throw new Error(
        `Request failed with status ${response.status}: ${errorText}`
      );
    }

    const data = await response.json();

    const { signature, expire, token } = data;

    return { token, expire, signature };
  } catch (error: unknown) {
    if (error instanceof Error) {
      throw new Error(`Authentication request failed: ${error.message}`);
    } else {
      throw new Error("Authentication request failed: Unknown error");
    }
  }
};

interface Props {
  type: "image" | "video";
  accept: string;
  placeholder: string;
  folder: string;
  variant: "dark" | "light";
  onFileChange: (filePath: string) => void;
  value?: string;
}

/**
 * File upload control. Uses ImageKit when configured, otherwise stores a data URL
 * so deployments can run without a third-party upload provider.
 */
const FileUpload = ({
  type,
  accept,
  placeholder,
  folder,
  variant,
  onFileChange,
  value,
}: Props) => {
  const ikUploadRef = useRef<HTMLInputElement | null>(null);
  const localUploadRef = useRef<HTMLInputElement | null>(null);
  const [file, setFile] = useState<{ filePath: string | null }>({
    filePath: value ?? null,
  });
  const [progress, setProgress] = useState(0);

  // CRITICAL: Sync value prop with internal state
  // This ensures the component updates when SSR data is provided (e.g., when editing a book)
  // The value prop comes from form fields (react-hook-form) which may be initialized with SSR data
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

  /**
   * Handle file upload errors
   *
   * @param error - Error object from ImageKit upload
   */
  const onError = (error: unknown): void => {
    console.log(error);

    showToast.error(
      `${type === "image" ? "Image" : "Video"} Upload Failed`,
      `Your ${type} could not be uploaded. Please try again.`
    );
  };

  /**
   * Upload success response type from ImageKit
   * Note: imagekitio-next types may not fully match, so we use a flexible interface
   */
  interface UploadSuccessResponse {
    filePath: string;
    [key: string]: unknown;
  }

  /**
   * Handle successful file upload
   *
   * @param res - Upload success response from ImageKit containing filePath
   * Note: Using type assertion because imagekitio-next's IKUploadResponse type
   * doesn't match the actual response structure at runtime
   */
  const onSuccess = (res: unknown): void => {
    // Type guard: Check if response has filePath property
    const response = res as UploadSuccessResponse;

    if (!response.filePath || typeof response.filePath !== "string") {
      console.error("Upload response missing filePath:", res);
      onError(new Error("Upload response missing filePath"));
      return;
    }

    // Construct full ImageKit URL from the relative filePath
    const fullUrl = `${urlEndpoint}${response.filePath}`;

    setFile({ filePath: fullUrl });
    onFileChange(fullUrl);

    showToast.success(
      ` ${type === "image" ? "Image" : "Video"} Uploaded Successfully!`,
      `${response.filePath} has been uploaded and is ready to use.`
    );
  };

  /**
   * Validate file before upload
   *
   * @param file - File to validate
   * @returns true if file is valid, false otherwise
   */
  const onValidate = (file: File): boolean => {
    if (type === "image") {
      if (file.size > 20 * 1024 * 1024) {
        showToast.error(
          " File Too Large",
          "Image files must be smaller than 20MB. Please compress your image and try again."
        );

        return false;
      }
    } else if (type === "video") {
      if (file.size > 50 * 1024 * 1024) {
        showToast.error(
          " File Too Large",
          "Video files must be smaller than 50MB. Please compress your video and try again."
        );
        return false;
      }
    }

    return true;
  };

  const onLocalFileChange = (event: ChangeEvent<HTMLInputElement>) => {
    const selectedFile = event.target.files?.[0];

    if (!selectedFile || !onValidate(selectedFile)) {
      return;
    }

    setProgress(0);

    const reader = new FileReader();
    reader.onload = () => {
      const filePath = typeof reader.result === "string" ? reader.result : "";

      if (!filePath) {
        onError(new Error("Could not read selected file"));
        return;
      }

      setFile({ filePath });
      onFileChange(filePath);
      setProgress(100);
      showToast.success(
        `${type === "image" ? "Image" : "Video"} Selected`,
        "The file is stored with the form because no upload provider is configured."
      );
    };
    reader.onerror = () => onError(reader.error);
    reader.readAsDataURL(selectedFile);
    event.target.value = "";
  };

  const uploadButton = (
    <button
      className={cn("upload-btn", styles.button)}
      onClick={(e) => {
        e.preventDefault();
        ikUploadRef.current?.click();
        localUploadRef.current?.click();
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

        {file && (
          <p
            className={cn(
              "upload-filename break-all text-[10px] sm:text-xs",
              styles.text
            )}
          >
            {file.filePath}
          </p>
        )}
      </div>
    </button>
  );

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

  const preview = (
    <>
      {file &&
        (type === "image" ? (
          file.filePath?.startsWith("http") ||
          file.filePath?.startsWith("data:") ? (
            <img
              src={file.filePath}
              alt="Uploaded image"
              width={500}
              height={300}
              className="h-auto w-full max-w-full rounded-xl"
            />
          ) : (
            <IKImage
              alt={file.filePath ?? ""}
              path={file.filePath ?? ""}
              width={500}
              height={300}
              className="h-auto w-full max-w-full"
            />
          )
        ) : type === "video" ? (
          file.filePath?.startsWith("http") ||
          file.filePath?.startsWith("data:") ? (
            <video
              src={file.filePath}
              controls={true}
              className="h-64 w-full rounded-xl sm:h-96"
            />
          ) : (
            <IKVideo
              path={file.filePath ?? ""}
              controls={true}
              className="h-64 w-full rounded-xl sm:h-96"
            />
          )
        ) : null)}
    </>
  );

  if (!isImageKitConfigured) {
    return (
      <>
        <input
          ref={localUploadRef}
          type="file"
          accept={accept}
          className="hidden"
          onChange={onLocalFileChange}
        />
        {uploadButton}
        {progressIndicator}
        {preview}
      </>
    );
  }

  return (
    <ImageKitProvider
      publicKey={publicKey}
      urlEndpoint={urlEndpoint}
      authenticator={authenticator}
    >
      <IKUpload
        ref={ikUploadRef}
        onError={onError}
        onSuccess={onSuccess}
        useUniqueFileName={true}
        validateFile={onValidate}
        onUploadStart={() => setProgress(0)}
        onUploadProgress={({ loaded, total }) => {
          const percent = Math.round((loaded / total) * 100);
          setProgress(percent);
        }}
        folder={folder}
        accept={accept}
        className="hidden"
      />
      {uploadButton}
      {progressIndicator}
      {preview}
    </ImageKitProvider>
  );
};

export default FileUpload;
