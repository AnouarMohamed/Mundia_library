import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import {
  ApiError,
  authorizeEditionDownload,
  getCatalogEditions,
  getEditionDownloadAvailability,
  type CatalogEdition,
  type DownloadableFormat,
  type EditionDownloadAvailability,
} from "../api/client";

type LoadState =
  | { kind: "loading" }
  | { kind: "ready"; edition: CatalogEdition; availability: EditionDownloadAvailability }
  | { kind: "missing" | "forbidden" | "failed" };

export function CatalogEditionPage() {
  const { editionId = "" } = useParams();
  const [state, setState] = useState<LoadState>({ kind: "loading" });

  useEffect(() => {
    const controller = new AbortController();
    Promise.all([
      getCatalogEditions([editionId], controller.signal),
      getEditionDownloadAvailability(editionId, controller.signal),
    ]).then(([editions, availability]) => {
      if (controller.signal.aborted) return;
      const edition = editions[0];
      setState(edition ? { kind: "ready", edition, availability } : { kind: "missing" });
    }).catch((error: unknown) => {
      if (controller.signal.aborted) return;
      if (error instanceof ApiError && error.status === 401) {
        window.location.assign("/oauth2/authorization/institutional");
        return;
      }
      if (error instanceof ApiError && error.status === 404) setState({ kind: "missing" });
      else setState({ kind: error instanceof ApiError && error.status === 403 ? "forbidden" : "failed" });
    });
    return () => controller.abort();
  }, [editionId]);

  if (state.kind === "loading") return <div className="detail-skeleton" aria-label="Loading edition"><span /><span /><span /></div>;
  if (state.kind !== "ready") return <EditionError kind={state.kind} />;

  const { edition, availability } = state;
  return (
    <article className="detail-shell edition-detail">
      <Link className="back-link" to="/catalog">← Back to catalog</Link>
      <header className="edition-detail-heading">
        <EditionCover edition={edition} />
        <div>
          <p className="eyebrow">Physical and digital edition</p>
          <h1>{edition.title}</h1>
          <p>{edition.publisher} · {edition.publicationYear} · {edition.language.toUpperCase()}</p>
        </div>
      </header>

      <div className="edition-detail-grid">
        <section aria-labelledby="digital-edition-title">
          <h2 id="digital-edition-title">Digital edition</h2>
          {!availability.downloadable ? (
            <div className="edition-download-empty">
              <h3>No verified digital file is available.</h3>
              <p>You can still request or reserve the physical edition from the catalog.</p>
            </div>
          ) : (
            <>
              <p className="edition-download-intro">Files are authorized only when you choose a format. The download link is short-lived and the licence and attribution remain visible here.</p>
              <ul className="edition-format-list">
                {availability.formats.map((format) => <EditionFormat key={format.assetId} format={format} />)}
              </ul>
            </>
          )}
        </section>

        <aside aria-labelledby="edition-facts-title">
          <h2 id="edition-facts-title">Edition details</h2>
          <dl className="resource-facts">
            <div><dt>ISBN</dt><dd>{edition.isbn}</dd></div>
            <div><dt>Length</dt><dd>{edition.pageCount} pages</dd></div>
            <div><dt>Availability</dt><dd>{edition.availableCopies} of {edition.totalCopies}</dd></div>
            <div><dt>Digital formats</dt><dd>{availability.formats.length > 0 ? availability.formats.map((format) => format.format).join(", ") : "None"}</dd></div>
          </dl>
          <Link className="edition-catalog-action" to={`/catalog?query=${encodeURIComponent(edition.title)}`}>Request or reserve a copy</Link>
        </aside>
      </div>
    </article>
  );
}

function EditionFormat({ format }: { format: DownloadableFormat }) {
  const [state, setState] = useState<"idle" | "authorizing" | "failed">("idle");

  async function download() {
    if (state === "authorizing") return;
    setState("authorizing");
    try {
      const authorization = await authorizeEditionDownload(format.assetId);
      window.location.assign(authorization.downloadUrl);
    } catch (error: unknown) {
      if (error instanceof ApiError && error.status === 401) {
        window.location.assign("/oauth2/authorization/institutional");
        return;
      }
      setState("failed");
    }
  }

  return (
    <li>
      <div className="edition-format-heading">
        <div><strong>{format.format}</strong><span>{formatBytes(format.sizeBytes)}</span></div>
        <button type="button" onClick={() => void download()} disabled={state === "authorizing"}>{state === "authorizing" ? "Authorizing…" : `Download ${format.format}`}</button>
      </div>
      <dl>
        <div><dt>Licence</dt><dd>{format.licenseExpression}</dd></div>
        <div><dt>Attribution</dt><dd>{format.attribution}</dd></div>
        <div><dt>SHA-256</dt><dd><code>{format.sha256}</code></dd></div>
      </dl>
      {state === "failed" && <output className="command-notice command-error">The secure download could not be authorized. Nothing was downloaded; try again in a moment.</output>}
    </li>
  );
}

function EditionCover({ edition }: { edition: CatalogEdition }) {
  return <div className="edition-detail-cover" aria-hidden="true">{edition.coverUrl ? <img src={edition.coverUrl} alt="" /> : <span>{edition.title.charAt(0)}</span>}</div>;
}

function formatBytes(bytes: number) {
  const megabytes = bytes / (1024 * 1024);
  return megabytes >= 1 ? `${megabytes.toFixed(megabytes >= 10 ? 0 : 1)} MB` : `${Math.ceil(bytes / 1024)} KB`;
}

function EditionError({ kind }: { kind: "missing" | "forbidden" | "failed" }) {
  const copy = kind === "missing"
    ? { title: "This edition was not found.", body: "It may have been removed from the active catalog." }
    : kind === "forbidden"
      ? { title: "Digital content is not available for this account.", body: "Contact library staff if you believe this is incorrect." }
      : { title: "This edition could not be loaded.", body: "No download was started. Try again in a moment." };
  return <section className="status-page"><p className="eyebrow">Catalog edition</p><h1>{copy.title}</h1><p>{copy.body}</p>{kind === "failed" && <button type="button" onClick={() => window.location.reload()}>Try again</button>}<Link to="/catalog">Return to catalog</Link></section>;
}
