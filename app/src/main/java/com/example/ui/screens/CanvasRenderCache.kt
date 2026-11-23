package com.example.ui.screens

import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.example.engine.GraphAlgorithms
import com.example.model.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

private val colorCache = ConcurrentHashMap<String, Color>()

fun parseHexColor(hex: String, fallback: Color = Color(0xFFDBEAFE)): Color {
  val key = "$hex|${fallback.value}"
  colorCache[key]?.let { return it }
  val parsed = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(fallback)
  if (colorCache.size < 512) {
    colorCache[key] = parsed
  }
  return parsed
}

data class FocalViewportTransform(
  val pan: Offset,
  val zoom: Float,
)

/**
 * Adjusts [pan] and [zoom] simultaneously so the world coordinate beneath [focalScreenPoint]
 * stays pinned under the user's fingers (or screen center when using toolbar zoom buttons).
 */
fun computeFocalZoomTransform(
  currentPan: Offset,
  currentZoom: Float,
  zoomChange: Float,
  panChange: Offset,
  focalScreenPoint: Offset,
  canvasCenter: Offset,
  minZoom: Float = 0.25f,
  maxZoom: Float = 3.5f,
): FocalViewportTransform {
  val safeOldZoom = currentZoom.coerceIn(minZoom, maxZoom)
  val newZoom = (safeOldZoom * zoomChange).coerceIn(minZoom, maxZoom)
  val scaleRatio = newZoom / safeOldZoom
  val newPan =
    (focalScreenPoint - canvasCenter) * (1f - scaleRatio) + currentPan * scaleRatio + panChange
  return FocalViewportTransform(pan = newPan, zoom = newZoom)
}

const val TEXT_CACHE_WORLD_SCALE = 2f
private val whitespaceRegex = Regex("\\s+")

data class PreparedNodeText(
  val layout: StaticLayout,
  val textPaint: TextPaint,
  val boxCenterXOffset: Float,
  val boxCenterYOffset: Float,
  val widthPx: Int,
  val heightPx: Int,
  val iconCenterYOffset: Float?,
  val iconSizeWorldPx: Float,
)

data class PreparedTableCellText(
  val title: String,
  val titleSizePx: Float,
  val cellSizePx: Float,
  val headers: List<String>,
  val rows: List<List<String>>,
)

data class PreparedStickyText(
  val layout: StaticLayout,
  val textPaint: TextPaint,
)

data class PreparedEdgeEntry(
  val edge: GraphEdge,
  val fromNode: GraphNode,
  val toNode: GraphNode,
  val bundleIndex: Int,
  val groupSize: Int,
  val staticGeom: EdgeWorldGeometry,
  val staticForwardArrow: Pair<Offset, Offset>?,
  val staticBackwardArrow: Pair<Offset, Offset>?,
  val labelText: String,
)

data class PreparedGraphRenderState(
  val doc: GraphDocument,
  val layerVisible: Map<CanvasLayerName, Boolean>,
  val hiddenByCollapse: Set<String>,
  val nodesWithChildren: Set<String>,
  val hiddenCountByNode: Map<String, Int>,
  val visibleNodes: List<GraphNode>,
  val visibleNodeMap: Map<String, GraphNode>,
  val edgeEntries: List<PreparedEdgeEntry>,
  val nodeTextCache: Map<String, PreparedNodeText>,
  val tableTextCache: Map<String, PreparedTableCellText>,
  val stickyTextCache: Map<String, PreparedStickyText>,
  val worldMinX: Float,
  val worldMinY: Float,
  val worldWidth: Float,
  val worldHeight: Float,
)

