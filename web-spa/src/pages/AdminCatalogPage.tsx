import { useEffect, useState, type FormEvent } from "react";
import { Link } from "react-router-dom";
import {
  ApiError,
  createAdminCatalogEdition,
  createAdminCatalogWork,
  getAdminCatalogEditions,
  setAdminCatalogEditionActive,
  updateAdminCatalogEdition,
  type CatalogEdition,
  type CatalogSearch,
  type CreateCatalogEdition,
  type CreateCatalogWork,
  type UpdateCatalogEdition,
} from "../api/client";
import { AdminNavigation } from "../components/AdminNavigation";

type LoadState = { kind: "loading" } | { kind: "ready"; result: CatalogSearch } | { kind: "forbidden" | "failed" };
type Editor = { kind: "work" } | { kind: "edition"; workId?: string } | { kind: "edit"; edition: CatalogEdition };
type Activation = { edition: CatalogEdition; isActive: boolean; reason: string; idempotencyKey: string };

export function AdminCatalogPage() {
  const [query, setQuery] = useState("");
  const [submittedQuery, setSubmittedQuery] = useState("");
  const [refreshToken, setRefreshToken] = useState(0);
  const [state, setState] = useState<LoadState>({ kind: "loading" });
  const [editor, setEditor] = useState<Editor | null>(null);
  const [activation, setActivation] = useState<Activation | null>(null);
  const [saving, setSaving] = useState(false);
  const [feedback, setFeedback] = useState<string | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    setState({ kind: "loading" });
    void getAdminCatalogEditions(submittedQuery, 0, 25, controller.signal)
      .then((result) => { if (!controller.signal.aborted) setState({ kind: "ready", result }); })
      .catch((error: unknown) => {
        if (controller.signal.aborted) return;
        if (error instanceof ApiError && error.status === 401) return window.location.assign("/oauth2/authorization/institutional");
        setState({ kind: error instanceof ApiError && error.status === 403 ? "forbidden" : "failed" });
      });
    return () => controller.abort();
  }, [submittedQuery, refreshToken]);

  function refresh() { setRefreshToken((value) => value + 1); }

  async function applyActivation(event: FormEvent) {
    event.preventDefault();
    if (!activation || state.kind !== "ready" || saving || activation.reason.trim().length < 8) return;
    setSaving(true);
    setFeedback(null);
    try {
      const command = await setAdminCatalogEditionActive(activation.edition, activation.isActive, activation.reason.trim(), activation.idempotencyKey);
      setState({ kind: "ready", result: { ...state.result, editions: state.result.editions.map((item) => item.editionId === activation.edition.editionId ? { ...item, isActive: activation.isActive, aggregateVersion: command.aggregateVersion } : item) } });
      setFeedback(`${activation.edition.title} is now ${activation.isActive ? "active" : "inactive"}. The change was audited.`);
      setActivation(null);
    } catch (error: unknown) {
      handleSession(error);
      setFeedback(error instanceof ApiError && error.status === 409 ? "This edition changed elsewhere. Refresh before trying again." : "The activation change was not applied.");
    } finally { setSaving(false); }
  }

  if (state.kind === "loading") return <div className="admin-skeleton" aria-label="Loading catalog administration"><span /><span /><span /></div>;
  if (state.kind !== "ready") return <section className="status-page"><p className="eyebrow">Catalog administration</p><h1>{state.kind === "forbidden" ? "Administrative access required." : "The catalog workspace could not be loaded."}</h1><Link to="/catalog">Return to catalog</Link></section>;

  const active = state.result.editions.filter((edition) => edition.isActive).length;
  return <article className="admin-shell">
    <AdminNavigation />
    <header className="admin-heading">
      <div><p className="eyebrow">Metadata operations</p><h1>Catalog administration</h1><p>Create works and editions, correct metadata, and control publication without changing Circulation-owned copy counts.</p></div>
      <aside><strong>Versioned catalog writes</strong><span>Every command requires fresh admin status, an exact aggregate version, an audit reason, and a replay-safe key.</span></aside>
    </header>
    <section className="admin-overview" aria-label="Catalog workspace summary">
      <div><span>Records shown</span><strong>{state.result.editions.length}</strong><small>of {state.result.total} matching editions</small></div>
      <div><span>Active shown</span><strong>{active}</strong><small>inactive editions remain manageable</small></div>
      <div><span>Copy authority</span><strong>Circulation</strong><small>catalog metadata cannot alter inventory</small></div>
    </section>
    {feedback && <output className="admin-feedback">{feedback}</output>}
    <section className="admin-workspace">
      <div className="admin-section-heading catalog-admin-toolbar">
        <form onSubmit={(event) => { event.preventDefault(); setSubmittedQuery(query.trim()); }}><label htmlFor="catalog-admin-search">Find editions</label><div><input id="catalog-admin-search" value={query} maxLength={200} onChange={(event) => setQuery(event.target.value)} placeholder="Title, work, summary, or author" /><button type="submit">Search</button></div></form>
        <div className="admin-member-actions"><button type="button" onClick={() => setEditor({ kind: "work" })}>New work</button><button type="button" onClick={() => setEditor({ kind: "edition" })}>Add edition</button></div>
      </div>
      {state.result.editions.length === 0 ? <div className="admin-empty"><h3>No editions found.</h3><p>Adjust the search or create an authoritative work and edition.</p></div> :
        <ol className="catalog-admin-list">{state.result.editions.map((edition) => <li key={edition.editionId}>
          <div><strong>{edition.title}</strong><span>{edition.publisher} · {edition.publicationYear} · ISBN {edition.isbn}</span></div>
          <dl><div><dt>Status</dt><dd>{edition.isActive ? "Active" : "Inactive"}</dd></div><div><dt>Copies</dt><dd>{edition.availableCopies} of {edition.totalCopies}</dd></div><div><dt>Version</dt><dd>{edition.aggregateVersion}</dd></div></dl>
          <div className="admin-member-actions"><button type="button" onClick={() => setEditor({ kind: "edit", edition })}>Edit</button><button type="button" className={edition.isActive ? "danger-action" : undefined} onClick={() => setActivation({ edition, isActive: !edition.isActive, reason: "", idempotencyKey: `spa:catalog-activation:${crypto.randomUUID()}` })}>{edition.isActive ? "Deactivate" : "Activate"}</button></div>
        </li>)}</ol>}
    </section>
    {editor && <CatalogEditor key={editor.kind === "edit" ? editor.edition.editionId : `${editor.kind}:${editor.kind === "edition" ? editor.workId ?? "new" : "new"}`} editor={editor} saving={saving} onCancel={() => setEditor(null)} onSaving={setSaving} onFeedback={setFeedback} onCreatedWork={(workId) => setEditor({ kind: "edition", workId })} onSaved={() => { setEditor(null); refresh(); }} />}
    {activation && <form className="admin-decision" onSubmit={(event) => void applyActivation(event)}><div><p className="eyebrow">Confirm publication change</p><h2>{activation.isActive ? "Activate" : "Deactivate"} {activation.edition.title}</h2><p>This affects discovery only. Physical inventory remains unchanged.</p></div><label>Reason<textarea required minLength={8} maxLength={500} value={activation.reason} onChange={(event) => setActivation({ ...activation, reason: event.target.value })} /></label><div className="admin-decision-actions"><button type="button" disabled={saving} onClick={() => setActivation(null)}>Cancel</button><button type="submit" disabled={saving || activation.reason.trim().length < 8} className={!activation.isActive ? "danger-action" : undefined}>{saving ? "Applying…" : "Confirm"}</button></div></form>}
  </article>;
}

