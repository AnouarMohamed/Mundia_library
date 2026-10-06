import { useState } from "react";
import { ApiError, authorizeExternalDownload } from "../api/client";

export function DownloadButton({ resourceId, sourceName }: { resourceId: string; sourceName: string }) {
  const [state, setState] = useState<"idle" | "loading" | "error">("idle");

  async function download() {
    setState("loading");
    try {
      const url = await authorizeExternalDownload(resourceId);
      window.location.assign(url);
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        window.location.assign("/oauth2/authorization/institutional");
        return;
      }
      setState("error");
    }
  }

  return (
    <div className="download-control">
      <button className="primary-action" type="button" onClick={download} disabled={state === "loading"}>
        {state === "loading" ? "Preparing download…" : `Download from ${sourceName}`}
      </button>
      {state === "error" && <p role="alert">The verified download is unavailable right now. Try again later.</p>}
    </div>
  );
}
