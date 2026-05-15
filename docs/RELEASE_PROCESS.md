# Release Process

This document describes how to ship Mundiapolis Library intentionally.

## Release Types

| Type | Example | Use |
| --- | --- | --- |
| Patch | `v0.2.2` | Bug fix, docs update, small UI polish, safe operational fix. |
| Minor | `v0.3.0` | New workflow, notable product improvement, schema addition. |
| Major | `v1.0.0` | Public stable release or breaking operational change. |

The current project is pre-`1.0`, so minor versions can still contain larger product changes. Document breaking setup or data changes clearly.

## Pre-Release Checklist

1. Confirm the branch is based on the intended release base.
2. Review uncommitted files:

```bash
git status --short
```

3. Run the quality gate:

```bash
npm run ci:quality
```

4. If API, database, cache, or server behavior changed, run benchmarks:

```bash
npm run benchmark:api
```

5. If schema changed, confirm migration plan and backup.
6. Confirm docs are updated.
7. Confirm security checks are passing or have documented exceptions.
8. Deploy to preview or staging when available.
9. Smoke test student and admin flows.

## Version Update

The app version is stored in `package.json`.

For a patch release:

```bash
npm version patch --no-git-tag-version
```

For a minor release:

```bash
npm version minor --no-git-tag-version
```

Commit the version change with the release changes.

## Production Deployment

Deploy before or after tagging depending on your release policy. Current flow:

1. Merge or push the release commit.
2. Deploy to Vercel production.
3. Verify production.
4. Tag the verified commit.
5. Publish GitHub release notes and artifacts.

Production verification:

```bash
curl -fsS "https://mundia-library.vercel.app/api/books?limit=1"
curl -fsS "https://mundia-library.vercel.app/api/books/genres"
```

Manual verification:

- Sign in as a student.
- Browse catalog.
- Open book detail.
- Check profile borrow history.
- Sign in as admin.
- Open admin dashboard.
- Open users, books, borrow requests, automation, and exports.

## Create Tag

```bash
git tag -a v0.2.1 -m "Release v0.2.1"
git push origin v0.2.1
```

Use the actual version number.

## Release Notes Standard

Release notes should read like a maintainer wrote them, not a generated changelog.

Include:

- What this release is.
- Production deployment URL or deployment status.
- Notable user-facing changes.
- Operational changes.
- Migration notes.
- Verification performed.
- Package artifacts and checksums when relevant.

Avoid:

- Huge commit lists.
- Generic "bug fixes and improvements".
- Marketing language.
- Unverified claims.
- Mentioning internal AI-generated phrasing.

Template:

```md
Mundiapolis Library vX.Y.Z is a [short description of the release].

Production is live at [URL] from commit [SHA].

What changed:
- ...
- ...
- ...

Operational notes:
- ...

Quality checks:
- Production build passed.
- Unit tests passed.
- API benchmarks passed where applicable.

Packages:
- Source archive: ...
- Standalone production package: ...

Checksums:
- ...
```

## Create GitHub Release

```bash
gh release create v0.2.1 \
  --title "Mundiapolis Library v0.2.1" \
  --notes-file release-notes.md
```

Edit release notes:

```bash
gh release edit v0.2.1 --notes-file release-notes.md
```

View release:

```bash
gh release view v0.2.1 --web
```

## Package Artifacts

This repository is an application and is marked `"private": true` in `package.json`. Do not publish it to npm unless the project owner explicitly decides to create a package with public metadata, package scope, license review, and registry auth.

Preferred release package types:

1. Source archive.
2. Standalone production package.

### Source Archive

Create a source archive from the release tag:

```bash
mkdir -p release
git archive --format=tar.gz \
  --prefix=mundia-library-v0.2.1/ \
  -o release/mundia-library-v0.2.1-source.tar.gz \
  v0.2.1
```

Checksum:

```bash
sha256sum release/mundia-library-v0.2.1-source.tar.gz \
  > release/mundia-library-v0.2.1-source.tar.gz.sha256
```

### Standalone Production Package

Build:

```bash
npm run build
```

Package the standalone output. The archive should include:

- `.next/standalone`
- `.next/static`
- `public`
- run instructions

It must exclude:

- `.env`
- `.env.*`
- `.vercel`
- `*.pem`
- local logs
- local database dumps

Verify:

```bash
tar -tzf release/mundia-library-v0.2.1-standalone.tar.gz | rg '(^|/)\\.env($|\\.)|\\.pem$|\\.vercel'
```

Expected result: no output.

Checksum:

```bash
sha256sum release/mundia-library-v0.2.1-standalone.tar.gz \
  > release/mundia-library-v0.2.1-standalone.tar.gz.sha256
```

Upload:

```bash
gh release upload v0.2.1 \
  release/mundia-library-v0.2.1-source.tar.gz \
  release/mundia-library-v0.2.1-source.tar.gz.sha256 \
  release/mundia-library-v0.2.1-standalone.tar.gz \
  release/mundia-library-v0.2.1-standalone.tar.gz.sha256
```

## npm Publishing Policy

Do not run `npm publish` for the current application package.

Reasons:

- `package.json` has `"private": true`.
- The repo is a deployable web app, not a reusable library package.
- The package contains app code, migrations, scripts, and deployment-specific files.
- Publishing would require package name, access, files allowlist, license review, README suitable for npm, and npm auth.

If a reusable package is needed later, create a separate package directory with:

- Clear public API.
- `files` allowlist.
- Separate README.
- Tests.
- Semantic versioning.
- Package scope such as `@mundiapolis/library-*`.

## Post-Release Checklist

1. Confirm GitHub release exists.
2. Confirm assets and checksums are attached.
3. Confirm production URL serves the expected version.
4. Confirm no secrets are present in package archives.
5. Watch production logs after deployment.
6. Close or update release tracking issues.
7. Start a follow-up issue for any known gaps.

## Rollback

For code-only issues:

- Redeploy previous Vercel deployment.
- Or deploy previous container/image/package.

For database issues:

- Prefer roll-forward fixes when possible.
- Restore backup only after understanding data impact.
- Never run destructive rollback commands without an explicit plan.
