# PHP Magic Method Navigation

A PhpStorm plugin that navigates PHP's **implicit magic-method calls** — the ones the language runs
for you but never spells out, so the IDE offers no jump to the code that actually executes.

| You wrote…                     | PHP secretly calls | This plugin jumps you to |
|--------------------------------|--------------------|--------------------------|
| `(string) $money`              | `__toString()`     | `Money::__toString`      |
| `echo $money;` / `print $money;` | `__toString()`   | `Money::__toString`      |
| `"balance: $money"`            | `__toString()`     | `Money::__toString`      |
| `"a" . $money . "b"`           | `__toString()`     | `Money::__toString`      |
| `$callable(1, 2)`              | `__invoke()`       | `Adder::__invoke`        |

## Features

- **Gutter icon** at every implicit site listed above. Click it to jump; a union type
  (`Money|Coin`) that can dispatch to several implementations opens a multi-target popup.
- **Go to Declaration** (Ctrl+Click / Ctrl+B) on the operand also offers the magic method,
  *alongside* the normal jump — you never lose the usual navigation.
- Understands the `\Stringable` interface (a value typed as the interface targets the interface's
  own `__toString` declaration) and nullable/union types (only the members that actually declare
  the method are offered).
- **De-duplicates**: nested casts and multi-argument / concat-inside-echo sites mark the operand
  once, not twice.
- **Settings** under *Settings | Tools | PHP Magic Nav*: a master switch plus per-method toggles
  for `__toString` and `__invoke`.

## How it works

Detection and resolution live in one place (`MagicSites`), so the gutter markers and the Goto
handler can never disagree about what is a site.

- `MagicMethods.kt` — the `MagicMethod` enum (`__toString`, `__invoke`) and `MagicMethodResolver`,
  which takes an operand's `PhpType`, completes it with `global()` (expanding unions / inferred
  signatures), and resolves each FQN through the `PhpIndex` as a class *or* interface to collect the
  magic method.
- `MagicSites.kt` — maps each recognised container node to its operand(s): `(string)` cast →
  `UnaryExpression`, `echo` → `PhpEchoStatement`, `print` → `PhpPrintExpression`, interpolation →
  `StringLiteralExpression`'s embedded expressions, concatenation → `ConcatenationExpression`'s
  operands, dynamic invoke → a `FunctionReference` whose callee is an expression.
- `MagicNavLineMarkerProvider.kt` — a `RelatedItemLineMarkerProvider`; overrides the *batch* hook to
  dedup by anchor leaf.
- `MagicNavGotoDeclarationHandler.kt` — adds magic targets when the caret sits on an operand.

### Performance

The line-marker provider runs on every PSI element in the daemon's slow pass, so the first thing it
does is a cheap `instanceof` gate on the container node types; only those touch the (expensive)
`getType().global()`. Scalars/strings fall out fast via a primitive skip. The settings master switch
short-circuits everything.

## Build & run

Requires **JDK 17** (Homebrew `openjdk@17`) and the Gradle wrapper. Use the `./gw` wrapper (it points
Gradle at the keg-only JDK).

```bash
./gw test          # headless proofs (resolution, dedup, goto, highlighting, settings)
./gw buildPlugin   # build the distributable zip in build/distributions/
./gw verifyPlugin  # JetBrains plugin verifier (PhpStorm 2025.3 GA + newest release & EAP)
./gw runPhpStorm   # launch a PhpStorm sandbox with the plugin loaded
```

### Eyeball it

`./gw runPhpStorm`, then open a PHP file such as:

```php
<?php
class Money implements Stringable {
    public function __toString(): string { return "€0.00"; }
}
$m = new Money();
echo $m;              // gutter icon → Money::__toString
$s = (string) $m;     // gutter icon → Money::__toString
$t = "total: $m";     // gutter icon → Money::__toString
```

A gutter icon appears on each line; click it (or Ctrl+B on `$m`) to jump to `__toString()`.

## Scope

Covered: forward navigation to `__toString()` from casts, `echo`/`print`, interpolation and
concatenation; navigation to `__invoke()` from dynamic `$callable(...)` calls.

Deliberately **not** in this version (kept out to ship everything verified):
- `__get` / `__set` / `__call` usage → magic-method markers.
- Reverse navigation (Find Usages of `__toString` surfacing the implicit sites).

## License

Not yet chosen. All rights reserved by the author until a license is added.
