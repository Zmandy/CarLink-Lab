package io.github.carlinklab;

public final class TpmsSnapshot {
    public final int frontLeftKpa;
    public final int frontRightKpa;
    public final int rearLeftKpa;
    public final int rearRightKpa;
    public final int frontLeftTempC;
    public final int frontRightTempC;
    public final int rearLeftTempC;
    public final int rearRightTempC;
    public final long updatedAtMillis;
    public final boolean stale;
    public final String source;
    public final int speedKmh;
    public final double fuelRateLph;
    public final double instantL100Km;
    public final double averageL100Km;
    public final boolean fuelAvailable;
    public final double tripFuelLiters;
    public final double currentCostYuan;
    public final double fuelPriceYuanPerLiter;

    public TpmsSnapshot(
            int frontLeftKpa,
            int frontRightKpa,
            int rearLeftKpa,
            int rearRightKpa,
            int frontLeftTempC,
            int frontRightTempC,
            int rearLeftTempC,
            int rearRightTempC,
            long updatedAtMillis,
            boolean stale,
            String source
    ) {
        this(frontLeftKpa, frontRightKpa, rearLeftKpa, rearRightKpa,
                frontLeftTempC, frontRightTempC, rearLeftTempC, rearRightTempC,
                updatedAtMillis, stale, source, 0, 0, 0, 0, false, 0, 0, 0);
    }

    public TpmsSnapshot(
            int frontLeftKpa,
            int frontRightKpa,
            int rearLeftKpa,
            int rearRightKpa,
            int frontLeftTempC,
            int frontRightTempC,
            int rearLeftTempC,
            int rearRightTempC,
            long updatedAtMillis,
            boolean stale,
            String source,
            int speedKmh,
            double fuelRateLph,
            double instantL100Km,
            double averageL100Km,
            boolean fuelAvailable
    ) {
        this(frontLeftKpa, frontRightKpa, rearLeftKpa, rearRightKpa,
                frontLeftTempC, frontRightTempC, rearLeftTempC, rearRightTempC,
                updatedAtMillis, stale, source, speedKmh, fuelRateLph,
                instantL100Km, averageL100Km, fuelAvailable, 0, 0, 0);
    }

    public TpmsSnapshot(
            int frontLeftKpa,
            int frontRightKpa,
            int rearLeftKpa,
            int rearRightKpa,
            int frontLeftTempC,
            int frontRightTempC,
            int rearLeftTempC,
            int rearRightTempC,
            long updatedAtMillis,
            boolean stale,
            String source,
            int speedKmh,
            double fuelRateLph,
            double instantL100Km,
            double averageL100Km,
            boolean fuelAvailable,
            double tripFuelLiters,
            double currentCostYuan,
            double fuelPriceYuanPerLiter
    ) {
        this.frontLeftKpa = frontLeftKpa;
        this.frontRightKpa = frontRightKpa;
        this.rearLeftKpa = rearLeftKpa;
        this.rearRightKpa = rearRightKpa;
        this.frontLeftTempC = frontLeftTempC;
        this.frontRightTempC = frontRightTempC;
        this.rearLeftTempC = rearLeftTempC;
        this.rearRightTempC = rearRightTempC;
        this.updatedAtMillis = updatedAtMillis;
        this.stale = stale;
        this.source = source;
        this.speedKmh = speedKmh;
        this.fuelRateLph = fuelRateLph;
        this.instantL100Km = instantL100Km;
        this.averageL100Km = averageL100Km;
        this.fuelAvailable = fuelAvailable;
        this.tripFuelLiters = tripFuelLiters;
        this.currentCostYuan = currentCostYuan;
        this.fuelPriceYuanPerLiter = fuelPriceYuanPerLiter;
    }
}