fun buildPreparedGraphRenderState(
  doc: GraphDocument,
  density: Float,
): PreparedGraphRenderState {
  val layerVisible = doc.layers.associate { it.name to it.visible }
  val hiddenByCollapse = GraphAlgorithms.getCollapsedHiddenSubtree(doc)
  val nodesWithChildren =
    doc.edges.asSequence().filter { it.fromId != it.toId }.map { it.fromId }.toHashSet()

  val visibleNodes =
    doc.nodes.filter { it.id !in hiddenByCollapse && (layerVisible[it.layer] != false) }
  val visibleNodeMap = visibleNodes.associateBy { it.id }

  val hiddenCountByNode = mutableMapOf<String, Int>()
  visibleNodes.forEach { n ->
    if (n.collapsed && n.id in nodesWithChildren) {
      hiddenCountByNode[n.id] = GraphAlgorithms.countHiddenDescendants(doc, n.id)
    }
  }

  val pairGroups = EdgeGeometryHelper.groupEdgesByPair(doc, visibleNodeMap, layerVisible)
  val edgeEntries = ArrayList<PreparedEdgeEntry>(doc.edges.size)
  pairGroups.values.forEach { group ->
    group.forEachIndexed { idx, edge ->
      val a = visibleNodeMap[edge.fromId] ?: return@forEachIndexed
      val b = visibleNodeMap[edge.toId] ?: return@forEachIndexed
      val geom = EdgeGeometryHelper.computeWorldGeometry(edge, a, b, idx, group.size)
      val fwdArrow =
        if (!geom.isSelfLoop && (edge.arrow == ArrowType.FORWARD || edge.arrow == ArrowType.BOTH)) {
          EdgeGeometryHelper.findBoundaryArrow(geom.points, b)
        } else null
      val bwdArrow =
        if (!geom.isSelfLoop && (edge.arrow == ArrowType.BACKWARD || edge.arrow == ArrowType.BOTH)) {
          EdgeGeometryHelper.findBoundaryArrow(geom.points.reversed(), a)
        } else null
      val labelText =
        buildString {
          append(edge.label)
          if (edge.weight != 1f) {
            if (isNotEmpty()) append(" ")
            append("(${edge.weight})")
          }
        }
      edgeEntries.add(
        PreparedEdgeEntry(
          edge = edge,
          fromNode = a,
          toNode = b,
          bundleIndex = idx,
          groupSize = group.size,
          staticGeom = geom,
          staticForwardArrow = fwdArrow,
          staticBackwardArrow = bwdArrow,
          labelText = labelText,
        )
      )
    }
  }

  val nodeTextCache = HashMap<String, PreparedNodeText>(visibleNodes.size)
  val tableTextCache = HashMap<String, PreparedTableCellText>()
  visibleNodes.forEach { node ->
    if (node.shape == NodeShape.TABLE && node.tableData != null) {
      tableTextCache[node.id] = buildPreparedTableText(node.tableData, node.text, node.w, node.h, density)
    } else {
      nodeTextCache[node.id] = buildPreparedNodeText(node, density)
    }
  }

  val stickyTextCache = HashMap<String, PreparedStickyText>(doc.stickyNotes.size)
  if (layerVisible[CanvasLayerName.ANNOTATIONS] != false) {
    doc.stickyNotes.forEach { s ->
      stickyTextCache[s.id] = buildPreparedStickyText(s, density)
    }
  }

  val xsMin = mutableListOf<Float>()
  val xsMax = mutableListOf<Float>()
  val ysMin = mutableListOf<Float>()
  val ysMax = mutableListOf<Float>()
  doc.nodes.forEach {
    xsMin += it.x - it.w / 2f
    xsMax += it.x + it.w / 2f
    ysMin += it.y - it.h / 2f
    ysMax += it.y + it.h / 2f
  }
  doc.unsupportedElements.forEach {
    xsMin += it.x - it.w / 2f
    xsMax += it.x + it.w / 2f
    ysMin += it.y - it.h / 2f
    ysMax += it.y + it.h / 2f
  }
  val minX = if (xsMin.isEmpty()) -200f else xsMin.min() - 80f
  val minY = if (ysMin.isEmpty()) -200f else ysMin.min() - 80f
  val maxX = if (xsMax.isEmpty()) 200f else xsMax.max() + 80f
  val maxY = if (ysMax.isEmpty()) 200f else ysMax.max() + 80f

  return PreparedGraphRenderState(
    doc = doc,
    layerVisible = layerVisible,
    hiddenByCollapse = hiddenByCollapse,
    nodesWithChildren = nodesWithChildren,
    hiddenCountByNode = hiddenCountByNode,
    visibleNodes = visibleNodes,
    visibleNodeMap = visibleNodeMap,
    edgeEntries = edgeEntries,
    nodeTextCache = nodeTextCache,
    tableTextCache = tableTextCache,
    stickyTextCache = stickyTextCache,
    worldMinX = minX,
    worldMinY = minY,
    worldWidth = (maxX - minX).coerceAtLeast(100f),
    worldHeight = (maxY - minY).coerceAtLeast(100f),
  )
}

