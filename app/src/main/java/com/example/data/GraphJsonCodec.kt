package com.example.data

import com.example.model.*
import org.json.JSONArray
import org.json.JSONObject

object GraphJsonCodec {

  fun encodeDocument(doc: GraphDocument): String {
    val root = JSONObject()
    root.put("nodes", JSONArray().apply { doc.nodes.forEach { put(encodeNode(it)) } })
    root.put("conns", JSONArray().apply { doc.edges.forEach { put(encodeEdge(it)) } })
    root.put("sticky_notes", JSONArray().apply { doc.stickyNotes.forEach { put(encodeSticky(it)) } })
    root.put("freehand_strokes", JSONArray().apply { doc.freehandStrokes.forEach { put(encodeStroke(it)) } })
    root.put(
      "layers",
      JSONArray().apply {
        doc.layers.forEach {
          put(
            JSONObject()
              .put("name", it.name.id)
              .put("visible", it.visible)
              .put("locked", it.locked)
          )
        }
      },
    )
    root.put(
      "settings",
      JSONObject()
        .put("grid_snap", doc.settings.gridSnap)
        .put("grid_size", doc.settings.gridSize)
        .put("flow_animation", doc.settings.flowAnimation),
    )
    return root.toString(2)
  }

  fun decodeDocument(json: String): GraphDocument {
    if (json.isBlank()) return GraphDocument()
    val root = JSONObject(json)
    val nodes = mutableListOf<GraphNode>()
    val nodesArr = root.optJSONArray("nodes") ?: JSONArray()
    for (i in 0 until nodesArr.length()) {
      nodes += decodeNode(nodesArr.getJSONObject(i))
    }

    val edges = mutableListOf<GraphEdge>()
    val connsArr = root.optJSONArray("conns") ?: root.optJSONArray("edges") ?: JSONArray()
    for (i in 0 until connsArr.length()) {
      edges += decodeEdge(connsArr.getJSONObject(i))
    }

    val stickies = mutableListOf<StickyNote>()
    val stickyArr = root.optJSONArray("sticky_notes") ?: JSONArray()
    for (i in 0 until stickyArr.length()) {
      stickies += decodeSticky(stickyArr.getJSONObject(i))
    }

    val strokes = mutableListOf<FreehandStroke>()
    val strokeArr = root.optJSONArray("freehand_strokes") ?: JSONArray()
    for (i in 0 until strokeArr.length()) {
      strokes += decodeStroke(strokeArr.getJSONObject(i))
    }

    val layers = mutableListOf<CanvasLayerState>()
    val layerArr = root.optJSONArray("layers")
    if (layerArr != null && layerArr.length() > 0) {
      for (i in 0 until layerArr.length()) {
        val obj = layerArr.getJSONObject(i)
        layers +=
          CanvasLayerState(
            name = CanvasLayerName.fromId(obj.optString("name", "Data")),
            visible = obj.optBoolean("visible", true),
            locked = obj.optBoolean("locked", false),
          )
      }
    } else {
      layers += GraphDocument().layers
    }

    val settingsObj = root.optJSONObject("settings")
    val settings =
      if (settingsObj != null) {
        GraphSettings(
          gridSnap = settingsObj.optBoolean("grid_snap", true),
          gridSize = settingsObj.optInt("grid_size", 24).coerceIn(8, 96),
          flowAnimation = settingsObj.optBoolean("flow_animation", false),
        )
      } else {
        GraphSettings()
      }

    return GraphDocument(
      nodes = nodes,
      edges = edges,
      stickyNotes = stickies,
      freehandStrokes = strokes,
      layers = layers,
      settings = settings,
    )
  }

