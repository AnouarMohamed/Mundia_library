# Mundiapolis Library Design System

## Register And Direction

The app uses the product register. The visual direction is Printed University Catalog: calm, academic, legible, and operational. The interface should feel like the Mundiapolis identity became a digital library service, not a generic dashboard with library content.

## Palette

Use official Mundiapolis navy as the primary product color. Gold is an institutional accent for ratings, featured picks, and rare emphasis. Status colors are reserved for status only.

```css
:root {
  --mundia-paper: oklch(96% 0.014 84);
  --mundia-surface: oklch(99% 0.006 84);
  --mundia-ink: oklch(18% 0.014 72);
  --mundia-muted: oklch(55% 0.016 78);
  --mundia-line: oklch(84% 0.015 82);
  --mundia-navy: oklch(32% 0.065 255);
  --mundia-navy-strong: oklch(24% 0.07 255);
  --mundia-gold: oklch(77% 0.12 82);
  --mundia-gold-strong: oklch(58% 0.1 78);
  --mundia-success: oklch(63% 0.11 150);
  --mundia-warning: oklch(74% 0.13 70);
  --mundia-danger: oklch(58% 0.18 28);
}
```

Do not use teal as a decorative accent. Do not use pure black or pure white. The page background is warm off-white; surfaces are quiet white; borders are warm gray.

## Typography

- Headings use Georgia or a compatible serif.
- Body, controls, labels, tables, and navigation use the system sans stack.
- Page titles: 28 to 32px serif, weight 400.
- Section titles: 20 to 24px serif, weight 400.
- H3 and panel titles: 16 to 18px sans or serif depending on density.
- Body: 14 to 16px sans, regular.
- Labels and captions: 12 to 13px sans, normal case, muted. No all-caps field labels.

## Components

- Primary button: filled navy, used once per view for the main task.
- Secondary actions: text links or quiet ghost buttons.
- Cards and panels: surface background, 1px warm border, 8px radius, no decorative shadow and no nested cards.
- Tags: small warm borders. Gold only for ratings or featured material.
- Sidebar active state: subtle leading rule and stronger text weight, no filled pill.
- Tables: bottom borders between rows, normal-case headers, lightweight text actions for row-level actions.
- Tabs: underline style, not filled pills.
- Empty states: explain the empty state and offer a next action.

## Voice

Copy should speak to the student, faculty member, or library staff member in the context of their task. Avoid labels that describe the UI itself. Avoid repeated all-caps micro-labels. Decorative metrics are not allowed unless the number is real and useful.
