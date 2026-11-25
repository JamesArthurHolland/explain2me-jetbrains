package e2m.example;

/** The three building blocks of least squares: a mean, a spread, and a co-spread. */
public final class Stats {
    private Stats() {
    }

    // e2m :f stats/mean
    public static double mean(double[] values) {
        double sum = 0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.length;
    }
    // e2m :end

    /* e2m := Sum of squared deviations from the mean, Sxx = Σ (v − mean)².
     *  Every term is ≥ 0, so this measures how spread out the values are.
     *  For xs = {1, 2, 3, 4, 5} with mean 3: 4 + 1 + 0 + 1 + 4 = 10. */
    public static double sumOfSquares(double[] values, double mean) {
        double total = 0;
        for (double v : values) {
            double d = v - mean;
            total += d * d;
        }
        return total;
    }

    // e2m :f stats/cross-deviation
    public static double sumOfCrossProducts(double[] xs, double xMean, double[] ys, double yMean) {
        double total = 0;
        for (int i = 0; i < xs.length; i++) {
            double dx = xs[i] - xMean;
            double dy = ys[i] - yMean;
            total += dx * dy;
        }
        return total;
    }
    // e2m :end
}
