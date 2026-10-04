# Build evaluator rubric

Report each category separately, using evidence rather than a score to imply unsupported release readiness.

| Category | Weight | Evidence |
|---|---|---|
| Domain correctness | 25% | Edge cases, route/flight states, timing, tests |
| Android integration | 20% | Services, setup, injection, lifecycle; compilation/device status explicit |
| Desktop integration | 20% | UI, typed native bridge, device scoping, protocol |
| Privacy and security | 25% | No sinks, bounded authorization, parser/file/command validation |
| Delivery and clarity | 10% | Usable build instructions, manifest, limitations, pack tools |

Critical/high security findings block the affected feature. Missing toolchains are release blockers, not failing logic tests. Code-only evidence does not satisfy real-device release gates. Target score 7/10 for reviewable implementation, never automatically for publication. Bounded review loop maximum 15 cycles; resolve findings and rerun relevant checks before reporting.
