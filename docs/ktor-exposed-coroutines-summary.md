# Ktor + Exposed + Coroutines: What We Learned (Team Summary)

An investigation into thread-local / transaction-context leaks when using Exposed suspended
transactions inside Ktor, verified empirically in this repo (2026-07). Detailed data lives in
[ktor-exposed-threadlocal-findings.md](ktor-exposed-threadlocal-findings.md) and
[exposed-version-compat-report.md](exposed-version-compat-report.md); this doc is the shareable
summary + FAQ.

## TL;DR — what to do

1. **Never call `newSuspendedTransaction` without an explicit dispatcher.** Always go through
   our `Database.kt` helpers (`transactionOnDbDispatcher` / `suspendedTransactionOnDbDispatcher`).
   Consider a detekt `ForbiddenMethodCall` rule.
2. **Prefer the blocking variant** (`transactionOnDbDispatcher`) unless you truly must suspend
   mid-transaction. It is immune to this entire bug class by construction.
3. **Upgrade Exposed to 1.3.1 before upgrading Ktor.** The Exposed 1.x rewrite eliminates the
   bug class at the source, even on our current Ktor 2.3.12. Migration is mechanical
   (imports, `Transaction`→`JdbcTransaction`, `suspendTransaction` + `withContext`).
4. **When upgrading Ktor, skip 3.2.x–3.3.x; target ≥ 3.4.3 (prefer 3.5.x).** A full version
   sweep (2.3.13, 3.0.3, 3.1.3, 3.2.3, 3.3.3, 3.4.3, 3.5.1 — dev and prod each) showed the
   broken window is exactly 3.2.x–3.3.x: dev mode differs from prod there and breaks even our
   helper. 3.0.3, 3.1.3 and 3.4.3 behave identically to 2.3.12 for the DB layer, so a
   stepping-stone path exists: `2.3.13 → 3.0.3 → 3.1.3 → (skip) → 3.4.3 → 3.5.x`. 3.5.x needs
   Kotlin ≥ 2.2. (MDC/CallLogging users: KTOR-6118-class leaks under load were reportedly fixed
   ~3.2.2, so load-test 3.0–3.1 with your plugin stack.)
5. Transaction boundaries live at the **usecase level**; Exposed types never escape the
   transaction lambda (return DTOs).

## Recommended migration path

Each stage is independently shippable and verifiable; run the regression check (last FAQ entry)
after each one. HikariCP may be added at any stage up to and including stage 4 — but see the
hard constraint there.

