---
name: math-physics
description: Execute and verify reproducible mathematics, physics and engineering calculations using the Hermes sandbox; do not claim uninstalled libraries work.
---

# Mathematical and engineering calculation workflow

1. Identify the user's physical system, given quantities, units, assumptions, target accuracy and constraints. Ask for missing safety-critical parameters.
2. Prefer exact derivations before numerical computation. Check dimensional consistency and limit cases.
3. Check the *actual* local runtime with the terminal tool before promising a solver:
   `python3 -c "import importlib.util; print({x: bool(importlib.util.find_spec(x)) for x in ('sympy','numpy','scipy','matplotlib')})"`
4. Use Python's built-in `math`, `cmath`, `decimal`, `fractions` and `statistics` when possible. Only use SymPy/NumPy/SciPy if the dependency check succeeds. If installation is necessary, request approval before network/package changes.
5. For numerical tasks, write a reproducible script inside the selected project; record inputs, units, solver version, numerical tolerance and expected output. Use terminal/coding_workflow.run_test to execute a meaningful independent check. A written formula is not evidence of an executed computation.
6. Test edge cases, invalid inputs and a known analytical example. Compare with a second independent calculation when practical. Capture exit status and actual output, and do not mark a task complete if execution fails.
7. Return the equation, substitutions, units, result, uncertainty/limitations, exact command executed and file path. Clearly label any simulation not run or result not verified.

## Minimal standard-library example

```sh
python3 - <<'PY'
from math import isclose, sqrt
g, h = 9.80665, 10.0
v = sqrt(2 * g * h)
assert isclose(v*v/(2*g), h, rel_tol=1e-12)
print(f"impact speed (vacuum, no drag): {v:.6f} m/s")
PY
```

This example verifies a mathematical identity under an idealized assumption, not a physical measurement. Do not claim real-world engineering safety certification.
