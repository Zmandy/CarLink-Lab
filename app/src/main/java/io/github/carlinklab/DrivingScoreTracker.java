package io.github.carlinklab;

public final class DrivingScoreTracker {
    private static final double HARSH_ACCEL_THRESHOLD_MPS2 = 1.4d;
    private static final double HARSH_BRAKE_THRESHOLD_MPS2 = -1.8d;
    private static final long EVENT_COOLDOWN_MS = 5_000L;
    private static final double OVERSPEED_KMH = 120.0d;

    private long lastSampleMs;
    private int lastSpeedKmh;
    private double totalSampleSeconds;
    private double idleSeconds;
    private int harshAccelerationCount;
    private int harshBrakingCount;
    private int overspeedCount;
    private boolean overspeedActive;
    private long lastAccelerationEventMs;
    private long lastBrakingEventMs;
    private Result current = Result.unavailable();

    public Result update(int speedKmh, double fuelRateLph, double averageL100Km, long nowMs) {
        if (lastSampleMs > 0L) {
            double deltaSeconds = Math.max(0.0d, Math.min(10.0d, (nowMs - lastSampleMs) / 1000.0d));
            if (deltaSeconds >= 0.5d) {
                totalSampleSeconds += deltaSeconds;
                if (speedKmh <= 3 && fuelRateLph > 0.05d) {
                    idleSeconds += deltaSeconds;
                }

                double accelerationMps2 = ((speedKmh - lastSpeedKmh) / 3.6d) / deltaSeconds;
                if (accelerationMps2 >= HARSH_ACCEL_THRESHOLD_MPS2
                        && speedKmh >= 10
                        && nowMs - lastAccelerationEventMs >= EVENT_COOLDOWN_MS) {
                    harshAccelerationCount++;
                    lastAccelerationEventMs = nowMs;
                }
                if (accelerationMps2 <= HARSH_BRAKE_THRESHOLD_MPS2
                        && lastSpeedKmh >= 15
                        && nowMs - lastBrakingEventMs >= EVENT_COOLDOWN_MS) {
                    harshBrakingCount++;
                    lastBrakingEventMs = nowMs;
                }
            }
        }

        if (speedKmh >= OVERSPEED_KMH) {
            if (!overspeedActive) {
                overspeedCount++;
                overspeedActive = true;
            }
        } else if (speedKmh < OVERSPEED_KMH - 5.0d) {
            overspeedActive = false;
        }

        lastSampleMs = nowMs;
        lastSpeedKmh = speedKmh;

        double idlePercent = totalSampleSeconds > 0.0d
                ? idleSeconds / totalSampleSeconds * 100.0d
                : 0.0d;
        boolean available = totalSampleSeconds >= 15.0d;
        int score = calculateScore(averageL100Km, idlePercent);
        current = new Result(score, available, harshAccelerationCount, harshBrakingCount,
                overspeedCount, Math.max(0.0d, Math.min(100.0d, idlePercent)));
        return current;
    }

    public Result current() {
        return current;
    }

    private int calculateScore(double averageL100Km, double idlePercent) {
        double score = 100.0d;
        score -= Math.min(24.0d, harshAccelerationCount * 6.0d);
        score -= Math.min(24.0d, harshBrakingCount * 7.0d);
        if (averageL100Km > 7.5d) {
            score -= Math.min(18.0d, (averageL100Km - 7.5d) * 3.0d);
        }
        score -= Math.min(12.0d, idlePercent * 0.6d);
        score -= Math.min(10.0d, overspeedCount * 5.0d);
        return (int) Math.round(Math.max(0.0d, Math.min(100.0d, score)));
    }

    public static final class Result {
        public final int score;
        public final boolean available;
        public final int harshAccelerationCount;
        public final int harshBrakingCount;
        public final int overspeedCount;
        public final double idlePercent;

        Result(int score, boolean available, int harshAccelerationCount,
               int harshBrakingCount, int overspeedCount, double idlePercent) {
            this.score = score;
            this.available = available;
            this.harshAccelerationCount = harshAccelerationCount;
            this.harshBrakingCount = harshBrakingCount;
            this.overspeedCount = overspeedCount;
            this.idlePercent = idlePercent;
        }

        static Result unavailable() {
            return new Result(0, false, 0, 0, 0, 0.0d);
        }
    }
}
