"""Ordinary least squares for a single predictor: y ≈ intercept + slope · x."""

from dataclasses import dataclass

import stats


@dataclass(frozen=True)
class Fit:
    intercept: float
    slope: float
    r_squared: float

    def predict(self, x: float) -> float:
        return self.intercept + self.slope * x


def fit(xs: list[float], ys: list[float]) -> Fit:
    if len(xs) != len(ys) or len(xs) < 2:
        raise ValueError("need at least two (x, y) pairs")

    # e2m :f regression/means
    x_mean = stats.mean(xs)
    y_mean = stats.mean(ys)

    # e2m :f regression/slope
    sxy = stats.sum_of_cross_products(xs, x_mean, ys, y_mean)
    sxx = stats.sum_of_squares(xs, x_mean)
    slope = sxy / sxx
    # e2m :end

    # e2m := The best line always passes through the centroid (x̄, ȳ),
    #        so the intercept is a = ȳ − b·x̄ = 4 − 0.6 × 3 = 2.2
    intercept = y_mean - slope * x_mean

    r2 = r_squared(xs, ys, intercept, slope, y_mean)
    return Fit(intercept, slope, r2)


# e2m :f regression/r-squared
def r_squared(xs, ys, intercept, slope, y_mean):
    ss_res = 0.0
    ss_tot = 0.0
    for x, y in zip(xs, ys):
        predicted = intercept + slope * x
        ss_res += (y - predicted) ** 2
        ss_tot += (y - y_mean) ** 2
    return 1 - ss_res / ss_tot
# e2m :end