function CatalogEditor({ editor, saving, onCancel, onSaving, onFeedback, onCreatedWork, onSaved }: { editor: Editor; saving: boolean; onCancel: () => void; onSaving: (value: boolean) => void; onFeedback: (value: string) => void; onCreatedWork: (workId: string) => void; onSaved: () => void }) {
  const edition = editor.kind === "edit" ? editor.edition : null;
  const [workId, setWorkId] = useState(editor.kind === "edition" ? editor.workId ?? "" : edition?.workId ?? "");
  const [title, setTitle] = useState(edition?.title ?? "");
  const [isbn, setIsbn] = useState(edition?.isbn ?? "");
  const [publisher, setPublisher] = useState(edition?.publisher ?? "");
  const [year, setYear] = useState(String(edition?.publicationYear ?? new Date().getFullYear()));
  const [language, setLanguage] = useState(edition?.language ?? "English");
  const [pages, setPages] = useState(String(edition?.pageCount ?? ""));
  const [genre, setGenre] = useState(""); const [summary, setSummary] = useState(""); const [description, setDescription] = useState(""); const [author, setAuthor] = useState("");
  const [reason, setReason] = useState("");
  const [idempotencyKey] = useState(() => `spa:catalog-${editor.kind}:${crypto.randomUUID()}`);
  async function submit(event: FormEvent) {
    event.preventDefault(); if (saving) return; onSaving(true);
    try {
      if (editor.kind === "work") {
        const newWorkId = crypto.randomUUID();
        const command: CreateCatalogWork = { workId: newWorkId, title: title.trim(), summary: summary.trim(), description: description.trim(), genre: genre.trim(), authors: [{ contributorId: crypto.randomUUID(), name: author.trim(), bio: null }], reason: reason.trim() };
        await createAdminCatalogWork(command, idempotencyKey); onFeedback("Work created. Add its first edition now."); onCreatedWork(newWorkId);
      } else {
        const command: UpdateCatalogEdition = { title: title.trim(), isbn: isbn.trim(), publisher: publisher.trim(), publicationYear: Number(year), language: language.trim(), pageCount: Number(pages), coverUrl: edition?.coverUrl ?? null, coverColor: edition?.coverColor ?? null, videoUrl: edition?.videoUrl ?? null, reason: reason.trim() };
        if (editor.kind === "edit") await updateAdminCatalogEdition(editor.edition, command, idempotencyKey);
        else await createAdminCatalogEdition(workId.trim(), { ...command, editionId: crypto.randomUUID(), isActive: true } as CreateCatalogEdition, idempotencyKey);
        onFeedback(editor.kind === "edit" ? "Edition metadata updated and audited." : "Edition created and activated."); onSaved();
      }
    } catch (error: unknown) { handleSession(error); onFeedback(error instanceof ApiError && error.status === 409 ? "The catalog changed or conflicts with existing metadata." : "The catalog command was not applied."); }
    finally { onSaving(false); }
  }
  const isWork = editor.kind === "work";
  return <form className="catalog-admin-editor" onSubmit={(event) => void submit(event)}><header><div><p className="eyebrow">{isWork ? "New authority record" : editor.kind === "edit" ? "Correct edition" : "New edition"}</p><h2>{isWork ? "Create work" : editor.kind === "edit" ? `Edit ${editor.edition.title}` : "Add edition"}</h2></div><button type="button" onClick={onCancel}>Close</button></header>
    {!isWork && <label>Work ID<input required value={workId} disabled={editor.kind === "edit"} onChange={(event) => setWorkId(event.target.value)} /></label>}
    <label>Title<input required maxLength={500} value={title} onChange={(event) => setTitle(event.target.value)} /></label>
    {isWork ? <><label>Genre<input required maxLength={120} value={genre} onChange={(event) => setGenre(event.target.value)} /></label><label>Author<input required maxLength={300} value={author} onChange={(event) => setAuthor(event.target.value)} /></label><label>Summary<textarea maxLength={1000} value={summary} onChange={(event) => setSummary(event.target.value)} /></label><label>Description<textarea maxLength={10000} value={description} onChange={(event) => setDescription(event.target.value)} /></label></> : <div className="catalog-editor-grid"><label>ISBN<input required maxLength={32} value={isbn} onChange={(event) => setIsbn(event.target.value)} /></label><label>Publisher<input required maxLength={300} value={publisher} onChange={(event) => setPublisher(event.target.value)} /></label><label>Publication year<input required type="number" min={1000} max={3000} value={year} onChange={(event) => setYear(event.target.value)} /></label><label>Language<input required maxLength={80} value={language} onChange={(event) => setLanguage(event.target.value)} /></label><label>Page count<input required type="number" min={1} max={100000} value={pages} onChange={(event) => setPages(event.target.value)} /></label></div>}
    <label>Audit reason<textarea required minLength={8} maxLength={500} value={reason} onChange={(event) => setReason(event.target.value)} placeholder="Why is this catalog change required?" /></label>
    <div className="admin-decision-actions"><button type="button" disabled={saving} onClick={onCancel}>Cancel</button><button type="submit" disabled={saving || reason.trim().length < 8}>{saving ? "Saving…" : "Save"}</button></div>
  </form>;
}

function handleSession(error: unknown) { if (error instanceof ApiError && error.status === 401) window.location.assign("/oauth2/authorization/institutional"); }
