---
name: data-science
description: Analyze actual datasets with reproducible scripts, schema checks and execution evidence.
---

# Executable data analysis

- Locate the user's dataset and confirm read access. Never invent rows, metrics, correlations or charts.
- Inspect format, row count, columns, missingness, duplicates, and numerical types before choosing a method.
- Check `python3` and required packages using `importlib.util.find_spec`; standard-library `csv`, `json`, `sqlite3` and `statistics` are the no-install fallback. Do not silently install packages.
- Use terminal and file tools to create a reproducible script in the selected project. Keep raw inputs unchanged, and write outputs to a separate directory.
- Validate results with explicit assertions: row counts, column schema, nonempty data, expected ranges and known examples. Run the script and tests through the terminal or `coding_workflow.run_test`; capture actual exit status and errors.
- For models, separate train/test data before preprocessing; avoid leakage, report baselines, and never infer causality from correlation alone.
- For plots, check whether matplotlib exists before generating them; otherwise deliver an auditable CSV summary.
- Return script and output paths, data provenance, assumptions, test commands and observed output. If execution was unavailable, say **not executed** instead of claiming success.
