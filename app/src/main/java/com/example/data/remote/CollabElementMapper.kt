package com.example.data.remote

import com.example.model.*
import com.google.firebase.Timestamp
import kotlin.math.max

object CollabElementMapper {

  const val CURRENT_SCHEMA_VERSION = 1L
  const val PLATFORM_ANDROID = "android"

  val SUPPORTED_KINDS: Set<String> = setOf("node", "edge", "sticky", "stroke")

  private val KNOWN_CORE_TABS: Set<String> = setOf("canvas", "notes", "utilities", "projects")

  private val KNOWN_SHAPE_IDS: Set<String> = NodeShape.entries.map { it.id }.toSet()
  private val KNOWN_EDGE_TYPE_IDS: Set<String> = EdgeType.entries.map { it.id }.toSet()
  private val KNOWN_ARROW_IDS: Set<String> = ArrowType.entries.map { it.id }.toSet()
  private val KNOWN_LINE_STYLE_IDS: Set<String> = LineStyle.entries.map { it.id }.toSet()

  private val KNOWN_NODE_KEYS: Set<String> =
    setOf(
      "id",
      "x", "left", "posX",
      "y", "top", "posY",
      "w", "width",
      "h", "height",
      "text", "label", "title", "name",
      "shape",
      "colorHex", "color", "fill", "backgroundColor",
      "textColorHex", "textColor", "fontColor",
      "fontSize", "fs",
      "icon", "iconPosition",
      "tags", "metadata",
      "richNoteText", "richNoteChecklist",
      "collapsed", "layer", "locked",
      "createdAtMs", "createdAt", "updatedAtMs", "updatedAt",
      "tableHeaders", "tableRows",
      "progress", "badge",
    )

  private val KNOWN_EDGE_KEYS: Set<String> =
    setOf(
      "id",
      "fromId", "source", "sourceId", "from", "a",
      "toId", "target", "targetId", "to", "b",
      "label", "text", "title",
      "colorHex", "color", "stroke",
      "width", "strokeWidth",
      "arrow", "arrowType",
      "type", "edgeType", "routing",
      "lineStyle", "dashStyle",
      "weight", "layer",
      "createdAtMs", "createdAt", "updatedAtMs", "updatedAt",
    )

  private val KNOWN_STICKY_KEYS: Set<String> =
    setOf(
      "id",
      "x", "left", "posX",
      "y", "top", "posY",
      "w", "width",
      "h", "height",
      "text", "content", "body",
      "colorHex", "color", "backgroundColor",
      "layer",
      "createdAtMs", "createdAt", "updatedAtMs", "updatedAt",
    )

  private val KNOWN_STROKE_KEYS: Set<String> =
    setOf(
      "id",
      "points",
      "colorHex", "color", "stroke",
      "width", "strokeWidth",
      "highlighter", "isHighlighter",
      "layer",
    )

  private val COLLAB_PALETTE =
    listOf("#2563EB", "#059669", "#D97706", "#DC2626", "#7C3AED", "#0891B2", "#DB2777")

  fun colorForUid(uid: String): String {
    val idx = (uid.hashCode() and 0x7FFFFFFF) % COLLAB_PALETTE.size
    return COLLAB_PALETTE[idx]
  }

  fun sanitizeElementId(rawId: String, kind: String): String {
    val cleaned = rawId.replace(Regex("[^a-zA-Z0-9_\\-.:@]"), "_").take(128)
    return if (cleaned.isNotBlank() && cleaned != "." && cleaned != "..") {
      cleaned
    } else {
      "${kind}_${System.currentTimeMillis()}"
    }
  }

  private fun numVal(p: Map<String, Any>, vararg keys: String): Float? {
    for (k in keys) {
      val v = p[k]
      if (v is Number) return v.toFloat()
    }
    return null
  }

  private fun strVal(p: Map<String, Any>, vararg keys: String): String? {
    for (k in keys) {
      val v = p[k]
      if (v is String) return v
    }
    return null
  }

  private fun timestampMsVal(p: Map<String, Any>, vararg keys: String): Long? {
    for (k in keys) {
      when (val v = p[k]) {
        is Number -> {
          val lv = v.toLong()
          if (lv > 0L) return lv
        }
        is Timestamp -> {
          val lv = v.toDate().time
          if (lv > 0L) return lv
        }
      }
    }
    return null
  }

