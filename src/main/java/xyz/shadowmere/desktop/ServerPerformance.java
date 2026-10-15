package xyz.shadowmere.desktop;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Cached HTTPS median, sample reliability, and an optional measured download rate. */
public record ServerPerformance(State state, long millis, Instant checkedAt, int successes, int attempts,
                                long bytesPerSecond, Instant throughputAt) {
    public enum State { UNTESTED, TESTING, VERIFIED, FAILED }
    public ServerPerformance {
        Objects.requireNonNull(state); Objects.requireNonNull(checkedAt); Objects.requireNonNull(throughputAt);
        if(successes<0||attempts<successes||attempts>3||bytesPerSecond< -1)throw new IllegalArgumentException("Invalid performance metrics");
    }
    public ServerPerformance(State state,long millis,Instant checkedAt){
        this(state,millis,checkedAt,state==State.VERIFIED?1:0,state==State.VERIFIED?1:0,-1,Instant.EPOCH);
    }
    public static ServerPerformance untested(){return new ServerPerformance(State.UNTESTED,0,Instant.EPOCH);}
    public static ServerPerformance testing(){return new ServerPerformance(State.TESTING,0,Instant.EPOCH);}
    public static ServerPerformance verified(long millis){return new ServerPerformance(State.VERIFIED,Math.max(0,millis),Instant.now());}
    public static ServerPerformance failed(){return new ServerPerformance(State.FAILED,0,Instant.now());}
    public static ServerPerformance measured(List<Long> successfulMillis,int attempts){
        Objects.requireNonNull(successfulMillis);
        List<Long> times=successfulMillis.stream().sorted().toList();
        if(times.stream().anyMatch(time -> time<0)||attempts<times.size()||attempts>3||attempts<0)throw new IllegalArgumentException("Invalid latency samples");
        int n=times.size();long median=n==0?0:times.get(n/2);
        if(n>0&&n%2==0){long lower=times.get(n/2-1);median=lower+(median-lower)/2;}
        return new ServerPerformance(n>0?State.VERIFIED:State.FAILED,median,Instant.now(),n,attempts,-1,Instant.EPOCH);
    }
    public ServerPerformance withThroughput(long bytesPerSecond){
        if(bytesPerSecond<0)throw new IllegalArgumentException("Negative download rate");
        return new ServerPerformance(state,millis,checkedAt,successes,attempts,bytesPerSecond,Instant.now());
    }
    public boolean successful(){return state==State.VERIFIED;}
    public boolean completed(){
        return (successful()&&successes>0||state==State.FAILED&&successes==0)&&millis>=0&&!checkedAt.isAfter(Instant.now())
                &&(bytesPerSecond<0?throughputAt.equals(Instant.EPOCH):!throughputAt.equals(Instant.EPOCH)&&!throughputAt.isAfter(Instant.now()));
    }
    private static boolean recent(Instant time){Instant now=Instant.now();return !time.isAfter(now)&&time.isAfter(now.minus(Duration.ofDays(7)));}
    public boolean fresh(){return completed()&&recent(checkedAt);}
    public boolean hasQuality(){return completed()&&attempts>=3;}
    public double reliability(){return attempts==0?0:(double)successes/attempts;}
    public boolean throughputFresh(){return completed()&&bytesPerSecond>=0&&recent(throughputAt);}
    /** Full three-sample results lead, then reliability. Score estimates a 256 KiB transfer plus latency;
     * unknown throughput adds 1 second, a failed download is last within its quality/reliability tier. */
    public static Comparator<ServerPerformance> bestFirst(){
        return Comparator.comparing((ServerPerformance value)->!value.hasQuality())
                .thenComparing(Comparator.comparingDouble(ServerPerformance::reliability).reversed())
                .thenComparingDouble(ServerPerformance::combinedScore).thenComparingLong(ServerPerformance::millis);
    }
    private double combinedScore(){return millis+(bytesPerSecond>0?262_144_000.0/bytesPerSecond:bytesPerSecond==0?Double.POSITIVE_INFINITY:1000.0);}
    public String label(){
        return switch(state){
            case UNTESTED -> "Not tested";
            case TESTING -> "Testing…";
            case FAILED -> hasQuality()?"Unavailable · 0%":"Unavailable";
            case VERIFIED -> millis+" ms"+(hasQuality()?" · "+Math.round(reliability()*100)+"%":"")
                    +(bytesPerSecond>0?String.format(Locale.ROOT," · %.1f Mb/s",bytesPerSecond*8.0/1_000_000):bytesPerSecond==0?" · download failed":"");
        };
    }
}
