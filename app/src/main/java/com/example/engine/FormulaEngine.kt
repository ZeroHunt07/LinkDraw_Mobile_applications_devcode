package com.example.engine

import com.example.model.GraphDocument
import kotlin.math.abs

object FormulaEngine {

  const val COLS = 4 // A, B, C, D
  const val ROWS = 8 // 1..8

  fun colLetter(col: Int): String = ('A' + col).toString()

  fun cellKey(col: Int, row: Int): String = "${colLetter(col)}${row + 1}"

  fun defaultSheet(): Map<String, String> =
    mapOf(
      "A1" to "Nodes",
      "B1" to "=GRAPH(\"nodes\")",
      "A2" to "Edges",
      "B2" to "=GRAPH(\"edges\")",
      "A3" to "Gateway",
      "B3" to "=NODE(\"Core API Gateway\", \"progress\")",
      "A4" to "Auth",
      "B4" to "=NODE(\"Auth Service\", \"progress\")",
      "A5" to "Analytics",
      "B5" to "=NODE(\"Graph Analytics\", \"progress\")",
      "A6" to "Avg Progress",
      "B6" to "=AVERAGE(B3:B5)",
      "A7" to "Sum Progress",
      "B7" to "=SUM(B3:B5)",
      "A8" to "Status",
      "B8" to "=IF(B6>80, \"Healthy\", \"Review\")",
    )

  fun evaluateAll(sheet: Map<String, String>, doc: GraphDocument): Map<String, String> {
    val cache = mutableMapOf<String, String>()
    val visiting = mutableSetOf<String>()

    fun evalCell(key: String): String {
      cache[key]?.let { return it }
      if (key in visiting) return "#CYCLE!"
      val raw = sheet[key]?.trim() ?: return ""
      if (!raw.startsWith("=")) {
        cache[key] = raw
        return raw
      }
      visiting.add(key)
      val result =
        runCatching { evalExpr(raw.drop(1).trim(), doc, ::evalCell) }
          .getOrElse { "#ERR" }
      visiting.remove(key)
      cache[key] = result
      return result
    }

    sheet.keys.forEach { evalCell(it) }
    return cache
  }

  private fun evalExpr(
    expr: String,
    doc: GraphDocument,
    evalCell: (String) -> String,
  ): String {
    val trimmed = expr.trim()

    // =GRAPH("nodes" | "edges" | "stickies")
    val graphMatch = Regex("""^GRAPH\(\s*"([^"]+)"\s*\)$""", RegexOption.IGNORE_CASE).matchEntire(trimmed)
    if (graphMatch != null) {
      return when (graphMatch.groupValues[1].lowercase()) {
        "nodes" -> doc.nodes.size.toString()
        "edges", "conns" -> doc.edges.size.toString()
        "stickies" -> doc.stickyNotes.size.toString()
        else -> "0"
      }
    }

    // =NODE("Name", "progress" | "weight" | "tags" | "x" | "y")
    val nodeMatch =
      Regex("""^NODE\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*\)$""", RegexOption.IGNORE_CASE)
        .matchEntire(trimmed)
    if (nodeMatch != null) {
      val name = nodeMatch.groupValues[1]
      val prop = nodeMatch.groupValues[2].lowercase()
      val node = doc.nodes.firstOrNull { it.text.equals(name, ignoreCase = true) || it.id == name }
        ?: return "0"
      return when (prop) {
        "progress" -> (node.progress ?: 0).toString()
        "x" -> node.x.toInt().toString()
        "y" -> node.y.toInt().toString()
        "w", "width" -> node.w.toInt().toString()
        "h", "height" -> node.h.toInt().toString()
        "tags" -> node.tags.size.toString()
        else -> node.metadata[prop] ?: "0"
      }
    }

    // =IF(cond, trueVal, falseVal)
    if (trimmed.uppercase().startsWith("IF(") && trimmed.endsWith(")")) {
      val inner = trimmed.substring(3, trimmed.length - 1)
      val args = splitTopLevelArgs(inner)
      if (args.size == 3) {
        val condResult = evalCondition(args[0], doc, evalCell)
        val branch = if (condResult) args[1].trim() else args[2].trim()
        return if (branch.startsWith("\"") && branch.endsWith("\"") && branch.length >= 2) {
          branch.substring(1, branch.length - 1)
        } else {
          evalExpr(branch, doc, evalCell)
        }
      }
    }

    // Aggregate functions: SUM, AVERAGE, MIN, MAX, COUNT
    val fnMatch =
      Regex("""^(SUM|AVERAGE|AVG|MIN|MAX|COUNT)\((.+)\)$""", RegexOption.IGNORE_CASE)
        .matchEntire(trimmed)
    if (fnMatch != null) {
      val fn = fnMatch.groupValues[1].uppercase()
      val argStr = fnMatch.groupValues[2]
      val values = collectNumericArgs(argStr, doc, evalCell)
      if (values.isEmpty()) return "0"
      val res =
        when (fn) {
          "SUM" -> values.sum()
          "AVERAGE", "AVG" -> values.average()
          "MIN" -> values.minOrNull() ?: 0.0
          "MAX" -> values.maxOrNull() ?: 0.0
          "COUNT" -> values.size.toDouble()
          else -> 0.0
        }
      return formatNumber(res)
    }

    if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length >= 2) {
      return trimmed.substring(1, trimmed.length - 1)
    }

