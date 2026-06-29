package com.paralife.codec;

/** Per SCHEMA.md §8.5 `g` block. role: '0'-'5'. */
public record RosterMember(Coord coord, char role) {
    public RosterMember {
        if (role < '0' || role > '5') {
            throw new IllegalArgumentException("role must be 0-5: " + role);
        }
    }
}
