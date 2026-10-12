package ru.scan.articles

import kotlin.math.sqrt

/** Строка, распознанная на кадре. cx, cy — центр строки в долях кадра (0..1). */
data class OcrLine(val text: String, val cx: Float, val cy: Float)

/** Вариант числа, который можно выбрать как артикул. */
data class Candidate(
    val value: String,
    val score: Int,
    val hasMarker: Boolean,   // рядом стоит «арт.»
    val isBarcode: Boolean    // похоже на штрихкод EAN (верная контрольная цифра)
)

/** Что приложение «выучило» по уже собранным артикулам. */
data class Learn(val expectedLen: Int = 10, val commonPrefix: String? = null) {
    companion object {
        fun from(saved: List<String>): Learn {
            if (saved.isEmpty()) return Learn()
            val len = saved.groupingBy { it.length }.eachCount().maxByOrNull { it.value }!!.key
            val prefix = if (saved.size >= 2)
                saved.filter { it.length >= 2 }.groupingBy { it.take(2) }.eachCount()
                    .maxByOrNull { it.value }?.key
            else null
            return Learn(len, prefix)
        }
    }
}

/**
 * Достаёт из распознанного текста все числа и ранжирует их по вероятности быть артикулом.
 * Логика проверена на присланных фото ценников (артикул оказывается первым в списке).
 */
object ArticleParser {
    private const val MIN_LEN = 4
    private val DATE = Regex("""\d{2}[/.\-]\d{2}[/.\-]\d{2}(\d{2})?""")
    private val DIGITS = Regex("""\d+""")
    private val SPACES = Regex("""\s+""")

    /** Буквы, которые OCR часто путает с цифрами. */
    private val LOOK = mapOf(
        'O' to '0', 'o' to '0', 'О' to '0', 'о' to '0',
        'I' to '1', 'l' to '1', '|' to '1', 'і' to '1',
        'З' to '3', 'з' to '3'
    )
    private val CYR2LAT = mapOf(
        'а' to 'a', 'р' to 'p', 'т' to 't', 'о' to 'o', 'с' to 'c',
        'е' to 'e', 'к' to 'k', 'х' to 'x', 'у' to 'y'
    )

    fun normalizeDigits(s: String): String {
        val ch = s.toCharArray()
        for (i in ch.indices) {
            val r = LOOK[s[i]] ?: continue
            val prev = if (i > 0) s[i - 1] else ' '
            val next = if (i < s.length - 1) s[i + 1] else ' '
            val digitNear = prev.isDigit() || next.isDigit()
            val letterNear = (prev.isLetter() && LOOK[prev] == null) ||
                    (next.isLetter() && LOOK[next] == null)
            if (digitNear && !letterNear) ch[i] = r
        }
        return String(ch)
    }

    private fun latin(s: String): String =
        s.lowercase().map { CYR2LAT[it] ?: it }.joinToString("")

    /** Стоит ли перед числом слово «арт.» (в любом написании, в т.ч. искажённом OCR). */
    private fun isMarker(prefix: String): Boolean {
        val tokens = latin(prefix).trimEnd().split(SPACES)
        val w = tokens.lastOrNull()?.trim('.', ':', '№', '#', ',', ' ') ?: return false
        if (w.isEmpty()) return false
        return w.startsWith("apt") || w.startsWith("art") || w == "ap" || w == "ot" || w == "at"
    }

    fun eanValid(v: String): Boolean {
        val d = v.map { it - '0' }
        val n = d.size
        var sum = 0
        for (i in 0 until n - 1) sum += d[i] * (if ((n - 1 - i) % 2 == 1) 3 else 1)
        return (10 - sum % 10) % 10 == d[n - 1]
    }

    private data class Found(val value: String, val start: Int, val text: String)
    private data class Scored(val value: String, val score: Int, val marker: Boolean, val barcode: Boolean)

    fun parse(lines: List<OcrLine>, learn: Learn): List<Candidate> {
        val result = HashMap<String, Candidate>()
        for (line in lines) {
            val n = normalizeDigits(line.text)
            // Вариант 1: дата целиком вырезается.
            val t1 = DATE.replace(n) { " ".repeat(it.value.length) }
            // Вариант 2: если дата «приклеилась» к артикулу, первая её цифра может принадлежать артикулу.
            val t2 = DATE.replace(n) { m ->
                val glued = m.range.first > 0 && n[m.range.first - 1].isDigit()
                if (glued) m.value.substring(0, 1) + " ".repeat(m.value.length - 1) else " ".repeat(m.value.length)
            }
            val found = ArrayList<Found>()
            for (t in setOf(t1, t2)) {
                val runs = DIGITS.findAll(t).toList()
                for (r in runs) if (r.value.length >= MIN_LEN) found.add(Found(r.value, r.range.first, t))
                for (i in 0 until runs.size - 1) {
                    val a = runs[i]
                    val b = runs[i + 1]
                    val gap = t.substring(a.range.last + 1, b.range.first)
                    if (gap == " " && a.value.length + b.value.length == learn.expectedLen)
                        found.add(Found(a.value + b.value, a.range.first, t))
                }
            }

            val dx = line.cx - 0.5f
            val dy = line.cy - 0.5f
            val dist = (sqrt(dx * dx + dy * dy) / 0.7071f).coerceIn(0f, 1f)
            val proximity = ((1f - dist) * 30f).toInt()

            // Для одной позиции в строке оставляем лучший вариант разбора
            val best = HashMap<Int, Scored>()
            for (f in found) {
                val v = f.value
                val marker = isMarker(f.text.substring(0, f.start))
                val barcode = (v.length == 13 || v.length == 8) && eanValid(v)
                var s = proximity
                if (marker) s += 100
                if (v.length == learn.expectedLen) s += 40 else if (v.length in 6..12) s += 10
                if (v.length <= 4) s -= 30
                if (learn.commonPrefix != null && v.startsWith(learn.commonPrefix)) s += 15
                if (barcode) s -= 50
                val old = best[f.start]
                if (old == null || old.score < s) best[f.start] = Scored(v, s, marker, barcode)
            }
            for (sc in best.values) {
                val old = result[sc.value]
                if (old == null || old.score < sc.score)
                    result[sc.value] = Candidate(sc.value, sc.score, sc.marker, sc.barcode)
            }
        }
        return result.values.sortedByDescending { it.score }.take(10)
    }
}
