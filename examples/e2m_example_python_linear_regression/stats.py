"""The three building blocks of least squares: a mean, a spread, and a co-spread."""


# e2m :f stats/mean
def mean(values):
    total = 0.0
    for v in values:
        total += v
    return total / len(values)
# e2m :end


# e2m := Sum of squared deviations from the mean, Sxx = Σ (v − mean)².
#        Every term is ≥ 0, so this measures how spread out the values are.
#        For xs = [1, 2, 3, 4, 5] with mean 3: 4 + 1 + 0 + 1 + 4 = 10.
def sum_of_squares(values, mean_value):
    total = 0.0
    for v in values:
        d = v - mean_value
        total += d * d
    return total


# e2m :f stats/cross-deviation
def sum_of_cross_products(xs, x_mean, ys, y_mean):
    total = 0.0
    for x, y in zip(xs, ys):
        dx = x - x_mean
        dy = y - y_mean
        total += dx * dy
    return total
# e2m :end
