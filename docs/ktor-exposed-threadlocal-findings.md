# Ktor × Exposed ThreadLocal / Coroutine-Context Findings

Empirical test results from this repo (2026-07-05), investigating
[KTOR-6802](https://youtrack.jetbrains.com/issue/KTOR-6802) and its interaction with
Exposed suspended transactions. All Exposed tests used Exposed 0.56.0 (core + jdbc) with H2.

## Test routes

| Route | What it does |
|---|---|
| `/foo` | `withContext(threadLocal.asContextElement(...))` in handler, no dispatcher change (original KTOR-6802 repro) |
| `/bar` | same but with `Dispatchers.IO` added |
| `/exposed-style` | `async(dbDispatcher + element).await()` — synthetic mimic of Exposed internals |
| `/blocking-style` | `withContext(dbDispatcher)` + synchronous ThreadLocal set/remove — mimics blocking `transaction {}` |
| `/tx-helper` | real `suspendedTransactionOnDbDispatcher` (dedicated dispatcher + permit) |
| `/tx-plain` | real `newSuspendedTransaction(db = db)` **without** dispatcher |
| `/tx-probe` | `/tx-plain` then 4 reads of `currentOrNull()` (straight-line, after suspend call, in `supervisorScope`, after scope) |
| `/tx-probe-helper` | same probes but transaction via the helper |
| `/tx-mixed` | `/tx-plain` then the helper immediately after (stale-window interaction) |

Leak = `database.transactionManager.currentOrNull()` returns the already-closed transaction
after the transaction block returned (a "stale window").

## Results matrix

| Route | 2.3.12 prod | 2.3.12 dev | 3.2.1 prod | 3.2.1 dev | 3.5.1 prod | 3.5.1 dev |
|---|---|---|---|---|---|---|
| `/foo` `/bar` `/exposed-style` (KTOR-6802) | ✅ | ✅ | ✅ | ❌ | ✅ | ✅ |
| `/tx-helper` | ✅ | not tested | ✅ | ❌ leak | ✅ | ✅ |
| `/tx-plain` | ❌ leak | not tested | ✅ | ⚠️ see below | ❌ leak | ❌ leak |
| `/tx-mixed` (permit skip + silent retry) | ❌ | not tested | ✅ | ✅ (by accident) | ❌ | ❌ |
| Handler thread | eventLoopGroupProxy | eventLoopGroupProxy | DefaultDispatcher | none/undispatched | eventLoopGroupProxy | eventLoopGroupProxy |

⚠️ 3.2.1 dev `/tx-plain`: after `delay` the transaction continues on
`kotlinx.coroutines.DefaultExecutor` (the coroutine timer thread) and the element binding is
lost (kotlinx.coroutines#2930 behavior) — no stale binding observed, but blocking SQL after a
suspension would run on the timer thread.

## Ktor version sweep (Exposed 0.56, dev AND prod tested per version)

| Ktor | Handler thread (prod) | dev == prod? | KTOR-6802 repro (dev) | Helper | Plain tx (no dispatcher) |
|---|---|---|---|---|---|
| 2.3.12 / 2.3.13 | event loop | ✅ | passes | ✅ clean | ❌ stale window |
| 3.0.3 | event loop | ✅ | passes | ✅ clean | ❌ stale window |
| 3.1.3 | event loop | ✅ | passes | ✅ clean | ❌ stale window |
| **3.2.1 / 3.2.3** | DefaultDispatcher | ❌ dev broken | **fails** | **❌ leaks in dev** | prod ✅ by accident |
| **3.3.3** | DefaultDispatcher | ❌ dev broken | **fails** | **❌ leaks in dev** | prod ✅ by accident |
| 3.4.3 | event loop | ✅ | passes | ✅ clean | ❌ stale window |
| 3.5.1 | event loop | ✅ | passes | ✅ clean | ❌ stale window |

The problem window is **exactly 3.2.x–3.3.x**: those versions moved handlers to
`DefaultDispatcher` in prod and lost the dispatcher entirely in dev mode. 3.4.3 reverted to the
event loop and fixed dev mode (note: a commenter reported 3.4.1 still broken, so within the 3.4
line prefer ≥ 3.4.3). Every version outside that window behaves identically to 2.3.12 for the
DB layer — so a stepping-stone upgrade path exists with no behavior change at each step:
`2.3.13 → 3.0.3 → 3.1.3 → (skip 3.2/3.3) → 3.4.3 → 3.5.x`.

## The unifying rule

> A stale window exists **iff the caller's resume after the transaction is synchronous
> (undispatched)**. A dispatched resume enqueues the continuation, so Exposed's
> `restoreThreadContext` runs before handler code continues.

- Netty event-loop dispatchers (2.3.12, 3.5.1) skip dispatch when already on the loop →
  `newSuspendedTransaction` without a dispatcher leaks.
- 3.2.1 prod ran handlers on `DefaultDispatcher`, which always dispatches → accidentally clean.
- 3.2.1 dev mode has no dispatcher in the call context at all → everything resumes
  undispatched wherever completion happened (even the dedicated-dispatcher helper leaked).
  Fixed by 3.5.1. Note: dev mode never used `SuspendFunctionGun` — it uses
  `DebugPipelineContext` (see `pipelineContextFor`), so `io.ktor.internal.disable.sfg=true`
  does **not** help; the 3.2.1-dev leak was in the engine's call dispatch, not SFG.

## Why the stale window is dangerous with our helpers

In the stale window, `withPermit`'s `currentOrNull()` check sees the closed transaction:

1. The permit is **skipped** (connection-accounting invariant broken).
2. `withSuspendTransaction` executes the statement on the closed connection →
   `The object is already closed` → Exposed's retry loop (`resetIfClosed()` in
   `suspendedTransactionAsyncInternal`) silently opens a **new permit-less transaction**
   and re-runs the statement body. Attempt #0's side effects before the first SQL call
   (logs, HTTP calls, counters) execute **twice**. Only a WARN log betrays it.
3. With `maxAttempts = 1` it would hard-fail instead.

The window ends at the first *real* dispatch (e.g. `call.respondText`). It survives plain
suspend-function calls and `supervisorScope` (neither dispatches). It was never observed to
cross into the next request.

## Rules for our code

1. **Never call `newSuspendedTransaction` without an explicit dispatcher.** Always use
   `suspendedTransactionOnDbDispatcher` / `transactionOnDbDispatcher`. Consider a detekt
   `ForbiddenMethodCall` rule.
2. **Prefer the blocking variant** (`transactionOnDbDispatcher`): synchronous ThreadLocal
   set/remove on one thread, no `ThreadContextElement`, immune everywhere.
3. Defense-in-depth (protects against stale windows from any source):
   in `withPermit`, treat a current transaction as absent if its connection is closed:
   `transactionManager.currentOrNull()?.takeUnless { it.connection.isClosed }`.
4. One `PermitController` per `Database`, permits = pool size = dbDispatcher threads.
   Why permits can't be replaced by HikariCP alone (on platform threads): without permits,
   pool exhaustion blocks `getConnection()` on dbDispatcher threads, and suspended
   connection-holders then have no thread to resume on to release their connections —
   a resume-starvation deadlock that stalls the DB layer until Hikari's `connectionTimeout`.
   permits = `maximumPoolSize` guarantees `getConnection()` never blocks. The permit becomes
   unnecessary only with a virtual-thread dbDispatcher (Java 21+, watch driver pinning until
   Java 24) or with R2DBC on Exposed 1.x — see the team summary FAQ for details.
5. `supervisorScope` around `newSuspendedTransaction` stays until Exposed 1.0
   (JetBrains/Exposed#1075, fixed in 1.0.0-rc-1 by PR #2601).

## Ktor upgrade guidelines for production (currently 2.3.12)

- **Target ≥ 3.4.3 (prefer 3.5.x); skip only 3.2.x–3.3.x.** The version sweep showed the
  dev≠prod divergence (KTOR-6802 dev leak that breaks the helper; call context loses its
  dispatcher) exists exactly in 3.2.x–3.3.x. 3.0.3, 3.1.3, and 3.4.3 behave identically to
  2.3.12 for the DB layer and are valid stepping stones. Caveat for CallLogging/MDC users:
  KTOR-6118-class SFG leaks under load were reportedly only fixed around 3.2.2, so 3.0.x–3.1.x
  may still carry those — load-test with your plugin stack if you use MDC.
- **3.5.1 needs Kotlin ≥ 2.2** (jars carry Kotlin 2.3.0 metadata; Kotlin 2.1.x cannot read it).
- **Don't rely on which thread runs handlers.** It changed 2.3.12 (event loop) → 3.2.1
  (DefaultDispatcher) → 3.5.1 (event loop again). Any code that got away with plain
  `newSuspendedTransaction` on 3.2.x prod breaks again on 3.5.x.
- **Regression-check on every Ktor upgrade** by running this repo's routes in both modes:
  `/tx-probe-helper` must print `s1=null s2=null s3=null s4=null`; grep the log for
  `Transaction attempt #0 failed`. Dev and prod must agree (they do from 3.5.1 on).
- **If production uses CallLogging/MDC, Sentry, or OpenTelemetry**: additionally load-test
  (`ab -n 6000 -c 100`) and verify no context bleeds across requests — KTOR-6118-class leaks
  need load and plugins these tests don't install.
- The Ktor Gradle plugin only gained `ktor { development = true }` in 3.x; on 2.3.12 use
  `-Dio.ktor.development=true`.

## Related issues

- KTOR-6802 — ThreadLocal context leak; dev-mode variant fixed by 3.5.1 (our finding; worth
  posting: dev mode never used SFG, and 2.3.12 was clean where 3.2.1-dev was not).
- KTOR-6118 — SFG MDC leak under load; marked Obsolete; reported fixed ~3.2.2.
- kotlinx.coroutines#2930 — ThreadContextElement + undispatched resume; root cause family
  of the `/tx-plain` stale window.
- JetBrains/Exposed#1075 — failed `newSuspendedTransaction` cancels parent scope; fixed in
  Exposed 1.0.0-rc-1; `supervisorScope` is the documented workaround for 0.x.
