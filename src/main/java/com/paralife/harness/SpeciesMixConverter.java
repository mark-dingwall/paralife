package com.paralife.harness;

import com.paralife.bot.SpeciesMix;
import picocli.CommandLine.ITypeConverter;

import java.util.Locale;

/**
 * Picocli type converter for {@link SpeciesMix} (D-15 / D-16).
 *
 * <p>Accepted formats:
 * <ul>
 *   <li>{@code balanced}</li>
 *   <li>{@code <C>:<M>:<S>} — three fractions summing to 1.0, e.g. {@code 0.5:0.3:0.2}</li>
 * </ul>
 */
public final class SpeciesMixConverter implements ITypeConverter<SpeciesMix> {

    @Override
    public SpeciesMix convert(String value) {
        // L-03 (Round B): tolerate operator finger-slips — surrounding whitespace and
        // mixed/upper case. Numeric parsing is unaffected by lowercasing.
        value = value.trim().toLowerCase(Locale.ROOT);
        if ("balanced".equals(value)) {
            return SpeciesMix.balanced();
        }
        String[] parts = value.split(":");
        if (parts.length != 3) {
            throw new IllegalArgumentException(
                    "--species-mix must be 'balanced' or '<C>:<M>:<S>' (three fractions); got: " + value);
        }
        try {
            double c = Double.parseDouble(parts[0]);
            double m = Double.parseDouble(parts[1]);
            double s = Double.parseDouble(parts[2]);
            return new SpeciesMix(c, m, s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "--species-mix fractions must be numeric; got: " + value);
        }
    }
}