private fun buildPreparedNodeText(node: GraphNode, density: Float): PreparedNodeText {
  val displayLabel =
    when {
      node.icon.isBlank() -> node.text
      node.iconPosition == IconPosition.INLINE -> "${node.icon} ${node.text}"
      node.iconPosition == IconPosition.LEFT -> "${node.icon}  ${node.text}"
      else -> node.text
    }

  val (wRatio, hRatio, yShiftRatio) =
    when (node.shape) {
      NodeShape.DIAMOND -> Triple(0.54f, 0.50f, 0f)
      NodeShape.TRIANGLE -> Triple(0.52f, 0.46f, 0.14f)
      NodeShape.CIRCLE, NodeShape.STAR -> Triple(0.68f, 0.62f, 0f)
      NodeShape.CLOUD -> Triple(0.72f, 0.54f, 0.06f)
      NodeShape.HEXAGON, NodeShape.PARALLELOGRAM -> Triple(0.68f, 0.72f, 0f)
      NodeShape.CYLINDER -> Triple(0.80f, 0.58f, 0.08f)
      NodeShape.CALLOUT -> Triple(0.82f, 0.64f, -0.07f)
      else -> Triple(0.84f, 0.78f, 0f)
    }

  val prog = node.progress
  val progressReserve = if (prog != null) 16f else 0f
  val boxWWorld = (node.w * wRatio).coerceAtLeast(24f)
  val boxHWorld = (node.h * hRatio - progressReserve).coerceAtLeast(16f)
  val boxCenterYOffset = node.h * yShiftRatio - progressReserve * 0.35f

  val sizeScale = min(node.w / 155f, node.h / 68f).coerceIn(0.6f, 2.2f)
  val idealWorldPx = node.fontSize * sizeScale * density * 0.92f
  val minWorldPx = (7.2f * density).coerceAtLeast(8f)

  val hasTopIcon = node.icon.isNotBlank() && node.iconPosition == IconPosition.TOP
  val iconHWorld = if (hasTopIcon) boxHWorld * 0.34f else 0f
  val textHWorld = if (hasTopIcon) (boxHWorld * 0.66f).coerceAtLeast(12f) else boxHWorld
  val textCenterYOffset = if (hasTopIcon) boxCenterYOffset + iconHWorld * 0.35f else boxCenterYOffset
  val iconCenterYOffset =
    if (hasTopIcon) boxCenterYOffset - boxHWorld / 2f + iconHWorld * 0.72f else null
  val iconSizeWorldPx = (idealWorldPx * 1.15f).coerceAtMost(iconHWorld * 0.85f * density.coerceAtLeast(1f))

  val scaledBoxW = (boxWWorld * TEXT_CACHE_WORLD_SCALE).toInt().coerceAtLeast(24)
  val scaledBoxH = textHWorld * TEXT_CACHE_WORLD_SCALE
  val startPx = (idealWorldPx * TEXT_CACHE_WORLD_SCALE).coerceAtLeast(minWorldPx * TEXT_CACHE_WORLD_SCALE)
  val minPx = minWorldPx * TEXT_CACHE_WORLD_SCALE

  val paint =
    TextPaint().apply {
      isAntiAlias = true
      typeface = Typeface.DEFAULT_BOLD
    }

  var px = startPx
  var layout: StaticLayout? = null
  val words = displayLabel.split(whitespaceRegex).filter { it.isNotEmpty() }

  for (attempt in 0 until 8) {
    paint.textSize = px
    val longestWord = words.maxOfOrNull { paint.measureText(it) } ?: 0f
    val maxLines = max(1, (scaledBoxH / (px * 1.25f)).toInt())
    val built =
      StaticLayout.Builder.obtain(displayLabel, 0, displayLabel.length, paint, scaledBoxW)
        .setAlignment(Layout.Alignment.ALIGN_CENTER)
        .setIncludePad(false)
        .setMaxLines(maxLines)
        .setEllipsize(TextUtils.TruncateAt.END)
        .build()

    val fitsHeight = built.height <= scaledBoxH + 2f
    val fitsWords = longestWord <= scaledBoxW + 4f
    val notTruncated = built.getEllipsisCount(max(0, built.lineCount - 1)) == 0
    layout = built
    if (fitsHeight && fitsWords && notTruncated) break
    if (px <= minPx + 1f) break
    px = (px * 0.85f).coerceAtLeast(minPx)
  }

  val finalLayout = layout!!
  return PreparedNodeText(
    layout = finalLayout,
    textPaint = paint,
    boxCenterXOffset = 0f,
    boxCenterYOffset = textCenterYOffset,
    widthPx = scaledBoxW,
    heightPx = finalLayout.height,
    iconCenterYOffset = iconCenterYOffset,
    iconSizeWorldPx = iconSizeWorldPx,
  )
}

