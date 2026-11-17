package cl.geoplano.geo

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * Lector mínimo de objetos PDF, suficiente para leer la georreferenciación
 * (diccionarios /VP y /Measure). No interpreta contenido gráfico.
 * Soporta objetos normales y "object streams" (PDF 1.5+) comprimidos con FlateDecode.
 * No soporta PDF cifrados.
 */
sealed class PdfValue
data class PdfNum(val v: Double) : PdfValue()
data class PdfName(val v: String) : PdfValue()
data class PdfStr(val v: String) : PdfValue()
data class PdfBool(val v: Boolean) : PdfValue()
object PdfNull : PdfValue()
data class PdfRef(val num: Int, val gen: Int) : PdfValue()
data class PdfArray(val items: List<PdfValue>) : PdfValue()
data class PdfDict(val map: Map<String, PdfValue>) : PdfValue() {
    operator fun get(key: String): PdfValue? = map[key]
}

class PdfObjectReader(private val data: ByteArray) {

    val objects = HashMap<Int, PdfValue>()

    fun readAll(): PdfObjectReader {
        val objStreams = ArrayList<Pair<PdfDict, ByteArray>>()
        var i = 0
        val n = data.size
        while (i < n - 3) {
            // busca "obj" precedido de "<num> <gen> "
            if (data[i] == 'o'.code.toByte() && data[i + 1] == 'b'.code.toByte() && data[i + 2] == 'j'.code.toByte() &&
                (i + 3 >= n || isDelimOrWs(data[i + 3]))
            ) {
                val header = parseObjHeaderBackwards(i)
                if (header != null) {
                    try {
                        val p = Parser(data, i + 3)
                        val value = p.parseValue()
                        p.skipWs()
                        var end = p.pos
                        if (value is PdfDict && p.matchKeyword("stream")) {
                            var s = p.pos + 6
                            if (s < n && data[s] == '\r'.code.toByte()) s++
                            if (s < n && data[s] == '\n'.code.toByte()) s++
                            val lenVal = value["Length"]
                            var streamEnd = -1
                            if (lenVal is PdfNum) {
                                val e = s + lenVal.v.toInt()
                                if (e in s..n && indexOf(ENDSTREAM, e, minOf(n, e + 64)) >= 0) streamEnd = e
                            }
                            val es = indexOf(ENDSTREAM, s, n)
                            if (streamEnd < 0) streamEnd = if (es >= 0) es else n
                            val type = value["Type"]
                            if (type is PdfName && type.v == "ObjStm") {
                                objStreams.add(value to data.copyOfRange(s, streamEnd))
                            }
                            end = if (es >= 0) es + ENDSTREAM.size else streamEnd
                        }
                        objects[header] = value
                        i = maxOf(end, i + 3)
                        continue
                    } catch (_: Exception) {
                        // objeto mal formado: seguir buscando
                    }
                }
            }
            i++
        }
        for ((dict, raw) in objStreams) {
            try {
                readObjectStream(dict, raw)
            } catch (_: Exception) {
            }
        }
        return this
    }

    private fun readObjectStream(dict: PdfDict, raw: ByteArray) {
        val filter = dict["Filter"]
        val isFlate = (filter is PdfName && filter.v == "FlateDecode") ||
            (filter is PdfArray && filter.items.size == 1 && (filter.items[0] as? PdfName)?.v == "FlateDecode")
        if (filter != null && !isFlate) return
        val bytes = if (isFlate) inflate(raw) else raw
        val count = (dict["N"] as? PdfNum)?.v?.toInt() ?: return
        val first = (dict["First"] as? PdfNum)?.v?.toInt() ?: return
        val hp = Parser(bytes, 0)
        val pairs = ArrayList<Pair<Int, Int>>()
        repeat(count) {
            val num = (hp.parseValue() as PdfNum).v.toInt()
            val off = (hp.parseValue() as PdfNum).v.toInt()
            pairs.add(num to off)
        }
        for ((num, off) in pairs) {
            if (objects.containsKey(num) && objects[num] !is PdfNull) {
                // un objeto suelto posterior (actualización incremental) tiene prioridad
                continue
            }
            try {
                objects[num] = Parser(bytes, first + off).parseValue()
            } catch (_: Exception) {
            }
        }
    }

