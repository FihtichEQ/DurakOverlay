package com.example.durakoverlay

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

data class Card(val rank: Int, val suit: Int) {
    override fun toString() = rankName(rank) + SUITS[suit]

    companion object {
        val SUITS = arrayOf("♠", "♥", "♦", "♣")
        fun rankName(r: Int) = when (r) {
            11 -> "В"; 12 -> "Д"; 13 -> "К"; 14 -> "Т"; else -> r.toString()
        }
    }
}

data class GameState(
    val trump: Card? = null,
    val trumpSuit: Int? = null,
    val table: List<Card> = emptyList(),
    val discard: Set<Card> = emptySet(),
    val seen: Set<Card> = emptySet(),
    val myHand: List<Card> = emptyList(),
    val oppCount: Int = -1,
    val deckLeft: Int = -1,
    val status: String = "—",
    val msgCount: Int = 0
)

object GameStore {
    @Volatile
    var state = GameState()
        private set
    private val listeners = CopyOnWriteArrayList<(GameState) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(l: (GameState) -> Unit) {
        listeners.add(l); l(state)
    }

    fun removeListener(l: (GameState) -> Unit) {
        listeners.remove(l)
    }

    fun publish(s: GameState) {
        state = s
        main.post { listeners.forEach { it(s) } }
    }
}

/**
 * ВАЖНО: имена ключей и кодировка карт ниже — эвристика.
 * Реальный протокол rstgames смотрите в logcat (тег DurakRaw) и поправьте наборы ключей.
 */
class GameParser(private val deckSize: () -> Int) {

    private class Acc {
        var trump: Card? = null
        var trumpSuit: Int? = null
        var table: List<Card>? = null
        val discard = mutableSetOf<Card>()
        var hand: List<Card>? = null
        var opp: Int? = null
        var deck: Int? = null
        var status: String? = null
        var reset = false
    }

    private val trumpKeys = setOf("trump", "trumpcard", "trump_card", "kozyr", "koz")
    private val tableKeys = setOf("table", "field", "board", "tablecards", "cards_on_table")
    private val discardKeys = setOf("discard", "discarded", "beat", "bita", "out", "trash", "dropped")
    private val deckKeys = setOf("deck", "talon", "stock", "deckcount", "deck_count", "cards_left", "remaining")
    private val oppKeys = setOf("opponent", "opp", "oppcount", "opp_cards", "enemy", "enemycards")
    private val handKeys = setOf("hand", "mycards", "my_cards", "cards")
    private val typeKeys = setOf("type", "cmd", "action", "event", "c")
    private val statusKeys = setOf("status", "state", "phase", "stage")
    private val resetRegex = Regex("(game_?start|new_?game|deal|start)", RegexOption.IGNORE_CASE)
    private val cardRegex = Regex("^\\s*(1[0-4]|[2-9]|[JQKA])\\s*[-_]?\\s*([SHDC♠♥♦♣])\\s*$", RegexOption.IGNORE_CASE)

    @Synchronized
    fun process(dir: String, raw: String) {
        if (dir != "in") return
        Log.d("DurakRaw", raw.take(500))
        val start = raw.indexOfFirst { it == '{' || it == '[' }
        if (start < 0) return
        val body = raw.substring(start)
        val root: Any = try {
            if (body.startsWith("{")) JSONObject(body) else JSONArray(body)
        } catch (e: Exception) {
            return
        }

        val acc = Acc()
        walk(root, acc)

        val size = deckSize()
        val old = GameStore.state
        val base = if (acc.reset) GameState() else old
        val table = acc.table ?: base.table
        val discard = base.discard + acc.discard
        val seen = base.seen + table + acc.discard
        val hand = acc.hand ?: base.myHand
        val opp = acc.opp ?: base.oppCount
        val unknown = size - (seen + hand).size
        val deckLeft = acc.deck ?: if (opp >= 0) maxOf(0, unknown - opp) else -1

        GameStore.publish(
            base.copy(
                trump = acc.trump ?: base.trump,
                trumpSuit = acc.trumpSuit ?: acc.trump?.suit ?: base.trumpSuit,
                table = table,
                discard = discard,
                seen = seen,
                myHand = hand,
                oppCount = opp,
                deckLeft = deckLeft,
                status = acc.status ?: base.status,
                msgCount = old.msgCount + 1
            )
        )
    }

