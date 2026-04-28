package com.paralife.harness;

import com.paralife.bot.RampUpSpec;
import picocli.CommandLine.ITypeConverter;

/**
 * Picocli type converter for {@link RampUpSpec} (D-15 / D-16).
 *
 * <p>Accepted formats:
 * <ul>
 *   <li>{@code instant}</li>
 *   <li>{@code rate:<n>} — e.g. {@code rate:50}</li>
 *   <li>{@code wave:<count>:<sleepMs>} — e.g. {@code wave:100:500}</li>
 * </ul>
 */
public final class RampUpConverter implements ITypeConverter<RampUpSpec> {

    @Override
    public RampUpSpec convert(String value) {
        if ("instant".equals(value)) {
            return RampUpSpec.instant();
        }
        if (value.startsWith("rate:")) {
            int n;
            try {
                n = Integer.parseInt(value.substring(5));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "--ramp-up rate format: rate:<n> where n is a positive integer; got: " + value);
            }
            return RampUpSpec.rate(n);
        }
        if (value.startsWith("wave:")) {
            String[] parts = value.substring(5).split(":");
            if (parts.length != 2) {
                throw new IllegalArgumentException(
                        "--ramp-up wave format: wave:<count>:<sleepMs>; got: " + value);
            }
            try {
                return RampUpSpec.wave(Integer.parseInt(parts[0]), Long.parseLong(parts[1]));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "--ramp-up wave format: wave:<count>:<sleepMs> where count and sleepMs are integers; got: " + value);
            }
        }
        throw new IllegalArgumentException(
                "--ramp-up must be 'instant' | 'rate:<n>' | 'wave:<count>:<sleepMs>'; got: " + value);
    }
}
