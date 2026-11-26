package com.lockwallet.app.policy

import org.json.JSONArray
import org.json.JSONObject

/**
 * Optional send protections (Ajustes > Segurança). Pure logic over a small key-value [Store]; every time is the
 * network clock (finalized block time, see NetworkManager.networkTimeMs) passed in by the caller, never the device
 * clock, so moving the phone's date does not shorten a cooldown.
 *
 * Rules, in one place:
 *  - Tightening is immediate: turning a protection on, lowering the limit, adding a contact.
 *  - Loosening waits [COOLDOWN_MS] after a PIN-authorized request: turning allowlist mode off, raising or removing
 *    the daily limit. A pending change can be cancelled at any time.
 *  - With allowlist mode on, a contact added less than [COOLDOWN_MS] ago is not yet usable.
 *  - The daily limit is a rolling 24 h window over SOL that leaves the wallet (SOL sends, confidential transfers
 *    and withdrawals to another address). Staking moves SOL to the user's own stake accounts and is not counted.
 *
 * Limits (documented, not hidden): the store is app-private preferences. Someone with root on the unlocked device
 * can edit it; these protections are about an unlocked phone in the wrong hands and about mistakes, not root.
 */
class SendPolicy(private val store: Store) {
    interface Store {
        fun get(key: String): String?
        fun put(key: String, value: String?)
    }

    data class Contact(val address: String, val name: String, val addedAtMs: Long)
    data class Pending(val kind: Kind, val value: Long, val effectiveAtMs: Long)
    enum class Kind { ALLOWLIST_OFF, LIMIT_CHANGE }

    sealed interface Verdict {
        data object Allowed : Verdict
        data class Blocked(val reason: String) : Verdict
    }

    // ---------------------------------------------------------------- address book

    fun contacts(): List<Contact> = runCatching {
        val a = JSONArray(store.get(K_BOOK) ?: "[]")
        (0 until a.length()).map { i -> a.getJSONObject(i).let { Contact(it.getString("a"), it.getString("n"), it.getLong("t")) } }
    }.getOrDefault(emptyList())

    fun contact(address: String): Contact? = contacts().firstOrNull { it.address == address }

    fun addContact(address: String, name: String, nowMs: Long): Contact {
        val clean = name.trim().take(MAX_NAME)
        require(clean.isNotEmpty()) { "Informe um nome para o contato." }
        val list = contacts()
        require(list.none { it.address == address }) { "Este endereço já está nos contatos." }
        require(list.size < MAX_CONTACTS) { "Limite de $MAX_CONTACTS contatos atingido." }
        val c = Contact(address, clean, nowMs)
        save(list + c)
        return c
    }

    fun renameContact(address: String, name: String) {
        val clean = name.trim().take(MAX_NAME)
        require(clean.isNotEmpty()) { "Informe um nome para o contato." }
        save(contacts().map { if (it.address == address) it.copy(name = clean) else it })
    }

    /** Removing a contact only tightens the policy, so it needs no cooldown. */
    fun removeContact(address: String) = save(contacts().filterNot { it.address == address })