  private fun encodeNode(n: GraphNode): JSONObject {
    val obj = JSONObject()
    obj.put("id", n.id)
    obj.put("x", n.x.toDouble())
    obj.put("y", n.y.toDouble())
    obj.put("w", n.w.toDouble())
    obj.put("h", n.h.toDouble())
    obj.put("text", n.text)
    obj.put("shape", n.shape.id)
    obj.put("color", n.colorHex)
    obj.put("text_color", n.textColorHex)
    obj.put("fs", n.fontSize.toDouble())
    obj.put("icon", n.icon)
    obj.put("icon_position", n.iconPosition.id)
    obj.put("tags", JSONArray(n.tags))
    obj.put("metadata", JSONObject(n.metadata))
    obj.put(
      "rich_notes",
      JSONObject()
        .put("text", n.richNotes.text)
        .put("checklist", encodeChecklistArray(n.richNotes.checklist)),
    )
    obj.put("collapsed", n.collapsed)
    if (n.tableData != null) {
      obj.put(
        "table_data",
        JSONObject()
          .put("headers", JSONArray(n.tableData.headers))
          .put(
            "rows",
            JSONArray().apply { n.tableData.rows.forEach { put(JSONArray(it)) } },
          ),
      )
    }
    if (n.progress != null) obj.put("progress", n.progress)
    if (n.badge != null) obj.put("badge", n.badge)
    obj.put("layer", n.layer.id)
    obj.put("locked", n.locked)
    obj.put("created_at", n.createdAt)
    obj.put("updated_at", n.updatedAt)
    return obj
  }

  private fun decodeNode(obj: JSONObject): GraphNode {
    val tagsArr = obj.optJSONArray("tags") ?: JSONArray()
    val tags = (0 until tagsArr.length()).map { tagsArr.getString(it) }

    val metaObj = obj.optJSONObject("metadata") ?: JSONObject()
    val metadata = mutableMapOf<String, String>()
    metaObj.keys().forEach { k -> metadata[k] = metaObj.optString(k, "") }

    val rnObj = obj.optJSONObject("rich_notes")
    val richNotes =
      if (rnObj != null) {
        RichNodeNote(
          text = rnObj.optString("text", ""),
          checklist = decodeChecklistArray(rnObj.optJSONArray("checklist")),
        )
      } else {
        RichNodeNote()
      }

    val tdObj = obj.optJSONObject("table_data")
    val tableData =
      if (tdObj != null) {
        val hArr = tdObj.optJSONArray("headers") ?: JSONArray()
        val headers = (0 until hArr.length()).map { hArr.optString(it) }
        val rArr = tdObj.optJSONArray("rows") ?: JSONArray()
        val rows =
          (0 until rArr.length()).map { r ->
            val rowArr = rArr.optJSONArray(r) ?: JSONArray()
            (0 until rowArr.length()).map { c -> rowArr.optString(c) }
          }
        TableData(headers = headers, rows = rows)
      } else null

    val progress = if (obj.has("progress") && !obj.isNull("progress")) obj.optInt("progress") else null
    val badge = if (obj.has("badge") && !obj.isNull("badge")) obj.optString("badge") else null
    val now = System.currentTimeMillis()
    val createdAt = obj.optLong("created_at", now).takeIf { it > 0L } ?: now
    val updatedAt = obj.optLong("updated_at", createdAt).takeIf { it > 0L } ?: createdAt

    return GraphNode(
      id = obj.optString("id"),
      x = obj.optDouble("x", 0.0).toFloat(),
      y = obj.optDouble("y", 0.0).toFloat(),
      w = obj.optDouble("w", 150.0).toFloat().coerceAtLeast(60f),
      h = obj.optDouble("h", 68.0).toFloat().coerceAtLeast(40f),
      text = obj.optString("text", "Node"),
      shape = NodeShape.fromId(obj.optString("shape", "rounded")),
      colorHex = obj.optString("color", "#DBEAFE"),
      textColorHex = obj.optString("text_color", "#0F172A"),
      fontSize = obj.optDouble("fs", 13.0).toFloat(),
      icon = obj.optString("icon", ""),
      iconPosition = IconPosition.fromId(obj.optString("icon_position", "inline")),
      tags = tags,
      metadata = metadata,
      richNotes = richNotes,
      collapsed = obj.optBoolean("collapsed", false),
      tableData = tableData,
      progress = progress,
      badge = badge,
      layer = CanvasLayerName.fromId(obj.optString("layer", "Data")),
      locked = obj.optBoolean("locked", false),
      createdAt = createdAt,
      updatedAt = updatedAt,
    )
  }

