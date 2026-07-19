<?php

declare(strict_types=1);

namespace App;

/**
 * A facade/proxy that resolves calls dynamically. It declares NO `charge()` or `refund()` method;
 * an undeclared instance call `$sdk->charge(...)` falls through to __call(), and an undeclared
 * static call `Sdk::configure(...)` falls through to __callStatic(). Since the member does not
 * resolve to a real method, the plugin marks the call and jumps to __call()/__callStatic().
 *
 * Contrast with reset() below: it IS a real declared method, so `$sdk->reset()` resolves to it and
 * is (correctly) not marked.
 */
final class Sdk
{
    /** A REAL declared method - this call is NOT magic and is deliberately not marked. */
    public function reset(): void
    {
    }

    /** Handles every undeclared instance call, e.g. $sdk->charge(1000). */
    public function __call(string $name, array $arguments): mixed
    {
        return "call {$name}(" . implode(', ', $arguments) . ')';
    }

    /** Handles every undeclared static call, e.g. Sdk::configure('key'). */
    public static function __callStatic(string $name, array $arguments): mixed
    {
        return "static {$name}(" . implode(', ', $arguments) . ')';
    }
}
