# Marketplace listing - PHP Magic Method Navigation

Upload-ready store copy for the JetBrains Marketplace listing. The long description is the single
source of truth in `src/main/resources/META-INF/plugin.xml` (`<description>` CDATA); this file
carries the short copy, the feature bullets, the tag pick, the "why free" line, and the screenshot
plan for the vendor UI.

No em-dashes or en-dashes anywhere in this file or in any user-facing text (owner requirement: long
dashes read as AI-written). Use regular hyphens only.

Status: text ready. Screenshots are owner-gated (need a licensed PhpStorm sandbox at an unlocked
display); see section 5 for the shot list and the `example/` scene that produces every frame.

---

## 1. One-liner (search card / tagline)

> Jump to the method PHP runs but never names: gutter-icon navigation from casts, echo, string
> interpolation, `$callable(...)`, and undeclared `$obj->prop` / `$obj->method()` to the
> `__toString` / `__invoke` / `__get` / `__set` / `__call` / `__callStatic` that actually executes.

Tight version (for cramped fields):

> Gutter-icon navigation to PHP's implicit magic-method calls (`__toString`, `__invoke`, `__get`,
> `__set`, `__call`, `__callStatic`).

## 2. Short description (Marketplace "summary" field)

PhpStorm shows no jump for the methods PHP runs implicitly: a `(string)` cast or `echo` that runs
`__toString()`, `$callable(...)` that runs `__invoke()`, an undeclared `$obj->prop` or
`$obj->method()` that falls through to `__get` / `__set` / `__call` / `__callStatic`. This plugin
adds a gutter icon at every such site and jumps you to the method that actually executes, in both
directions (forward navigation and reverse Find Usages). Free, no account, nothing leaves your
machine.

## 3. Long description

Lives in `plugin.xml` (`<description>`). Paste the same HTML into the Marketplace description field
at upload; do not maintain a second copy here. It opens with the hook ("Jump to the method PHP runs
but never names"), a concrete before/after paragraph, the five feature bullets, the "free and
staying free" line, and the honest "reuses the IDE's own type resolution" close.

## 4. Feature bullets (for the vendor UI highlights, if used separately)

- Gutter icon at every implicit magic-method site: `(string)` cast, `echo` / `print`, string
  interpolation, concatenation, dynamic `$callable(...)`, and undeclared member access
  (`$obj->prop` read/write, `$obj->m(...)`, `Foo::m(...)`).
- Union types open a multi-target popup, so a value typed `Money|Percentage` offers every
  `__toString` it could dispatch to.
- Go to Declaration (Ctrl+Click / Ctrl+B) offers the magic method alongside the normal jump, so you
  never lose the navigation you already use.
- Reverse Find Usages: run Find Usages on `__toString` / `__get` / `__call` and the results include
  the implicit call sites that trigger them, which PhpStorm's own Find Usages omits.
- Precise: member access is marked only when the magic method genuinely fires. A reference that
  resolves to a real declared property, constant, or method is left alone. Handles `\Stringable`,
  nullable / union types, and de-duplicates so nested and multi-argument sites never double-mark.
- Configurable: master switch plus a per-method toggle for all six magic methods and reverse Find
  Usages, under Settings | Tools | PHP Magic Nav.

## 5. Tags

Pick against the Marketplace's real tag vocabulary (`plugins.jetbrains.com/api/tags`, intellij
family) at upload time, exactly as hotpath-heatmap did. Confirmed-safe primary tag:

1. **PHP** - the only audience; the plugin hard-depends on `com.jetbrains.php`.

Recommended additions (verify each name exists in the tag API before selecting - names like
"Navigation" / "Code Navigation" are not guaranteed to be real tags):

2. **Code Tools** - the broad browse aisle for editor productivity helpers.
3. **Editor** - accurate: the value shows up as a gutter icon in the editor.
4. **Inspection** - what IDE users call "the thing in the gutter / margin". Judgment call; include
   only if the tag API confirms it and it does not over-promise (this is navigation, not a warning).

