package com.paralife.codec;

/**
 * Thrown when wire bytes cannot be parsed or produced against SCHEMA.md grammar.
 * Maps to E|400 on the server; causes session close on the client.
 */
public class CodecException extends RuntimeException {

    public CodecException(String message) {
        super(message);
    }

    public CodecException(String message, Throwable cause) {
        super(message, cause);
    }
}
