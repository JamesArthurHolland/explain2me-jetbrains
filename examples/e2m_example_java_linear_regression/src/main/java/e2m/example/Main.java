package e2m.example;

/**
 * Explain2Me demo: fit the straight line y = a + b·x through five points with
 * ordinary least squares, then measure how good the fit is (R²).
 */
public class Main {
    public static void main(String[] args) {
        // e2m :f regression/overview
        double[] xs = {1, 2, 3, 4, 5};
        double[] ys = {2, 4, 5, 4, 5};

        LinearRegression.Fit fit = LinearRegression.fit(xs, ys);

        System.out.printf("y = %.2f + %.2f·x%n", fit.intercept(), fit.slope());
        System.out.printf("R² = %.2f%n", fit.rSquared());
        // e2m := Predicting is just plugging a new x into the fitted line:
        //        ŷ = 2.2 + 0.6 × 6 = 5.8
        System.out.printf("prediction for x = 6: %.2f%n", fit.predict(6));
    }
}
