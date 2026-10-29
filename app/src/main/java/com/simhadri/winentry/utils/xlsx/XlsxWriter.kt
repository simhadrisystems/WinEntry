package com.simhadri.winentry.utils.xlsx

import java.io.OutputStream
import java.io.Writer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Writes an [XlsxWorkbook] as a minimal SpreadsheetML package (shared strings, styles, merges, widths). */
internal object XlsxWriter {

    private const val MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val PKG_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"

    fun write(wb: XlsxWorkbook, out: OutputStream) {
        if (wb.sheets.isEmpty()) wb.createSheet()
        val strings = LinkedHashMap<String, Int>()
        val zip = ZipOutputStream(out)
        val w = zip.writer(Charsets.UTF_8)

        fun entry(name: String, body: (Writer) -> Unit) {
            zip.putNextEntry(ZipEntry(name))
            w.write(HEADER)
            body(w)
            w.flush()
            zip.closeEntry()
        }

        entry("[Content_Types].xml") { contentTypes(it, wb) }
        entry("_rels/.rels") {
            it.write("<Relationships xmlns=\"$PKG_REL_NS\">" +
                "<Relationship Id=\"rId1\" Type=\"$REL_NS/officeDocument\" Target=\"xl/workbook.xml\"/>" +
                "</Relationships>")
        }
        entry("xl/workbook.xml") { workbook(it, wb) }
        entry("xl/_rels/workbook.xml.rels") { workbookRels(it, wb) }
        wb.sheets.forEachIndexed { i, sheet -> entry("xl/worksheets/sheet${i + 1}.xml") { sheet(it, sheet, strings) } }
        entry("xl/sharedStrings.xml") { sharedStrings(it, strings) }
        entry("xl/styles.xml") { styles(it, wb) }
        zip.finish()
    }