    private fun parseObjHeaderBackwards(objPos: Int): Int? {
        var j = objPos - 1
        if (j < 0 || !isWs(data[j])) return null
        while (j >= 0 && isWs(data[j])) j--
        val genEnd = j
        while (j >= 0 && isDigit(data[j])) j--
        if (j == genEnd) return null
        if (j < 0 || !isWs(data[j])) return null
        while (j >= 0 && isWs(data[j])) j--
        val numEnd = j
        while (j >= 0 && isDigit(data[j])) j--
        if (j == numEnd) return null
        if (j >= 0 && !isDelimOrWs(data[j])) return null
        return String(data, j + 1, numEnd - j, Charsets.ISO_8859_1).toIntOrNull()
    }

    private fun indexOf(pat: ByteArray, from: Int, to: Int): Int {
        var k = from
        val last = to - pat.size
        outer@ while (k <= last) {
            for (m in pat.indices) if (data[k + m] != pat[m]) { k++; continue@outer }
            return k
        }
        return -1
    }

    fun resolve(v: PdfValue?, depth: Int = 0): PdfValue? {
        if (v is PdfRef && depth < 16) return resolve(objects[v.num], depth + 1)
        return v
    }

    companion object {
        private val ENDSTREAM = "endstream".toByteArray(Charsets.ISO_8859_1)

        fun inflate(input: ByteArray): ByteArray {
            val inf = Inflater()
            inf.setInput(input)
            val out = ByteArrayOutputStream(input.size * 4)
            val buf = ByteArray(65536)
            try {
                while (!inf.finished()) {
                    val r = inf.inflate(buf)
                    if (r == 0) {
                        if (inf.needsInput() || inf.needsDictionary()) break
                    }
                    out.write(buf, 0, r)
                }
            } finally {
                inf.end()
            }
            return out.toByteArray()
        }

        fun isWs(b: Byte): Boolean = b == 0x20.toByte() || b == 0x0A.toByte() || b == 0x0D.toByte() ||
            b == 0x09.toByte() || b == 0x0C.toByte() || b == 0x00.toByte()

        fun isDelim(b: Byte): Boolean = when (b.toInt().toChar()) {
            '(', ')', '<', '>', '[', ']', '{', '}', '/', '%' -> true
            else -> false
        }

        fun isDelimOrWs(b: Byte) = isWs(b) || isDelim(b)
        fun isDigit(b: Byte) = b >= '0'.code.toByte() && b <= '9'.code.toByte()
    }

    /** Parser recursivo de valores PDF a partir de una posición. */
    class Parser(private val d: ByteArray, var pos: Int) {

        fun skipWs() {
            while (pos < d.size) {
                val b = d[pos]
                if (isWs(b)) pos++
                else if (b == '%'.code.toByte()) {
                    while (pos < d.size && d[pos] != '\n'.code.toByte() && d[pos] != '\r'.code.toByte()) pos++
                } else break
            }
        }

        fun matchKeyword(k: String): Boolean {
            if (pos + k.length > d.size) return false
            for (i in k.indices) if (d[pos + i] != k[i].code.toByte()) return false
            return true
        }

