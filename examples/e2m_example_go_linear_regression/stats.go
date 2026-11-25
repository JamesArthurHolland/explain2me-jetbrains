package main

// The three building blocks of least squares: a mean, a spread, and a co-spread.

// e2m :f stats/mean
func Mean(values []float64) float64 {
	sum := 0.0
	for _, v := range values {
		sum += v
	}
	return sum / float64(len(values))
}

// e2m :end

/* e2m := Sum of squared deviations from the mean, Sxx = Σ (v − mean)².
 *  Every term is ≥ 0, so this measures how spread out the values are.
 *  For xs = {1, 2, 3, 4, 5} with mean 3: 4 + 1 + 0 + 1 + 4 = 10. */
func SumOfSquares(values []float64, mean float64) float64 {
	total := 0.0
	for _, v := range values {
		d := v - mean
		total += d * d
	}
	return total
}

// e2m :f stats/cross-deviation
func SumOfCrossProducts(xs []float64, xMean float64, ys []float64, yMean float64) float64 {
	total := 0.0
	for i := range xs {
		dx := xs[i] - xMean
		dy := ys[i] - yMean
		total += dx * dy
	}
	return total
}

// e2m :end