    private fun contentTypes(w: Writer, wb: XlsxWorkbook) {
        val ct = "application/vnd.openxmlformats-officedocument.spreadsheetml"
        w.write("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
        w.write("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        w.write("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        w.write("<Override PartName=\"/xl/workbook.xml\" ContentType=\"$ct.sheet.main+xml\"/>")
        for (i in wb.sheets.indices) {
            w.write("<Override PartName=\"/xl/worksheets/sheet${i + 1}.xml\" ContentType=\"$ct.worksheet+xml\"/>")
        }
        w.write("<Override PartName=\"/xl/sharedStrings.xml\" ContentType=\"$ct.sharedStrings+xml\"/>")
        w.write("<Override PartName=\"/xl/styles.xml\" ContentType=\"$ct.styles+xml\"/>")
        w.write("</Types>")
    }

    private fun workbook(w: Writer, wb: XlsxWorkbook) {
        w.write("<workbook xmlns=\"$MAIN_NS\" xmlns:r=\"$REL_NS\"><sheets>")
        wb.sheets.forEachIndexed { i, s ->
            w.write("<sheet name=\"${esc(sheetName(s.sheetName))}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>")
        }
        // No cached results are written for formulas, so ask Excel to calculate on open.
        w.write("</sheets><calcPr calcId=\"191029\" fullCalcOnLoad=\"1\"/></workbook>")
    }

    private fun workbookRels(w: Writer, wb: XlsxWorkbook) {
        w.write("<Relationships xmlns=\"$PKG_REL_NS\">")
        val n = wb.sheets.size
        for (i in 1..n) {
            w.write("<Relationship Id=\"rId$i\" Type=\"$REL_NS/worksheet\" Target=\"worksheets/sheet$i.xml\"/>")
        }
        w.write("<Relationship Id=\"rId${n + 1}\" Type=\"$REL_NS/sharedStrings\" Target=\"sharedStrings.xml\"/>")
        w.write("<Relationship Id=\"rId${n + 2}\" Type=\"$REL_NS/styles\" Target=\"styles.xml\"/>")
        w.write("</Relationships>")
    }

    private fun sheet(w: Writer, sheet: Sheet, strings: MutableMap<String, Int>) {
        w.write("<worksheet xmlns=\"$MAIN_NS\" xmlns:r=\"$REL_NS\">")
        if (sheet.columnWidths.isNotEmpty()) {
            w.write("<cols>")
            for ((col, width) in sheet.columnWidths) {
                w.write("<col min=\"${col + 1}\" max=\"${col + 1}\" width=\"${num(width / 256.0)}\" customWidth=\"1\"/>")
            }
            w.write("</cols>")
        }
        w.write("<sheetData>")
        for (row in sheet.rows.values) {
            w.write("<row r=\"${row.rowNum + 1}\">")
            for (cell in row.cells.values) cell(w, cell, strings)
            w.write("</row>")
        }
        w.write("</sheetData>")
        if (sheet.mergedRegions.isNotEmpty()) {
            w.write("<mergeCells count=\"${sheet.mergedRegions.size}\">")
            for (r in sheet.mergedRegions) w.write("<mergeCell ref=\"${r.ref()}\"/>")
            w.write("</mergeCells>")
        }
        w.write("<pageMargins left=\"0.7\" right=\"0.7\" top=\"0.75\" bottom=\"0.75\" header=\"0.3\" footer=\"0.3\"/>")
        w.write("</worksheet>")
    }

    private fun cell(w: Writer, cell: Cell, strings: MutableMap<String, Int>) {
        val ref = CellRef.name(cell.rowIndex, cell.columnIndex)
        val s = cell.cellStyle.index.let { if (it == 0) "" else " s=\"$it\"" }
        val formula = cell.cellFormula
        if (formula != null) {
            w.write("<c r=\"$ref\"$s><f>${esc(formula.removePrefix("="))}</f></c>")
            return
        }
        when (cell.writeType) {
            CellType.STRING -> {
                val idx = strings.getOrPut(cell.rawText.orEmpty()) { strings.size }
                w.write("<c r=\"$ref\"$s t=\"s\"><v>$idx</v></c>")
            }
            CellType.NUMERIC -> {
                val d = cell.rawNumber
                if (d.isNaN() || d.isInfinite()) w.write("<c r=\"$ref\"$s t=\"e\"><v>#NUM!</v></c>")
                else w.write("<c r=\"$ref\"$s><v>${num(d)}</v></c>")
            }
            CellType.BOOLEAN -> w.write("<c r=\"$ref\"$s t=\"b\"><v>${if (cell.rawBoolean) 1 else 0}</v></c>")
            else -> if (s.isNotEmpty()) w.write("<c r=\"$ref\"$s/>")
        }
    }

    private fun sharedStrings(w: Writer, strings: Map<String, Int>) {
        w.write("<sst xmlns=\"$MAIN_NS\" count=\"${strings.size}\" uniqueCount=\"${strings.size}\">")
        for (text in strings.keys) w.write("<si><t xml:space=\"preserve\">${esc(text)}</t></si>")
        w.write("</sst>")
    }

    private fun styles(w: Writer, wb: XlsxWorkbook) {
        w.write("<styleSheet xmlns=\"$MAIN_NS\">")
        if (wb.customFormats.isNotEmpty()) {
            w.write("<numFmts count=\"${wb.customFormats.size}\">")
            for ((id, code) in wb.customFormats) w.write("<numFmt numFmtId=\"$id\" formatCode=\"${esc(code)}\"/>")
            w.write("</numFmts>")
        }

        w.write("<fonts count=\"${wb.fonts.size}\">")
        for (f in wb.fonts) {
            w.write("<font>")
            if (f.bold) w.write("<b/>")
            if (f.italic) w.write("<i/>")
            w.write("<sz val=\"${f.fontHeightInPoints}\"/>")
            if (f.color == IndexedColors.BLACK.index) w.write("<color theme=\"1\"/>")
            else w.write("<color indexed=\"${f.color}\"/>")
            w.write("<name val=\"${esc(f.fontName)}\"/><family val=\"2\"/></font>")
        }
        w.write("</fonts>")

        // Fills 0 and 1 are reserved by Excel (none, gray125); each solid fill gets its own entry.
        val fillColors = wb.styles.filter { it.fillPattern == FillPatternType.SOLID_FOREGROUND }
            .map { it.fillForegroundColor }.distinct()
        w.write("<fills count=\"${fillColors.size + 2}\">")
        w.write("<fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>")
        for (c in fillColors) {
            w.write("<fill><patternFill patternType=\"solid\"><fgColor indexed=\"$c\"/><bgColor indexed=\"64\"/></patternFill></fill>")
        }
        w.write("</fills>")

        val borders = wb.styles.map { listOf(it.borderLeft, it.borderRight, it.borderTop, it.borderBottom) }
            .distinct().let { list -> listOf(List(4) { BorderStyle.NONE }) + list.filter { it.any { b -> b != BorderStyle.NONE } } }
        w.write("<borders count=\"${borders.size}\">")
        for (b in borders) {
            w.write("<border>")
            listOf("left", "right", "top", "bottom").forEachIndexed { i, side ->
                val style = b[i].xml
                if (style == null) w.write("<$side/>")
                else w.write("<$side style=\"$style\"><color indexed=\"64\"/></$side>")
            }
            w.write("<diagonal/></border>")
        }
        w.write("</borders>")

        w.write("<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>")
        w.write("<cellXfs count=\"${wb.styles.size}\">")
        for (st in wb.styles) {
            val fontId = st.font?.index ?: 0
            val fillId = if (st.fillPattern == FillPatternType.SOLID_FOREGROUND) fillColors.indexOf(st.fillForegroundColor) + 2 else 0
            val borderId = borders.indexOf(listOf(st.borderLeft, st.borderRight, st.borderTop, st.borderBottom)).coerceAtLeast(0)
            val h = st.alignment.xml
            val v = st.verticalAlignment.xml
            val align = h != null || v != null || st.wrapText
            w.write("<xf numFmtId=\"${st.dataFormat}\" fontId=\"$fontId\" fillId=\"$fillId\" borderId=\"$borderId\" xfId=\"0\"")
            if (st.dataFormat.toInt() != 0) w.write(" applyNumberFormat=\"1\"")
            if (fontId != 0) w.write(" applyFont=\"1\"")
            if (fillId != 0) w.write(" applyFill=\"1\"")
            if (borderId != 0) w.write(" applyBorder=\"1\"")
            if (align) {
                w.write(" applyAlignment=\"1\"><alignment")
                if (h != null) w.write(" horizontal=\"$h\"")
                if (v != null) w.write(" vertical=\"$v\"")
                if (st.wrapText) w.write(" wrapText=\"1\"")
                w.write("/></xf>")
            } else {
                w.write("/>")
            }
        }
        w.write("</cellXfs>")
        w.write("<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>")
        w.write("</styleSheet>")
    }

    private fun num(d: Double): String =
        if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString() else d.toString()

    /** Excel rejects names over 31 characters or with any of : \ / ? * [ ]. */
    private fun sheetName(name: String): String =
        name.replace(Regex("""[:\\/?*\[\]]"""), "_").take(31).ifBlank { "Sheet" }

    private fun esc(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when {
                c == '&' -> sb.append("&amp;")
                c == '<' -> sb.append("&lt;")
                c == '>' -> sb.append("&gt;")
                c == '"' -> sb.append("&quot;")
                c < ' ' && c != '\t' && c != '\n' && c != '\r' -> {}
                c in '￾'..'￿' -> {}
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}
