package com.paralife.codec;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Shared encode/decode per 15-SCHEMA.md. Pure static; no hidden state (D-41).
 *
 * <h2>DoS bounds (15-SCHEMA.md §12)</h2>
 * {@link #MAX_S_ENTRIES} and {@link #MAX_V_ENTRIES} bound the decoder's
 * per-frame list allocation. Exceeding either during decode is a wire-protocol
 * violation and throws {@link CodecException} (mapped by the handler to E|400).
 * These bounds are public so tests and static analysis can reference them.
 *
 * <h2>Zero-trust invariant (T-15-03)</h2>
 * The codec consumes and produces {@link CellEntry} records which carry no
 * entity identifier. There is no encode path that can emit an occupant id;
 * end-to-end verification lands in plan 15-08 (ZeroTrustFilteringTest).
 */
public final class PerceptionCodec {

    /**
     * Maximum cell entries per {@code s} block. Structurally-valid frames with
     * more than this many entries are rejected on decode. 256 comfortably
     * covers a 7×7 sensor radius (49 cells) plus env supplements with slack.
     */
    public static final int MAX_S_ENTRIES = 256;

    /**
     * Maximum event entries per {@code v} block. 32 covers the worst-case
     * LOCOMOTOR tick (multi-member alarms + own events) with slack.
     */
    public static final int MAX_V_ENTRIES = 32;

    /** Canonical block order per SCHEMA §6.3.1 when TickFrame.blockOrder is empty. */
    private static final char[] CANONICAL_BLOCK_ORDER = {'s', 'c', 'f', 'v', 'p', 'g'};

    private PerceptionCodec() {
        // utility — not instantiable
    }

    // ============================================================
    // ENCODE
    // ============================================================

    public static String encode(Frame f) {
        if (f == null) throw new CodecException("Cannot encode null frame");
        StringBuilder sb = new StringBuilder(128);
        switch (f) {
            case Frame.TickFrame t -> encodeTick(sb, t);
            case Frame.SyncFrame s -> encodeSync(sb, s);
            case Frame.RegisterFrame r -> encodeRegister(sb, r);
            case Frame.ActionFrame a -> encodeAction(sb, a);
            case Frame.ErrorFrame e -> encodeError(sb, e);
        }
        return sb.toString();
    }

    // ---- Tick frame ----

    private static void encodeTick(StringBuilder sb, Frame.TickFrame t) {
        sb.append('T').append('|');
        // tickId — fixed 3-char base64 per SCHEMA §10 vectors (e.g. "001", "004").
        encodeFixedBase64(sb, t.tickId(), 3);
        sb.append('|');
        // curX (2 chars) + curY (2 chars) concatenated = 4 chars absolute
        encodeFixedBase64(sb, t.curX(), 2);
        encodeFixedBase64(sb, t.curY(), 2);
        sb.append('|');
        encodeVarBase64(sb, t.energy());
        sb.append('/');
        encodeVarBase64(sb, t.maxEnergy());
        if (t.isMinimal()) {
            // Minimal form (§6.3.2): no sensorRadius slot, only v block (if any) follows.
            if (!t.events().isEmpty()) {
                sb.append('|');
                encodeVBlock(sb, t.events());
            }
            return;
        }
        sb.append('|').append(Base64Codec.encodeDigit(t.sensorRadius()));

        List<Character> order = t.blockOrder().isEmpty()
                ? canonicalPresentOrder(t)
                : t.blockOrder();
        for (Character block : order) {
            switch (block) {
                case 's' -> {
                    if (!t.cells().isEmpty()) {
                        sb.append('|');
                        encodeSBlock(sb, t.cells());
                    }
                }
                case 'c' -> {
                    if (t.change().isPresent()) {
                        sb.append('|');
                        encodeCBlock(sb, t.change().get());
                    }
                }
                case 'f' -> {
                    if (!t.effects().isEmpty()) {
                        sb.append('|');
                        encodeFBlock(sb, t.effects());
                    }
                }
                case 'v' -> {
                    if (!t.events().isEmpty()) {
                        sb.append('|');
                        encodeVBlock(sb, t.events());
                    }
                }
                case 'p' -> {
                    if (t.pool().isPresent()) {
                        sb.append('|');
                        encodePBlock(sb, t.pool().get());
                    }
                }
                case 'g' -> {
                    if (!t.roster().isEmpty()) {
                        sb.append('|');
                        encodeGBlock(sb, t.roster());
                    }
                }
                default -> throw new CodecException("Unknown block char: " + block);
            }
        }
    }

    private static List<Character> canonicalPresentOrder(Frame.TickFrame t) {
        List<Character> out = new ArrayList<>(6);
        if (!t.cells().isEmpty()) out.add('s');
        if (t.change().isPresent()) out.add('c');
        if (!t.effects().isEmpty()) out.add('f');
        if (!t.events().isEmpty()) out.add('v');
        if (t.pool().isPresent()) out.add('p');
        if (!t.roster().isEmpty()) out.add('g');
        return out;
    }

    // ---- Sync frame ----

    private static void encodeSync(StringBuilder sb, Frame.SyncFrame s) {
        sb.append('S').append('|').append(s.entityId());
        if (!s.effects().isEmpty()) {
            sb.append('|');
            encodeEffectList(sb, s.effects());
        }
    }

    // ---- Register frame ----

    private static void encodeRegister(StringBuilder sb, Frame.RegisterFrame r) {
        sb.append('r').append('|').append(r.entityType());
    }

    // ---- Action frame ----

    private static void encodeAction(StringBuilder sb, Frame.ActionFrame a) {
        sb.append('a').append('|').append(a.verb());
        if (a.arg().isPresent()) {
            sb.append('|').append(a.arg().get());
        }
    }

    // ---- Error frame ----

    private static void encodeError(StringBuilder sb, Frame.ErrorFrame e) {
        sb.append('E').append('|');
        // 3-digit decimal code per §6.5 (range 100..999 enforced by ErrorFrame ctor)
        sb.append(e.code());
        if (e.message().isPresent()) {
            sb.append('|').append(e.message().get());
        }
    }

    // ---- s block (vision cells, coord-first) ----

    private static void encodeSBlock(StringBuilder sb, List<CellEntry> cells) {
        sb.append('s');
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) sb.append(',');
            encodeCellEntry(sb, cells.get(i));
        }
    }

    private static void encodeCellEntry(StringBuilder sb, CellEntry e) {
        encodeCoord(sb, e.coord());
        sb.append(Base64Codec.encodeDigit(e.presence()));
        if ((e.presence() & 0x01) != 0) {
            // kind present
            KindData kd = e.kind().orElseThrow(
                    () -> new CodecException("presence bit 0 set but kind missing"));
            switch (kd) {
                case KindData.Simple simple -> sb.append(simple.code());
                case KindData.RockSolo ignored -> sb.append('R');
                case KindData.RockRun run -> sb.append('R')
                        .append(run.direction())
                        .append(Base64Codec.encodeDigit(run.additionalCount()));
            }
            // entityState — only for non-rock kinds, and only when value != 0
            boolean isRock = kd instanceof KindData.RockSolo || kd instanceof KindData.RockRun;
            if (!isRock && e.entityState().isPresent() && e.entityState().getAsInt() != 0) {
                sb.append(Base64Codec.encodeDigit(e.entityState().getAsInt()));
            }
        }
        if ((e.presence() & 0x02) != 0) {
            // envState required when presence bit 1 set
            if (e.envState().isEmpty()) {
                throw new CodecException("presence bit 1 set but envState missing");
            }
            sb.append(Base64Codec.encodeDigit(e.envState().getAsInt()));
        }
    }

    // ---- c block (single token) ----

    private static void encodeCBlock(StringBuilder sb, StateChange sc) {
        sb.append('c').append(sc.code());
        if (sc.ctx().isPresent()) {
            sb.append(':').append(sc.ctx().get());
        }
    }

    // ---- f block ----

    private static void encodeFBlock(StringBuilder sb, List<ActiveEffect> effects) {
        sb.append('f');
        encodeEffectList(sb, effects);
    }

    private static void encodeEffectList(StringBuilder sb, List<ActiveEffect> effects) {
        for (int i = 0; i < effects.size(); i++) {
            if (i > 0) sb.append(',');
            ActiveEffect ae = effects.get(i);
            sb.append(ae.code()).append(':');
            encodeVarBase64(sb, ae.expiryTick());
            if (ae.ctx().isPresent()) {
                int[] xy = ae.ctx().get();
                if (xy.length != 2) {
                    throw new CodecException("Effect ctx must be 2-element abs coord: length=" + xy.length);
                }
                sb.append(':');
                encodeFixedBase64(sb, xy[0], 2);
                encodeFixedBase64(sb, xy[1], 2);
            }
        }
    }

    // ---- v block ----

    private static void encodeVBlock(StringBuilder sb, List<Event> events) {
        sb.append('v');
        for (int i = 0; i < events.size(); i++) {
            if (i > 0) sb.append(',');
            Event ev = events.get(i);
            if (ev.coord().isPresent()) {
                encodeCoord(sb, ev.coord().get());
            }
            sb.append(ev.code());
            if (ev.magnitude().isPresent()) {
                sb.append(Base64Codec.encodeDigit(ev.magnitude().getAsInt()));
            }
        }
    }

    // ---- p block ----

    private static void encodePBlock(StringBuilder sb, PoolSnapshot p) {
        sb.append('p');
        encodeVarBase64(sb, p.pool());
        sb.append('/');
        encodeVarBase64(sb, p.maxPool());
    }

    // ---- g block ----

    private static void encodeGBlock(StringBuilder sb, List<RosterMember> roster) {
        sb.append('g');
        for (int i = 0; i < roster.size(); i++) {
            if (i > 0) sb.append(',');
            RosterMember m = roster.get(i);
            encodeCoord(sb, m.coord());
            sb.append(m.role());
        }
    }

    // ---- coord encoding (single-form: 1 numpad, 4 relative, 4 absolute) ----

    private static void encodeCoord(StringBuilder sb, Coord c) {
        switch (c) {
            case Coord.Numpad n -> sb.append(n.digit());
            case Coord.Relative r -> encodeRelative(sb, r.dx(), r.dy());
            case Coord.Absolute a -> {
                encodeFixedBase64(sb, a.x(), 2);
                encodeFixedBase64(sb, a.y(), 2);
            }
        }
    }

    private static void encodeRelative(StringBuilder sb, int dx, int dy) {
        // Per SCHEMA §2 & §8.4: clamp to ±63, always emit exactly 4 chars.
        int cdx = clampRelative(dx);
        int cdy = clampRelative(dy);
        sb.append(cdx >= 0 ? '+' : '-');
        sb.append(Base64Codec.encodeDigit(Math.abs(cdx)));
        sb.append(cdy >= 0 ? '+' : '-');
        sb.append(Base64Codec.encodeDigit(Math.abs(cdy)));
    }

    private static int clampRelative(int v) {
        if (v > 63) return 63;
        if (v < -63) return -63;
        return v;
    }

    // ---- base64 integer helpers ----

    /** Fixed-width unsigned base64 (big-endian). Zero-pads on the left. */
    private static void encodeFixedBase64(StringBuilder sb, long v, int width) {
        if (v < 0) throw new CodecException("Cannot encode negative as fixed base64: " + v);
        long max = 1L << (6 * width);
        if (v >= max) {
            throw new CodecException("Value " + v + " exceeds " + width + "-char base64 capacity");
        }
        char[] buf = new char[width];
        for (int i = width - 1; i >= 0; i--) {
            buf[i] = Base64Codec.encodeDigit((int) (v & 63));
            v >>>= 6;
        }
        sb.append(buf);
    }

    /** Variable-width unsigned base64 (minimum digits; 0 encodes to "0"). */
    private static void encodeVarBase64(StringBuilder sb, long v) {
        if (v < 0) throw new CodecException("Cannot encode negative as var base64: " + v);
        if (v == 0) {
            sb.append('0');
            return;
        }
        // 11 base64 digits * 6 bits = 66 bits — covers long range.
        char[] buf = new char[11];
        int i = buf.length;
        while (v > 0) {
            buf[--i] = Base64Codec.encodeDigit((int) (v & 63));
            v >>>= 6;
        }
        sb.append(buf, i, buf.length - i);
    }

    // ============================================================
    // DECODE
    // ============================================================

    public static Frame decode(String s) {
        if (s == null) throw new CodecException("Null input");
        if (s.isEmpty()) throw new CodecException("Empty input");
        ParseCursor c = new ParseCursor(s);
        char type = c.next();
        return switch (type) {
            case 'T' -> { c.expect('|'); yield parseTick(c); }
            case 'S' -> { c.expect('|'); yield parseSync(c); }
            case 'r' -> { c.expect('|'); yield parseRegister(c); }
            case 'a' -> { c.expect('|'); yield parseAction(c); }
            case 'E' -> { c.expect('|'); yield parseError(c); }
            default -> throw new CodecException("Unknown frame type: " + type + " at 0");
        };
    }

    // ---- Tick parse ----

    private static Frame.TickFrame parseTick(ParseCursor c) {
        long tickId = readFixedBase64(c, 3);
        c.expect('|');
        int curX = (int) readFixedBase64(c, 2);
        int curY = (int) readFixedBase64(c, 2);
        c.expect('|');
        long energy = readVarBase64Until(c, '/');
        c.expect('/');
        long maxEnergy = readVarBase64UntilPipeOrEnd(c);

        int sensorRadius;
        boolean minimal;
        if (c.atEnd()) {
            // Full form with no sensor digit? Per §6.3.1 sensorRadius is always present in full form.
            // If we see end-of-input right after energy/max, there's no block & no sensorRadius:
            // this is invalid per grammar; but pragmatically treat as minimal form with no events.
            sensorRadius = 0;
            minimal = true;
        } else {
            c.expect('|');
            // Disambiguate sensorRadius vs minimal-form-block-letter.
            char peek = c.peek();
            if (peek == 's' || peek == 'c' || peek == 'f' || peek == 'v' || peek == 'p' || peek == 'g') {
                // Minimal form: block-letter immediately. Only 'v' is allowed by §6.3.2.
                if (peek != 'v') {
                    throw new CodecException(
                            "Minimal T-frame only permits v block, found '" + peek + "' at " + c.index());
                }
                sensorRadius = 0;
                minimal = true;
            } else {
                sensorRadius = Base64Codec.decodeDigit(c.next());
                if (sensorRadius < 1 || sensorRadius > 3) {
                    throw new CodecException(
                            "sensorRadius must be 1..3 in full T-frame: " + sensorRadius + " at " + (c.index() - 1));
                }
                minimal = false;
            }
        }

        List<CellEntry> cells = List.of();
        Optional<StateChange> change = Optional.empty();
        List<ActiveEffect> effects = List.of();
        List<Event> events = List.of();
        Optional<PoolSnapshot> pool = Optional.empty();
        List<RosterMember> roster = List.of();
        List<Character> blockOrder = new ArrayList<>(6);

        // Minimal-form note: the '|' before the block letter was already consumed
        // during disambiguation; subsequent blocks (if any) still start with '|'.
        boolean firstBlockPipeConsumed = minimal;

        while (!c.atEnd()) {
            if (!firstBlockPipeConsumed) {
                c.expect('|');
            }
            firstBlockPipeConsumed = false;
            char blockType = c.next();
            switch (blockType) {
                case 's' -> {
                    if (minimal) {
                        throw new CodecException("Minimal form forbids s block at " + (c.index() - 1));
                    }
                    cells = parseSBlock(c);
                    blockOrder.add('s');
                }
                case 'c' -> {
                    if (minimal) {
                        throw new CodecException("Minimal form forbids c block at " + (c.index() - 1));
                    }
                    change = Optional.of(parseCBlock(c));
                    blockOrder.add('c');
                }
                case 'f' -> {
                    if (minimal) {
                        throw new CodecException("Minimal form forbids f block at " + (c.index() - 1));
                    }
                    effects = parseFBlock(c);
                    blockOrder.add('f');
                }
                case 'v' -> {
                    events = parseVBlock(c);
                    blockOrder.add('v');
                }
                case 'p' -> {
                    if (minimal) {
                        throw new CodecException("Minimal form forbids p block at " + (c.index() - 1));
                    }
                    pool = Optional.of(parsePBlock(c));
                    blockOrder.add('p');
                }
                case 'g' -> {
                    if (minimal) {
                        throw new CodecException("Minimal form forbids g block at " + (c.index() - 1));
                    }
                    roster = parseGBlock(c);
                    blockOrder.add('g');
                }
                default -> throw new CodecException(
                        "Unknown block type: " + blockType + " at " + (c.index() - 1));
            }
        }

        return new Frame.TickFrame(tickId, curX, curY, (int) energy, (int) maxEnergy,
                sensorRadius, cells, change, effects, events, pool, roster, blockOrder);
    }

    // ---- s block parse ----

    private static List<CellEntry> parseSBlock(ParseCursor c) {
        List<CellEntry> cells = new ArrayList<>();
        while (true) {
            if (cells.size() >= MAX_S_ENTRIES) {
                throw new CodecException("MAX_S_ENTRIES exceeded at " + c.index());
            }
            cells.add(parseCellEntry(c));
            if (c.atEnd() || c.peek() == '|') break;
            c.expect(',');
        }
        return cells;
    }

    private static CellEntry parseCellEntry(ParseCursor c) {
        Coord coord = parseCoordFirst(c);
        int presence = Base64Codec.decodeDigit(c.next());
        if (presence < 1 || presence > 3) {
            throw new CodecException("presence must be 1..3 at " + (c.index() - 1) + ": " + presence);
        }
        Optional<KindData> kind = Optional.empty();
        OptionalInt entityState = OptionalInt.empty();
        OptionalInt envState = OptionalInt.empty();

        // Determine remaining chars before next ',' or '|' or end.
        int remainingBytes = remainingToDelim(c);

        if ((presence & 0x01) != 0) {
            // Kind present. Read kind char.
            if (remainingBytes < 1) {
                throw new CodecException("s entry missing kind byte at " + c.index());
            }
            char kindChar = c.next();
            remainingBytes--;
            if (kindChar == 'R') {
                // solo or run — decide by remaining layout per §8.1.4
                boolean envPresent = (presence & 0x02) != 0;
                // layouts:
                //   presence=1, solo → remainingBytes==0
                //   presence=1, run  → remainingBytes==2 (<dir><count>)
                //   presence=3, solo → remainingBytes==1 (envState)
                //   presence=3, run  → remainingBytes==3 (<dir><count><envState>)
                boolean isRun;
                if (envPresent) {
                    if (remainingBytes == 1) isRun = false;
                    else if (remainingBytes == 3) isRun = true;
                    else throw new CodecException(
                            "s entry presence=3 kind=R invalid remaining=" + remainingBytes + " at " + c.index());
                } else {
                    if (remainingBytes == 0) isRun = false;
                    else if (remainingBytes == 2) isRun = true;
                    else throw new CodecException(
                            "s entry presence=1 kind=R invalid remaining=" + remainingBytes + " at " + c.index());
                }
                if (isRun) {
                    char dir = c.next();
                    if (dir < '1' || dir > '9' || dir == '5') {
                        throw new CodecException("Invalid RLE dir '" + dir + "' at " + (c.index() - 1));
                    }
                    int additional = Base64Codec.decodeDigit(c.next());
                    if (additional < 1 || additional > 63) {
                        throw new CodecException("RLE additional out of range at " + (c.index() - 1));
                    }
                    kind = Optional.of(new KindData.RockRun(dir, additional));
                } else {
                    kind = Optional.of(new KindData.RockSolo());
                }
                remainingBytes = envPresent ? 1 : 0;
                // entityState is not allowed on rock entries per schema (kind=R ⇒ no entityState slot).
            } else {
                // Non-rock simple kind.
                validateSimpleKindCode(kindChar, c.index() - 1);
                kind = Optional.of(new KindData.Simple(kindChar));
                // layouts:
                //   presence=1, non-R, 0 remaining → no state
                //   presence=1, non-R, 1 remaining → entityState only
                //   presence=3, non-R, 1 remaining → envState only (entityState omitted because 0)
                //   presence=3, non-R, 2 remaining → entityState + envState
                boolean envPresent = (presence & 0x02) != 0;
                if (!envPresent) {
                    if (remainingBytes == 0) {
                        // no state
                    } else if (remainingBytes == 1) {
                        entityState = OptionalInt.of(Base64Codec.decodeDigit(c.next()));
                        remainingBytes--;
                    } else {
                        throw new CodecException(
                                "s entry presence=1 non-R invalid remaining=" + remainingBytes + " at " + c.index());
                    }
                } else {
                    if (remainingBytes == 1) {
                        // envState only, entityState omitted (=0 implicitly; left as empty on decoded record).
                    } else if (remainingBytes == 2) {
                        entityState = OptionalInt.of(Base64Codec.decodeDigit(c.next()));
                        remainingBytes--;
                    } else {
                        throw new CodecException(
                                "s entry presence=3 non-R invalid remaining=" + remainingBytes + " at " + c.index());
                    }
                }
            }
        }

        if ((presence & 0x02) != 0) {
            // envState required
            if (remainingBytes != 1) {
                throw new CodecException(
                        "s entry presence bit 1 missing envState at " + c.index());
            }
            envState = OptionalInt.of(Base64Codec.decodeDigit(c.next()));
        } else if (remainingBytes != 0) {
            throw new CodecException(
                    "s entry has trailing bytes at " + c.index() + " remaining=" + remainingBytes);
        }

        return new CellEntry(coord, presence, kind, entityState, envState);
    }

    private static void validateSimpleKindCode(char kind, int pos) {
        // Per §8.1.1: C/M/S/D/N/T/0-5/F (R handled separately).
        switch (kind) {
            case 'C', 'M', 'S', 'D', 'N', 'T',
                 '0', '1', '2', '3', '4', '5',
                 'F' -> { /* ok */ }
            default -> throw new CodecException("Unknown kind code '" + kind + "' at " + pos);
        }
    }

    // ---- c block parse ----

    private static StateChange parseCBlock(ParseCursor c) {
        // `c<type>[:<ctx>]` — ctx reads until next '|' or end.
        char code = c.next();
        validateChangeCode(code, c.index() - 1);
        Optional<String> ctx = Optional.empty();
        if (!c.atEnd() && c.peek() == ':') {
            c.next(); // consume ':'
            String raw = c.readUntil('|', false);
            ctx = Optional.of(raw);
        }
        return new StateChange(code, ctx);
    }

    private static void validateChangeCode(char code, int pos) {
        switch (code) {
            case 'C', 'M', 'S', 'D', 'N', 'T',
                 '0', '1', '2', '3', '4', '5',
                 'Z' -> { /* ok */ }
            default -> throw new CodecException("Unknown change code '" + code + "' at " + pos);
        }
    }

    // ---- f block parse ----

    private static List<ActiveEffect> parseFBlock(ParseCursor c) {
        return parseEffectList(c);
    }

    private static List<ActiveEffect> parseEffectList(ParseCursor c) {
        List<ActiveEffect> out = new ArrayList<>();
        while (true) {
            out.add(parseEffect(c));
            if (c.atEnd() || c.peek() == '|') break;
            c.expect(',');
        }
        return out;
    }

    private static ActiveEffect parseEffect(ParseCursor c) {
        char code = c.next();
        validateEffectCode(code, c.index() - 1);
        c.expect(':');
        // expiryTick: read until ':' or ',' or '|' or end.
        long expiry = readVarBase64UntilAny(c, ":,|");
        Optional<int[]> ctx = Optional.empty();
        if (!c.atEnd() && c.peek() == ':') {
            c.next(); // consume ':'
            if (code != 'F') {
                throw new CodecException(
                        "Effect code '" + code + "' does not carry ctx at " + (c.index() - 1));
            }
            int cx = (int) readFixedBase64(c, 2);
            int cy = (int) readFixedBase64(c, 2);
            ctx = Optional.of(new int[]{cx, cy});
        }
        return new ActiveEffect(code, expiry, ctx);
    }

    private static void validateEffectCode(char code, int pos) {
        switch (code) {
            case 'I', 'F', 'A', 'M', 'S', 'U' -> { /* ok */ }
            default -> throw new CodecException("Unknown effect code '" + code + "' at " + pos);
        }
    }

    // ---- v block parse ----

    private static List<Event> parseVBlock(ParseCursor c) {
        List<Event> out = new ArrayList<>();
        while (true) {
            if (out.size() >= MAX_V_ENTRIES) {
                throw new CodecException("MAX_V_ENTRIES exceeded at " + c.index());
            }
            out.add(parseEvent(c));
            if (c.atEnd() || c.peek() == '|') break;
            c.expect(',');
        }
        return out;
    }

    private static Event parseEvent(ParseCursor c) {
        char first = c.peek();
        Optional<Coord> coord = Optional.empty();
        if (first >= '1' && first <= '9') {
            coord = Optional.of(new Coord.Numpad(c.next()));
        } else if (first == '+' || first == '-') {
            coord = Optional.of(parseRelative(c));
        } else if (!isLetter(first)) {
            throw new CodecException("Event entry bad leading char '" + first + "' at " + c.index());
        }
        char code = c.next();
        validateEventCode(code, c.index() - 1);
        OptionalInt magnitude = OptionalInt.empty();
        if (eventHasMagnitude(code)) {
            if (c.atEnd() || c.peek() == ',' || c.peek() == '|') {
                throw new CodecException(
                        "Event code '" + code + "' requires magnitude at " + c.index());
            }
            magnitude = OptionalInt.of(Base64Codec.decodeDigit(c.next()));
        }
        return new Event(code, coord, magnitude);
    }

    private static void validateEventCode(char code, int pos) {
        switch (code) {
            case 'E', 'A', 'H', 'T', 'M', 'R', 'L', 'N', 'S', 'D' -> { /* ok */ }
            default -> throw new CodecException("Unknown event code '" + code + "' at " + pos);
        }
    }

    private static boolean eventHasMagnitude(char code) {
        // Per §8.4 table: E/A/H/T/M/R/L have magnitude; N/S/D do not.
        return switch (code) {
            case 'E', 'A', 'H', 'T', 'M', 'R', 'L' -> true;
            case 'N', 'S', 'D' -> false;
            default -> false;
        };
    }

    // ---- p block parse ----

    private static PoolSnapshot parsePBlock(ParseCursor c) {
        long pool = readVarBase64Until(c, '/');
        c.expect('/');
        long maxPool = readVarBase64UntilPipeOrEnd(c);
        return new PoolSnapshot((int) pool, (int) maxPool);
    }

    // ---- g block parse ----

    private static List<RosterMember> parseGBlock(ParseCursor c) {
        List<RosterMember> out = new ArrayList<>();
        while (true) {
            Coord coord = parseCoordFirst(c);
            char role = c.next();
            if (role < '0' || role > '5') {
                throw new CodecException("roster role must be 0-5 at " + (c.index() - 1) + ": " + role);
            }
            out.add(new RosterMember(coord, role));
            if (c.atEnd() || c.peek() == '|') break;
            c.expect(',');
        }
        return out;
    }

    // ---- Sync frame parse ----

    private static Frame.SyncFrame parseSync(ParseCursor c) {
        String entityId = c.readUntil('|', false);
        if (entityId.isEmpty()) throw new CodecException("Sync entityId missing at " + c.index());
        List<ActiveEffect> effects = List.of();
        if (!c.atEnd() && c.peek() == '|') {
            c.next(); // consume '|'
            effects = parseEffectList(c);
        }
        return new Frame.SyncFrame(entityId, effects);
    }

    // ---- Register frame parse ----

    private static Frame.RegisterFrame parseRegister(ParseCursor c) {
        char t = c.next();
        if (!c.atEnd()) {
            throw new CodecException("Register frame has trailing bytes at " + c.index());
        }
        if (t != 'C' && t != 'M' && t != 'S') {
            throw new CodecException("Register entityType must be C/M/S at " + (c.index() - 1) + ": " + t);
        }
        return new Frame.RegisterFrame(t);
    }

    // ---- Action frame parse ----

    private static Frame.ActionFrame parseAction(ParseCursor c) {
        char verb = c.next();
        if (verb != 'M' && verb != 'E' && verb != 'A' && verb != 'R' && verb != 'V' && verb != 'L') {
            throw new CodecException("Unknown action verb '" + verb + "' at " + (c.index() - 1));
        }
        Optional<String> arg = Optional.empty();
        if (!c.atEnd()) {
            if (c.peek() != '|') {
                throw new CodecException("Action frame expected '|' or end at " + c.index());
            }
            c.next(); // consume '|'
            // Remaining until end-of-input = arg (verb-specific grammar).
            String rest = c.readRun(c.remaining());
            validateActionArg(verb, rest);
            arg = Optional.of(rest);
        } else {
            // Verb requires no arg — only L legitimately has no arg.
            if (verb != 'L') {
                throw new CodecException("Action verb '" + verb + "' requires arg");
            }
        }
        return new Frame.ActionFrame(verb, arg);
    }

    private static void validateActionArg(char verb, String arg) {
        // Per §8.6: M/E/A/R take single numpad; V takes 3-char numpad string; L takes no arg.
        switch (verb) {
            case 'M', 'E', 'A', 'R' -> {
                if (arg.length() != 1 || arg.charAt(0) < '1' || arg.charAt(0) > '9') {
                    throw new CodecException("Verb '" + verb + "' requires single numpad digit: " + arg);
                }
            }
            case 'V' -> {
                if (arg.length() != 3) {
                    throw new CodecException("Verb V requires 3-char numpad string: " + arg);
                }
                for (int i = 0; i < 3; i++) {
                    char d = arg.charAt(i);
                    if (d < '1' || d > '9') {
                        throw new CodecException("Verb V digit out of range at " + i + ": " + d);
                    }
                }
            }
            case 'L' -> {
                if (!arg.isEmpty()) {
                    throw new CodecException("Verb L takes no arg but got: " + arg);
                }
            }
            default -> throw new CodecException("Internal: unvalidated verb " + verb);
        }
    }

    // ---- Error frame parse ----

    private static Frame.ErrorFrame parseError(ParseCursor c) {
        String codeStr = c.readUntil('|', false);
        if (codeStr.length() != 3) {
            throw new CodecException("Error code must be 3 decimal digits: " + codeStr);
        }
        int code;
        try {
            code = Integer.parseInt(codeStr);
        } catch (NumberFormatException nfe) {
            throw new CodecException("Error code not numeric: " + codeStr);
        }
        Optional<String> msg = Optional.empty();
        if (!c.atEnd() && c.peek() == '|') {
            c.next();
            msg = Optional.of(c.readRun(c.remaining()));
        }
        return new Frame.ErrorFrame(code, msg);
    }

    // ============================================================
    // PARSING PRIMITIVES
    // ============================================================

    private static Coord parseCoordFirst(ParseCursor c) {
        char first = c.peek();
        if (first >= '1' && first <= '9') {
            return new Coord.Numpad(c.next());
        }
        if (first == '+' || first == '-') {
            return parseRelative(c);
        }
        throw new CodecException("Expected numpad or relative coord at " + c.index() + ": '" + first + "'");
    }

    private static Coord.Relative parseRelative(ParseCursor c) {
        char sx = c.next();
        if (sx != '+' && sx != '-') {
            throw new CodecException("Relative coord sign expected '+' or '-' at " + (c.index() - 1) + ": " + sx);
        }
        int mx = Base64Codec.decodeDigit(c.next());
        char sy = c.next();
        if (sy != '+' && sy != '-') {
            throw new CodecException("Relative coord sign expected '+' or '-' at " + (c.index() - 1) + ": " + sy);
        }
        int my = Base64Codec.decodeDigit(c.next());
        int dx = (sx == '-') ? -mx : mx;
        int dy = (sy == '-') ? -my : my;
        return new Coord.Relative(dx, dy);
    }

    private static long readFixedBase64(ParseCursor c, int width) {
        long v = 0;
        for (int i = 0; i < width; i++) {
            v = (v << 6) | Base64Codec.decodeDigit(c.next());
        }
        return v;
    }

    private static long readVarBase64Until(ParseCursor c, char delim) {
        long v = 0;
        int count = 0;
        while (!c.atEnd() && c.peek() != delim) {
            v = (v << 6) | Base64Codec.decodeDigit(c.next());
            count++;
            if (count > 11) throw new CodecException("var base64 int too long at " + c.index());
        }
        if (count == 0) throw new CodecException("Expected base64 int at " + c.index());
        return v;
    }

    private static long readVarBase64UntilPipeOrEnd(ParseCursor c) {
        long v = 0;
        int count = 0;
        while (!c.atEnd() && c.peek() != '|') {
            v = (v << 6) | Base64Codec.decodeDigit(c.next());
            count++;
            if (count > 11) throw new CodecException("var base64 int too long at " + c.index());
        }
        if (count == 0) throw new CodecException("Expected base64 int at " + c.index());
        return v;
    }

    private static long readVarBase64UntilAny(ParseCursor c, String delims) {
        long v = 0;
        int count = 0;
        while (!c.atEnd() && delims.indexOf(c.peek()) < 0) {
            v = (v << 6) | Base64Codec.decodeDigit(c.next());
            count++;
            if (count > 11) throw new CodecException("var base64 int too long at " + c.index());
        }
        if (count == 0) throw new CodecException("Expected base64 int at " + c.index());
        return v;
    }

    /**
     * Returns the number of bytes remaining before the next ',' or '|' or end-of-input.
     * Does not advance the cursor. Used by parseCellEntry to disambiguate the §8.1.4
     * look-ahead cases (e.g. presence=1 kind=R solo vs run).
     */
    private static int remainingToDelim(ParseCursor c) {
        int i = c.index();
        int n = 0;
        int len = c.length();
        while (c.index() + n < len) {
            char ch = c.peekAt(n);
            if (ch == ',' || ch == '|') break;
            n++;
        }
        return n;
    }

    private static boolean isLetter(char ch) {
        return (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z');
    }
}
