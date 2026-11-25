// Explain2Me demo: fit the straight line y = a + b·x through five points with
// ordinary least squares, then measure how good the fit is (R²).
package main

import "fmt"

func main() {
	// e2m :f regression/overview
	xs := []float64{1, 2, 3, 4, 5}
	ys := []float64{2, 4, 5, 4, 5}

	fit, err := FitLine(xs, ys)
	if err != nil {
		panic(err)
	}

	fmt.Printf("y = %.2f + %.2f·x\n", fit.Intercept, fit.Slope)
	fmt.Printf("R² = %.2f\n", fit.RSquared)
	// e2m := Predicting is just plugging a new x into the fitted line:
	//        ŷ = 2.2 + 0.6 × 6 = 5.8
	fmt.Printf("prediction for x = 6: %.2f\n", fit.Predict(6))
}
