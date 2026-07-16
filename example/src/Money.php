<?php

declare(strict_types=1);

namespace App;

/**
 * A value object that renders itself as a string.
 *
 * `implements Stringable` makes the intent explicit, but note: PHP calls __toString() for ANY
 * class that declares it, interface or not. The plugin marks the implicit call site regardless;
 * the interface just lets a value typed as `Stringable` still resolve to a target (this class's
 * own __toString).
 */
final class Money implements \Stringable
{
    public function __construct(
        private readonly int $cents,
        private readonly string $currency = 'EUR',
    ) {
    }

    /** PHP calls this implicitly on every (string) cast, echo/print, interpolation and concat. */
    public function __toString(): string
    {
        return number_format($this->cents / 100, 2) . ' ' . $this->currency;
    }
}