  fun nodeToPayload(n: GraphNode): Map<String, Any> {
    val map = n.extraPayload.toMutableMap()
    val effectiveShapeId =
      if (
        n.rawShapeId != null &&
          n.rawShapeId !in KNOWN_SHAPE_IDS &&
          n.shape == NodeShape.ROUNDED
      ) {
        n.rawShapeId
      } else {
        n.shape.id
      }
    map["id"] = n.id
    map["x"] = n.x.toDouble()
    map["y"] = n.y.toDouble()
    map["w"] = n.w.toDouble()
    map["h"] = n.h.toDouble()
    map["text"] = n.text
    map["shape"] = effectiveShapeId
    map["colorHex"] = n.colorHex
    map["textColorHex"] = n.textColorHex
    map["fontSize"] = n.fontSize.toDouble()
    map["icon"] = n.icon
    map["iconPosition"] = n.iconPosition.id
    map["tags"] = n.tags.take(20)
    map["metadata"] = n.metadata
    map["richNoteText"] = n.richNotes.text
    map["richNoteChecklist"] =
      n.richNotes.checklist.map { c ->
        mapOf("id" to c.id, "text" to c.text, "checked" to c.checked)
      }
    map["collapsed"] = n.collapsed
    map["layer"] = n.layer.id
    map["locked"] = n.locked
    map["createdAtMs"] = n.createdAt
    map["updatedAtMs"] = n.updatedAt
    n.tableData?.let { td ->
      map["tableHeaders"] = td.headers
      // Firestore does not allow nested arrays; encode each row as { cells: [...] }
      map["tableRows"] = td.rows.map { row -> mapOf("cells" to row) }
    }
    n.progress?.let { map["progress"] = it.toLong() }
    n.badge?.let { map["badge"] = it }
    return map
  }

  fun edgeToPayload(e: GraphEdge): Map<String, Any> {
    val map = e.extraPayload.toMutableMap()
    val effectiveType =
      if (e.rawTypeId != null && e.rawTypeId !in KNOWN_EDGE_TYPE_IDS && e.type == EdgeType.CURVED) {
        e.rawTypeId
      } else {
        e.type.id
      }
    val effectiveArrow =
      if (e.rawArrowId != null && e.rawArrowId !in KNOWN_ARROW_IDS && e.arrow == ArrowType.FORWARD) {
        e.rawArrowId
      } else {
        e.arrow.id
      }
    val effectiveLineStyle =
      if (
        e.rawLineStyleId != null &&
          e.rawLineStyleId !in KNOWN_LINE_STYLE_IDS &&
          e.lineStyle == LineStyle.SOLID
      ) {
        e.rawLineStyleId
      } else {
        e.lineStyle.id
      }

    map["id"] = e.id
    map["fromId"] = e.fromId
    map["toId"] = e.toId
    map["label"] = e.label
    map["colorHex"] = e.colorHex
    map["width"] = e.width.toDouble()
    map["arrow"] = effectiveArrow
    map["type"] = effectiveType
    map["lineStyle"] = effectiveLineStyle
    map["weight"] = e.weight.toDouble()
    map["layer"] = e.layer.id
    map["createdAtMs"] = e.createdAt
    map["updatedAtMs"] = e.updatedAt
    return map
  }

  fun stickyToPayload(s: StickyNote): Map<String, Any> {
    val map = s.extraPayload.toMutableMap()
    map["id"] = s.id
    map["x"] = s.x.toDouble()
    map["y"] = s.y.toDouble()
    map["w"] = s.w.toDouble()
    map["h"] = s.h.toDouble()
    map["text"] = s.text
    map["colorHex"] = s.colorHex
    map["layer"] = s.layer.id
    map["createdAtMs"] = s.createdAt
    map["updatedAtMs"] = s.updatedAt
    return map
  }

  fun strokeToPayload(st: FreehandStroke): Map<String, Any> {
    val map = st.extraPayload.toMutableMap()
    val sampled =
      if (st.points.size <= 350) st.points
      else st.points.filterIndexed { index, _ -> index % ((st.points.size / 350) + 1) == 0 }
    map["id"] = st.id
    map["points"] = sampled.map { pt -> mapOf("x" to pt.x.toDouble(), "y" to pt.y.toDouble()) }
    map["colorHex"] = st.colorHex
    map["width"] = st.width.toDouble()
    map["highlighter"] = st.isHighlighter
    map["layer"] = st.layer.id
    return map
  }

