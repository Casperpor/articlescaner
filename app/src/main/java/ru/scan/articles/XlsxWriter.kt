package ru.scan.articles

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Минимальный генератор .xlsx (Office Open XML) без сторонних библиотек.
 * Все ячейки — текст, поэтому артикулы не превращаются в 6,01E+09 и не теряют ведущие нули.
 */
object XlsxWriter {

    fun write(out: OutputStream, sheetName: String, header: List<String>, rows: List<List<String>>, colWidths: List<Double>) {
        val strings = LinkedHashMap<String, Int>()
        fun idx(s: String): Int = strings.getOrPut(s) { strings.size }

        val sheet = StringBuilder()
        sheet.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sheet.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""")
        sheet.append("<cols>")
        colWidths.forEachIndexed { i, w -> sheet.append("""<col min="${i + 1}" max="${i + 1}" width="$w" customWidth="1"/>""") }
        sheet.append("</cols><sheetData>")
        val all = listOf(header) + rows
        all.forEachIndexed { r, row ->
            val rowNum = r + 1
            sheet.append("""<row r="$rowNum">""")
            row.forEachIndexed { c, value ->
                val style = if (r == 0) 1 else 0
                sheet.append("""<c r="${colName(c)}$rowNum" t="s" s="$style"><v>${idx(value)}</v></c>""")
            }
            sheet.append("</row>")
        }
        sheet.append("</sheetData></worksheet>")

        val sst = StringBuilder()
        sst.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sst.append("""<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="${all.sumOf { it.size }}" uniqueCount="${strings.size}">""")
        for (s in strings.keys) sst.append("""<si><t xml:space="preserve">${esc(s)}</t></si>""")
        sst.append("</sst>")

        ZipOutputStream(out).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/><Override PartName="/xl/sharedStrings.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/></Types>""")
            put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            put("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="${esc(sheetName)}" sheetId="1" r:id="rId1"/></sheets></workbook>""")
            put("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/><Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings" Target="sharedStrings.xml"/></Relationships>""")
            put("xl/styles.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="2"><xf numFmtId="49" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/><xf numFmtId="49" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1" applyNumberFormat="1"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>""")
            put("xl/worksheets/sheet1.xml", sheet.toString())
            put("xl/sharedStrings.xml", sst.toString())
        }
    }

    private fun colName(i: Int): String {
        var n = i
        val sb = StringBuilder()
        do {
            sb.insert(0, ('A' + n % 26))
            n = n / 26 - 1
        } while (n >= 0)
        return sb.toString()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
