# PHP Magic Method Navigation

A PhpStorm plugin that navigates PHP's **implicit magic-method calls** — the ones the language runs
for you but never spells out, so the IDE offers no jump to the code that actually executes.

| You wrote…                       | PHP secretly calls | This plugin jumps you to |
|----------------------------------|--------------------|--------------------------|
| `(string) $money`                | `__toString()`     | `Money::__toString`      |
| `echo $money;` / `print $money;` | `__toString()`     | `Money::__toString`      |
| `"balance: $money"`              | `__toString()`     | `Money::__toString`      |
| `"a" . $money . "b"`             | `__toString()`     | `Money::__toString`      |
| `$callable(1, 2)`                | `__invoke()`       | `Adder::__invoke`        |
| `$obj->undeclaredProp` (read)    | `__get()`          | `Obj::__get`             |
| `$obj->undeclaredProp = …`       | `__set()`          | `Obj::__set`             |
| `$obj->undeclaredMethod(…)`      | `__call()`         | `Obj::__call`            |
| `Foo::undeclaredStatic(…)`       | `__callStatic()`   | `Foo::__callStatic`      |

The last four fire **only** when the member is not really there. `$obj->realProp` /
`$obj->realMethod()` resolve to a genuine declaration, so PHP never calls the magic method and the
plugin leaves them alone.

## Features

- **Gutter icon** at every implicit site listed above. Click it to jump; a union type
  (`Money|Coin`) that can dispatch to several implementations opens a multi-target popup.
- **Go to Declaration** (Ctrl+Click / Ctrl+B) on the operand also offers the magic method,
  *alongside* the normal jump — you never lose the usual navigation.
- **Reverse Find Usages**: run *Find Usages* on a magic method (e.g. `__toString`, `__get`, `__call`)
  and the result list includes the implicit sites — the `(string)` casts, `$obj->prop` accesses and
  unresolved calls — that trigger it, which PhpStorm's own Find Usages never shows.
- Understands the `\Stringable` interface (a value typed as the interface targets the interface's
  own `__toString` declaration) and nullable/union types (only the members that actually declare
  the method are offered).
- **De-duplicates** without losing a jump: nested casts and multi-argument / concat-inside-echo sites
  mark the operand once, not twice — but when two *different* magic methods genuinely fire on the same
  operand (e.g. `echo $obj->prop` where `__get` returns a `Stringable`: both `__get` and `__toString`
  run), the single marker offers both targets.
- **Read-modify-write** on an undeclared property (`$obj->prop += …`, `$obj->prop++`) offers both
  `__get` and `__set`, since PHP reads the old value then writes the new one.
- **Settings** under *Settings | Tools | PHP Magic Nav*: a master switch plus per-method toggles
  for `__toString`, `__invoke`, `__get`, `__set`, `__call`, `__callStatic`, and reverse Find Usages.

## "Is the member real?" — heuristic boundaries

The member-access markers (`__get`/`__set`/`__call`/`__callStatic`) fire only when the reference does
**not** resolve to a real declared member. That decision uses PhpStorm's own resolution
(`multiResolve`), which has two deliberate boundaries worth knowing:

- **Visibility is not modelled.** PhpStorm resolves by *declaration*, not by call-site
  accessibility. A `private`/`protected` member reached from an outside scope still resolves, so a
  magic call triggered purely by *inaccessibility* (PHP does invoke `__get`/`__call` there) is **not**
  marked. This is intentional — we would rather miss that rarer case than falsely mark legitimate
  member access.
- **`@property` / `@method` phpdoc counts as declared.** A member documented in phpdoc resolves to
  that phpdoc declaration and is treated as real (not marked), because the IDE already navigates it
  natively.

Reverse Find Usages is bounded the same way it must be: an implicit site is only detectable in a file
where the value's type is inferable, which requires the class to be named in that file. The searcher
therefore scans only files that mention the class's short name (via the identifier index), then reuses
the exact same `MagicSites` logic — so a site marked forward is exactly a site found in reverse.

## How it works

Detection and resolution live in one place (`MagicSites`), so the gutter markers and the Goto
handler can never disagree about what is a site.

- `MagicMethods.kt` — the `MagicMethod` enum (`__toString`, `__invoke`, `__get`, `__set`, `__call`,
  `__callStatic`) and `MagicMethodResolver`, which takes an operand's `PhpType`, completes it with
  `global()` (expanding unions / inferred signatures), and resolves each FQN through the `PhpIndex` as
  a class *or* interface to collect the magic method.
- `MagicSites.kt` — maps each recognised node to its operand(s): `(string)` cast → `UnaryExpression`,
  `echo` → `PhpEchoStatement`, `print` → `PhpPrintExpression`, interpolation →
  `StringLiteralExpression`'s embedded expressions, concatenation → `ConcatenationExpression`'s
  operands, dynamic invoke → a `FunctionReference` whose callee is an expression, property access →
  `FieldReference` (`__get`/`__set`, split by `RWAccess`), and method call → `MethodReference`
  (`__call`/`__callStatic`, split by `isStatic`). The member cases first check that the reference does
  *not* resolve to a real declared member before touching the (expensive) type resolution.
- `MagicNavLineMarkerProvider.kt` — a `RelatedItemLineMarkerProvider`; overrides the *batch* hook to
  group sites by anchor leaf, merging the targets of every site on a leaf into one marker (so genuine
  duplicates collapse but distinct magic methods on the same operand each stay reachable).
- `MagicNavGotoDeclarationHandler.kt` — adds magic targets when the caret sits on an operand.
- `MagicNavUsageSearcher.kt` — a `CustomUsageSearcher` that powers reverse Find Usages, reusing
  `MagicSites` over the files that mention the magic method's class.

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

Covered: forward navigation to `__toString()` (casts, `echo`/`print`, interpolation, concatenation),
`__invoke()` (dynamic `$callable(...)`), and `__get`/`__set`/`__call`/`__callStatic` (member access
that resolves to no real declaration); plus reverse Find Usages for all of them.

Known limitations (see *heuristic boundaries* above): visibility-only magic (a `private` member
reached from outside) and `@property`/`@method`-documented members are treated as real and not marked.

## License

Not yet chosen. All rights reserved by the author until a license is added.