    val numeric = evalArithmetic(trimmed, evalCell)
    return formatNumber(numeric)
  }

  private fun evalCondition(
    cond: String,
    doc: GraphDocument,
    evalCell: (String) -> String,
  ): Boolean {
    val ops = listOf(">=", "<=", "!=", "=", ">", "<")
    for (op in ops) {
      val idx = cond.indexOf(op)
      if (idx > 0) {
        val left = evalExpr(cond.substring(0, idx), doc, evalCell).toDoubleOrNull() ?: 0.0
        val right = evalExpr(cond.substring(idx + op.length), doc, evalCell).toDoubleOrNull() ?: 0.0
        return when (op) {
          ">=" -> left >= right
          "<=" -> left <= right
          "!=" -> abs(left - right) > 1e-6
          "=" -> abs(left - right) <= 1e-6
          ">" -> left > right
          "<" -> left < right
          else -> false
        }
      }
    }
    return false
  }

  private fun collectNumericArgs(
    argStr: String,
    doc: GraphDocument,
    evalCell: (String) -> String,
  ): List<Double> {
    val nums = mutableListOf<Double>()
    splitTopLevelArgs(argStr).forEach { token ->
      val t = token.trim()
      val rangeMatch = Regex("""^([A-Z])(\d+):([A-Z])(\d+)$""", RegexOption.IGNORE_CASE).matchEntire(t)
      if (rangeMatch != null) {
        val c1 = rangeMatch.groupValues[1].uppercase()[0] - 'A'
        val r1 = rangeMatch.groupValues[2].toInt()
        val c2 = rangeMatch.groupValues[3].uppercase()[0] - 'A'
        val r2 = rangeMatch.groupValues[4].toInt()
        for (c in minOf(c1, c2)..maxOf(c1, c2)) {
          for (r in minOf(r1, r2)..maxOf(r1, r2)) {
            val v = evalCell("${('A' + c)}$r").toDoubleOrNull()
            if (v != null) nums += v
          }
        }
      } else {
        val v = evalExpr(t, doc, evalCell).toDoubleOrNull()
        if (v != null) nums += v
      }
    }
    return nums
  }

  private fun evalArithmetic(expr: String, evalCell: (String) -> String): Double {
    val replaced =
      Regex("""\b([A-Z])(\d+)\b""", RegexOption.IGNORE_CASE).replace(expr) { m ->
        val key = "${m.groupValues[1].uppercase()}${m.groupValues[2]}"
        evalCell(key).toDoubleOrNull()?.toString() ?: "0"
      }
    return SimpleMathParser(replaced).parse()
  }

  private fun splitTopLevelArgs(s: String): List<String> {
    val parts = mutableListOf<String>()
    var depth = 0
    var inQuotes = false
    val sb = StringBuilder()
    for (ch in s) {
      when {
        ch == '"' -> {
          inQuotes = !inQuotes
          sb.append(ch)
        }
        !inQuotes && ch == '(' -> {
          depth++
          sb.append(ch)
        }
        !inQuotes && ch == ')' -> {
          depth--
          sb.append(ch)
        }
        !inQuotes && depth == 0 && ch == ',' -> {
          parts += sb.toString()
          sb.clear()
        }
        else -> sb.append(ch)
      }
    }
    if (sb.isNotEmpty()) parts += sb.toString()
    return parts
  }

  private fun formatNumber(d: Double): String =
    if (d % 1.0 == 0.0) d.toLong().toString() else String.format("%.2f", d)

  private class SimpleMathParser(private val input: String) {
    private var pos = 0

    fun parse(): Double {
      val v = parseExpression()
      return v
    }

    private fun skipWs() {
      while (pos < input.length && input[pos].isWhitespace()) pos++
    }

    private fun parseExpression(): Double {
      var x = parseTerm()
      while (true) {
        skipWs()
        if (pos < input.length && input[pos] == '+') {
          pos++
          x += parseTerm()
        } else if (pos < input.length && input[pos] == '-') {
          pos++
          x -= parseTerm()
        } else {
          return x
        }
      }
    }

    private fun parseTerm(): Double {
      var x = parseFactor()
      while (true) {
        skipWs()
        if (pos < input.length && input[pos] == '*') {
          pos++
          x *= parseFactor()
        } else if (pos < input.length && input[pos] == '/') {
          pos++
          val denom = parseFactor()
          x = if (denom == 0.0) 0.0 else x / denom
        } else {
          return x
        }
      }
    }

    private fun parseFactor(): Double {
      skipWs()
      if (pos < input.length && input[pos] == '+') {
        pos++
        return parseFactor()
      }
      if (pos < input.length && input[pos] == '-') {
        pos++
        return -parseFactor()
      }
      if (pos < input.length && input[pos] == '(') {
        pos++
        val x = parseExpression()
        skipWs()
        if (pos < input.length && input[pos] == ')') pos++
        return x
      }
      val start = pos
      while (pos < input.length && (input[pos].isDigit() || input[pos] == '.')) pos++
      return input.substring(start, pos).toDoubleOrNull() ?: 0.0
    }
  }
}