Do not tag languages the plugin does not touch. It is PHP-only by design.

## 6. Why free

The full plugin is free for any use, including commercial. There is no paid tier, no trial, no
account, and no telemetry. It runs entirely inside the IDE and makes no network calls, so nothing
about your code ever leaves your machine. The source is proprietary (see `LICENSE`): free to install
and use, not to copy, modify, or redistribute.

Reasoning for the listing: this is a focused, single-purpose navigation aid. Its job is to remove a
small daily friction, and a paywall on that friction would only push people back to grepping by
hand. Free removes the adoption barrier entirely.

## 7. Screenshots - shot list and captions

Marketplace screenshots are uploaded in the vendor UI media gallery, not embedded in the
description. The first image is the card / hero. All frames come from the committed `example/`
project (`example/src/demo.php` plus its one-class-per-file companions), which is built precisely to
show every navigable case; open `example/` as a project in a plugin-loaded PhpStorm, let indexing
finish, and open `demo.php`.

Capture is owner-gated: it needs a licensed PhpStorm (the sandbox launched by `./gw runPhpStorm`
requires a one-time JetBrains browser login at an unlocked display, the same blocker documented for
hotpath-heatmap in `../hotpath-heatmap/scripts/screenshots/README.md`). There is no headless path.
When the owner is present, reuse hotpath's recipe (theme flip via `laf.xml` + `colors.scheme.xml`,
window-scoped `screencapture -x -l <windowID>`, de-noising config) against this repo's `example/`
scene.

Target set (dark first, since most devs run dark and it is the card thumbnail):

| # | Asset (target filename) | Scene in `demo.php` | Caption |
|---|---|---|---|
| 1 | `screenshot-gutter-dark.png` | The `__toString` block (lines 34-41): gutter icons on the `(string)` cast, `echo`, `print`, interpolation, concatenation, and the union-type `echo priceOrRate(true)`. | Every implicit `__toString` call gets a gutter icon. Click it to jump to the method PHP runs; a union type opens a multi-target popup. |
| 2 | `screenshot-popup-dark.png` | Line 41 gutter icon clicked, showing the multi-target popup with `Money::__toString` and `Percentage::__toString`. | A value typed `Money\|Percentage` can dispatch to either implementation, so the icon offers both jumps. |
| 3 | `screenshot-members-dark.png` | The `__get` / `__set` / `__call` block (lines 48-58), including the two "correctly not marked" real-member lines (52, 58) sitting next to their magic twins. | Undeclared `$obj->prop` and `$obj->method()` are marked; the real declared property and method right beside them are not. Precision you can see. |
| 4 | `screenshot-findusages-dark.png` | Find Usages (Alt+F7) on `Money::__toString`, result tree showing the implicit sites from `demo.php`. | Reverse direction: Find Usages on a magic method lists the casts, echoes, and interpolations that trigger it, which PhpStorm's own Find Usages never shows. |
| 5 | `screenshot-gutter-light.png` | Same as #1, light theme. | The gutter icon and navigation read correctly in light and dark themes. |

If only one frame ships first, ship #1 (dark): it tells the whole story (implicit calls made
visible and jumpable) in a single glance.

## 8. Listing-field hygiene (vendor UI, at upload)

- **Source Code field: leave empty.** The license is proprietary; the field is only mandatory for
  open-source plugins.
- **Vendor:** neoralphy, `pelyhearon@gmail.com` (already in plugin.xml).
- **License:** custom free-use license (see repo `LICENSE`). Free to install and use, including
  commercially; source is all-rights-reserved.
- **Pricing:** free. No `<product-descriptor>`, no paid tier.
- **Compatibility:** PhpStorm (hard dependency on `com.jetbrains.php`); it has nothing to do in a
  non-PHP IDE, so the dependency is intentionally required rather than optional.
