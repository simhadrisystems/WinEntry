package com.simhadri.winentry.utils.xlsx

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory

/**
 * Reads cell values, number formats and sheet names from an .xlsx file. Fonts, fills and
 * merges are not read back; imports only need values and whether a number is a date.
 *
 * Zip entries are buffered by name first: a content URI only gives a stream, and nothing
 * guarantees sharedStrings.xml or styles.xml come before the sheets in the zip.
 */
internal object XlsxReader {

    private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024

    fun read(input: InputStream, wb: XlsxWorkbook) {
        val parts = unzip(input)
        if (parts.isEmpty()) throw IOException("Not an .xlsx file (no zip entries found)")

        val workbookPath = rels(parts, "_rels/.rels", "").values.firstOrNull { it.endsWith("workbook.xml") }
            ?: "xl/workbook.xml"
        val workbookXml = parts[workbookPath] ?: throw IOException("Not an .xlsx file (no workbook.xml)")
        val baseDir = workbookPath.substringBeforeLast('/', "")
        val relsPath = (if (baseDir.isEmpty()) "" else "$baseDir/") + "_rels/" + workbookPath.substringAfterLast('/') + ".rels"
        val targets = rels(parts, relsPath, baseDir)

        val sheetRefs = mutableListOf<Pair<String, String>>()
        parse(workbookXml) { name, a ->
            when (name) {
                "workbookPr" -> wb.date1904 = a.value("date1904").let { it == "1" || it == "true" }
                "sheet" -> {
                    val id = a.value("r:id") ?: a.value("id")
                    targets[id]?.let { sheetRefs += a.value("name").orEmpty() to it }
                }
            }
        }

        val sharedPath = targets.values.firstOrNull { it.endsWith("sharedStrings.xml") } ?: "$baseDir/sharedStrings.xml"
        val stylesPath = targets.values.firstOrNull { it.endsWith("styles.xml") } ?: "$baseDir/styles.xml"
        val shared = parts[sharedPath]?.let { sharedStrings(it) } ?: emptyList()
        parts[stylesPath]?.let { styles(it, wb) }

        for ((name, path) in sheetRefs) {
            val sheet = wb.createSheet(name)
            parts[path]?.let { sheetData(it, sheet, shared) }
        }
    }

