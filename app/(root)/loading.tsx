const RootLoading = () => (
  <div className="mx-auto w-full max-w-[1500px]" aria-busy="true" aria-live="polite">
    <span className="sr-only">Loading page</span>
    <div className="h-5 w-28 animate-pulse rounded bg-slate-200 motion-reduce:animate-none" />
    <div className="mt-3 h-10 w-3/4 max-w-md animate-pulse rounded bg-slate-200 motion-reduce:animate-none" />
    <div className="mt-3 h-5 w-full max-w-xl animate-pulse rounded bg-slate-100 motion-reduce:animate-none" />
    <div className="mt-7 grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4">
      {Array.from({ length: 4 }, (_, index) => (
        <div
          key={index}
          className="h-72 animate-pulse rounded-lg border border-[var(--mundia-line)] bg-[var(--mundia-surface)] motion-reduce:animate-none"
        />
      ))}
    </div>
  </div>
);

export default RootLoading;
