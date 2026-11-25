# Least-squares line fitting

`main` fits the straight line **y = a + b·x** through five points and reports how good the fit is.

| x | 1 | 2 | 3 | 4 | 5 |
|---|---|---|---|---|---|
| y | 2 | 4 | 5 | 4 | 5 |

The computation is split over three files:

1. `main.go` – the data, printing and a prediction.
2. `regression.go` – `FitLine`, the recipe: means → slope → intercept → R².
3. `stats.go` – the building blocks: `Mean`, `SumOfSquares` (Sxx), `SumOfCrossProducts` (Sxy).

Result: **y = 2.2 + 0.6·x**, **R² = 0.6**.

Replay the guided debugger walkthrough from the *Explain2Me* tool window: session `linear-regression`.
