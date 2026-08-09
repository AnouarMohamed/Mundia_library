# Mobile Product Gate

The student portal is a mobile-first product. The reference use case is a
student checking availability, requesting a book, or reading a due date on a
phone between classes. Desktop remains the primary operating surface for the
library administration area.

## Architecture Rules

- Student catalog, recommendations, book metadata, availability, and profile
  views render on the server. Client JavaScript is reserved for mutations,
  dialogs, session controls, and other interaction islands.
- The primary phone navigation remains visible at the bottom of the viewport;
  account and administrative actions live in a separate accessible drawer.
- Controls used on touch screens are at least 44 by 44 CSS pixels. Primary
  controls target 48 pixels.
- Fixed navigation and drawers include safe-area padding. Page content keeps
  enough bottom padding to remain visible above the fixed navigation.
- Catalog covers are lazy-loaded except for the first two visible results.
  Decorative duplicate media must not create extra network priority.
- Search and filters use normal GET URLs so they work without client hydration,
  remain shareable, and survive constrained connections.

## Automated Gates

`npm run test:e2e` runs desktop Chromium and a mobile Chromium project. The
mobile product suite covers 360, 390, and 430 pixel widths and verifies:

- no horizontal document overflow on Library, Catalog, or My account;
- sign-in and catalog controls meet touch target minimums;
- the mobile keyboard Enter path submits catalog search;
- persistent primary navigation does not obscure page content;
- the account drawer supports focus return, Escape, and correct semantics;
- authenticated student routes remain operable at all target widths.

`npm run build` also fails when a production JavaScript budget regresses:

| Route | Maximum first-load JavaScript |
| --- | ---: |
| Shared framework | 110 kB |
| `/all-books` | 120 kB |
| `/library` | 165 kB |
| `/my-profile` | 155 kB |
| `/books/[id]` | 190 kB |
| `/sign-in` | 155 kB |

These are regression ceilings, not targets to consume. A change that adds
JavaScript must justify why server rendering or native browser behavior cannot
provide the same outcome.

## Current Checkpoint

The mobile-first server-rendering checkpoint reduced production first-load
JavaScript as follows:

| Route | Before | Current |
| --- | ---: | ---: |
| `/all-books` | 281 kB | 111 kB |
| `/library` | 183 kB | 157 kB |
| `/my-profile` | 189 kB | 148 kB |
| `/books/[id]` | 186 kB | 180 kB |

The authenticated audit at 390 by 844 reports zero horizontal overflow, zero
layout shift in the audited routes, two eager catalog covers instead of all 12,
and no undersized product controls. Development-only framework and query
debugging buttons are excluded from the product result.

## Manual Constrained-Device Audit

Start the seeded application, then run:

```bash
AUDIT_BASE_URL=http://127.0.0.1:3100 \
AUDIT_EMAIL=test@user.com \
AUDIT_PASSWORD=12345678 \
npm run audit:mobile
```

The ignored `artifacts/mobile-audit/` directory receives full-page screenshots
and JSON metrics for Library, Catalog, My account, book detail, sign-in, and the
account drawer. Before release, repeat the core request and return flows with
Chrome DevTools set to a four-times CPU slowdown and Slow 4G, then check the
screens at 360, 390, and 430 pixel widths with the software keyboard open.
