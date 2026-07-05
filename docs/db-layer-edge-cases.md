# DB Layer Edge Cases — Plain Version

Every known way our DB layer (helpers + permit + dispatcher) can stall or misbehave, each with
a one-line verdict. **Spoiler: almost everything here is the same class of problem as classic
blocking Spring MVC + HikariCP** — too many requests waiting on a fixed pool — and is handled
the same way: tune sizes, fix slow queries, set timeouts. The helper does not add exotic risks.
Only two items are genuinely new (marked 🆕).

## The one sizing rule

> **permits ≤ dbDispatcher threads** (simplest: equal). Later with Hikari:
> **permits = threads = `maximumPoolSize`** — one number everywhere.

Why: a blocking transaction occupies a thread for its whole life. If permits > threads, a
transaction can hold a permit while *waiting for a thread* — invisible waiting that your 30s
permit timeout doesn't cover. With permits ≤ threads, holding a permit guarantees a thread.
(Second database later? Give it its own dispatcher + its own PermitController.)

---

## 1. Waiting on other DB work while inside a transaction

Not about multiple databases — one DB, one PermitController. The trap is about **who** runs the
DB work. Transaction context travels down your own call stack, **not sideways to other
coroutines**:

```kotlin
// ✅ SAFE — nested call in the SAME coroutine: joins your tx, no second permit
db.suspendedTransactionOnDbDispatcher(permits, dispatcher) {
    val user = findUser(id)
    val stats = statsService.loadStats(user)  // internally uses the helper → joins
    save(user, stats)
}

// ❌ TRAP — DB work in a DIFFERENT coroutine while you hold a permit
val reportDeferred = appScope.async {                    // independent coroutine
    db.suspendedTransactionOnDbDispatcher(permits, dispatcher) {
        runHeavyAggregation()                            // needs its OWN permit
    }
}
db.suspendedTransactionOnDbDispatcher(permits, dispatcher) {  // you hold a permit
    val report = reportDeferred.await()                  // wait for the other coroutine
    saveUserReport(findUser(id), report)
}
```

Walk it with permits = 1: you take the only permit → you `await()` → the async needs the permit
you're holding → it waits for you, you wait for it → frozen 30s → both fail. With 30 permits
the same happens under load: 30 requests each hold a permit awaiting a worker that needs one.
(A→B→A across two databases is just the two-DB flavor of the same hold-and-wait.)

