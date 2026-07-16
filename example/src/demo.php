<?php

declare(strict_types=1);

namespace App;

/*
 * ---------------------------------------------------------------------------------------------
 * PHP Magic Method Navigation — showcase.
 *
 * Every line flagged "MAGIC ->" below is an IMPLICIT magic-method call: PHP runs a method whose
 * name never appears in the source. Open this file in PhpStorm with the plugin installed. A
 * gutter icon appears on each flagged line; click it (or Ctrl/Cmd-click / Ctrl+B on the operand)
 * to jump to the method that actually runs. See README.md for the full line-by-line map.
 *
 * The lines flagged "(not magic)" resolve to a REAL declared member, so PHP never calls a magic
 * method there and the plugin correctly leaves them unmarked.
 * ---------------------------------------------------------------------------------------------
 */

/** Returns one of two Stringables — a union type, to demo the multi-target popup. */
function priceOrRate(bool $asRate): Money|Percentage
{
    return $asRate ? new Percentage(12.5) : new Money(1999);
}

$price    = new Money(1999);
$rate     = new Percentage(12.5);
$discount = new Discount(20);
$settings = new Settings();
$sdk      = new Sdk();

// === __toString ==============================================================================
$label = (string) $price;              // MAGIC -> Money::__toString   (explicit (string) cast)
echo $price;                           // MAGIC -> Money::__toString   (echo)
print $price;                          // MAGIC -> Money::__toString   (print)
$line = "Total due: $price";           // MAGIC -> Money::__toString   (string interpolation)
$row  = 'Item: ' . $price . ' each';   // MAGIC -> Money::__toString   (concatenation)
$msg  = 'Saved ';
$msg .= $rate;                         // MAGIC -> Percentage::__toString   (.= concat-assign)
echo priceOrRate(true);                // MAGIC -> Money::__toString + Percentage::__toString (union popup)

// === __invoke ================================================================================
$net  = $discount(1999);               // MAGIC -> Discount::__invoke   (object called like a function)
$half = (new Discount(50))(1999);      // MAGIC -> Discount::__invoke   (invoke on a fresh instance)

// === __get / __set ===========================================================================
$settings->theme = 'dark';             // MAGIC -> Settings::__set   (write undeclared property)
$current = $settings->theme;           // MAGIC -> Settings::__get   (read undeclared property)
$settings->retries += 1;               // MAGIC -> Settings::__get + Settings::__set  (read-modify-write)
$settings->hits++;                     // MAGIC -> Settings::__get + Settings::__set  (increment)
$v = $settings->version;               // (not magic) real declared property Settings::$version

// === __call / __callStatic ===================================================================
$receipt = $sdk->charge(1999);         // MAGIC -> Sdk::__call         (undeclared instance method)
$void    = $sdk->refund(500);          // MAGIC -> Sdk::__call         (undeclared instance method)
$config  = Sdk::configure('api-key');  // MAGIC -> Sdk::__callStatic   (undeclared static method)
$sdk->reset();                         // (not magic) real declared method Sdk::reset()

// Keep the values "used" so static analysis stays quiet.
unset($label, $line, $row, $msg, $net, $half, $current, $v, $receipt, $void, $config);
