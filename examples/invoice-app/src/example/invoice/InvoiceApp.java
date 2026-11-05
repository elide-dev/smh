// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package example.invoice;

/** The application's real pricing code, shared by its CLI and the benchmark. */
public final class InvoiceApp {
  private InvoiceApp() {}

  /** Sum quantity:unit-price-in-cents lines, rejecting malformed or overflowing input. */
  public static long total(String invoice) {
    long total = 0;
    for (String line : invoice.lines().toList()) {
      String[] fields = line.split(":", -1);
      if (fields.length != 2) throw new IllegalArgumentException("Expected quantity:price");
      long quantity = Long.parseLong(fields[0]);
      long price = Long.parseLong(fields[1]);
      if (quantity < 0 || price < 0)
        throw new IllegalArgumentException("Negative quantity or price");
      total = Math.addExact(total, Math.multiplyExact(quantity, price));
    }
    return total;
  }

  public static void main(String[] args) {
    System.out.println("Total: " + total("2:1250\n3:499\n1:2500\n") + " cents");
  }
}
