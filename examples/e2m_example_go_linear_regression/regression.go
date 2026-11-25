package main

import "errors"

// Fit is the result of ordinary least squares for one predictor: y ≈ Intercept + Slope·x.
type Fit struct {
	Intercept float64
	Slope     float64
	RSquared  float64
}

// Predict evaluates the fitted line at x.
func (f Fit) Predict(x float64) float64 {
	return f.Intercept + f.Slope*x
}

// FitLine fits y = a + b·x through the points (xs[i], ys[i]).
func FitLine(xs, ys []float64) (Fit, error) {
	if len(xs) != len(ys) || len(xs) < 2 {
		return Fit{}, errors.New("need at least two (x, y) pairs")
	}

	// e2m :f regression/means
	xMean := Mean(xs)
	yMean := Mean(ys)

	// e2m :f regression/slope
	sxy := SumOfCrossProducts(xs, xMean, ys, yMean)
	sxx := SumOfSquares(xs, xMean)
	slope := sxy / sxx
	// e2m :end

	// e2m := The best line always passes through the centroid (x̄, ȳ),
	//        so the intercept is a = ȳ − b·x̄ = 4 − 0.6 × 3 = 2.2
	intercept := yMean - slope*xMean

	r2 := RSquared(xs, ys, intercept, slope, yMean)
	return Fit{Intercept: intercept, Slope: slope, RSquared: r2}, nil
}

// e2m :f regression/r-squared
func RSquared(xs, ys []float64, intercept, slope, yMean float64) float64 {
	ssRes := 0.0
	ssTot := 0.0
	for i := range xs {
		predicted := intercept + slope*xs[i]
		ssRes += (ys[i] - predicted) * (ys[i] - predicted)
		ssTot += (ys[i] - yMean) * (ys[i] - yMean)
	}
	return 1 - ssRes/ssTot
}

// e2m :end