  private fun encodeEdge(e: GraphEdge): JSONObject =
    JSONObject()
      .put("id", e.id)
      .put("a", e.fromId)
      .put("b", e.toId)
      .put("label", e.label)
      .put("color", e.colorHex)
      .put("width", e.width.toDouble())
      .put("arrow", e.arrow.id)
      .put("type", e.type.id)
      .put("line_style", e.lineStyle.id)
      .put("weight", e.weight.toDouble())
      .put("layer", e.layer.id)
      .put("created_at", e.createdAt)
      .put("updated_at", e.updatedAt)

  private fun decodeEdge(obj: JSONObject): GraphEdge {
    val now = System.currentTimeMillis()
    val createdAt = obj.optLong("created_at", now).takeIf { it > 0L } ?: now
    val updatedAt = obj.optLong("updated_at", createdAt).takeIf { it > 0L } ?: createdAt
    return GraphEdge(
      id = obj.optString("id"),
      fromId = obj.optString("a", obj.optString("fromId")),
      toId = obj.optString("b", obj.optString("toId")),
      label = obj.optString("label", ""),
      colorHex = obj.optString("color", "#475569"),
      width = obj.optDouble("width", 2.4).toFloat(),
      arrow = ArrowType.fromId(obj.optString("arrow", "forward")),
      type = EdgeType.fromId(obj.optString("type", "curved")),
      lineStyle = LineStyle.fromId(obj.optString("line_style", "solid")),
      weight = obj.optDouble("weight", 1.0).toFloat(),
      layer = CanvasLayerName.fromId(obj.optString("layer", "Data")),
      createdAt = createdAt,
      updatedAt = updatedAt,
    )
  }

  private fun encodeSticky(s: StickyNote): JSONObject =
    JSONObject()
      .put("id", s.id)
      .put("x", s.x.toDouble())
      .put("y", s.y.toDouble())
      .put("w", s.w.toDouble())
      .put("h", s.h.toDouble())
      .put("text", s.text)
      .put("color", s.colorHex)
      .put("layer", s.layer.id)
      .put("created_at", s.createdAt)
      .put("updated_at", s.updatedAt)

  private fun decodeSticky(obj: JSONObject): StickyNote {
    val now = System.currentTimeMillis()
    val createdAt = obj.optLong("created_at", now).takeIf { it > 0L } ?: now
    val updatedAt = obj.optLong("updated_at", createdAt).takeIf { it > 0L } ?: createdAt
    return StickyNote(
      id = obj.optString("id"),
      x = obj.optDouble("x", 0.0).toFloat(),
      y = obj.optDouble("y", 0.0).toFloat(),
      w = obj.optDouble("w", 170.0).toFloat(),
      h = obj.optDouble("h", 120.0).toFloat(),
      text = obj.optString("text", ""),
      colorHex = obj.optString("color", "#FEF08A"),
      layer = CanvasLayerName.fromId(obj.optString("layer", "Annotations")),
      createdAt = createdAt,
      updatedAt = updatedAt,
    )
  }

  private fun encodeStroke(st: FreehandStroke): JSONObject =
    JSONObject()
      .put("id", st.id)
      .put("color", st.colorHex)
      .put("width", st.width.toDouble())
      .put("highlighter", st.isHighlighter)
      .put("layer", st.layer.id)
      .put(
        "points",
        JSONArray().apply {
          st.points.forEach { p -> put(JSONArray().put(p.x.toDouble()).put(p.y.toDouble())) }
        },
      )

