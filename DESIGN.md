# Mundia Library Design System

## Register And Direction

The app uses the product register. The visual direction is Scholarly Product: prestigious, usable, quiet, and institutional. The interface should look like a serious university library product, not a marketing site.

## Color Strategy

Use a restrained product palette. Tinted neutrals carry most surfaces. Teal marks primary action, focus, and selected state. Gold marks institutional emphasis and high-value highlights. Burgundy is reserved for rare review or history accents. Use OKLCH tokens for new color work.

```css
:root {
  --mundia-ink: oklch(18% 0.025 225);
  --mundia-ink-strong: oklch(12% 0.028 225);
  --mundia-paper: oklch(97% 0.008 218);
  --mundia-paper-warm: oklch(95% 0.018 92);
  --mundia-panel: oklch(23% 0.035 220);
  --mundia-panel-raised: oklch(29% 0.04 218);
  --mundia-line: oklch(76% 0.035 218 / 0.34);
  --mundia-text: oklch(94% 0.012 215);
  --mundia-muted: oklch(75% 0.03 215);
  --mundia-teal: oklch(72% 0.08 212);
  --mundia-teal-strong: oklch(56% 0.09 214);
  --mundia-gold: oklch(78% 0.105 88);
  --mundia-gold-strong: oklch(58% 0.09 82);
  --mundia-burgundy: oklch(43% 0.11 8);
  --mundia-danger: oklch(58% 0.18 28);
  --mundia-warning: oklch(74% 0.13 70);
  --mundia-success: oklch(63% 0.11 150);
}
```

Do not introduce pure black, pure white, decorative gradient text, or heavy saturated inactive states. Gradients are allowed only for large brand surfaces and should use tokenized colors.

## Typography

- Use the app sans stack for product UI labels, controls, body copy, tables, and charts.
- Use the display font only for brand mastheads and major page titles, never for form labels, table data, filter labels, or compact UI controls.
- Body copy should stay between 45 and 75 characters where it is prose.
- Avoid viewport-scaled type. Use fixed responsive steps.

## Layout And Spacing

- Use predictable product structure: root shell, header or sidebar, page header, filters, data region, and local actions.
- Use the existing Tailwind spacing scale. Prefer `2`, `3`, `4`, `5`, `6`, `8`, `10`, and `12`.
- Avoid nested cards. If a panel is already framed, inner groups should use separators, tints, or table rows.
- Mobile layouts must avoid horizontal scroll and keep all touch targets at least 44px.

## Components

- Buttons: one primary teal action, one quiet secondary action, one destructive action. Every button needs hover, focus, active, disabled, and loading states where async.
- Inputs and selects: same radius, border, background, focus ring, placeholder tone, and height across auth, catalog, reviews, profile, and admin.
- Cards and panels: use full borders or subtle surface changes, never colored side stripes.
- Tables: dense but readable, with sticky or clear headers where useful, row hover state, empty state, and mobile fallback where tables become too wide.
- Badges: semantic colors mean the same thing everywhere: pending, approved, borrowed, returned, overdue, rejected, admin.
- Dialogs: use only when inline disclosure would make the page worse. Dialog copy should include the action and recovery path.

## Motion

- Use 150 to 250ms state transitions.
- Use ease-out quart, quint, or expo style timing.
- Motion should convey state change, reveal, or feedback. Do not animate layout properties.
- Respect `prefers-reduced-motion`.

## Accessibility

- Text contrast must meet WCAG AA.
- Focus indicators must remain visible on every interactive control.
- Icons need accessible names when they are controls and hidden semantics when decorative.
- Images require useful alt text. Book covers should use title-aware alt text when possible.
- Forms require labels and helpful error messages.