    private fun unzip(input: InputStream): Map<String, ByteArray> {
        val parts = HashMap<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val name = e.name.removePrefix("/")
                if (!e.isDirectory && (name.endsWith(".xml") || name.endsWith(".rels"))) {
                    parts[name] = readLimited(zip)
                }
            }
        }
        return parts
    }

    private fun readLimited(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_ENTRY_BYTES) throw IOException("Excel file part is too large")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Relationship id to zip path, resolved against [baseDir]. */
    private fun rels(parts: Map<String, ByteArray>, path: String, baseDir: String): Map<String, String> {
        val bytes = parts[path] ?: return emptyMap()
        val map = HashMap<String, String>()
        parse(bytes) { name, a ->
            if (name == "Relationship" && a.value("TargetMode") != "External") {
                val id = a.value("Id") ?: return@parse
                val target = a.value("Target") ?: return@parse
                map[id] = resolve(baseDir, target)
            }
        }
        return map
    }

    private fun resolve(baseDir: String, target: String): String {
        if (target.startsWith("/")) return target.removePrefix("/")
        val parts = (if (baseDir.isEmpty()) emptyList() else baseDir.split('/')).toMutableList()
        for (seg in target.split('/')) {
            when (seg) {
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                ".", "" -> {}
                else -> parts += seg
            }
        }
        return parts.joinToString("/")
    }

    private fun sharedStrings(bytes: ByteArray): List<String> {
        val list = ArrayList<String>()
        val sb = StringBuilder()
        var inT = false
        var phonetic = 0
        parse(bytes, object : Handler() {
            override fun start(name: String, a: Attributes) {
                when (name) {
                    "si" -> sb.setLength(0)
                    "rPh" -> phonetic++
                    "t" -> inT = phonetic == 0
                }
            }
            override fun end(name: String) {
                when (name) {
                    "si" -> list += sb.toString()
                    "rPh" -> phonetic--
                    "t" -> inT = false
                }
            }
            override fun text(ch: CharArray, start: Int, length: Int) {
                if (inT) sb.append(ch, start, length)
            }
        })
        return list
    }

    private fun styles(bytes: ByteArray, wb: XlsxWorkbook) {
        val formats = ArrayList<Int>()
        var inCellXfs = false
        parse(bytes, object : Handler() {
            override fun start(name: String, a: Attributes) {
                when (name) {
                    "numFmt" -> {
                        val id = a.value("numFmtId")?.toIntOrNull()
                        val code = a.value("formatCode")
                        if (id != null && code != null) wb.customFormats[id] = code
                    }
                    "cellXfs" -> inCellXfs = true
                    "xf" -> if (inCellXfs) formats += a.value("numFmtId")?.toIntOrNull() ?: 0
                }
            }
            override fun end(name: String) {
                if (name == "cellXfs") inCellXfs = false
            }
        })
        formats.forEachIndexed { i, fmt ->
            val style = if (i == 0) wb.styles[0] else wb.createCellStyle()
            style.dataFormat = fmt.toShort()
        }
    }

    private fun sheetData(bytes: ByteArray, sheet: Sheet, shared: List<String>) {
        parse(bytes, object : Handler() {
            var row: Row? = null
            var nextRow = 0
            var nextCol = 0
            var col = 0
            var type: String? = null
            var styleIdx = 0
            var hasFormula = false
            val value = StringBuilder()
            val inline = StringBuilder()
            var inV = false
            var inInlineT = false
            var inIs = false
            var phonetic = 0

            override fun start(name: String, a: Attributes) {
                when (name) {
                    "row" -> {
                        val r = a.value("r")?.toIntOrNull()?.minus(1) ?: nextRow
                        row = sheet.getRow(r) ?: sheet.createRow(r)
                        nextRow = r + 1
                        nextCol = 0
                    }
                    "c" -> {
                        col = a.value("r")?.let { CellRef.parse(it)?.second } ?: nextCol
                        nextCol = col + 1
                        type = a.value("t")
                        styleIdx = a.value("s")?.toIntOrNull() ?: 0
                        hasFormula = false
                        value.setLength(0)
                        inline.setLength(0)
                    }
                    "v" -> inV = true
                    "f" -> hasFormula = true
                    "is" -> inIs = true
                    "rPh" -> phonetic++
                    "t" -> inInlineT = inIs && phonetic == 0
                }
            }

            override fun end(name: String) {
                when (name) {
                    "v" -> inV = false
                    "t" -> inInlineT = false
                    "is" -> inIs = false
                    "rPh" -> phonetic--
                    "c" -> finishCell()
                }
            }

            override fun text(ch: CharArray, start: Int, length: Int) {
                if (inV) value.append(ch, start, length)
                else if (inInlineT) inline.append(ch, start, length)
            }

            private fun finishCell() {
                val r = row ?: sheet.createRow(nextRow).also { row = it; nextRow++ }
                val v = value.toString()
                val cell = r.createCell(col)
                cell.cellStyle = sheet.workbook.styleAt(styleIdx)
                if (hasFormula) cell.cellFormula = ""
                when (type) {
                    "s" -> {
                        val text = v.trim().toIntOrNull()?.let { shared.getOrNull(it) }
                        if (text == null) cell.setRead(CellType.BLANK, null, 0.0, false)
                        else cell.setRead(CellType.STRING, text, 0.0, false)
                    }
                    "inlineStr" -> cell.setRead(CellType.STRING, inline.toString(), 0.0, false)
                    "str" -> cell.setRead(CellType.STRING, v, 0.0, false)
                    "b" -> cell.setRead(CellType.BOOLEAN, null, 0.0, v.trim() == "1" || v.trim() == "true")
                    "e" -> cell.setRead(CellType.ERROR, v, 0.0, false)
                    // ISO 8601 date cell (rare; strict OOXML). Kept as text, which StrictDate parses.
                    "d" -> cell.setRead(CellType.STRING, v.substringBefore('T'), 0.0, false)
                    else -> {
                        val d = v.trim().toDoubleOrNull()
                        if (d == null) cell.setRead(CellType.BLANK, null, 0.0, false)
                        else cell.setRead(CellType.NUMERIC, null, d, false)
                    }
                }
            }
        })
    }

    private abstract class Handler : DefaultHandler() {
        open fun start(name: String, a: Attributes) {}
        open fun end(name: String) {}
        open fun text(ch: CharArray, start: Int, length: Int) {}

        override fun startElement(uri: String?, localName: String?, qName: String, a: Attributes) =
            start(qName.substringAfter(':'), a)

        override fun endElement(uri: String?, localName: String?, qName: String) =
            end(qName.substringAfter(':'))

        override fun characters(ch: CharArray, start: Int, length: Int) = text(ch, start, length)
    }

    private fun parse(bytes: ByteArray, onStart: (String, Attributes) -> Unit) =
        parse(bytes, object : Handler() {
            override fun start(name: String, a: Attributes) = onStart(name, a)
        })

    private fun parse(bytes: ByteArray, handler: DefaultHandler) {
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = false
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        } catch (_: Exception) {
            // Android's parser doesn't know this feature and never fetches external entities.
        }
        factory.newSAXParser().parse(InputSource(ByteArrayInputStream(bytes)), handler)
    }

    /** Attribute by name, ignoring any namespace prefix ("r:id" also matches "id" under another prefix). */
    private fun Attributes.value(name: String): String? {
        getValue(name)?.let { return it }
        val local = name.substringAfter(':')
        for (i in 0 until length) {
            val q = getQName(i)
            if (q.substringAfter(':') == local && (name.contains(':') == q.contains(':'))) return getValue(i)
        }
        return null
    }
}