    private fun walk(node: Any?, acc: Acc) {
        when (node) {
            is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), acc)
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val key = k.lowercase()
                    val v = node.opt(k)
                    when {
                        key in trumpKeys -> {
                            val c = parseCard(v)
                            if (c != null) acc.trump = c else parseSuit(v)?.let { acc.trumpSuit = it }
                        }
                        key in tableKeys -> acc.table = collectCards(v)
                        key in discardKeys -> acc.discard.addAll(collectCards(v))
                        key in deckKeys -> countOf(v)?.let { acc.deck = it }
                        key in oppKeys -> countOf(v)?.let { acc.opp = it }
                        key in handKeys -> {
                            val cards = collectCards(v)
                            if (cards.isNotEmpty()) acc.hand = cards
                        }
                        key in typeKeys && v is String -> {
                            if (resetRegex.containsMatchIn(v)) acc.reset = true
                            acc.status = v
                        }
                        key in statusKeys && v is String -> acc.status = v
                        else -> walk(v, acc)
                    }
                }
            }
        }
    }

    private fun collectCards(node: Any?): List<Card> {
        val out = mutableListOf<Card>()
        fun rec(n: Any?) {
            parseCard(n)?.let { out.add(it); return }
            when (n) {
                is JSONArray -> for (i in 0 until n.length()) rec(n.opt(i))
                is JSONObject -> n.keys().forEach { rec(n.opt(it)) }
            }
        }
        rec(node)
        return out
    }

    private fun countOf(v: Any?): Int? = when (v) {
        is Int -> v
        is Long -> v.toInt()
        is JSONArray -> v.length()
        is JSONObject -> listOf("count", "n", "size", "cards", "hand")
            .firstNotNullOfOrNull { k -> if (v.has(k)) countOf(v.opt(k)) else null }
        else -> null
    }

    private fun parseSuit(v: Any?): Int? = when (v) {
        is Int -> if (v in 0..3) v else null
        is String -> suitIndex(v.trim())
        else -> null
    }

    private fun suitIndex(s: String): Int? = when (s.uppercase()) {
        "S", "♠" -> 0
        "H", "♥" -> 1
        "D", "♦" -> 2
        "C", "♣" -> 3
        else -> null
    }

    private fun rankFromString(s: String): Int? = when (s.uppercase()) {
        "J" -> 11; "Q" -> 12; "K" -> 13; "A" -> 14
        else -> s.toIntOrNull()?.takeIf { it in 2..14 }
    }

    fun parseCard(v: Any?): Card? {
        when (v) {
            is Int -> return fromId(v)
            is Long -> return fromId(v.toInt())
            is String -> {
                val m = cardRegex.matchEntire(v) ?: return null
                val r = rankFromString(m.groupValues[1]) ?: return null
                val s = suitIndex(m.groupValues[2]) ?: return null
                return Card(r, s)
            }
            is JSONObject -> {
                val rv = listOf("rank", "r", "value", "v").firstNotNullOfOrNull { if (v.has(it)) v.opt(it) else null }
                val sv = listOf("suit", "s").firstNotNullOfOrNull { if (v.has(it)) v.opt(it) else null }
                if (rv == null || sv == null) return null
                val r = when (rv) {
                    is Int -> rv.takeIf { it in 2..14 }
                    is String -> rankFromString(rv)
                    else -> null
                } ?: return null
                val s = parseSuit(sv) ?: return null
                return Card(r, s)
            }
        }
        return null
    }

    /** Предположение: id = suit * ranksPerSuit + (rank - minRank). */
    private fun fromId(id: Int): Card? {
        val size = deckSize()
        if (id !in 0 until size) return null
        val rps = size / 4
        val minRank = 14 - rps + 1
        return Card(minRank + id % rps, id / rps)
    }
}