  fun payloadToNode(id: String, p: Map<String, Any>): GraphNode {
    val tagsRaw = p["tags"] as? List<*>
    val tags = tagsRaw?.mapNotNull { it as? String } ?: emptyList()

    val metaRaw = p["metadata"] as? Map<*, *>
    val metadata =
      metaRaw?.entries?.mapNotNull { (k, v) ->
        val ks = k as? String ?: return@mapNotNull null
        ks to (v?.toString() ?: "")
      }?.toMap() ?: emptyMap()

    val checklistRaw = p["richNoteChecklist"] as? List<*>
    val checklist =
      checklistRaw?.mapNotNull { item ->
        val m = item as? Map<*, *> ?: return@mapNotNull null
        ChecklistItem(
          id = (m["id"] as? String).orEmpty().ifBlank { "chk_${m.hashCode()}" },
          text = (m["text"] as? String).orEmpty(),
          checked = (m["checked"] as? Boolean) ?: false,
        )
      } ?: emptyList()

    val headersRaw = p["tableHeaders"] as? List<*>
    val rowsRaw = p["tableRows"] as? List<*>
    val tableData =
      if (headersRaw != null) {
        val headers = headersRaw.map { it?.toString() ?: "" }
        val rows =
          rowsRaw?.map { rowObj ->
            when (rowObj) {
              is Map<*, *> -> (rowObj["cells"] as? List<*>)?.map { it?.toString() ?: "" } ?: emptyList()
              is List<*> -> rowObj.map { it?.toString() ?: "" }
              else -> emptyList()
            }
          } ?: emptyList()
        TableData(headers = headers, rows = rows)
      } else null

    val now = System.currentTimeMillis()
    val createdAt = timestampMsVal(p, "createdAtMs", "createdAt") ?: now
    val updatedAt = timestampMsVal(p, "updatedAtMs", "updatedAt") ?: createdAt
    val rawShape = strVal(p, "shape")
    val extra = p.filterKeys { it !in KNOWN_NODE_KEYS }

    return GraphNode(
      id = id,
      x = numVal(p, "x", "left", "posX") ?: 0f,
      y = numVal(p, "y", "top", "posY") ?: 0f,
      w = (numVal(p, "w", "width") ?: 155f).coerceAtLeast(60f),
      h = (numVal(p, "h", "height") ?: 68f).coerceAtLeast(40f),
      text = strVal(p, "text", "label", "title", "name") ?: "Node",
      shape = NodeShape.fromId(rawShape ?: "rounded"),
      colorHex = strVal(p, "colorHex", "color", "fill", "backgroundColor") ?: "#DBEAFE",
      textColorHex = strVal(p, "textColorHex", "textColor", "fontColor") ?: "#0F172A",
      fontSize = numVal(p, "fontSize", "fs") ?: 13f,
      icon = strVal(p, "icon") ?: "",
      iconPosition = IconPosition.fromId(strVal(p, "iconPosition") ?: "inline"),
      tags = tags,
      metadata = metadata,
      richNotes = RichNodeNote(text = strVal(p, "richNoteText") ?: "", checklist = checklist),
      collapsed = (p["collapsed"] as? Boolean) ?: false,
      tableData = tableData,
      progress = (p["progress"] as? Number)?.toInt(),
      badge = p["badge"] as? String,
      layer = CanvasLayerName.fromId(strVal(p, "layer") ?: "Data"),
      locked = (p["locked"] as? Boolean) ?: false,
      createdAt = createdAt,
      updatedAt = updatedAt,
      rawShapeId = rawShape?.takeIf { it !in KNOWN_SHAPE_IDS },
      extraPayload = extra,
    )
  }

  fun payloadToEdge(id: String, p: Map<String, Any>): GraphEdge {
    val now = System.currentTimeMillis()
    val createdAt = timestampMsVal(p, "createdAtMs", "createdAt") ?: now
    val updatedAt = timestampMsVal(p, "updatedAtMs", "updatedAt") ?: createdAt
    val rawType = strVal(p, "type", "edgeType", "routing")
    val rawArrow = strVal(p, "arrow", "arrowType")
    val rawStyle = strVal(p, "lineStyle", "dashStyle")
    val extra = p.filterKeys { it !in KNOWN_EDGE_KEYS }

    return GraphEdge(
      id = id,
      fromId = strVal(p, "fromId", "source", "sourceId", "from", "a") ?: "",
      toId = strVal(p, "toId", "target", "targetId", "to", "b") ?: "",
      label = strVal(p, "label", "text", "title") ?: "",
      colorHex = strVal(p, "colorHex", "color", "stroke") ?: "#475569",
      width = numVal(p, "width", "strokeWidth") ?: 2.4f,
      arrow = ArrowType.fromId(rawArrow ?: "forward"),
      type = EdgeType.fromId(rawType ?: "curved"),
      lineStyle = LineStyle.fromId(rawStyle ?: "solid"),
      weight = (p["weight"] as? Number)?.toFloat() ?: 1f,
      layer = CanvasLayerName.fromId(strVal(p, "layer") ?: "Data"),
      createdAt = createdAt,
      updatedAt = updatedAt,
      rawTypeId = rawType?.takeIf { it !in KNOWN_EDGE_TYPE_IDS },
      rawArrowId = rawArrow?.takeIf { it !in KNOWN_ARROW_IDS },
      rawLineStyleId = rawStyle?.takeIf { it !in KNOWN_LINE_STYLE_IDS },
      extraPayload = extra,
    )
  }