        fun parseValue(depth: Int = 0): PdfValue {
            if (depth > 64) throw IllegalStateException("anidamiento excesivo")
            skipWs()
            if (pos >= d.size) throw IllegalStateException("fin de datos")
            val c = d[pos].toInt().toChar()
            return when {
                c == '/' -> PdfName(readName())
                c == '[' -> {
                    pos++
                    val items = ArrayList<PdfValue>()
                    while (true) {
                        skipWs()
                        if (pos >= d.size) throw IllegalStateException("array sin cerrar")
                        if (d[pos] == ']'.code.toByte()) { pos++; break }
                        items.add(parseValueOrRef(depth + 1))
                    }
                    PdfArray(items)
                }
                c == '<' && pos + 1 < d.size && d[pos + 1] == '<'.code.toByte() -> {
                    pos += 2
                    val map = LinkedHashMap<String, PdfValue>()
                    while (true) {
                        skipWs()
                        if (pos >= d.size) throw IllegalStateException("dict sin cerrar")
                        if (d[pos] == '>'.code.toByte() && pos + 1 < d.size && d[pos + 1] == '>'.code.toByte()) {
                            pos += 2; break
                        }
                        if (d[pos] != '/'.code.toByte()) throw IllegalStateException("clave inválida")
                        val key = readName()
                        map[key] = parseValueOrRef(depth + 1)
                    }
                    PdfDict(map)
                }
                c == '<' -> PdfStr(readHexString())
                c == '(' -> PdfStr(readLiteralString())
                c == '+' || c == '-' || c == '.' || c in '0'..'9' -> PdfNum(readNumber())
                matchKeyword("true") -> { pos += 4; PdfBool(true) }
                matchKeyword("false") -> { pos += 5; PdfBool(false) }
                matchKeyword("null") -> { pos += 4; PdfNull }
                else -> throw IllegalStateException("token inesperado '$c'")
            }
        }

        /** Dentro de arrays y diccionarios: detecta referencias "n g R". */
        fun parseValueOrRef(depth: Int): PdfValue {
            val v = parseValue(depth)
            if (v is PdfNum && v.v >= 0 && v.v == Math.floor(v.v)) {
                val save = pos
                skipWs()
                if (pos < d.size && isDigit(d[pos])) {
                    val p2 = pos
                    val gen = try { readNumber() } catch (_: Exception) { pos = save; return v }
                    skipWs()
                    if (pos < d.size && d[pos] == 'R'.code.toByte() &&
                        (pos + 1 >= d.size || isDelimOrWs(d[pos + 1]))
                    ) {
                        pos++
                        return PdfRef(v.v.toInt(), gen.toInt())
                    }
                    pos = p2
                }
                pos = save
            }
            return v
        }

        private fun readName(): String {
            pos++ // '/'
            val sb = StringBuilder()
            while (pos < d.size && !isDelimOrWs(d[pos])) {
                val b = d[pos]
                if (b == '#'.code.toByte() && pos + 2 < d.size) {
                    val hex = String(d, pos + 1, 2, Charsets.ISO_8859_1).toIntOrNull(16)
                    if (hex != null) { sb.append(hex.toChar()); pos += 3; continue }
                }
                sb.append((b.toInt() and 0xFF).toChar())
                pos++
            }
            return sb.toString()
        }

        private fun readNumber(): Double {
            val start = pos
            if (d[pos] == '+'.code.toByte() || d[pos] == '-'.code.toByte()) pos++
            while (pos < d.size && (isDigit(d[pos]) || d[pos] == '.'.code.toByte())) pos++
            val s = String(d, start, pos - start, Charsets.ISO_8859_1)
            return s.toDoubleOrNull() ?: throw IllegalStateException("número inválido '$s'")
        }

        private fun readHexString(): String {
            pos++
            val sb = StringBuilder()
            while (pos < d.size && d[pos] != '>'.code.toByte()) {
                if (!isWs(d[pos])) sb.append(d[pos].toInt().toChar())
                pos++
            }
            pos++
            val hex = if (sb.length % 2 == 1) sb.append('0') else sb
            val out = StringBuilder()
            var k = 0
            while (k + 1 < hex.length) {
                out.append(hex.substring(k, k + 2).toIntOrNull(16)?.toChar() ?: '?')
                k += 2
            }
            return out.toString()
        }

        private fun readLiteralString(): String {
            pos++
            var level = 1
            val sb = StringBuilder()
            while (pos < d.size) {
                val b = d[pos]
                when (b.toInt().toChar()) {
                    '\\' -> { if (pos + 1 < d.size) sb.append(d[pos + 1].toInt().toChar()); pos += 2; continue }
                    '(' -> level++
                    ')' -> { level--; if (level == 0) { pos++; break } }
                }
                sb.append((b.toInt() and 0xFF).toChar())
                pos++
            }
            return sb.toString()
        }
    }
}