  private fun decodeStroke(obj: JSONObject): FreehandStroke {
    val pts = mutableListOf<StrokePoint>()
    val pArr = obj.optJSONArray("points") ?: JSONArray()
    for (i in 0 until pArr.length()) {
      val pt = pArr.optJSONArray(i) ?: continue
      if (pt.length() >= 2) {
        pts += StrokePoint(pt.optDouble(0).toFloat(), pt.optDouble(1).toFloat())
      }
    }
    return FreehandStroke(
      id = obj.optString("id"),
      points = pts,
      colorHex = obj.optString("color", "#EF4444"),
      width = obj.optDouble("width", 4.0).toFloat(),
      isHighlighter = obj.optBoolean("highlighter", false),
      layer = CanvasLayerName.fromId(obj.optString("layer", "Annotations")),
    )
  }

  fun encodeChecklist(items: List<ChecklistItem>): String = encodeChecklistArray(items).toString()

  fun decodeChecklist(json: String): List<ChecklistItem> {
    if (json.isBlank()) return emptyList()
    return runCatching { decodeChecklistArray(JSONArray(json)) }.getOrDefault(emptyList())
  }

  private fun encodeChecklistArray(items: List<ChecklistItem>): JSONArray =
    JSONArray().apply {
      items.forEach {
        put(
          JSONObject()
            .put("id", it.id)
            .put("text", it.text)
            .put("checked", it.checked),
        )
      }
    }

  private fun decodeChecklistArray(arr: JSONArray?): List<ChecklistItem> {
    if (arr == null) return emptyList()
    val list = mutableListOf<ChecklistItem>()
    for (i in 0 until arr.length()) {
      val obj = arr.optJSONObject(i) ?: continue
      list +=
        ChecklistItem(
          id = obj.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
          text = obj.optString("text", ""),
          checked = obj.optBoolean("checked", false),
        )
    }
    return list
  }

  fun exportSvg(doc: GraphDocument, title: String): String {
    if (doc.nodes.isEmpty()) {
      return """<svg xmlns="http://www.w3.org/2000/svg" width="400" height="200"><text x="20" y="40">Empty LinkDraw Graph</text></svg>"""
    }
    val minX = doc.nodes.minOf { it.x - it.w / 2f } - 80f
    val minY = doc.nodes.minOf { it.y - it.h / 2f } - 80f
    val maxX = doc.nodes.maxOf { it.x + it.w / 2f } + 80f
    val maxY = doc.nodes.maxOf { it.y + it.h / 2f } + 80f
    val width = (maxX - minX).coerceAtLeast(300f)
    val height = (maxY - minY).coerceAtLeast(200f)
    val nodeMap = doc.nodes.associateBy { it.id }

    return buildString {
      appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
      appendLine(
        """<svg xmlns="http://www.w3.org/2000/svg" viewBox="$minX $minY $width $height" width="$width" height="$height">"""
      )
      appendLine("""  <title>${escapeXml(title)}</title>""")
      appendLine(
        """  <defs><marker id="arrow" markerWidth="10" markerHeight="10" refX="8" refY="3" orient="auto"><path d="M0,0 L0,6 L9,3 z" fill="#475569"/></marker></defs>"""
      )
      doc.edges.forEach { edge ->
        val a = nodeMap[edge.fromId] ?: return@forEach
        val b = nodeMap[edge.toId] ?: return@forEach
        val marker =
          if (edge.arrow == ArrowType.FORWARD || edge.arrow == ArrowType.BOTH) {
            """ marker-end="url(#arrow)""""
          } else ""
        appendLine(
          """  <line x1="${a.x}" y1="${a.y}" x2="${b.x}" y2="${b.y}" stroke="${edge.colorHex}" stroke-width="${edge.width}"$marker/>"""
        )
        if (edge.label.isNotBlank()) {
          val mx = (a.x + b.x) / 2f
          val my = (a.y + b.y) / 2f - 6f
          appendLine(
            """  <text x="$mx" y="$my" font-family="sans-serif" font-size="11" text-anchor="middle" fill="#334155">${escapeXml(edge.label)}</text>"""
          )
        }
      }
      doc.nodes.forEach { n ->
        val left = n.x - n.w / 2f
        val top = n.y - n.h / 2f
        appendLine(
          """  <rect x="$left" y="$top" width="${n.w}" height="${n.h}" rx="12" fill="${n.colorHex}" stroke="#334155" stroke-width="1.5"/>"""
        )
        val label = if (n.icon.isNotBlank()) "${n.icon} ${n.text}" else n.text
        appendLine(
          """  <text x="${n.x}" y="${n.y + 4}" font-family="sans-serif" font-size="13" text-anchor="middle" fill="${n.textColorHex}">${escapeXml(label)}</text>"""
        )
      }
      appendLine("</svg>")
    }
  }