  fun payloadToSticky(id: String, p: Map<String, Any>): StickyNote {
    val now = System.currentTimeMillis()
    val createdAt = timestampMsVal(p, "createdAtMs", "createdAt") ?: now
    val updatedAt = timestampMsVal(p, "updatedAtMs", "updatedAt") ?: createdAt
    val extra = p.filterKeys { it !in KNOWN_STICKY_KEYS }

    return StickyNote(
      id = id,
      x = numVal(p, "x", "left", "posX") ?: 0f,
      y = numVal(p, "y", "top", "posY") ?: 0f,
      w = (numVal(p, "w", "width") ?: 170f).coerceAtLeast(60f),
      h = (numVal(p, "h", "height") ?: 120f).coerceAtLeast(48f),
      text = strVal(p, "text", "content", "body") ?: "",
      colorHex = strVal(p, "colorHex", "color", "backgroundColor") ?: "#FEF08A",
      layer = CanvasLayerName.fromId(strVal(p, "layer") ?: "Annotations"),
      createdAt = createdAt,
      updatedAt = updatedAt,
      extraPayload = extra,
    )
  }

  fun payloadToStroke(id: String, p: Map<String, Any>): FreehandStroke {
    val ptsRaw = p["points"] as? List<*>
    val points =
      ptsRaw?.mapNotNull { item ->
        when (item) {
          is Map<*, *> -> {
            val x = (item["x"] as? Number)?.toFloat() ?: return@mapNotNull null
            val y = (item["y"] as? Number)?.toFloat() ?: return@mapNotNull null
            StrokePoint(x, y)
          }
          is List<*> -> {
            if (item.size < 2) return@mapNotNull null
            val x = (item[0] as? Number)?.toFloat() ?: return@mapNotNull null
            val y = (item[1] as? Number)?.toFloat() ?: return@mapNotNull null
            StrokePoint(x, y)
          }
          else -> null
        }
      } ?: emptyList()
    val extra = p.filterKeys { it !in KNOWN_STROKE_KEYS }
    return FreehandStroke(
      id = id,
      points = points,
      colorHex = strVal(p, "colorHex", "color", "stroke") ?: "#EF4444",
      width = numVal(p, "width", "strokeWidth") ?: 4f,
      isHighlighter = (p["highlighter"] as? Boolean) ?: (p["isHighlighter"] as? Boolean) ?: false,
      layer = CanvasLayerName.fromId(strVal(p, "layer") ?: "Annotations"),
      extraPayload = extra,
    )
  }

  fun elemToPlaceholder(elem: BoardElement): UnsupportedElementPlaceholder {
    val p = elem.payload
    var x = numVal(p, "x", "left", "posX", "centerX")
    var y = numVal(p, "y", "top", "posY", "centerY")
    var w = numVal(p, "w", "width") ?: 176f
    var h = numVal(p, "h", "height") ?: 80f

    if (x == null || y == null) {
      val pts = (p["points"] as? List<*>)?.mapNotNull { item ->
        val m = item as? Map<*, *> ?: return@mapNotNull null
        val px = (m["x"] as? Number)?.toFloat() ?: return@mapNotNull null
        val py = (m["y"] as? Number)?.toFloat() ?: return@mapNotNull null
        px to py
      }
      if (!pts.isNullOrEmpty()) {
        val minX = pts.minOf { it.first }
        val maxX = pts.maxOf { it.first }
        val minY = pts.minOf { it.second }
        val maxY = pts.maxOf { it.second }
        x = (minX + maxX) / 2f
        y = (minY + maxY) / 2f
        w = max(120f, maxX - minX)
        h = max(60f, maxY - minY)
      }
    }

    val label =
      strVal(p, "title", "text", "label", "name", "caption", "url")
        ?.takeIf { it.isNotBlank() }
        ?: "${elem.kind} (${elem.id.take(8)})"
    val color = strVal(p, "colorHex", "color", "fill", "backgroundColor") ?: "#FEF3C7"

    return UnsupportedElementPlaceholder(
      id = elem.id,
      kind = elem.kind,
      x = x ?: 0f,
      y = y ?: 0f,
      w = w.coerceAtLeast(80f),
      h = h.coerceAtLeast(48f),
      label = label,
      colorHex = color,
      updatedByName = elem.updatedByName,
      sourcePlatform = elem.sourcePlatform,
      payloadKeys = p.keys.map { it }.sorted(),
      rawPayload = p,
    )
  }