**Spring equivalent: yes** — request thread holds a Hikari connection, blocks on
`future.get()` for an executor task whose DAO needs a connection; classic pool deadlock
(Hikari's docs give the sizing formula for it).

**Verdict: avoidable pattern, not a config problem.** Rule: *inside a transaction, only do
that transaction's work.* Either `await()` **before** opening the transaction, or call the
code **directly** (nested same-coroutine call is safe — it joins). Coroutines make this easier
to type accidentally than Spring did, because `await()` doesn't look like blocking — but while
holding a permit, it is.

## 2. Slow queries / too many requests → permit waiting

30 concurrent transactions is the ceiling; request #31 waits, and after 30s is rejected.

**Spring equivalent: yes, exactly** — `getConnection()` waiting on an exhausted pool.

**Verdict: not a bug — capacity behavior working as designed.** Same playbook as always:
fix slow queries, tune pool/permit size, keep transactions short. The only improvement over
Spring is that our waiters suspend cheaply and shed cleanly instead of blocking threads.

## 3. Client disconnects don't stop running queries

A disconnect cancels the coroutine, but a blocking JDBC query keeps its thread + permit until
the query finishes. A disconnect/retry storm on a slow endpoint = orphaned queries hogging the
whole DB layer.

**Spring equivalent: yes, identical** — a servlet thread blocked on JDBC doesn't notice the
client left either.

**Verdict: set a DB-side statement timeout.** Same standard fix as Spring. One extra wrinkle to
know: a request cancelled *after* commit still committed — never assume "client saw an error,
so nothing was written."

## 4. 🆕 Exposed auto-retries failed statements (0.x and 1.x)

On `SQLException`, Exposed re-runs your whole statement lambda — default up to 3 attempts,
silently (WARN log only). Anything non-idempotent in the lambda (counters, log lines, events)
executes multiple times. Even a plain constraint violation is retried pointlessly.

**Spring equivalent: no** — Spring never retries `@Transactional` on its own.

**Verdict: set `maxAttempts = 1`** in the Database config (or per transaction); opt into
retries deliberately, with idempotent statements, where you actually want them.

## 5. 🆕 Plain `newSuspendedTransaction` anywhere poisons the helpers (Exposed 0.x only)

One call without a dispatcher, anywhere in the codebase, creates the stale window that fools
`withPermit` in the *same request* — permit skipped, closed transaction reused, silent retry.

**Spring equivalent: no** — this is the coroutine/Exposed-0.x-specific one.

**Verdict: the helper-only ban is a hard rule until Exposed 1.x** (where this whole item
disappears). Consider a detekt `ForbiddenMethodCall` rule.

## 6. Launching fire-and-forget work inside a transaction

```kotlin
db.transactionOnDbDispatcher(permits, dispatcher) {
    insertOrder(order)
    appScope.launch { updateSearchIndex(order) }   // BAD if it touches this tx / Exposed
}
```

The launched coroutine may run after the transaction closed → "connection closed" errors, or
writes landing outside the transaction.

**Spring equivalent: yes, same family** — `@Async` touching lazy entities after
`@Transactional` returned.

**Verdict: nothing async escapes the statement lambda** (companion to the "return DTOs" rule).
Kick off follow-up work *after* the helper returns.

## 6b. No coroutine builders inside a transaction lambda

The classic rule — **never share one transaction/connection across threads** — applied to
coroutines. Where a coroutine gets launched from decides what it inherits:

| Coroutine origin | Tx context inherited? | Result |
|---|---|---|
| Plain nested call, same coroutine | yes | ✅ joins tx, one permit — the supported case |
| `appScope.launch` (external scope) | no | new permit + new tx (fine if not awaited inside a tx; mind item 6) |
| `coroutineScope { launch }` *inside* the statement | yes | ❌ two coroutines share one connection concurrently |

The last row is the trap: it doesn't take a new permit — it **joins the same transaction in
parallel**, i.e. concurrent use of a single JDBC connection (corrupted statements, protocol
errors). "Parallelize inserts with async inside the tx" is broken on principle, not just slow.

**Spring equivalent: yes** — same as handing a `Connection` to two threads; everyone already
knows not to. Coroutines just make the mistake look innocent because `launch` doesn't look
like a thread.

**Verdict — one review rule covers all rows: inside a transaction lambda, no `launch`/`async`
at all.** Sequential calls only; anything parallel or fire-and-forget happens before or after
the helper.

## 7. Housekeeping (same as any JDBC service)

- **Until Hikari is added:** set `connectTimeout`/`socketTimeout` in the JDBC URL — during a DB
  outage, hanging connects happen *inside* the permit and would hold all 30 for the OS TCP
  timeout. (Hikari's `connectionTimeout` covers this later.)
- **Shutdown order:** stop accepting traffic → drain in-flight transactions → then close the
  dispatcher's executor. Wrong order = failed resumes mid-transaction.

**Spring equivalent: yes** to both. **Verdict: config, not code.**

---

## Summary table

| # | Issue | Same as Spring+Hikari? | What to do |
|---|---|---|---|
| 1 | Await DB-needing work inside a tx | ✅ (pool deadlock) | Avoid the pattern: tx does only its own work |
| 2 | Permit waiting under load | ✅ (pool waiting) | Normal tuning: sizes, slow queries |
| 3 | Disconnects don't stop queries | ✅ | DB statement timeout |
| 4 | 🆕 Exposed auto-retry | ❌ | `maxAttempts = 1`, opt-in retries |
| 5 | 🆕 Plain API poisons helpers | ❌ (0.x only) | Helper-only ban until Exposed 1.x |
| 6 | Fire-and-forget inside tx | ✅ (@Async + lazy entities) | Nothing async escapes the lambda |
| 6b | launch/async inside tx lambda | ✅ (connection shared across threads) | No coroutine builders inside a tx — sequential only |
| 7 | Connect timeouts / shutdown order | ✅ | Config |

Sizing rule on top of all of it: **permits ≤ dispatcher threads (= Hikari maxPoolSize later)**.
If you already run blocking services against Hikari, you already know how to operate this —
items 4 and 5 are the only new homework, and item 5 expires at Exposed 1.x.
