<?php

declare(strict_types=1);

namespace App;

/**
 * A callable object. Because it declares __invoke(), an instance can be "called" like a function:
 * `$discount($cents)` runs __invoke() - no method name appears at the call site, so the IDE
 * normally offers no jump. The plugin marks `$discount(...)` and jumps to __invoke().
 */
final class Discount
{
    public function __construct(private readonly int $percentOff)
    {
    }

    /** PHP calls this implicitly whenever the object is used as `$discount(...)`. */
    public function __invoke(int $cents): int
    {
        return (int) round($cents * (100 - $this->percentOff) / 100);
    }
}
