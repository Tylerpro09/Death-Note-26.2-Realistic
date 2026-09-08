package dev.deathnote.realistic;

import java.util.Locale;

public enum DeathCause {
    HEART_ATTACK("heart_attack"),
    ACCIDENT("accident"),
    MYSTERIOUS("mysterious");

    private final String id;

    DeathCause(String id) { this.id = id; }
    public String id() { return id; }

    public static DeathCause fromId(String value) {
        if (value == null) return HEART_ATTACK;
        String normalized = value.toLowerCase(Locale.ROOT);
        for (DeathCause cause : values()) {
            if (cause.id.equals(normalized)) return cause;
        }
        return HEART_ATTACK;
    }
}