**Stage 0 — today (Exposed 0.5x + Ktor 2.3.12, current `Database.kt`, permit, no pool).**
Valid state: the permit caps concurrent connections at 30, so the DB is protected even without
a pool. Conditions while pool-less: (a) the helper-only ban is load-bearing — one plain
`newSuspendedTransaction` bypasses the permit and opens an unbounded connection; (b) set
driver-level `connectTimeout`/`socketTimeout` in the JDBC URL — connections are opened *inside*
the permit, so during a DB outage, raw connects hanging on the OS TCP timeout would starve all
permits (Hikari's `connectionTimeout` normally caps this; the URL params are the substitute).
Accepted operational cost: per-transaction connect latency, DB-side connection churn (backend
forks, cold caches, auth/TLS per connect — watch DB CPU and connect-rate alerts), no pool
metrics.

**Stage 1 — Exposed 1.3.1.** Helper internals change only
(`withContext(dbDispatcher) { suspendTransaction(db = ...) }`, delete `supervisorScope`,
`withPermit` → companion `TransactionManager.currentOrNull()` + `.db == this`); repo-wide
mechanical imports and `Transaction` → `JdbcTransaction`. Callers keep their shape. The stale
window / permit-skip / silent-retry class is now gone. **Keep the permit, dispatcher, and the
helper-only ban** — the ban's reason shifts from "leak" to "connection bounding" while pool-less.

**Stage 2 — Ktor ≥ 3.4.3, prefer 3.5.x** (skip 3.2.x–3.3.x; Kotlin ≥ 2.2 for 3.5.x). Low-stakes
after stage 1 because the Exposed failure mode that made Ktor's dispatch behavior dangerous no
longer exists. If stepping gradually: `3.0.3 → 3.1.3 → 3.4.3 → 3.5.x` are all
behavior-identical to 2.3.12 for the DB layer (verified per version, both modes).

**Stage 3 — add HikariCP.** `maximumPoolSize` = permits = dbDispatcher threads. Restores
connection reuse, uniform `connectionTimeout`, keepalive/validation, and pool metrics. New
tuning duty: stale-idle-connection settings now exist (they don't in the pool-less stages).

**Stage 4 — virtual threads (Java 21+, prefer 24+), drop the permit.**
`Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()` as dbDispatcher — no pool
to size; blocked `getConnection()` parks a cheap virtual thread; the resume-starvation deadlock
is structurally impossible. Hikari's `maximumPoolSize` is the bound, `connectionTimeout` the
shedding (tune it down from 30s). `PermitController` may remain as a metrics/shedding hook but
is no longer load-bearing. **Hard constraint: never permit-less *and* pool-less** — this stage
requires stage 3. On Java 21 verify driver pinning (`-Djdk.tracePinnedThreads=full`); Java 24
(JEP 491) removes the concern. Do this after stage 1, not before: on 0.5x the helper's
`currentOrNull` branch also does same-tx joining, and the plain-API stale window still exists.

Version-independent rules that carry through every stage: transaction boundaries at the usecase
level, DTOs at the lambda edge, no cross-DB re-entry (A→B→A), suspending I/O outside
transactions, DB-side statement timeouts, close the dispatcher's executor on shutdown.

## The one-paragraph mechanism

Exposed 0.x binds the active transaction to the current *thread* while transaction code runs,
using a coroutine `ThreadContextElement`. Its cleanup is supposed to run before your code
continues after the transaction — but Exposed 0.x's `async`-based implementation lets the
caller resume **synchronously inside Exposed's completion code, before cleanup**. When that
happens, your handler runs in a *stale window*: the thread still claims to be inside an
already-committed, closed transaction. Whether the flaw is visible depends on how the server
resumes coroutines: an always-dispatching host hides it, an event-loop host (Netty) exposes it.

The stale window is **within one request only**. It never contaminates the next request or the
next task on a pool thread (cleanup is `finally`-based per execution slice; verified under
`ab -c 50` on a 4-thread pool across thousands of requests: zero cross-request effects).

Why it's dangerous anyway: inside the window, `transactionManager.currentOrNull()` returns the
dead transaction. Our `withPermit` then skips its permit, hands the closed transaction to
Exposed — and Exposed's built-in retry **silently** opens a new, permit-less transaction and
re-runs the statement body. Attempt #0's pre-SQL side effects run twice. Only a WARN log
(`Transaction attempt #0 failed: The object is already closed`) betrays it.

## Evidence matrix (real Exposed transactions, H2)

| | Ktor 2.3.12 prod/dev | Ktor 3.2.1 prod | Ktor 3.2.1 dev | Ktor 3.5.1 prod/dev |
|---|---|---|---|---|
| Our helper (dedicated dispatcher) | ✅ | ✅ | ❌ leak | ✅ |
| Plain `newSuspendedTransaction`, Exposed 0.56/0.61 | ❌ stale window | ✅ (by accident) | ⚠️ runs on timer thread | ❌ stale window |
| *Anything*, Exposed 1.1.1 / 1.3.1 | ✅ | — | — | — |

- 3.2.1 prod is only clean because that version happened to run handlers on
  `DefaultDispatcher` (always dispatches). 3.5.1 went back to the Netty event loop — code that
  "worked" on 3.2.x prod with the plain API breaks again on 3.5.x. Don't depend on it.
- Ktor 3.2.1 dev mode drops the dispatcher from the call context entirely — the only
  configuration where even our helper leaked. Fixed in 3.5.1 (dev == prod there).
- Exposed 1.x was clean in **every** combination, including the deprecated
  `newSuspendedTransaction` shim and the no-dispatcher `suspendTransaction`, incl. under load.

## FAQ

**Q: Whose bug is it — Exposed or Ktor?**
Mostly Exposed (0.x's resume-ordering flaw, rooted in kotlinx undispatched-resume semantics).
Ktor's dispatch choices only decide whether you *see* it. Ktor's one genuinely own bug was
3.2.x dev mode losing the dispatcher (fixed in 3.5.1). Proof: swapping Exposed 0.56→1.3.1 on
unchanged Ktor 2.3.12 removed every symptom.

**Q: Can transactions leak into our `Executors.newFixedThreadPool` dbDispatcher and hit the
next request that reuses a thread?**
No, in no circumstance. The binding is per *execution slice*: bound when your transaction code
starts running on a thread, unbound in a `finally` when it suspends or completes — before the
thread returns to the pool. Suspension hand-offs, completions, exceptions, cancellation all go
through the same path. Empirically: `startLeak=false` on every request across all versions,
modes, and load tests.

**Q: Is our helper just reimplementing what Exposed 1.x does?**
Functionally yes — same safety property ("handler never runs on a thread carrying a dead
transaction"), achieved from the outside by forcing a dispatched resume. But 1.x is strictly
stronger: it protects *every* call site (helper users and forgotten plain calls alike) and
doesn't depend on the host having a dispatcher (the helper's one assumption, violated by Ktor
3.2.x dev). Keep the helpers after upgrading anyway — permits, load shedding, and keeping SQL
off the event loop are *not* provided by Exposed 1.x.

**Q: Nested helper calls — same transaction? double permit? deadlock?**
Calling the helper inside the helper's transaction joins the same transaction and takes only
one permit (the `withPermit` nested check). On Exposed 0.x the nested call reuses the same
`Transaction` object; on 1.x it creates a thin wrapper joined to the outer — with default
`useNestedTransactions = false` the wrapper never commits on its own (`shouldCommit = outer.db.useNestedTransactions`),
so it's still one atomic unit. On 1.x the detection is also immune to being fooled by stale
windows. What remains forbidden at design level: cross-DB re-entry (A→B→A) — DB-level
self-deadlock risk and two permits, on any version.

**Q: Should we adopt the 1.x style (`withContext` wrapper) on 0.5x now?**
No. 1.x recommends `withContext` only because `suspendTransaction` dropped the context
parameter — the *safety* comes from Exposed's internal rewrite, not the wrapper. Wrapping 0.x
in `withContext` fixes nothing (the flawed `async` still runs underneath; traced and confirmed
it still leaks on 3.2.1 dev) and adds two context switches. Keep
`newSuspendedTransaction(dbDispatcher, db)` until the 1.x migration.

**Q: Why prefer the blocking `transactionOnDbDispatcher`?**
Blocking `transaction {}` uses plain ThreadLocal set/remove synchronously on one thread with no
suspension possible in between — there is nothing for broken resume-ordering to corrupt. Also:
holding a connection across a suspension (the suspended variant's use case) is usually a design
smell — do suspending I/O outside the transaction.

**Q: What are the permit/semaphore and the dedicated dispatcher actually for?**
Suspension frees threads, so thread-pool size does **not** bound concurrent connections — a
suspended transaction holds its connection while its thread serves new requests. The semaphore
is the only real bound (size it = pool size = HikariCP `maximumPoolSize`, one `PermitController`
per `Database`). The 30s acquire timeout is load shedding: waiters are cancelled and rejected,
and a cancelled `Semaphore.acquire` cannot leak a permit. The dedicated dispatcher keeps
blocking JDBC off the Netty event loop *and* (on 0.x) forces the dispatched resume that
prevents the stale window.

**Q: Without the permit — isn't HikariCP alone enough?**
No pool at all → **unbounded**: the suspended variant holds its connection across suspensions
while freed threads accept more requests, each opening a new connection, until the DB's
`max_connections`. Hikari alone → the connection *count* is capped (that's its job), but the
overflow turns into `getConnection()` **blocking dbDispatcher threads** (up to Hikari's 30s
`connectionTimeout`), an unbounded suspended queue upstream — and a **resume-starvation
deadlock**: suspended transactions that *hold* connections need a dbDispatcher thread to
resume on and release them; if all (few) dispatcher threads are blocked in `getConnection()`
waiting for those very connections, the DB layer freezes until `connectionTimeout` fails the
waiters, then the cycle repeats — 30-second sawtooth stalls under sustained load. The permit
rule **permits = `maximumPoolSize`** makes `getConnection()` mathematically never block
(connections are returned before permits are released), so the deadlock cannot form. On plain
JDBC + platform threads, Hikari and the permit are complements, not alternatives.

**Q: When does the permit become unnecessary?**
Two stacks dissolve it: **(a) JDBC + virtual threads** (Java 21+): use
`Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()` as the dbDispatcher —
blocked `getConnection()` parks a cheap virtual thread, and the starvation deadlock is
structurally impossible because the dispatcher has no finite pool for waiters to occupy;
Hikari's `maximumPoolSize` remains the connection bound and `connectionTimeout` becomes the
shedding mechanism. Caveat: on Java 21, `synchronized` in the JDBC driver *pins* carrier
threads (check with `-Djdk.tracePinnedThreads=full`; older MySQL Connector/J is the classic
offender); Java 24 (JEP 491) removes synchronized pinning entirely. **(b) R2DBC** (requires
Exposed 1.x `exposed-r2dbc`): DB I/O itself suspends and `r2dbc-pool`'s acquire is a
non-blocking suspend with `maxSize` / `acquireTimeout` / `maxPendingAcquireSize` — the pool
natively provides everything `PermitController` hand-builds. Note the payoff of either is
bounded by pool size (30 parked platform threads → 0), unlike HTTP clients; and the connection
*bound itself* never goes away — it just stops being our code. The dedicated-dispatcher jobs
(SQL off the event loop; dispatched resume on 0.x) are still covered by the virtual-thread
dispatcher in option (a).

**Q: Does the stale window ever cross requests?**
Never observed, by design: cleanup always runs by the next real suspension (e.g. writing the
response). It survives *non-dispatching* constructs though — plain suspend calls and
`supervisorScope` don't end it. It's a within-request hazard: "plain transaction, then more
transaction-aware code before the next suspension" is the poison pattern.

**Q: Why is there `supervisorScope` in the 0.x helper?**
Workaround for [Exposed#1075](https://github.com/JetBrains/Exposed/issues/1075): a failed
`newSuspendedTransaction` cancels the caller's scope even when you catch the exception.
Fixed in 1.0.0-rc-1 — delete the `supervisorScope` during the 1.x migration.

**Q: Can we trust dev mode to reflect production behavior?**
On 2.3.12 and ≥3.5.1: yes (verified identical). On 3.0–3.4: no — dev mode used a different
pipeline (`DebugPipelineContext`, never `SuspendFunctionGun` — so `io.ktor.internal.disable.sfg`
is irrelevant there) *and* lost the call dispatcher. Another reason to skip that range.

**Q: DAO module?**
We deliberately use core+jdbc only. DAO entities lazy-load on property access — an entity
escaping the transaction lambda throws (or worse, races) at access time. Rule: map to DTOs
inside the transaction lambda; `Query`/`SizedIterable`/entities never escape.

**Q: How do we verify after any upgrade (Ktor or Exposed)?**
Run this repo's routes in both modes: `/tx-probe` and `/tx-probe-helper` must print
`s1=null s2=null s3=null s4=null`; `/tx-mixed` must not produce
`Transaction attempt #0 failed` in the log; add `ab -n 500 -c 50` for confidence. Five minutes,
catches the whole bug class.
