package xyz.shadowmere.desktop;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** A complete HTTPS round-trip measurement, rather than estimated bandwidth. */
public record ServerPerformance(State state, long millis, Instant checkedAt) {
    public enum State { UNTESTED, TESTING, VERIFIED, FAILED }
    public ServerPerformance { Objects.requireNonNull(state); Objects.requireNonNull(checkedAt); }
    public static ServerPerformance untested() { return new ServerPerformance(State.UNTESTED, 0, Instant.EPOCH); }
    public static ServerPerformance testing() { return new ServerPerformance(State.TESTING, 0, Instant.EPOCH); }
    public static ServerPerformance verified(long millis) { return new ServerPerformance(State.VERIFIED, Math.max(0, millis), Instant.now()); }
    public static ServerPerformance failed() { return new ServerPerformance(State.FAILED, 0, Instant.now()); }
    public boolean successful() { return state == State.VERIFIED; }
    public boolean fresh() {
        if (state != State.VERIFIED && state != State.FAILED) return false;
        Instant now = Instant.now();
        return !checkedAt.isAfter(now) && checkedAt.plus(Duration.ofMinutes(successful() ? 30 : 5)).isAfter(now);
    }
    public String label() {
        return switch (state) {
            case UNTESTED -> "Not tested";
            case TESTING -> "Testing…";
            case VERIFIED -> millis + " ms";
            case FAILED -> "Unavailable";
        };
    }
}
