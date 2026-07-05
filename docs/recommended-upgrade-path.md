# Recommended Upgrade Path: Exposed & Ktor (Separately)

The concrete, ordered plan combining all findings. Background/evidence:
[team summary](ktor-exposed-coroutines-summary.md) ·
[Ktor findings + version sweep](ktor-exposed-threadlocal-findings.md) ·
[Exposed version report](exposed-version-compat-report.md).

Current production baseline: **Ktor 2.3.12 + Exposed 0.56.0 (core+jdbc) + `Database.kt` helpers
(permit + dedicated dispatcher), no connection pool.**

## Ordering principle

**Exposed first, Ktor second.** One upgrade at a time, and this order specifically, because:

1. Exposed 1.3.1 on Ktor 2.3.12 is the exact combination we verified end-to-end (both modes,
   under load, including deliberately poisoned scenarios).
2. The only reason Ktor versions ever mattered to the DB layer was Exposed 0.x's
   resume-ordering flaw reacting to Ktor's dispatch changes. Exposed 1.x removes the flaw, so
   the later Ktor upgrade stops being a DB-risk event and becomes a plain API migration.
3. The reverse order buys nothing: on Exposed 0.5x, every safe Ktor target (3.0.3 → 3.5.x) has
   *identical* DB-layer behavior to 2.3.12 — same stale window on plain calls, same helper
   dependency. Ktor-first changes the risk profile by zero and stacks API churn on a fragile
   foundation.

## The one rule that survives everything

> **Never run Ktor 3.2.x–3.3.x with Exposed 0.5x.**

That is the only genuinely poisonous cell in the whole matrix: those Ktor versions lose the
call dispatcher in dev mode (even the helper leaks there) and dev mode lies about prod
behavior. Every other combination is either fine, or fine-with-the-helper. The Exposed-first
ordering makes this rule nearly impossible to violate by accident.

## The steps

### Step 0 — Kotlin 2.2.x (standalone, anytime)
Compatible with every Ktor/Exposed version in this plan; required by Ktor 3.5.x (its jars carry
Kotlin 2.3.0 metadata that 2.1.x cannot read). Zero behavior change; ship alone.

### Step 1 — Exposed 0.56.0 → 1.3.1 (on Ktor 2.3.12) — the real code-churn step
- Helper internals: `withContext(dbDispatcher) { suspendTransaction(db = ...) }`, delete
  `supervisorScope`, `withPermit` → companion `TransactionManager.currentOrNull()` + `.db == this`.
- Repo-wide mechanical: imports → `org.jetbrains.exposed.v1.core.*` / `v1.jdbc.*`,
  `Transaction` → `JdbcTransaction` where the type is named. Callers keep their shape;
  nested helper calls still join the same transaction with one permit.
- Skip 0.61.0 entirely (drop-in but fixes nothing relevant). Note there is no stable "1.0.0";
  the v1 line starts at 1.1.0.
- **Verify**: probe routes print `s1..s4=null` in both modes; no `Transaction attempt #0
  failed` in logs; short `ab` run. Then let it bake in production — give this step the
  observation time, it's the one with real churn.

### Step 2 — Ktor 2.3.12 → 3.5.x directly (one careful PR)
The version sweep (2.3.13, 3.0.3, 3.1.3, 3.2.3, 3.3.3, 3.4.3, 3.5.1 — dev and prod each)
proved all safe versions are behavior-identical for the DB layer. Therefore stepping stones
(`3.0.3 → 3.1.3 → 3.4.3`) deliver **no behavioral de-risking** — the only real event in the
whole path is the 2→3 API migration, and it costs the same wherever you land. Spend the
caution budget on that one PR and go straight to ≥ 3.4.3, preferably 3.5.x.
- Stepping stones remain a valid *organizational* option (smaller diffs for review) — just
  know they're comfort, not safety, and never park on 3.2.x–3.3.x.
- MDC/CallLogging users: KTOR-6118-class leaks under load were reportedly fixed ~3.2.2, so if
  stepping, load-test 3.0/3.1 with the real plugin stack before parking there.
- **Verify**: same regression routes, both modes — from 3.4.3/3.5.x on, dev mode is trustworthy
  again (dev == prod).

### Step 3 — Add HikariCP
`maximumPoolSize` = permits = dbDispatcher threads. Restores connection reuse, uniform
`connectionTimeout`, keepalive, pool metrics. Until this step, two pool-less conditions apply:
the helper-only ban is load-bearing (a plain call bypasses the permit = unbounded connection),
and driver-level `connectTimeout`/`socketTimeout` must be set in the JDBC URL (connects happen
*inside* the permit; hung connects during an outage would starve all permits).

### Step 4 — Virtual threads, drop the permit (Java 21+, prefer 24+)
`Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()`; Hikari's
`maximumPoolSize` becomes the bound, `connectionTimeout` the shedding. **Requires Step 3** —
never permit-less *and* pool-less. On Java 21 check driver pinning
(`-Djdk.tracePinnedThreads=full`); Java 24 (JEP 491) removes the concern.

## Summary for the impatient

> Kotlin 2.2 → **Exposed 1.3.1** (bake) → **Ktor 3.5.x in one PR** → Hikari → virtual threads.
> Never 3.2.x–3.3.x with Exposed 0.5x. Verify each step with the probe routes in both modes.

Every step is independently shippable, independently revertible, and changes exactly one thing.
