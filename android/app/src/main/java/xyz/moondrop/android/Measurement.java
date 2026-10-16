package xyz.moondrop.android;

/** Local HTTPS verification, never inferred from a feed's availability claim. */
public final class Measurement {
    public static final long MAX_AGE=7L*24*60*60*1000;
    public final long latencyMs, checkedAt, bytesPerSecond;
    public final int successes, attempts;
    public final boolean verified, fresh;
    public final String label;
    public Measurement(long latencyMs,long checkedAt,long bytesPerSecond,int successes,int attempts) {
        this.latencyMs=latencyMs;this.checkedAt=checkedAt;this.bytesPerSecond=bytesPerSecond;
        this.successes=successes;this.attempts=attempts;
        verified=successes>0&&latencyMs>=0;
        fresh=checkedAt>0&&checkedAt<=System.currentTimeMillis()+60_000&&System.currentTimeMillis()-checkedAt<MAX_AGE;
        label=verified?latencyMs+" ms":"Unavailable";
    }
    public boolean verified() {return verified;}
    public boolean fresh() {return checkedAt>0&&checkedAt<=System.currentTimeMillis()+60_000&&System.currentTimeMillis()-checkedAt<MAX_AGE;}
    public String label() {return label;}
    public double score() {return verified?latencyMs+(bytesPerSecond>0?262144000.0/bytesPerSecond:1000)+(attempts-successes)*2000.0:Double.POSITIVE_INFINITY;}
}
