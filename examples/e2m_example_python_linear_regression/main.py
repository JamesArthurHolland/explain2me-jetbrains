"""Explain2Me demo: fit the straight line y = a + b·x through five points with
ordinary least squares, then measure how good the fit is (R²)."""

import linear_regression


def main():
    # e2m :f regression/overview
    xs = [1.0, 2.0, 3.0, 4.0, 5.0]
    ys = [2.0, 4.0, 5.0, 4.0, 5.0]

    fit = linear_regression.fit(xs, ys)

    print(f"y = {fit.intercept:.2f} + {fit.slope:.2f}·x")
    print(f"R² = {fit.r_squared:.2f}")
    # e2m := Predicting is just plugging a new x into the fitted line:
    #        ŷ = 2.2 + 0.6 × 6 = 5.8
    print(f"prediction for x = 6: {fit.predict(6):.2f}")


if __name__ == "__main__":
    main()