  fun assembleDocument(
    board: CollabBoard?,
    elements: List<BoardElement>,
    existingLayers: List<CanvasLayerState> = GraphDocument().layers,
  ): GraphDocument {
    val nodes = mutableListOf<GraphNode>()
    val edges = mutableListOf<GraphEdge>()
    val stickies = mutableListOf<StickyNote>()
    val strokes = mutableListOf<FreehandStroke>()
    val unsupported = mutableListOf<UnsupportedElementPlaceholder>()

    elements.forEach { elem ->
      when (elem.kind) {
        "node" -> nodes += payloadToNode(elem.id, elem.payload)
        "edge" -> edges += payloadToEdge(elem.id, elem.payload)
        "sticky" -> stickies += payloadToSticky(elem.id, elem.payload)
        "stroke" -> strokes += payloadToStroke(elem.id, elem.payload)
        else -> unsupported += elemToPlaceholder(elem)
      }
    }

    nodes.sortBy { it.createdAt }
    edges.sortBy { it.createdAt }
    stickies.sortBy { it.createdAt }

    val settings =
      if (board != null) {
        GraphSettings(
          gridSnap = board.gridSnap,
          gridSize = board.gridSize.toInt().coerceIn(8, 96),
          flowAnimation = board.flowAnimation,
        )
      } else {
        GraphSettings()
      }

    return GraphDocument(
      nodes = nodes,
      edges = edges,
      stickyNotes = stickies,
      freehandStrokes = strokes,
      layers = existingLayers,
      settings = settings,
      unsupportedElements = unsupported,
    )
  }