  fun exportDot(doc: GraphDocument, title: String): String {
    val safeName = title.replace(Regex("[^A-Za-z0-9_]"), "_").ifBlank { "LinkDraw" }
    return buildString {
      appendLine("digraph $safeName {")
      appendLine("  rankdir=LR;")
      doc.nodes.forEach { n ->
        val lbl = n.text.replace("\"", "\\\"")
        appendLine("""  "${n.id}" [label="$lbl", shape="${n.shape.id}", fillcolor="${n.colorHex}", style="filled"];""")
      }
      doc.edges.forEach { e ->
        val lbl = e.label.replace("\"", "\\\"")
        appendLine("""  "${e.fromId}" -> "${e.toId}" [label="$lbl", weight="${e.weight}"];""")
      }
      appendLine("}")
    }
  }

  fun exportGraphMl(doc: GraphDocument): String = buildString {
    appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
    appendLine("""<graphml xmlns="http://graphml.graphdrawing.org/xmlns">""")
    appendLine("""  <key id="label" for="node" attr.name="label" attr.type="string"/>""")
    appendLine("""  <key id="color" for="node" attr.name="color" attr.type="string"/>""")
    appendLine("""  <key id="weight" for="edge" attr.name="weight" attr.type="double"/>""")
    appendLine("""  <graph id="G" edgedefault="directed">""")
    doc.nodes.forEach { n ->
      appendLine("""    <node id="${escapeXml(n.id)}">""")
      appendLine("""      <data key="label">${escapeXml(n.text)}</data>""")
      appendLine("""      <data key="color">${escapeXml(n.colorHex)}</data>""")
      appendLine("""    </node>""")
    }
    doc.edges.forEach { e ->
      appendLine(
        """    <edge id="${escapeXml(e.id)}" source="${escapeXml(e.fromId)}" target="${escapeXml(e.toId)}">"""
      )
      appendLine("""      <data key="weight">${e.weight}</data>""")
      appendLine("""    </edge>""")
    }
    appendLine("""  </graph>""")
    appendLine("""</graphml>""")
  }

  fun exportCsv(doc: GraphDocument): String = buildString {
    appendLine("# NODES")
    appendLine("id,text,shape,color,x,y,tags")
    doc.nodes.forEach { n ->
      val t = n.text.replace("\"", "\"\"")
      val tags = n.tags.joinToString(";").replace("\"", "\"\"")
      appendLine(""""${n.id}","$t","${n.shape.id}","${n.colorHex}",${n.x},${n.y},"$tags"""")
    }
    appendLine()
    appendLine("# EDGES")
    appendLine("id,source,target,label,weight,type")
    doc.edges.forEach { e ->
      val l = e.label.replace("\"", "\"\"")
      appendLine(""""${e.id}","${e.fromId}","${e.toId}","$l",${e.weight},"${e.type.id}"""")
    }
  }

  private fun escapeXml(s: String): String =
    s.replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&apos;")
}
