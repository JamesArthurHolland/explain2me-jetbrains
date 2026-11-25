# Step 2 – the slope

    b = Sxy / Sxx = Σ(x−x̄)(y−ȳ) / Σ(x−x̄)²

* Sxy = 6 – how x and y move *together* (see `stats/cross-deviation`).
* Sxx = 10 – how much x moves on its own.
* **b = 0.6**

Why this formula? Minimising SSE(a, b) = Σ (y − a − b·x)² means setting both partial derivatives to 0.
∂/∂a gives a = ȳ − b·x̄; substituting that into ∂/∂b = 0 leaves exactly b = Sxy / Sxx.