  fun analyzeCompatibility(
    board: CollabBoard?,
    elements: List<BoardElement>,
    presence: List<BoardPresence>,
    notes: List<CollabNote> = emptyList(),
  ): FeatureCompatibilityReport {
    val findings = mutableListOf<UnknownFeatureFinding>()
    val placeholders = mutableListOf<UnsupportedElementPlaceholder>()

    val unknownByKind = linkedMapOf<String, MutableList<BoardElement>>()
    val unknownShapes = linkedMapOf<String, Int>()
    val unknownEdgeTypes = linkedMapOf<String, Int>()
    val unknownArrows = linkedMapOf<String, Int>()
    val unknownLineStyles = linkedMapOf<String, Int>()
    val unknownPayloadKeysByKind = linkedMapOf<String, MutableSet<String>>()
    val unknownPayloadCountByKey = linkedMapOf<String, Int>()

    elements.forEach { elem ->
      when (elem.kind) {
        "node" -> {
          val shape = strVal(elem.payload, "shape")
          if (!shape.isNullOrBlank() && shape !in KNOWN_SHAPE_IDS) {
            unknownShapes[shape] = (unknownShapes[shape] ?: 0) + 1
          }
          elem.payload.keys.filter { it !in KNOWN_NODE_KEYS }.forEach { k ->
            val tag = "node.$k"
            unknownPayloadCountByKey[tag] = (unknownPayloadCountByKey[tag] ?: 0) + 1
            unknownPayloadKeysByKind.getOrPut("node") { linkedSetOf() }.add(k)
          }
        }
        "edge" -> {
          val et = strVal(elem.payload, "type", "edgeType", "routing")
          if (!et.isNullOrBlank() && et !in KNOWN_EDGE_TYPE_IDS) {
            unknownEdgeTypes[et] = (unknownEdgeTypes[et] ?: 0) + 1
          }
          val ar = strVal(elem.payload, "arrow", "arrowType")
          if (!ar.isNullOrBlank() && ar !in KNOWN_ARROW_IDS) {
            unknownArrows[ar] = (unknownArrows[ar] ?: 0) + 1
          }
          val ls = strVal(elem.payload, "lineStyle", "dashStyle")
          if (!ls.isNullOrBlank() && ls !in KNOWN_LINE_STYLE_IDS) {
            unknownLineStyles[ls] = (unknownLineStyles[ls] ?: 0) + 1
          }
          elem.payload.keys.filter { it !in KNOWN_EDGE_KEYS }.forEach { k ->
            val tag = "edge.$k"
            unknownPayloadCountByKey[tag] = (unknownPayloadCountByKey[tag] ?: 0) + 1
            unknownPayloadKeysByKind.getOrPut("edge") { linkedSetOf() }.add(k)
          }
        }
        "sticky" -> {
          elem.payload.keys.filter { it !in KNOWN_STICKY_KEYS }.forEach { k ->
            val tag = "sticky.$k"
            unknownPayloadCountByKey[tag] = (unknownPayloadCountByKey[tag] ?: 0) + 1
            unknownPayloadKeysByKind.getOrPut("sticky") { linkedSetOf() }.add(k)
          }
        }
        "stroke" -> {
          elem.payload.keys.filter { it !in KNOWN_STROKE_KEYS }.forEach { k ->
            val tag = "stroke.$k"
            unknownPayloadCountByKey[tag] = (unknownPayloadCountByKey[tag] ?: 0) + 1
            unknownPayloadKeysByKind.getOrPut("stroke") { linkedSetOf() }.add(k)
          }
        }
        else -> {
          unknownByKind.getOrPut(elem.kind) { mutableListOf() }.add(elem)
          placeholders += elemToPlaceholder(elem)
        }
      }
    }

    unknownByKind.forEach { (kind, list) ->
      val allKeys = list.flatMap { it.payload.keys }.distinct().sorted()
      val sampleSrc = list.firstNotNullOfOrNull { it.sourcePlatform } ?: "web"
      findings +=
        UnknownFeatureFinding(
          category = "New Element Kind",
          featureKey = kind,
          sampleCount = list.size,
          details = "${list.size} '$kind' element(s) added from $sampleSrc. Rendered as read-only placeholder(s) on canvas and preserved safely.",
          samplePayloadKeys = allKeys,
          suggestedPrompt =
            "Implement new element kind '$kind' in LinkDraw Android (model, CollabElementMapper, CanvasRenderer, and Inspector) with payload keys: ${allKeys.joinToString(", ")}.",
        )
    }

    unknownShapes.forEach { (shapeId, count) ->
      findings +=
        UnknownFeatureFinding(
          category = "New Node Shape",
          featureKey = shapeId,
          sampleCount = count,
          details = "$count node(s) use shape '$shapeId' not yet in Android NodeShape enum.",
          suggestedPrompt = "Add NodeShape '$shapeId' to LinkDrawModels.kt and drawNodeShape() in CanvasRenderer.kt.",
        )
    }

    unknownEdgeTypes.forEach { (edgeTypeId, count) ->
      findings +=
        UnknownFeatureFinding(
          category = "New Edge Routing",
          featureKey = edgeTypeId,
          sampleCount = count,
          details = "$count edge(s) use routing type '$edgeTypeId'.",
          suggestedPrompt = "Add EdgeType '$edgeTypeId' to LinkDrawModels.kt and EdgeGeometryHelper in CanvasRenderer.kt.",
        )
    }

    unknownArrows.forEach { (arrowId, count) ->
      findings +=
        UnknownFeatureFinding(
          category = "New Arrow Style",
          featureKey = arrowId,
          sampleCount = count,
          details = "$count edge(s) use arrow style '$arrowId'.",
          suggestedPrompt = "Add ArrowType '$arrowId' to LinkDrawModels.kt and drawArrowHead() in CanvasRenderer.kt.",
        )
    }

    unknownLineStyles.forEach { (styleId, count) ->
      findings +=
        UnknownFeatureFinding(
          category = "New Line Style",
          featureKey = styleId,
          sampleCount = count,
          details = "$count edge(s) use line style '$styleId'.",
          suggestedPrompt = "Add LineStyle '$styleId' to LinkDrawModels.kt and CanvasRenderer.kt.",
        )
    }

    unknownPayloadKeysByKind.forEach { (kind, keys) ->
      val sortedKeys = keys.sorted()
      val totalOccurrences = sortedKeys.sumOf { k -> unknownPayloadCountByKey["$kind.$k"] ?: 1 }
      findings +=
        UnknownFeatureFinding(
          category = "New ${kind.replaceFirstChar { it.uppercase() }} Fields",
          featureKey = sortedKeys.joinToString(", "),
          sampleCount = totalOccurrences,
          details = "Extra payload fields detected on '$kind': ${sortedKeys.joinToString(", ")}. Preserved losslessly on edits.",
          samplePayloadKeys = sortedKeys,
          suggestedPrompt = "Support new '$kind' payload fields [${sortedKeys.joinToString(", ")}] in LinkDraw Android UI and renderer.",
        )
    }

    val extraTabs = presence.map { it.activeTab }.filter { it.isNotBlank() && it !in KNOWN_CORE_TABS }.distinct()
    extraTabs.forEach { tab ->
      findings +=
        UnknownFeatureFinding(
          category = "New Studio Tab",
          featureKey = tab,
          sampleCount = presence.count { it.activeTab == tab },
          details = "A collaborator is active on studio tab '$tab' (not present in Android).",
          suggestedPrompt = "Add the '$tab' feature/tab to LinkDraw Android to match the Web studio.",
        )
    }

    if (board != null && (board.schemaVersion > CURRENT_SCHEMA_VERSION || board.extensions.isNotEmpty())) {
      val extKeys = board.extensions.keys.sorted()
      findings +=
        UnknownFeatureFinding(
          category = "Board Extensions / Schema v${board.schemaVersion}",
          featureKey = if (extKeys.isNotEmpty()) extKeys.joinToString(", ") else "schema_v${board.schemaVersion}",
          sampleCount = extKeys.size.coerceAtLeast(1),
          details =
            if (extKeys.isNotEmpty()) {
              "Board has schemaVersion=${board.schemaVersion} with extension keys: ${extKeys.joinToString(", ")}."
            } else {
              "Board schemaVersion=${board.schemaVersion} is newer than Android schema v$CURRENT_SCHEMA_VERSION."
            },
          samplePayloadKeys = extKeys,
          suggestedPrompt =
            "Implement board schema v${board.schemaVersion} extensions [${extKeys.joinToString(", ")}] in LinkDraw Android.",
        )
    }

    val unknownNoteTypes =
      notes
        .map { it.noteType }
        .filter { nt -> nt.isNotBlank() && NoteType.entries.none { it.label.equals(nt, ignoreCase = true) } }
        .distinct()
        .sorted()
    if (unknownNoteTypes.isNotEmpty()) {
      findings +=
        UnknownFeatureFinding(
          category = "New Note Type",
          featureKey = unknownNoteTypes.joinToString(", "),
          sampleCount = unknownNoteTypes.size,
          details = "Web notes use unknown type(s): ${unknownNoteTypes.joinToString(", ")}. Preserved losslessly.",
          suggestedPrompt =
            "Add NoteType entries [${unknownNoteTypes.joinToString(", ")}] to LinkDrawModels.kt and NotesScreen.kt.",
        )
    }

    val unknownNotePriorities =
      notes
        .map { it.priority }
        .filter { pr -> pr.isNotBlank() && NotePriority.entries.none { it.id.equals(pr, ignoreCase = true) } }
        .distinct()
        .sorted()
    if (unknownNotePriorities.isNotEmpty()) {
      findings +=
        UnknownFeatureFinding(
          category = "New Note Priority",
          featureKey = unknownNotePriorities.joinToString(", "),
          sampleCount = unknownNotePriorities.size,
          details = "Web notes use unknown priority value(s): ${unknownNotePriorities.joinToString(", ")}.",
          suggestedPrompt =
            "Add NotePriority entries [${unknownNotePriorities.joinToString(", ")}] to LinkDrawModels.kt.",
        )
    }

    val noteExtKeys = notes.flatMap { it.extensions.keys }.distinct().sorted()
    if (noteExtKeys.isNotEmpty()) {
      findings +=
        UnknownFeatureFinding(
          category = "New Note Fields",
          featureKey = noteExtKeys.joinToString(", "),
          sampleCount = notes.count { it.extensions.isNotEmpty() },
          details = "Extra Web note extension fields detected: ${noteExtKeys.joinToString(", ")}. Preserved losslessly.",
          samplePayloadKeys = noteExtKeys,
          suggestedPrompt =
            "Support new Web note fields [${noteExtKeys.joinToString(", ")}] in StudioNote & NotesScreen.kt.",
        )
    }

    return FeatureCompatibilityReport(
      totalRemoteElements = elements.size,
      boardSchemaVersion = board?.schemaVersion ?: CURRENT_SCHEMA_VERSION,
      findings = findings,
      unsupportedPlaceholders = placeholders,
    )
  }

