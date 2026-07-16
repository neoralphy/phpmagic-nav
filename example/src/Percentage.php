<?php

declare(strict_types=1);

namespace App;

/**
 * A second Stringable, used to demonstrate the multi-target popup: a value typed as a union
 * `Money|Percentage` can dispatch to either class's __toString, so the gutter marker offers both.
 */
final class Percentage implements \Stringable
{
    public function __construct(private readonly float $value)
    {
    }

    public function __toString(): string
    {
        return rtrim(rtrim(number_format($this->value, 2), '0'), '.') . '%';
    }
}
