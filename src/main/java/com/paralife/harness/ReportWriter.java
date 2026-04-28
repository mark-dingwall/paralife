package com.paralife.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Crash-safe report writer for the load harness (D-17).
 *
 * <p>Two modes:
 * <ul>
 *   <li><b>Overwrite</b> — {@link #writeOverwrite}: writes to {@code <target>.tmp} then
 *       atomic-rename to {@code <target>}. External readers never see a half-written file.
 *       Always merges header fields + live counters so header is never lost after the first
 *       write (OpenCode amendment).</li>
 *   <li><b>Append (JSONL)</b> — {@link #writeJsonlHeader} once at startup (overwrites any
 *       pre-existing file), then {@link #appendJsonlCounter} per interval. Each line is
 *       independently parseable JSON. Append writes use {@code O_SYNC} for durability.
 *       Within-run semantics only — across restarts the file is overwritten with a fresh header
 *       (Round 2 Codex MEDIUM clarification).</li>
 * </ul>
 *
 * <p><b>snake_case wire format (Round 2 Codex HIGH):</b> The {@link ObjectMapper} is configured
 * ONCE with {@link PropertyNamingStrategies#SNAKE_CASE}. Java field names stay camelCase;
 * Jackson serialises them to {@code peak_registered}, {@code current_registered}, etc.
 * No per-field {@code @JsonProperty} annotations needed.
 *
 * <p><b>Windows fallback (Pitfall 6):</b> If {@link StandardCopyOption#ATOMIC_MOVE} is not
 * supported by the filesystem, the writer falls back to a non-atomic move and emits a single
 * {@code WARN} log line. This is benign for the load harness use case.
 */
public final class ReportWriter {

    private static final Logger log = LoggerFactory.getLogger(ReportWriter.class);

    /**
     * Round 2 Codex HIGH amendment: D-17 requires snake_case wire format.
     * Configure ONCE here — NOT via per-field {@code @JsonProperty}.
     * Java field names stay camelCase; ObjectMapper translates automatically.
     */
    private final ObjectMapper mapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    /** Guards JSONL append-mode header-first invariant. */
    private final AtomicBoolean headerWritten = new AtomicBoolean(false);

    /**
     * Write {@code snapshot} to {@code target} via atomic temp-rename.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Ensure parent directory exists.</li>
     *   <li>Write JSON to {@code <target>.tmp}.</li>
     *   <li>Atomic-rename {@code .tmp} → {@code target}.</li>
     * </ol>
     *
     * <p>The snapshot should be a {@link ReportSnapshot#merge(ReportSnapshot, ReportSnapshot)}
     * result so header fields are always present (OpenCode header-retention amendment).
     */
    public void writeOverwrite(Path target, ReportSnapshot snapshot) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        if (dir != null) Files.createDirectories(dir);
        Path tmp = resolvedSibling(target, ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            mapper.writeValue(out, snapshot);
        }
        atomicReplace(tmp, target);
    }

    /**
     * Write the JSONL header line, overwriting any pre-existing file.
     * Must be called before {@link #appendJsonlCounter}.
     *
     * <p>Uses atomic-rename to ensure crash-safety of the initial header write.
     */
    public void writeJsonlHeader(Path target, ReportSnapshot header) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        if (dir != null) Files.createDirectories(dir);
        Path tmp = resolvedSibling(target, ".tmp");
        String line = mapper.writeValueAsString(header) + "\n";
        Files.writeString(tmp, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        atomicReplace(tmp, target);
        headerWritten.set(true);
    }

    /**
     * Append one JSONL counter line to the report file.
     * {@link #writeJsonlHeader} must be called first.
     *
     * <p>Uses {@link StandardOpenOption#SYNC} (O_SYNC) for per-write durability.
     *
     * @throws IllegalStateException if called before {@link #writeJsonlHeader}
     */
    public void appendJsonlCounter(Path target, ReportSnapshot counter) throws IOException {
        if (!headerWritten.get()) {
            throw new IllegalStateException(
                    "appendJsonlCounter called before writeJsonlHeader — call writeJsonlHeader first");
        }
        String line = mapper.writeValueAsString(counter) + "\n";
        Files.writeString(target, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND, StandardOpenOption.SYNC);
    }

    /**
     * Resolve a sibling path with the given suffix appended to the filename.
     * E.g. {@code /tmp/report.json} + {@code ".tmp"} → {@code /tmp/report.json.tmp}.
     */
    private static Path resolvedSibling(Path target, String suffix) {
        Path dir = target.toAbsolutePath().getParent();
        String name = target.getFileName().toString() + suffix;
        return (dir == null ? Path.of(".") : dir).resolve(name);
    }

    /**
     * Atomic-rename {@code tmp} to {@code target}, falling back to non-atomic on
     * filesystems that don't support {@link StandardCopyOption#ATOMIC_MOVE} (Pitfall 6 — Windows).
     */
    private void atomicReplace(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            log.warn("Atomic move unsupported on this filesystem ({}); falling back to non-atomic rename",
                    e.getMessage());
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
