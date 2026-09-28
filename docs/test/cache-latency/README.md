# Flight-instance cold/warm cache benchmark

The retained benchmark runs from the laptop at **100 RPS for 60 seconds per
pass**, using 6,000 distinct flight IDs for the cold pass and replaying the
same IDs for the warm pass.

- [Report](REPORT-LAPTOP-100RPS-60S.md): methodology, results, percentage
  reductions, and ten-second arrival groups.
- [Raw results](results-laptop-100rps-60s-2026-10-15.json): all 12,000 request
  records and validation results.
- [Harness](benchmark.py): Python standard-library script and laptop mode.

See the report for reproduction instructions. Earlier cache benchmark reports
and result files have been removed.
