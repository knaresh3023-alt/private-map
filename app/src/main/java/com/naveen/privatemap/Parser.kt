package com.naveen.privatemap

object Parser {
    // Porter: "Mogappair (11.7 km)" then address
    private val porterRe = Regex(
        "^(.+?)\\s*\\((\\d+(?:[.,]\\d+)?)\\s*km\\)\\s*(.*)$",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    // Rapido: "21.9 km" then "Area - address"
    private val kmRe = Regex("^(\\d+(?:[.,]\\d+)?)\\s*km$", RegexOption.IGNORE_CASE)
    private val dashRe = Regex("\\s[-\u2013]\\s")

    fun parse(lines: List<String>): Drop? {
        if (lines.none { it.contains("accept", true) }) return null
        if (lines.none { it.contains("\u20B9") }) return null
        return porter(lines) ?: rapido(lines)
    }

    private fun num(s: String): Double? = s.replace(',', '.').toDoubleOrNull()

    private fun isLabel(s: String) =
        s.equals("drop", true) || s.equals("pickup", true) || s.equals("pick up", true)

    private fun isOther(s: String) =
        s.contains("accept", true) || kmRe.matches(s) || porterRe.matches(s)

    private fun porter(lines: List<String>): Drop? {
        val idx = lines.indices.filter { porterRe.matches(lines[it]) }
        if (idx.size < 2) return null
        val m = porterRe.matchEntire(lines[idx[1]]) ?: return null
        val area = m.groupValues[1].trim()
        val km = num(m.groupValues[2])
        var addr = m.groupValues[3].trim()
        if (addr.isEmpty()) {
            for (j in (idx[1] + 1) until minOf(lines.size, idx[1] + 4)) {
                val l = lines[j]
                if (isLabel(l) || isOther(l)) continue
                if (l.length >= 6) { addr = l; break }
            }
        }
        val pk = porterRe.matchEntire(lines[idx[0]])?.groupValues?.get(2)?.let { num(it) }
        return Drop(area, addr, km, pk, "porter")
    }

    private fun rapido(lines: List<String>): Drop? {
        val idx = lines.indices.filter { kmRe.matches(lines[it]) }
        if (idx.size < 2) return null
        val i2 = idx[1]
        val km = num(kmRe.matchEntire(lines[i2])!!.groupValues[1])
        val pk = num(kmRe.matchEntire(lines[idx[0]])!!.groupValues[1])
        val rest = ArrayList<String>()
        for (j in (i2 + 1) until minOf(lines.size, i2 + 4)) {
            val l = lines[j]
            if (isLabel(l) || isOther(l)) break
            rest.add(l)
        }
        if (rest.isEmpty()) return null
        val l1 = rest[0]
        val mm = dashRe.find(l1)
        val area: String
        val addr: String
        if (mm != null) {
            area = l1.substring(0, mm.range.first).trim()
            addr = l1.substring(mm.range.last + 1).trim()
        } else {
            area = l1.trim()
            addr = (rest.getOrNull(1) ?: "").trim().trimStart('-', '\u2013', ' ')
        }
        return Drop(area, addr, km, pk, "rapido")
    }
}