  fun documentElementEntries(doc: GraphDocument): Map<String, Pair<String, Map<String, Any>>> {
    val out = linkedMapOf<String, Pair<String, Map<String, Any>>>()
    doc.nodes.forEach { n -> out[sanitizeElementId(n.id, "node")] = "node" to nodeToPayload(n) }
    doc.edges.forEach { e -> out[sanitizeElementId(e.id, "edge")] = "edge" to edgeToPayload(e) }
    doc.stickyNotes.forEach { s ->
      out[sanitizeElementId(s.id, "sticky")] = "sticky" to stickyToPayload(s)
    }
    doc.freehandStrokes.forEach { st ->
      out[sanitizeElementId(st.id, "stroke")] = "stroke" to strokeToPayload(st)
    }
    return out
  }

  fun collabNoteToDomain(cn: CollabNote): StudioNote {
    val knownType = NoteType.entries.any { it.label.equals(cn.noteType, ignoreCase = true) }
    val knownPriority = NotePriority.entries.any { it.id.equals(cn.priority, ignoreCase = true) }
    val checklist =
      cn.checklist.mapIndexedNotNull { idx, item ->
        val txt = (item["text"] as? String) ?: return@mapIndexedNotNull null
        ChecklistItem(
          id = (item["id"] as? String)?.takeIf { it.isNotBlank() } ?: "chk_$idx",
          text = txt,
          checked = (item["checked"] as? Boolean) ?: false,
        )
      }
    val now = System.currentTimeMillis()
    return StudioNote(
      id = cn.id,
      projectId = cn.projectId,
      title = cn.title.ifBlank { "Untitled Note" },
      body = cn.body,
      noteType = NoteType.fromLabel(cn.noteType),
      folder = cn.folder.ifBlank { "General" },
      tags = cn.tags,
      priority = NotePriority.fromId(cn.priority),
      colorHex = cn.colorHex.ifBlank { "#EEF2FF" },
      pinned = cn.pinned,
      archived = cn.archived,
      linkedNodeId = cn.linkedNodeId,
      linkedNodeName = cn.linkedNodeName,
      sourceUrl = cn.sourceUrl,
      checklist = checklist,
      wordCount = cn.wordCount.toInt(),
      updatedAt = cn.updatedAt?.toDate()?.time ?: now,
      createdAt = cn.createdAt?.toDate()?.time ?: now,
      rawNoteType = cn.noteType.takeIf { !knownType && it.isNotBlank() },
      rawPriority = cn.priority.takeIf { !knownPriority && it.isNotBlank() },
      extensions = cn.extensions,
    )
  }

