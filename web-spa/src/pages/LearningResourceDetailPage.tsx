import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { ApiError, getLearningResource, type LearningResource } from "../api/client";
import { DownloadButton } from "../components/DownloadButton";
import { ResourceCover } from "../components/ResourceCover";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu;

export function LearningResourceDetailPage() {
  const { resourceId = "" } = useParams();
  const [resource, setResource] = useState<LearningResource | null>(null);
  const [status, setStatus] = useState<"loading" | "missing" | "failed">("loading");

  useEffect(() => {
    if (!UUID.test(resourceId)) { setStatus("missing"); return; }
    void getLearningResource(resourceId).then((value) => { setResource(value); setStatus("loading"); }).catch((error: unknown) => {
      if (error instanceof ApiError && error.status === 401) window.location.assign("/oauth2/authorization/institutional");
      else setStatus(error instanceof ApiError && error.status === 404 ? "missing" : "failed");
    });
  }, [resourceId]);

  if (!resource) return <DetailState status={status} />;
  return (
    <article className="detail-shell">
      <Link className="back-link" to="/resources">Back to open learning</Link>
      <header className="detail-heading"><ResourceCover resource={resource} detail /><div><p className="eyebrow">{resource.category}</p><h1>{resource.title}</h1><p>{resource.author ?? "Author not listed"}</p></div></header>
      <div className="detail-grid">
        <section>
          {resource.description && <><h2>About this resource</h2><p className="description">{resource.description}</p></>}
          <h2>Access</h2>
          <p>The file or reading page stays with the verified third-party source. Mundia does not host this material.</p>
          <div className="detail-actions">
            {resource.accessMode === "DOWNLOAD" ? <DownloadButton resourceId={resource.resourceId} sourceName={resource.sourceName} /> : resource.readUrl && <a className="primary-action" href={resource.readUrl} target="_blank" rel="noopener noreferrer">Open official source</a>}
          </div>
        </section>
        <dl className="resource-facts">
          <div><dt>Licence</dt><dd><a href={resource.licenseUrl} target="_blank" rel="noopener noreferrer">{resource.licenseExpression}</a></dd></div>
          <div><dt>Source</dt><dd><a href={resource.sourceUrl} target="_blank" rel="noopener noreferrer">{resource.sourceName}</a></dd></div>
          <div><dt>Language</dt><dd>{resource.language.toUpperCase()}</dd></div>
        </dl>
      </div>
    </article>
  );
}

function DetailState({ status }: { status: "loading" | "missing" | "failed" }) {
  if (status === "loading") return <div className="detail-skeleton" aria-label="Loading resource"><span /><span /><span /></div>;
  return <section className="status-page"><h1>{status === "missing" ? "Resource not found." : "Resource could not be loaded."}</h1><p>{status === "missing" ? "It may no longer meet the verified-access policy." : "Try again in a moment."}</p><Link to="/resources">Return to open learning</Link></section>;
}
