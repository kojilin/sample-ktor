# Exposed Version Compatibility Report (on Ktor 2.3.12)

Tested 2026-07-05 on this repo. Environment: Ktor 2.3.12 (Netty), Kotlin 2.2.20, H2 in-memory,
`exposed-core` + `exposed-jdbc` only. Routes and the "stale window" definition are described in
[ktor-exposed-threadlocal-findings.md](ktor-exposed-threadlocal-findings.md).

Versions requested were 0.61.0 / 1.0.0 / 1.3.1. **There is no stable `1.0.0`** — the line went
`1.0.0-beta-1..5 → 1.0.0-rc-1..4 → 1.1.0 → 1.1.1 → 1.2.0 → 1.3.0 → 1.3.1`, so `1.1.1` was
tested in its place (`1.0.0-rc-4` was also prod-tested with identical clean results).

## Results (each tested in prod AND dev mode)

| Check | 0.56.0 (baseline) | 0.61.0 | 1.1.1 | 1.3.1 |
|---|---|---|---|---|
| Compiles without code changes | — | ✅ drop-in | ❌ v1 migration | ❌ v1 migration |
| `/tx-helper` (our helper) | ✅ clean | ✅ clean | ✅ clean | ✅ clean |
| `/tx-plain` (no dispatcher) | ❌ stale window | ❌ stale window | ✅ clean | ✅ clean |
| deprecated `newSuspendedTransaction` shim | n/a (is the plain API) | same as plain | ✅ clean | ✅ clean |
| `/tx-mixed` (permit skip + silent retry) | ❌ | ❌ | ✅ none | ✅ none |
| `/tx-probe` after plain tx | ❌ s1–s4 stale | ❌ s1–s4 stale | ✅ all null | ✅ all null |
| `ab -n 500 -c 50` on `/tx-plain`, `/tx-mixed` | — | — | — | ✅ 0 errors, 0 retries |

## Key finding

**The Exposed 1.x rewrite eliminates the stale-window class of bugs entirely**, even on Ktor
2.3.12 where 0.x leaks. Reasons visible in the source:

- Suspended transactions are implemented with `withContext` instead of `async`
  (PR [#2601](https://github.com/JetBrains/Exposed/pull/2601), which also fixed
  [#1075](https://github.com/JetBrains/Exposed/issues/1075) — a caught transaction failure no
  longer cancels the caller's scope, so our `supervisorScope` workaround can be deleted).
- The current transaction is carried in the **coroutine context** (per-manager
  `TransactionContextHolder` key) rather than only a ThreadLocal, and thread state is restored
  via kotlinx's `UndispatchedCoroutine` machinery, which handles undispatched resumes correctly
  (the kotlinx#2930 fix infrastructure).
- Even the deprecated `newSuspendedTransaction` compatibility shim delegates to the new
  mechanism, so *un-migrated* call sites stop leaking after the upgrade.

## Code changes required for 1.x (core + jdbc)

- Packages: `org.jetbrains.exposed.sql.*` → `org.jetbrains.exposed.v1.core.*` (DSL) and
  `org.jetbrains.exposed.v1.jdbc.*` (Database, transactions).
- `Transaction` receiver → `JdbcTransaction` in statement lambdas.
- `newSuspendedTransaction(context, db) {}` → `withContext(dispatcher) { suspendTransaction(db = db) {} }`
  — `suspendTransaction` has **no context parameter** by design; the migration guide says to use
  `withContext`. Nesting is handled natively (an inner `suspendTransaction` sees the outer via
  coroutine context and opens a nested transaction).
- `withSuspendTransaction` is deprecated and no longer needed.
- `Database.transactionManager.currentOrNull()` (per-db) → `TransactionManager.currentOrNull()`
  (companion; check `.db` yourself). `getCurrentContextTransaction()` is `internal`, so the
  permit check uses the thread-local read plus a db comparison.
- `supervisorScope` workaround for #1075: delete.

### Migrated `Database.kt` helper (tested)

```kotlin
private suspend fun <T> Database.withPermit(permitController: PermitController, block: suspend () -> T): T {
    val current = TransactionManager.currentOrNull()
    if (current != null && current.db == this) {
        return block()
    }
    return permitController.permit { block() }
}

suspend fun <T> Database.transactionOnDbDispatcher(
    permitController: PermitController,
    dbDispatcher: CoroutineDispatcher,
    statement: JdbcTransaction.() -> T
): T = withPermit(permitController) {
    withContext(dbDispatcher) {
        transaction(this@transactionOnDbDispatcher, statement = statement)
    }
}

suspend fun <T> Database.suspendedTransactionOnDbDispatcher(
    permitController: PermitController,
    dbDispatcher: CoroutineDispatcher,
    statement: suspend JdbcTransaction.() -> T
): T = withPermit(permitController) {
    withContext(dbDispatcher) {
        suspendTransaction(db = this@suspendedTransactionOnDbDispatcher, statement = statement)
    }
}
```

Imports: `org.jetbrains.exposed.v1.jdbc.{Database, JdbcTransaction}`,
`org.jetbrains.exposed.v1.jdbc.transactions.{TransactionManager, suspendTransaction, transaction}`.

## Recommendation

1. **Skip 0.61.0** — a drop-in upgrade but it fixes nothing relevant; same stale window as 0.56.0.
2. **Target Exposed 1.3.1.** The v1 migration is mechanical for core+jdbc (packages, receiver
   type, `suspendTransaction` + `withContext`) and removes the entire stale-window /
   permit-skip / silent-retry hazard even while production stays on Ktor 2.3.12. It decouples
   the Exposed risk from the Ktor upgrade decision.
3. **Keep the helpers after upgrading.** The leak is gone, but the helpers still provide the
   things Exposed doesn't: bounded concurrent connections (permit), acquire timeout/load
   shedding, and keeping SQL off the Netty event loop.
4. Do the Exposed upgrade *before* the Ktor 3.5.x upgrade — it removes the failure mode that
   made Ktor's dispatch behavior dangerous, so the Ktor upgrade becomes lower-stakes.

## Repo state

The working tree was restored to the 0.56.0 baseline after testing. The full 1.x-migrated
`Database.kt`/`Routing.kt` used for these tests are reproducible from the snippets above
(only imports and the two API renames differ from the 0.56.0 versions).