  fun studioNoteToFields(
    note: StudioNote,
    uid: String,
    updaterName: String,
  ): MutableMap<String, Any> {
    val words = note.body.trim().split(Regex("\\s+")).count { it.isNotBlank() }.toLong()
    val effectiveType =
      if (
        note.rawNoteType != null &&
          NoteType.entries.none { it.label.equals(note.rawNoteType, ignoreCase = true) } &&
          note.noteType == NoteType.OBSERVATION
      ) {
        note.rawNoteType.take(40)
      } else {
        note.noteType.label
      }
    val effectivePriority =
      if (
        note.rawPriority != null &&
          NotePriority.entries.none { it.id.equals(note.rawPriority, ignoreCase = true) } &&
          note.priority == NotePriority.NORMAL
      ) {
        note.rawPriority.take(24)
      } else {
        note.priority.id
      }
    val cleanTitle = note.title.trim().ifBlank { "Untitled Note" }.take(200)
    val cleanFolder = note.folder.trim().ifBlank { "General" }.take(80)
    val cleanUpdater = updaterName.trim().ifBlank { "Analyst" }.take(80)

    val map =
      mutableMapOf<String, Any>(
        "title" to cleanTitle,
        "body" to note.body.take(20000),
        "noteType" to effectiveType,
        "folder" to cleanFolder,
        "tags" to note.tags.take(30),
        "priority" to effectivePriority,
        "colorHex" to note.colorHex.ifBlank { "#EEF2FF" }.take(16),
        "pinned" to note.pinned,
        "archived" to note.archived,
        "sourceUrl" to note.sourceUrl.take(500),
        "checklist" to
          note.checklist.take(100).map { c ->
            mapOf("id" to c.id, "text" to c.text, "checked" to c.checked)
          },
        "wordCount" to words,
        "updatedByUid" to uid,
        "updatedByName" to cleanUpdater,
        "schemaVersion" to CURRENT_SCHEMA_VERSION,
        "sourcePlatform" to PLATFORM_ANDROID,
      )
    note.projectId?.takeIf { it.isNotBlank() }?.let { map["projectId"] = it.take(128) }
    note.linkedNodeId?.takeIf { it.isNotBlank() }?.let { map["linkedNodeId"] = it.take(128) }
    note.linkedNodeName?.takeIf { it.isNotBlank() }?.let { map["linkedNodeName"] = it.take(120) }
    if (note.extensions.isNotEmpty()) {
      map["extensions"] = note.extensions
    }
    return map
  }
}