private fun buildPreparedTableText(
  table: TableData,
  title: String,
  wWorld: Float,
  hWorld: Float,
  density: Float,
): PreparedTableCellText {
  val cols = max(1, table.headers.size)
  val tableScale = min(wWorld / 220f, hWorld / 115f).coerceIn(0.6f, 1.8f)
  val titleSizePx = (10f * tableScale * density * TEXT_CACHE_WORLD_SCALE).coerceAtLeast(14f)
  val cellSizePx = (8.8f * tableScale * density * TEXT_CACHE_WORLD_SCALE).coerceAtLeast(12f)

  val titlePaint =
    TextPaint().apply {
      isAntiAlias = true
      textSize = titleSizePx
      typeface = Typeface.DEFAULT_BOLD
    }
  val titleFit =
    TextUtils.ellipsize(
      title,
      titlePaint,
      ((wWorld - 12f) * TEXT_CACHE_WORLD_SCALE).coerceAtLeast(16f),
      TextUtils.TruncateAt.END,
    ).toString()

  val cellPaint =
    TextPaint().apply {
      isAntiAlias = true
      textSize = cellSizePx
    }
  val colW = (wWorld / cols) * TEXT_CACHE_WORLD_SCALE
  val maxCellW = (colW - 12f).coerceAtLeast(14f)
  val headersFit =
    table.headers.map { h ->
      TextUtils.ellipsize(h, cellPaint, maxCellW, TextUtils.TruncateAt.END).toString()
    }
  val rowsFit =
    table.rows.take(3).map { row ->
      row.take(cols).map { cell ->
        TextUtils.ellipsize(cell, cellPaint, maxCellW, TextUtils.TruncateAt.END).toString()
      }
    }
  return PreparedTableCellText(
    title = titleFit,
    titleSizePx = titleSizePx,
    cellSizePx = cellSizePx,
    headers = headersFit,
    rows = rowsFit,
  )
}

private fun buildPreparedStickyText(sticky: StickyNote, density: Float): PreparedStickyText {
  val boxWWorld = (sticky.w - 20f).coerceAtLeast(24f)
  val boxHWorld = (sticky.h - 20f).coerceAtLeast(16f)
  val scaledBoxW = (boxWWorld * TEXT_CACHE_WORLD_SCALE).toInt().coerceAtLeast(24)
  val scaledBoxH = boxHWorld * TEXT_CACHE_WORLD_SCALE
  val startPx = 11f * density * TEXT_CACHE_WORLD_SCALE
  val minPx = (7.5f * density * TEXT_CACHE_WORLD_SCALE).coerceAtLeast(12f)

  val paint =
    TextPaint().apply {
      isAntiAlias = true
      color = android.graphics.Color.parseColor("#1E293B")
    }
  var px = startPx.coerceAtLeast(minPx)
  var layout: StaticLayout? = null
  for (attempt in 0 until 6) {
    paint.textSize = px
    val maxLines = max(1, (scaledBoxH / (px * 1.25f)).toInt())
    val built =
      StaticLayout.Builder.obtain(sticky.text, 0, sticky.text.length, paint, scaledBoxW)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setIncludePad(false)
        .setMaxLines(maxLines)
        .setEllipsize(TextUtils.TruncateAt.END)
        .build()
    layout = built
    if (built.height <= scaledBoxH + 2f || px <= minPx + 1f) break
    px = (px * 0.85f).coerceAtLeast(minPx)
  }
  return PreparedStickyText(layout = layout!!, textPaint = paint)
}

/**
 * Reusable native [Paint] / [TextPaint] scratchpad so [renderGraphCanvas] allocates zero Paint
 * objects inside per-frame loops.
 */
class CanvasScratchPaints {
  val edgeLabelPaint =
    Paint().apply {
      isAntiAlias = true
      textAlign = Paint.Align.CENTER
    }
  val iconPaint =
    Paint().apply {
      isAntiAlias = true
      textAlign = Paint.Align.CENTER
    }
  val badgePaint =
    Paint().apply {
      isAntiAlias = true
      textAlign = Paint.Align.CENTER
      typeface = Typeface.DEFAULT_BOLD
      color = android.graphics.Color.WHITE
    }
  val togglePaint =
    Paint().apply {
      isAntiAlias = true
      textAlign = Paint.Align.CENTER
      typeface = Typeface.DEFAULT_BOLD
      color = android.graphics.Color.WHITE
    }
  val presencePaint =
    Paint().apply {
      isAntiAlias = true
      typeface = Typeface.DEFAULT_BOLD
      color = android.graphics.Color.WHITE
    }
  val tableTitlePaint =
    Paint().apply {
      isAntiAlias = true
      typeface = Typeface.DEFAULT_BOLD
      textAlign = Paint.Align.CENTER
      color = android.graphics.Color.WHITE
    }
  val tableCellPaint =
    Paint().apply {
      isAntiAlias = true
      textAlign = Paint.Align.CENTER
      color = android.graphics.Color.parseColor("#0F172A")
    }
}
