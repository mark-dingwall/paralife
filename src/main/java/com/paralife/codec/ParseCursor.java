package com.paralife.codec;

/**
 * Package-private index cursor over a wire string. Single-pass LL(1) parser state
 * per 15-SCHEMA.md §12. Mutable by design — hand this cursor down the parse tree.
 */
final class ParseCursor {

    private final String source;
    private int idx;

    ParseCursor(String source) {
        this.source = source;
        this.idx = 0;
    }

    int index() { return idx; }
    int length() { return source.length(); }
    boolean atEnd() { return idx >= source.length(); }
    int remaining() { return source.length() - idx; }

    char peek() {
        if (atEnd()) throw new CodecException("Unexpected end of input at " + idx);
        return source.charAt(idx);
    }

    char peekAt(int offset) {
        int p = idx + offset;
        if (p >= source.length()) throw new CodecException("Unexpected end of input at " + p);
        return source.charAt(p);
    }

    char next() {
        char c = peek();
        idx++;
        return c;
    }

    void expect(char expected) {
        char actual = next();
        if (actual != expected) {
            throw new CodecException("Expected '" + expected + "' at " + (idx - 1) + " but got '" + actual + "'");
        }
    }

    String readUntil(char delim, boolean consumeDelim) {
        int start = idx;
        while (idx < source.length() && source.charAt(idx) != delim) idx++;
        String out = source.substring(start, idx);
        if (consumeDelim && idx < source.length()) idx++;
        return out;
    }

    String readRun(int count) {
        if (idx + count > source.length()) {
            throw new CodecException("Cannot read " + count + " chars at " + idx + "; only " + remaining() + " left");
        }
        String out = source.substring(idx, idx + count);
        idx += count;
        return out;
    }
}
