package com.paralife.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for ReportWriter: atomic-rename JSON overwrite mode and JSONL append mode.
 *
 * <p>Round 2 Codex HIGH: verifies snake_case wire format via Jackson PropertyNamingStrategies.SNAKE_CASE.
 * <p>OpenCode amendment: verifies header fields are retained across multiple overwrite writes.
 */
class ReportWriterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ReportSnapshot buildHeader() {
        return ReportSnapshot.header("test-harness-01", "ws://localhost:8080/ws/world",
                100, "2026-01-01T00:00:00Z", "21");
    }

    private static ReportSnapshot buildCounters(String exitReason) {
        return ReportSnapshot.counters(
                50, 40, 2L, 1L, 3L, 1000L, 5000L, 200L, 30L, exitReason);
    }

    // --- Overwrite mode tests ---

    @Test
    void writeOverwrite_createsFileWithValidJson(@TempDir Path tmp) throws Exception {
        Path target = tmp.resolve("report.json");
        ReportWriter writer = new ReportWriter();
        ReportSnapshot snap = ReportSnapshot.merge(buildHeader(), buildCounters(null));

        writer.writeOverwrite(target, snap);

        assertThat(target).exists();
        JsonNode tree = MAPPER.readTree(target.toFile());
        assertThat(tree.isObject()).isTrue();
    }

    @Test
    void writeOverwrite_tmpFileDoesNotExistAfterWrite(@TempDir Path tmp) throws Exception {
        Path target = tmp.resolve("report.json");
        ReportWriter writer = new ReportWriter();

        writer.writeOverwrite(target, ReportSnapshot.merge(buildHeader(), buildCounters(null)));

        // writeOverwrite stages via Files.createTempFile (a random <name>.<n>.tmp), so a fixed
        // "report.json.tmp" path is never produced and would pass vacuously even if the temp file
        // were orphaned. Assert the directory holds NO residual *.tmp after the atomic replace.
        try (var stream = Files.list(tmp)) {
            assertThat(stream.filter(p -> p.getFileName().toString().endsWith(".tmp")))
                    .as("no residual temp file after atomic write")
                    .isEmpty();
        }
        assertThat(target).exists();
    }

    @Test
    void writeOverwrite_secondWriteReplacesFirst(@TempDir Path tmp) throws Exception {
        Path target = tmp.resolve("report.json");
        ReportWriter writer = new ReportWriter();

        ReportSnapshot first = ReportSnapshot.merge(buildHeader(),
                ReportSnapshot.counters(5, 4, 0L, 0L, 0L, 10L, 50L, 5L, 5L, null));
        writer.writeOverwrite(target, first);

        ReportSnapshot second = ReportSnapshot.merge(buildHeader(),
                ReportSnapshot.counters(50, 40, 2L, 1L, 3L, 1000L, 5000L, 200L, 30L, "duration-reached"));
        writer.writeOverwrite(target, second);

        JsonNode tree = MAPPER.readTree(target.toFile());
        // Second write should have peak_registered=50
        assertThat(tree.get("peak_registered").asInt()).isEqualTo(50);
    }

    // --- OpenCode header-retention test ---

    @Test
    void writeOverwrite_secondWriteRetainsHeaderFields(@TempDir Path tmp) throws Exception {
        Path target = tmp.resolve("report.json");
        ReportWriter writer = new ReportWriter();

        ReportSnapshot header = buildHeader();
        ReportSnapshot counters1 = buildCounters(null);
        writer.writeOverwrite(target, ReportSnapshot.merge(header, counters1));

        ReportSnapshot counters2 = ReportSnapshot.counters(
                60, 55, 3L, 2L, 4L, 2000L, 8000L, 300L, 60L, null);
        writer.writeOverwrite(target, ReportSnapshot.merge(header, counters2));

        // After second write, static header fields must still be present
        JsonNode tree = MAPPER.readTree(target.toFile());
        assertThat(tree.has("harness_id")).isTrue();
        assertThat(tree.get("harness_id").asText()).isEqualTo("test-harness-01");
        assertThat(tree.has("server_uri")).isTrue();
        assertThat(tree.has("target_count")).isTrue();
    }

    // --- Round 2 Codex HIGH: snake_case test ---

    @Test
    void writeOverwrite_fieldNamesAreSnakeCase(@TempDir Path tmp) throws Exception {
        Path target = tmp.resolve("report.json");
        ReportWriter writer = new ReportWriter();

        writer.writeOverwrite(target, ReportSnapshot.merge(buildHeader(), buildCounters(null)));

        JsonNode tree = MAPPER.readTree(target.toFile());
        // snake_case names must be present
        assertThat(tree.has("harness_id")).isTrue();
        assertThat(tree.has("server_uri")).isTrue();
        assertThat(tree.has("target_count")).isTrue();
        assertThat(tree.has("start_wall_time")).isTrue();
        assertThat(tree.has("peak_registered")).isTrue();
        assertThat(tree.has("current_registered")).isTrue();
        assertThat(tree.has("connect_failures_total")).isTrue();
        assertThat(tree.has("e408_reconnect_required_total")).isTrue();
        assertThat(tree.has("actions_sent_total")).isTrue();
        assertThat(tree.has("perceptions_received_total")).isTrue();
        assertThat(tree.has("syncs_received_total")).isTrue();
        assertThat(tree.has("wall_time_seconds_elapsed")).isTrue();
        // camelCase must NOT be present
        assertThat(tree.has("harnessId")).isFalse();
        assertThat(tree.has("peakRegistered")).isFalse();
        assertThat(tree.has("currentRegistered")).isFalse();
        assertThat(tree.has("connectFailuresTotal")).isFalse();
        assertThat(tree.has("actionsSentTotal")).isFalse();
    }

    // --- M-01 (Round B): concurrent overwrite must not produce a torn file ---

    @Test
    void concurrentWriteOverwrite_neverProducesTornFile(@TempDir Path tmp) throws Exception {
        // M-01 fix: writeOverwrite uses a unique per-call tmp filename
        // (Files.createTempFile) so concurrent callers cannot truncate each other's tmp file
        // or race on the atomic-rename source path. The final target file must always parse
        // as valid JSON, and no IOException must escape.
        Path target = tmp.resolve("report.json");
        ReportWriter writer = new ReportWriter();
        int N = 50;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(N);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < N; i++) {
            final int x = i;
            Thread.startVirtualThread(() -> {
                try {
                    start.await();
                    writer.writeOverwrite(target, ReportSnapshot.merge(buildHeader(),
                            ReportSnapshot.counters(x, x, 0L, 0L, 0L, 0L, 0L, 0L, 0L, null)));
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(15, TimeUnit.SECONDS)).isTrue();
        assertThat(errors).as("no IOException must escape concurrent writes").isEmpty();

        JsonNode tree = MAPPER.readTree(target.toFile());
        assertThat(tree.isObject()).as("final target must parse as valid JSON").isTrue();
        assertThat(tree.has("harness_id")).isTrue();
    }

    // --- JSONL append mode tests ---

    @Test
    void appendJsonl_firstCallWritesHeaderAsJson(@TempDir Path tmp) throws Exception {
        Path target = tmp.resolve("report.jsonl");
        ReportWriter writer = new ReportWriter();

        writer.writeJsonlHeader(target, buildHeader());

        assertThat(target).exists();
        String[] lines = Files.readString(target).trim().split("\n");
        assertThat(lines.length).isEqualTo(1);
        JsonNode header = MAPPER.readTree(lines[0]);
        assertThat(header.has("harness_id")).isTrue();
        assertThat(header.get("harness_id").asText()).isEqualTo("test-harness-01");
    }

    @Test
    void appendJsonl_subsequentCallsAppendCounterLines(@TempDir Path tmp) throws Exception {
        Path target = tmp.resolve("report.jsonl");
        ReportWriter writer = new ReportWriter();

        writer.writeJsonlHeader(target, buildHeader());
        writer.appendJsonlCounter(target, buildCounters(null));
        writer.appendJsonlCounter(target, buildCounters("duration-reached"));

        String[] lines = Files.readString(target).trim().split("\n");
        assertThat(lines.length).isEqualTo(3); // header + 2 counter lines
    }

    @Test
    void appendJsonl_eachLineIsIndependentlyParseable(@TempDir Path tmp) throws Exception {
        Path target = tmp.resolve("report.jsonl");
        ReportWriter writer = new ReportWriter();

        writer.writeJsonlHeader(target, buildHeader());
        writer.appendJsonlCounter(target, buildCounters(null));

        String[] lines = Files.readString(target).trim().split("\n");
        for (String line : lines) {
            assertThat(line).isNotBlank();
            JsonNode node = MAPPER.readTree(line);
            assertThat(node.isObject()).isTrue();
        }
    }

    @Test
    void appendJsonl_counterLinesAreSnakeCase(@TempDir Path tmp) throws Exception {
        Path target = tmp.resolve("report.jsonl");
        ReportWriter writer = new ReportWriter();

        writer.writeJsonlHeader(target, buildHeader());
        writer.appendJsonlCounter(target, buildCounters(null));

        String[] lines = Files.readString(target).trim().split("\n");
        // Counter line (index 1) should have snake_case
        JsonNode counter = MAPPER.readTree(lines[1]);
        assertThat(counter.has("peak_registered")).isTrue();
        assertThat(counter.has("connect_failures_total")).isTrue();
        assertThat(counter.has("peakRegistered")).isFalse();
    }
}
