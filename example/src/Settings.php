<?php

declare(strict_types=1);

namespace App;

/**
 * A dynamic property bag. It declares NO `$theme`, `$locale`, ... fields; reading or writing any
 * undeclared property falls through to __get()/__set(). Because these members are not really
 * declared, the plugin marks `$settings->theme` (read -> __get) and `$settings->theme = ...`
 * (write -> __set) and jumps to the magic method.
 *
 * Contrast with $version below: it IS a real declared property, so `$settings->version` resolves
 * to it, PHP never calls __get(), and the plugin correctly leaves it alone.
 */
final class Settings
{
    /** A REAL declared property — access to this is NOT magic and is deliberately not marked. */
    public string $version = '1.0';

    /** @var array<string, mixed> */
    private array $bag = [];

    public function __get(string $name): mixed
    {
        return $this->bag[$name] ?? null;
    }

    public function __set(string $name, mixed $value): void
    {
        $this->bag[$name] = $value;
    }
}