    private fun save(list: List<Contact>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("a", it.address).put("n", it.name).put("t", it.addedAtMs)) }
        store.put(K_BOOK, a.toString())
    }

    // ---------------------------------------------------------------- settings

    /** Any stored value counts as on (only "off" removes the key): a damaged value never silently disables it. */
    val allowlistOnly: Boolean get() = store.get(K_ALLOW) != null
    /**
     * Daily limit in lamports; 0 = off (key absent). A damaged or non-positive stored value fails closed to 1 lamport
     * (sending SOL is blocked until a new limit is requested; loosening it then goes through the usual cooldown).
     */
    val dailyLimitLamports: Long get() {
        val raw = store.get(K_LIMIT) ?: return 0L
        return raw.toLongOrNull()?.takeIf { it > 0L } ?: 1L
    }
    /** Type the last characters of an address the wallet has never sent to before signing. On by default. */
    var confirmNewAddress: Boolean
        get() = store.get(K_CONFIRM_NEW) != "0"
        set(v) { store.put(K_CONFIRM_NEW, if (v) "1" else "0") }

    fun pending(): Pending? = runCatching {
        val o = JSONObject(store.get(K_PENDING) ?: return null)
        Pending(Kind.valueOf(o.getString("k")), o.getLong("v"), o.getLong("t"))
    }.getOrNull()

    fun enableAllowlist() {
        store.put(K_ALLOW, "1")
        if (pending()?.kind == Kind.ALLOWLIST_OFF) store.put(K_PENDING, null)
    }

    /** Call only after a PIN authorization. Returns when the change takes effect. */
    fun requestAllowlistOff(nowMs: Long): Long {
        check(allowlistOnly)
        return schedule(Kind.ALLOWLIST_OFF, 0L, nowMs)
    }

    /**
     * New daily limit (0 = off). Lowering (or enabling when off) applies now; raising or removing needs a prior
     * PIN authorization and applies after the cooldown. Returns the effective time.
     */
    fun requestLimit(newLimit: Long, nowMs: Long): Long {
        require(newLimit >= 0L)
        val cur = dailyLimitLamports
        val tighter = newLimit > 0L && (cur == 0L || newLimit < cur)
        if (tighter) {
            store.put(K_LIMIT, newLimit.toString())
            if (pending()?.kind == Kind.LIMIT_CHANGE) store.put(K_PENDING, null)
            return nowMs
        }
        if (newLimit == cur) return nowMs
        return schedule(Kind.LIMIT_CHANGE, newLimit, nowMs)
    }

    /** True when the request needs a PIN first (anything that loosens the policy). */
    fun limitChangeLoosens(newLimit: Long): Boolean {
        val cur = dailyLimitLamports
        return !(newLimit > 0L && (cur == 0L || newLimit <= cur))
    }

    fun cancelPending() = store.put(K_PENDING, null)

    private fun schedule(kind: Kind, value: Long, nowMs: Long): Long {
        val at = nowMs + COOLDOWN_MS
        store.put(K_PENDING, JSONObject().put("k", kind.name).put("v", value).put("t", at).toString())
        return at
    }

    /** Applies a matured pending change. Call with the network time before every policy decision. */
    fun settle(nowMs: Long) {
        val p = pending() ?: return
        if (nowMs < p.effectiveAtMs) return
        when (p.kind) {
            Kind.ALLOWLIST_OFF -> store.put(K_ALLOW, null)
            Kind.LIMIT_CHANGE -> store.put(K_LIMIT, p.value.takeIf { it > 0L }?.toString())
        }
        store.put(K_PENDING, null)
    }

    // ---------------------------------------------------------------- spending window

    /** Recorded spends; a damaged log is replaced by one "window full" entry at [nowMs] (fails closed for 24 h). */
    private fun spends(nowMs: Long): List<Pair<Long, Long>> {
        val raw = store.get(K_SPENT) ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { i -> a.getJSONArray(i).let { it.getLong(0) to it.getLong(1) } }
        }.getOrElse {
            listOf(nowMs to CORRUPT_SPEND).also { write(it) }
        }
    }

    private fun write(list: List<Pair<Long, Long>>) {
        val a = JSONArray()
        list.forEach { a.put(JSONArray().put(it.first).put(it.second)) }
        store.put(K_SPENT, a.toString())
    }

    /** Saturating sum: no amount combination can wrap around and reopen the window. */
    fun spentInWindow(nowMs: Long): Long =
        spends(nowMs).filter { it.first > nowMs - WINDOW_MS && it.first <= nowMs + CLOCK_SKEW_MS }
            .fold(0L) { acc, e -> val v = e.second.coerceAtLeast(0L); if (acc > Long.MAX_VALUE - v) Long.MAX_VALUE else acc + v }

    /** Remaining allowance; null when no limit is set. */
    fun remaining(nowMs: Long): Long? = dailyLimitLamports.takeIf { it > 0L }?.let { (it - spentInWindow(nowMs)).coerceAtLeast(0L) }

    fun recordSpend(lamports: Long, nowMs: Long) {
        if (lamports <= 0L) return
        var all = spends(nowMs).filter { it.first > nowMs - WINDOW_MS } + (nowMs to lamports)
        // Bounded log without forgetting money: the oldest entries are merged (summed, stamped with the newest merged
        // time, so they stay in the window at least as long). Dropping them would let many tiny sends erase a large one.
        if (all.size > MAX_SPENDS) {
            val sorted = all.sortedBy { it.first }
            val merge = sorted.take(sorted.size - MAX_SPENDS + 1)
            val sum = merge.fold(0L) { acc, e -> val v = e.second.coerceAtLeast(0L); if (acc > Long.MAX_VALUE - v) Long.MAX_VALUE else acc + v }
            all = listOf(merge.last().first to sum) + sorted.drop(merge.size)
        }
        write(all)
    }

    // ---------------------------------------------------------------- decision

    /**
     * Checks a send of [lamports] SOL (0 for tokens) to [destination]. [ownAddresses] are the user's own wallets,
     * always allowed in allowlist mode.
     */
    fun check(destination: String, lamports: Long, nowMs: Long, ownAddresses: Set<String>): Verdict {
        settle(nowMs)
        if (allowlistOnly && destination !in ownAddresses) {
            val c = contact(destination) ?: return Verdict.Blocked(
                "Modo somente contatos ativo: adicione este endereço aos contatos. Ele poderá receber 24 h depois de adicionado.")
            if (nowMs < c.addedAtMs + COOLDOWN_MS) return Verdict.Blocked(
                "Contato adicionado há menos de 24 h. Com o modo somente contatos ativo, ele fica disponível 24 h após ser adicionado.")
        }
        val limit = dailyLimitLamports
        if (limit > 0L && lamports > 0L) {
            val left = (limit - spentInWindow(nowMs)).coerceAtLeast(0L)
            if (lamports > left) return Verdict.Blocked(LIMIT_PREFIX + left)
        }
        return Verdict.Allowed
    }

    companion object {
        const val COOLDOWN_MS = 24L * 60 * 60 * 1000
        const val WINDOW_MS = 24L * 60 * 60 * 1000
        /** Records "in the future" beyond this are ignored, so a past clock glitch cannot pin the window shut. */
        const val CLOCK_SKEW_MS = 10L * 60 * 1000
        const val MAX_CONTACTS = 200
        const val MAX_NAME = 32
        const val MAX_SPENDS = 200
        /** Placeholder spend for a damaged log: larger than any limit, so the window counts as full. */
        const val CORRUPT_SPEND = Long.MAX_VALUE / 4
        /** Blocked limit reason starts with this, followed by the remaining lamports (formatted by the UI). */
        const val LIMIT_PREFIX = "limit:"
        private const val K_BOOK = "sp.book"
        private const val K_ALLOW = "sp.allow"
        private const val K_LIMIT = "sp.limit"
        private const val K_CONFIRM_NEW = "sp.confirmNew"
        private const val K_PENDING = "sp.pending"
        private const val K_SPENT = "sp.spent"
    }
}
