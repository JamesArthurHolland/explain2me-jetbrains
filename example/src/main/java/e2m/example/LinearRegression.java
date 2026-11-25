package e2m.example;

/** Ordinary least squares for a single predictor: y ≈ intercept + slope · x. */
public final class LinearRegression {
    private LinearRegression() {
    }

    public record Fit(double intercept, double slope, double rSquared) {
        public double predict(double x) {
            return intercept + slope * x;
        }
    }

    public static Fit fit(double[] xs, double[] ys) {
        if (xs.length != ys.length || xs.length < 2) {
            throw new IllegalArgumentException("need at least two (x, y) pairs");
        }

        // e2m :f regression/means
        double xMean = Stats.mean(xs);
        double yMean = Stats.mean(ys);

        // e2m :f regression/slope
        double sxy = Stats.sumOfCrossProducts(xs, xMean, ys, yMean);
        double sxx = Stats.sumOfSquares(xs, xMean);
        double slope = sxy / sxx;
        // e2m :end

        // e2m := The best line always passes through the centroid (x̄, ȳ),
        //        so the intercept is a = ȳ − b·x̄ = 4 − 0.6 × 3 = 2.2
        double intercept = yMean - slope * xMean;

        double rSquared = rSquared(xs, ys, intercept, slope, yMean);
        return new Fit(intercept, slope, rSquared);
    }

    // e2m :f regression/r-squared
    static double rSquared(double[] xs, double[] ys, double intercept, double slope, double yMean) {
        double ssRes = 0;
        double ssTot = 0;
        for (int i = 0; i < xs.length; i++) {
            double predicted = intercept + slope * xs[i];
            ssRes += Math.pow(ys[i] - predicted, 2);
            ssTot += Math.pow(ys[i] - yMean, 2);
        }
        return 1 - ssRes / ssTot;
    }
    // e2m :end
}
