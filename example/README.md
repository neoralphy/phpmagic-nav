# PHP Magic Method Navigation — example project

A small, realistic PHP project whose only purpose is to let you **see every navigable case** of the
*PHP Magic Method Navigation* plugin with your own eyes. Every interesting line lives in
[`src/demo.php`](src/demo.php); the classes it uses are one per file alongside it.

Open this `example/` folder in PhpStorm with the plugin installed, let indexing finish, then open
`src/demo.php` and walk the table below. Each row tells you the exact line, what to click, and where
it should jump. It doubles as the material for the Marketplace listing screenshots.

## How to use it

1. Open the `example/` directory as a project (or add it to an existing one).
2. Wait for PhpStorm to finish indexing (the plugin needs PHP type info).
3. Open `src/demo.php`. A **gutter icon** appears in the left margin of every "MAGIC" line below.
4. For each row: click the gutter icon **or** Ctrl/Cmd-click (or Ctrl+B / Cmd+B) on the **operand**
   named in the row. You jump to the magic method that PHP runs implicitly.
5. Try the **reverse** direction too: open a class, put the caret on a magic method's name (e.g.
   `Money::__toString`), and run **Find Usages** (Alt+F7). The result list includes the implicit
   sites from `demo.php` — the `(string)` casts, `echo`s and concatenations — that PhpStorm's own
   Find Usages never shows.

## Project tree

```
example/
├── composer.json            PSR-4  App\ -> src/
├── README.md                (this file)
└── src/
    ├── demo.php             every navigable call site (open THIS file; click the gutter icons)
    ├── Money.php            Stringable value object  -> __toString
    ├── Percentage.php       second Stringable, for the union-type multi-target popup
    ├── Discount.php         callable object          -> __invoke
    ├── Settings.php         dynamic property bag      -> __get / __set  (+ one real property)
    └── Sdk.php              dynamic-call facade       -> __call / __callStatic  (+ one real method)
```

## Line-by-line map (all lines are in `src/demo.php`)

| Line | You wrote (click the operand shown) | PHP implicitly calls | Jumps to |
|------|--------------------------------------|----------------------|----------|
| 34 | `(string) $price` — explicit cast | `__toString()` | `Money::__toString` |
| 35 | `echo $price;` | `__toString()` | `Money::__toString` |
| 36 | `print $price;` | `__toString()` | `Money::__toString` |
| 37 | `"Total due: $price"` — interpolation | `__toString()` | `Money::__toString` |
| 38 | `'Item: ' . $price . ' each'` — concatenation | `__toString()` | `Money::__toString` |
| 40 | `$msg .= $rate;` — concat-assign | `__toString()` | `Percentage::__toString` |
| 41 | `echo priceOrRate(true);` — union `Money\|Percentage` | `__toString()` (either type) | **multi-target popup:** `Money::__toString` **and** `Percentage::__toString` |
| 44 | `$discount(1999)` — object called like a function | `__invoke()` | `Discount::__invoke` |
| 45 | `(new Discount(50))(1999)` — invoke on a fresh instance | `__invoke()` | `Discount::__invoke` |
| 48 | `$settings->theme = 'dark';` — write undeclared property | `__set()` | `Settings::__set` |
| 49 | `$settings->theme` — read undeclared property | `__get()` | `Settings::__get` |
| 50 | `$settings->retries += 1;` — read-modify-write | `__get()` **and** `__set()` | **both:** `Settings::__get` **and** `Settings::__set` |
| 51 | `$settings->hits++;` — increment (read then write) | `__get()` **and** `__set()` | **both:** `Settings::__get` **and** `Settings::__set` |
| 55 | `$sdk->charge(1999)` — undeclared instance method | `__call()` | `Sdk::__call` |
| 56 | `$sdk->refund(500)` — undeclared instance method | `__call()` | `Sdk::__call` |
| 57 | `Sdk::configure('api-key')` — undeclared static method | `__callStatic()` | `Sdk::__callStatic` |

## The "correctly NOT marked" lines — proof the plugin is precise

These lines resolve to a **real declared member**, so PHP never calls a magic method and the plugin
leaves them alone (no gutter icon). They are in `demo.php` right next to their magic twins so the
contrast is obvious:

| Line | You wrote | Why it's not magic |
|------|-----------|--------------------|
| 52 | `$settings->version` | `Settings::$version` is a real **declared property** — normal navigation, no `__get`. |
| 58 | `$sdk->reset()` | `Sdk::reset()` is a real **declared method** — normal navigation, no `__call`. |

If those two lines *had* gutter icons, the plugin would be over-marking; that they don't is the
whole point of the "is the member real?" resolution described in the top-level README.
