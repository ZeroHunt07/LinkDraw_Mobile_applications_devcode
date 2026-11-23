package com.example.ui.screens

import android.graphics.Paint
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.example.engine.GraphAlgorithms
import com.example.model.*
import com.example.viewmodel.AlgorithmHighlight
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class EdgeWorldGeometry(
  val points: List<Offset>,
  val controlPoint: Offset?,
  val labelPos: Offset,
  val isSelfLoop: Boolean,
)

object EdgeGeometryHelper {

  fun groupEdgesByPair(
    doc: GraphDocument,
    visibleNodeMap: Map<String, GraphNode>,
    layerVisible: Map<CanvasLayerName, Boolean>,
  ): Map<String, List<GraphEdge>> =
    doc.edges
      .filter {
        it.fromId in visibleNodeMap &&
          it.toId in visibleNodeMap &&
          (layerVisible[it.layer] != false)
      }
      .groupBy { e ->
        if (e.fromId <= e.toId) "${e.fromId}__${e.toId}" else "${e.toId}__${e.fromId}"
      }

  fun computeWorldGeometry(
    edge: GraphEdge,
    a: GraphNode,
    b: GraphNode,
    bundleIndex: Int,
    groupSize: Int,
  ): EdgeWorldGeometry {
    if (a.id == b.id) {
      val topY = a.y - a.h / 2f
      val p0 = Offset(a.x - 16f, topY)
      val p1 = Offset(a.x - 44f, topY - 64f)
      val p2 = Offset(a.x + 44f, topY - 64f)
      val p3 = Offset(a.x + 16f, topY)
      val pts =
        (0..16).map { step ->
          val t = step / 16f
          val u = 1f - t
          Offset(
            u * u * u * p0.x + 3f * u * u * t * p1.x + 3f * u * t * t * p2.x + t * t * t * p3.x,
            u * u * u * p0.y + 3f * u * u * t * p1.y + 3f * u * t * t * p2.y + t * t * t * p3.y,
          )
        }
      return EdgeWorldGeometry(
        points = pts,
        controlPoint = Offset(a.x + 26f, topY - 26f),
        labelPos = Offset(a.x, topY - 50f),
        isSelfLoop = true,
      )
    }

    val pStart = Offset(a.x, a.y)
    val pEnd = Offset(b.x, b.y)
    val mid = Offset((pStart.x + pEnd.x) / 2f, (pStart.y + pEnd.y) / 2f)
    val dx = pEnd.x - pStart.x
    val dy = pEnd.y - pStart.y
    val len = hypot(dx, dy).coerceAtLeast(1f)
    val nx = -dy / len
    val ny = dx / len
    val bundleOffset = (bundleIndex - (groupSize - 1) / 2f) * 34f

    return when (edge.type) {
      EdgeType.STRAIGHT -> {
        if (groupSize == 1) {
          EdgeWorldGeometry(listOf(pStart, pEnd), null, mid, false)
        } else {
          val ctrl = Offset(mid.x + nx * bundleOffset, mid.y + ny * bundleOffset)
          val pts = sampleQuadBezier(pStart, ctrl, pEnd)
          val lp = quadPoint(pStart, ctrl, pEnd, 0.5f)
          EdgeWorldGeometry(pts, ctrl, lp, false)
        }
      }
      EdgeType.CURVED -> {
        val curveDisp = (len * 0.16f) + bundleOffset
        val ctrl = Offset(mid.x + nx * curveDisp, mid.y + ny * curveDisp)
        val pts = sampleQuadBezier(pStart, ctrl, pEnd)
        val lp = quadPoint(pStart, ctrl, pEnd, 0.5f)
        EdgeWorldGeometry(pts, ctrl, lp, false)
      }
      EdgeType.ELBOW -> {
        val corner = Offset(pEnd.x, pStart.y)
        EdgeWorldGeometry(listOf(pStart, corner, pEnd), corner, corner, false)
      }
      EdgeType.ORTHOGONAL -> {
        val midX = (pStart.x + pEnd.x) / 2f
        val c1 = Offset(midX, pStart.y)
        val c2 = Offset(midX, pEnd.y)
        EdgeWorldGeometry(listOf(pStart, c1, c2, pEnd), c2, Offset(midX, mid.y), false)
      }
    }
  }

  private fun quadPoint(p0: Offset, p1: Offset, p2: Offset, t: Float): Offset {
    val u = 1f - t
    return Offset(
      u * u * p0.x + 2f * u * t * p1.x + t * t * p2.x,
      u * u * p0.y + 2f * u * t * p1.y + t * t * p2.y,
    )
  }

  private fun sampleQuadBezier(p0: Offset, p1: Offset, p2: Offset): List<Offset> =
    (0..16).map { i -> quadPoint(p0, p1, p2, i / 16f) }

  fun distanceToSegment(px: Float, py: Float, a: Offset, b: Offset): Float {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val lenSq = dx * dx + dy * dy
    if (lenSq < 1f) return hypot(px - a.x, py - a.y)
    val t = (((px - a.x) * dx + (py - a.y) * dy) / lenSq).coerceIn(0f, 1f)
    return hypot(px - (a.x + t * dx), py - (a.y + t * dy))
  }

  fun hitTestEdge(doc: GraphDocument, wx: Float, wy: Float, zoom: Float): GraphEdge? {
    val hidden = GraphAlgorithms.getCollapsedHiddenSubtree(doc)
    val layerVisible = doc.layers.associate { it.name to it.visible }
    val visibleNodes =
      doc.nodes.filter { it.id !in hidden && (layerVisible[it.layer] != false) }
    val visibleNodeMap = visibleNodes.associateBy { it.id }
    val groups = groupEdgesByPair(doc, visibleNodeMap, layerVisible)

    val hitRadius = max(28f, 32f / zoom.coerceAtLeast(0.25f))
    var bestEdge: GraphEdge? = null
    var bestDist = Float.MAX_VALUE

    groups.values.forEach { group ->
      group.forEachIndexed { idx, edge ->
        val a = visibleNodeMap[edge.fromId] ?: return@forEachIndexed
        val b = visibleNodeMap[edge.toId] ?: return@forEachIndexed
        val geom = computeWorldGeometry(edge, a, b, idx, group.size)

        var minEdgeDist = Float.MAX_VALUE
        for (i in 0 until geom.points.lastIndex) {
          val d = distanceToSegment(wx, wy, geom.points[i], geom.points[i + 1])
          if (d < minEdgeDist) minEdgeDist = d
        }
        // Check label pill area generously
        val labelDist = hypot(wx - geom.labelPos.x, wy - geom.labelPos.y)
        val effectiveDist = min(minEdgeDist, labelDist * 0.85f)
        if (effectiveDist <= hitRadius && effectiveDist < bestDist) {
          bestDist = effectiveDist
          bestEdge = edge
        }
      }
    }
    return bestEdge
  }

  fun isPointInsideNode(
    node: GraphNode,
    wx: Float,
    wy: Float,
    margin: Float = 2.5f,
  ): Boolean {
    val dx = wx - node.x
    val dy = wy - node.y
    val hw = (node.w / 2f + margin).coerceAtLeast(1f)
    val hh = (node.h / 2f + margin).coerceAtLeast(1f)
    return when (node.shape) {
      NodeShape.CIRCLE -> (dx / hw) * (dx / hw) + (dy / hh) * (dy / hh) <= 1f
      NodeShape.DIAMOND -> (abs(dx) / hw) + (abs(dy) / hh) <= 1f
      NodeShape.HEXAGON -> {
        if (abs(dx) > hw || abs(dy) > hh) false
        else {
          val flatHalf = hw * 0.68f
          if (abs(dx) <= flatHalf) true
          else ((abs(dx) - flatHalf) / (hw - flatHalf).coerceAtLeast(1f)) + (abs(dy) / hh) <= 1f
        }
      }
      NodeShape.TRIANGLE -> {
        if (dy < -hh || dy > hh) false
        else {
          val allowedHalfW = hw * ((dy + hh) / (2f * hh))
          abs(dx) <= allowedHalfW
        }
      }
      else -> abs(dx) <= hw && abs(dy) <= hh
    }
  }

  /**
   * Finds the exact (fromWorld, tipWorld) pair where a path enters [targetNode]'s outer perimeter
   * so arrowheads sit visibly on the border and never get hidden behind the node fill.
   */
  fun findBoundaryArrow(
    points: List<Offset>,
    targetNode: GraphNode,
    margin: Float = 2.5f,
  ): Pair<Offset, Offset>? {
    if (points.size < 2) return null
    for (i in points.lastIndex - 1 downTo 0) {
      val outside = points[i]
      val inside = points[i + 1]
      if (!isPointInsideNode(targetNode, outside.x, outside.y, margin)) {
        var lo = 0f
        var hi = 1f
        repeat(12) {
          val midT = (lo + hi) * 0.5f
          val mx = outside.x + (inside.x - outside.x) * midT
          val my = outside.y + (inside.y - outside.y) * midT
          if (isPointInsideNode(targetNode, mx, my, margin)) {
            hi = midT
          } else {
            lo = midT
          }
        }
        val tip =
          Offset(
            outside.x + (inside.x - outside.x) * lo,
            outside.y + (inside.y - outside.y) * lo,
          )
        val anchor =
          if (hypot(tip.x - outside.x, tip.y - outside.y) < 1.5f && i > 0) {
            points[i - 1]
          } else {
            outside
          }
        if (hypot(tip.x - anchor.x, tip.y - anchor.y) >= 1f) {
          return anchor to tip
        }
      }
    }
    // Fallback if nodes overlap completely
    val start = points.first()
    val end = points.last()
    return if (hypot(end.x - start.x, end.y - start.y) >= 1f) start to end else null
  }
}

fun DrawScope.renderGraphCanvas(
  prepared: PreparedGraphRenderState,
  scratch: CanvasScratchPaints,
  pan: Offset,
  zoom: Float,
  selectedNodeIds: Set<String>,
  selectedEdgeId: String?,
  selectedStickyId: String?,
  connectSourceId: String?,
  algoHighlight: AlgorithmHighlight,
  searchQuery: String,
  flowPhase: Float,
  activeNodeDragDelta: Offset = Offset.Zero,
  activeStickyDragId: String? = null,
  activeStickyDragDelta: Offset = Offset.Zero,
  activeStroke: List<StrokePoint>,
  activeStrokeIsHighlighter: Boolean,
  gridColor: Color,
  selectionColor: Color,
  highlightNodeColor: Color,
  defaultTextColor: Color,
  isDarkSurface: Boolean,
  remotePresence: List<BoardPresence> = emptyList(),
) {
  val doc = prepared.doc
  val cx = size.width / 2f + pan.x
  val cy = size.height / 2f + pan.y

  fun toScreen(wx: Float, wy: Float): Offset = Offset(wx * zoom + cx, wy * zoom + cy)

  // 1. Grid
  val gridStep = (doc.settings.gridSize * zoom).coerceAtLeast(16f)
  val startX = ((cx % gridStep) + gridStep) % gridStep
  val startY = ((cy % gridStep) + gridStep) % gridStep
  var gx = startX
  while (gx < size.width) {
    drawLine(gridColor, Offset(gx, 0f), Offset(gx, size.height), strokeWidth = 1f)
    gx += gridStep
  }
  var gy = startY
  while (gy < size.height) {
    drawLine(gridColor, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f)
    gy += gridStep
  }

  val layerVisible = prepared.layerVisible
  val visibleNodes = prepared.visibleNodes

  val isDraggingNodes = activeNodeDragDelta != Offset.Zero && selectedNodeIds.isNotEmpty()
  fun effectiveNode(n: GraphNode): GraphNode =
    if (isDraggingNodes && n.id in selectedNodeIds && !n.locked) {
      n.copy(x = n.x + activeNodeDragDelta.x, y = n.y + activeNodeDragDelta.y)
    } else n

  val q = searchQuery.trim().lowercase()
  fun nodeMatchesQuery(n: GraphNode): Boolean {
    if (q.isEmpty()) return true
    return n.text.lowercase().contains(q) ||
      n.tags.any { it.lowercase().contains(q) } ||
      n.metadata.entries.any { it.key.lowercase().contains(q) || it.value.lowercase().contains(q) }
  }

  // 2. Edges
  val edgePath = Path()
  val dashFlowEffect =
    if (doc.settings.flowAnimation) {
      PathEffect.dashPathEffect(floatArrayOf(12f * zoom, 8f * zoom), -flowPhase * zoom)
    } else null
  val dashedEffect = PathEffect.dashPathEffect(floatArrayOf(12f * zoom, 8f * zoom), 0f)
  val dottedEffect = PathEffect.dashPathEffect(floatArrayOf(3f * zoom, 6f * zoom), 0f)

  val labelTextColorDark = Color(0xFFE2E8F0)
  val labelTextColorLight = Color(0xFF1E293B)

  prepared.edgeEntries.forEach { entry ->
    val edge = entry.edge
    val aMoved = isDraggingNodes && entry.fromNode.id in selectedNodeIds && !entry.fromNode.locked
    val bMoved = isDraggingNodes && entry.toNode.id in selectedNodeIds && !entry.toNode.locked
    val a = if (aMoved) effectiveNode(entry.fromNode) else entry.fromNode
    val b = if (bMoved) effectiveNode(entry.toNode) else entry.toNode

    val isHighlighted = edge.id in algoHighlight.edgeIds
    val isSelected = edge.id == selectedEdgeId
    val dimmed = q.isNotEmpty() && (!nodeMatchesQuery(a) && !nodeMatchesQuery(b))
    val baseColor =
      when {
        isHighlighted -> highlightNodeColor
        isSelected -> selectionColor
        else -> parseHexColor(edge.colorHex, Color(0xFF475569))
      }
    val alpha = if (dimmed) 0.2f else 1f
    val color = baseColor.copy(alpha = alpha)
    val strokeWidth = (edge.width + if (isSelected || isHighlighted) 2.2f else 0f) * zoom

    val dashEffect =
      when {
        dashFlowEffect != null -> dashFlowEffect
        edge.lineStyle == LineStyle.DASHED -> dashedEffect
        edge.lineStyle == LineStyle.DOTTED -> dottedEffect
        else -> null
      }

    val geom =
      if (aMoved || bMoved) {
        EdgeGeometryHelper.computeWorldGeometry(edge, a, b, entry.bundleIndex, entry.groupSize)
      } else {
        entry.staticGeom
      }
    val pStart = toScreen(a.x, a.y)
    val pEnd = toScreen(b.x, b.y)
    val labelPos = toScreen(geom.labelPos.x, geom.labelPos.y)

    edgePath.reset()
    if (geom.isSelfLoop) {
      val topY = pStart.y - (a.h * zoom) / 2f
      edgePath.moveTo(pStart.x - 16f * zoom, topY)
      edgePath.cubicTo(
        pStart.x - 44f * zoom,
        topY - 64f * zoom,
        pStart.x + 44f * zoom,
        topY - 64f * zoom,
        pStart.x + 16f * zoom,
        topY,
      )
      if (isSelected || isHighlighted) {
        drawPath(
          edgePath,
          color.copy(alpha = 0.28f),
          style = Stroke(width = strokeWidth + 10f * zoom, cap = StrokeCap.Round),
        )
      }
      drawPath(edgePath, color, style = Stroke(width = strokeWidth, pathEffect = dashEffect))
      if (edge.arrow == ArrowType.FORWARD || edge.arrow == ArrowType.BOTH) {
        drawArrowHead(
          Offset(pStart.x + 26f * zoom, topY - 26f * zoom),
          Offset(pStart.x + 16f * zoom, topY - 2f * zoom),
          color,
          zoom,
          edge.width,
        )
      }
      if (edge.arrow == ArrowType.BACKWARD || edge.arrow == ArrowType.BOTH) {
        drawArrowHead(
          Offset(pStart.x - 26f * zoom, topY - 26f * zoom),
          Offset(pStart.x - 16f * zoom, topY - 2f * zoom),
          color,
          zoom,
          edge.width,
        )
      }
    } else {
      val ctrlScreen = geom.controlPoint?.let { toScreen(it.x, it.y) }
      when {
        edge.type == EdgeType.CURVED || (edge.type == EdgeType.STRAIGHT && entry.groupSize > 1) -> {
          val ctrl = ctrlScreen ?: pStart
          edgePath.moveTo(pStart.x, pStart.y)
          edgePath.quadraticBezierTo(ctrl.x, ctrl.y, pEnd.x, pEnd.y)
        }
        else -> {
          val pts = geom.points
          if (pts.isNotEmpty()) {
            val first = toScreen(pts[0].x, pts[0].y)
            edgePath.moveTo(first.x, first.y)
            for (i in 1 until pts.size) {
              val sp = toScreen(pts[i].x, pts[i].y)
              edgePath.lineTo(sp.x, sp.y)
            }
          }
        }
      }

      if (isSelected || isHighlighted) {
        drawPath(
          edgePath,
          color.copy(alpha = 0.28f),
          style = Stroke(width = strokeWidth + 10f * zoom, cap = StrokeCap.Round),
        )
      }
      drawPath(
        edgePath,
        color,
        style = Stroke(width = strokeWidth, pathEffect = dashEffect, cap = StrokeCap.Round),
      )

      if (edge.arrow == ArrowType.FORWARD || edge.arrow == ArrowType.BOTH) {
        val arrowPair =
          if (aMoved || bMoved) EdgeGeometryHelper.findBoundaryArrow(geom.points, b)
          else entry.staticForwardArrow
        arrowPair?.let { (fromW, tipW) ->
          drawArrowHead(
            from = toScreen(fromW.x, fromW.y),
            tip = toScreen(tipW.x, tipW.y),
            color = color,
            zoom = zoom,
            edgeWidth = edge.width,
          )
        }
      }
      if (edge.arrow == ArrowType.BACKWARD || edge.arrow == ArrowType.BOTH) {
        val arrowPair =
          if (aMoved || bMoved) EdgeGeometryHelper.findBoundaryArrow(geom.points.reversed(), a)
          else entry.staticBackwardArrow
        arrowPair?.let { (fromW, tipW) ->
          drawArrowHead(
            from = toScreen(fromW.x, fromW.y),
            tip = toScreen(tipW.x, tipW.y),
            color = color,
            zoom = zoom,
            edgeWidth = edge.width,
          )
        }
      }
    }

    // Level-of-Detail (LOD): hide edge labels below 0.45x zoom
    if (zoom >= 0.45f && (entry.labelText.isNotEmpty() || isSelected)) {
      val txt = if (entry.labelText.isEmpty() && isSelected) "Selected" else entry.labelText
      val paint = scratch.edgeLabelPaint
      paint.textSize = 11f * zoom * density
      paint.color =
        (if (isDarkSurface) labelTextColorDark else labelTextColorLight)
          .copy(alpha = alpha)
          .toArgb()
      val pillW = paint.measureText(txt) + 16f * zoom
      val pillH = 19f * zoom * density
      drawRoundRect(
        color =
          (if (isSelected) selectionColor.copy(alpha = 0.22f)
           else if (isDarkSurface) Color(0xFF1E293B) else Color.White)
            .copy(alpha = 0.94f * alpha),
        topLeft = Offset(labelPos.x - pillW / 2f, labelPos.y - pillH / 2f),
        size = Size(pillW, pillH),
        cornerRadius = CornerRadius(8f * zoom),
      )
      if (isSelected) {
        drawRoundRect(
          color = selectionColor,
          topLeft = Offset(labelPos.x - pillW / 2f, labelPos.y - pillH / 2f),
          size = Size(pillW, pillH),
          cornerRadius = CornerRadius(8f * zoom),
          style = Stroke(width = 1.6f * zoom),
        )
      }
      drawContext.canvas.nativeCanvas.drawText(txt, labelPos.x, labelPos.y + 4f * zoom * density, paint)
    }
  }

  // 3. Nodes (14 shapes + Table + Progress + Collapsible badge + Cached 2x StaticLayout)
  val nativeCanvas = drawContext.canvas.nativeCanvas
  val textScale = zoom / TEXT_CACHE_WORLD_SCALE

  visibleNodes.forEach { rawNode ->
    val node = effectiveNode(rawNode)
    val center = toScreen(node.x, node.y)
    val nw = node.w * zoom
    val nh = node.h * zoom
    val topLeft = Offset(center.x - nw / 2f, center.y - nh / 2f)

    // Viewport culling in screen coordinates for large boards
    if (
      topLeft.x + nw < -80f ||
        topLeft.x > size.width + 80f ||
        topLeft.y + nh < -80f ||
        topLeft.y > size.height + 80f
    ) {
      return@forEach
    }

    val matches = nodeMatchesQuery(node)
    val alpha = if (matches) 1f else 0.22f
    val fillHex = algoHighlight.clusterColorMap[node.id] ?: node.colorHex
    val fillColor = parseHexColor(fillHex).copy(alpha = alpha)
    val isSelected = node.id in selectedNodeIds || node.id == connectSourceId
    val isHighlighted = node.id in algoHighlight.nodeIds
    val borderColor =
      when {
        isHighlighted -> highlightNodeColor
        isSelected -> selectionColor
        else -> Color(0xFF334155).copy(alpha = 0.55f * alpha)
      }
    val borderWidth = if (isSelected || isHighlighted) 3.2f * zoom else 1.5f * zoom

    drawNodeShape(
      shape = node.shape,
      topLeft = topLeft,
      size = Size(nw, nh),
      fillColor = fillColor,
      borderColor = borderColor,
      borderWidth = borderWidth,
      zoom = zoom,
    )

    val prog = node.progress
    if (prog != null && node.shape != NodeShape.TABLE) {
      val barW = nw * 0.78f
      val barH = 7f * zoom
      val barX = center.x - barW / 2f
      val barY = topLeft.y + nh - 14f * zoom
      drawRoundRect(
        color = Color(0xFF94A3B8).copy(alpha = 0.35f * alpha),
        topLeft = Offset(barX, barY),
        size = Size(barW, barH),
        cornerRadius = CornerRadius(4f * zoom),
      )
      val barColor =
        when {
          prog >= 80 -> Color(0xFF10B981)
          prog >= 45 -> Color(0xFFF59E0B)
          else -> Color(0xFFEF4444)
        }.copy(alpha = alpha)
      drawRoundRect(
        color = barColor,
        topLeft = Offset(barX, barY),
        size = Size(barW * (prog.coerceIn(0, 100) / 100f), barH),
        cornerRadius = CornerRadius(4f * zoom),
      )
    }

    if (node.shape == NodeShape.TABLE && node.tableData != null && zoom >= 0.4f) {
      prepared.tableTextCache[node.id]?.let { cachedTable ->
        drawPreparedTableNodeContent(
          table = cachedTable,
          scratch = scratch,
          topLeft = topLeft,
          size = Size(nw, nh),
          zoom = zoom,
          alpha = alpha,
        )
      }
    } else if (zoom >= 0.35f) {
      val cachedText = prepared.nodeTextCache[node.id]
      if (cachedText != null) {
        val txtColor = parseHexColor(node.textColorHex, defaultTextColor).copy(alpha = alpha).toArgb()
        cachedText.iconCenterYOffset?.let { iconOffY ->
          if (node.icon.isNotBlank()) {
            val iconPaint = scratch.iconPaint
            iconPaint.textSize = cachedText.iconSizeWorldPx * zoom
            iconPaint.color = txtColor
            nativeCanvas.drawText(
              node.icon,
              center.x,
              center.y + iconOffY * zoom,
              iconPaint,
            )
          }
        }
        cachedText.textPaint.color = txtColor
        nativeCanvas.save()
        nativeCanvas.translate(
          center.x - (cachedText.widthPx * textScale) / 2f,
          center.y + cachedText.boxCenterYOffset * zoom - (cachedText.heightPx * textScale) / 2f,
        )
        nativeCanvas.scale(textScale, textScale)
        cachedText.layout.draw(nativeCanvas)
        nativeCanvas.restore()
      }
    }

    if (node.badge != null) {
      val bx = topLeft.x + nw - 8f * zoom
      val by = topLeft.y + 4f * zoom
      drawCircle(Color(0xFF4F46E5), radius = 12f * zoom, center = Offset(bx, by))
      val bPaint = scratch.badgePaint
      bPaint.textSize = 10f * zoom * density
      nativeCanvas.drawText(node.badge, bx, by + 4f * zoom, bPaint)
    }

    if (node.id in prepared.nodesWithChildren) {
      val tx = topLeft.x + nw
      val ty = center.y
      drawCircle(
        color = if (node.collapsed) Color(0xFF0284C7) else Color(0xFF334155),
        radius = 11f * zoom,
        center = Offset(tx, ty),
      )
      val hiddenCount = prepared.hiddenCountByNode[node.id] ?: 0
      val symbol = if (node.collapsed) "▶$hiddenCount" else "▼"
      val tPaint = scratch.togglePaint
      tPaint.textSize = 9f * zoom * density
      nativeCanvas.drawText(symbol, tx, ty + 3.5f * zoom, tPaint)
    }

    val watchers = remotePresence.filter { it.selectedNodeId == node.id }
    if (watchers.isNotEmpty() && zoom >= 0.45f) {
      val firstWatcher = watchers.first()
      val badgeColor = parseHexColor(firstWatcher.colorHex, Color(0xFF2563EB))
      drawRoundRect(
        color = badgeColor.copy(alpha = 0.85f),
        topLeft = Offset(topLeft.x - 4f * zoom, topLeft.y - 4f * zoom),
        size = Size(nw + 8f * zoom, nh + 8f * zoom),
        cornerRadius = CornerRadius(16f * zoom),
        style = Stroke(width = 2.5f * zoom),
      )
      val tagLabel =
        if (watchers.size == 1) "● ${firstWatcher.displayName.take(14)}"
        else "● ${firstWatcher.displayName.take(10)} +${watchers.size - 1}"
      val pPaint = scratch.presencePaint
      pPaint.textSize = 9.5f * zoom * density
      val pillW = pPaint.measureText(tagLabel) + 14f * zoom
      val pillH = 16f * zoom * density
      val pillX = topLeft.x
      val pillY = topLeft.y - pillH - 4f * zoom
      drawRoundRect(
        color = badgeColor,
        topLeft = Offset(pillX, pillY),
        size = Size(pillW, pillH),
        cornerRadius = CornerRadius(8f * zoom),
      )
      nativeCanvas.drawText(tagLabel, pillX + 7f * zoom, pillY + pillH * 0.72f, pPaint)
    }
  }

  // 4. Sticky Notes & 5. Freehand Strokes
  if (layerVisible[CanvasLayerName.ANNOTATIONS] != false) {
    doc.stickyNotes.forEach { rawSticky ->
      val s =
        if (rawSticky.id == activeStickyDragId && activeStickyDragDelta != Offset.Zero) {
          rawSticky.copy(
            x = rawSticky.x + activeStickyDragDelta.x,
            y = rawSticky.y + activeStickyDragDelta.y,
          )
        } else rawSticky
      val center = toScreen(s.x, s.y)
      val sw = s.w * zoom
      val sh = s.h * zoom
      val tl = Offset(center.x - sw / 2f, center.y - sh / 2f)
      val bg = parseHexColor(s.colorHex, Color(0xFFFEF08A))
      drawRoundRect(
        color = Color.Black.copy(alpha = 0.12f),
        topLeft = Offset(tl.x + 4f * zoom, tl.y + 4f * zoom),
        size = Size(sw, sh),
        cornerRadius = CornerRadius(6f * zoom),
      )
      drawRoundRect(
        color = bg,
        topLeft = tl,
        size = Size(sw, sh),
        cornerRadius = CornerRadius(6f * zoom),
      )
      if (s.id == selectedStickyId) {
        drawRoundRect(
          color = selectionColor,
          topLeft = tl,
          size = Size(sw, sh),
          cornerRadius = CornerRadius(6f * zoom),
          style = Stroke(width = 2.8f * zoom),
        )
      }
      if (zoom >= 0.4f) {
        prepared.stickyTextCache[s.id]?.let { cachedSticky ->
          nativeCanvas.save()
          nativeCanvas.translate(tl.x + 10f * zoom, tl.y + 10f * zoom)
          nativeCanvas.scale(textScale, textScale)
          cachedSticky.layout.draw(nativeCanvas)
          nativeCanvas.restore()
        }
      }
    }

    val strokePath = Path()
    doc.freehandStrokes.forEach { st ->
      if (st.points.size >= 2) {
        strokePath.reset()
        val first = toScreen(st.points.first().x, st.points.first().y)
        strokePath.moveTo(first.x, first.y)
        for (i in 1 until st.points.size) {
          val p = toScreen(st.points[i].x, st.points[i].y)
          strokePath.lineTo(p.x, p.y)
        }
        val base = parseHexColor(st.colorHex, Color.Red)
        drawPath(
          path = strokePath,
          color = if (st.isHighlighter) base.copy(alpha = 0.42f) else base,
          style = Stroke(width = st.width * zoom, cap = StrokeCap.Round),
        )
      }
    }
  }

  // 5b. Unsupported Remote Elements (e.g., Web-only element kinds rendered as visible read-only placeholders)
  if (doc.unsupportedElements.isNotEmpty()) {
    val placeholderDash = PathEffect.dashPathEffect(floatArrayOf(8f * zoom, 5f * zoom), 0f)
    doc.unsupportedElements.forEach { u ->
      val center = toScreen(u.x, u.y)
      val uw = u.w * zoom
      val uh = u.h * zoom
      val tl = Offset(center.x - uw / 2f, center.y - uh / 2f)
      if (
        tl.x + uw < -80f ||
          tl.x > size.width + 80f ||
          tl.y + uh < -80f ||
          tl.y > size.height + 80f
      ) {
        return@forEach
      }
      val fill = parseHexColor(u.colorHex, Color(0xFFFEF3C7)).copy(alpha = 0.28f)
      val border = Color(0xFFD97706)
      drawRoundRect(
        color = fill,
        topLeft = tl,
        size = Size(uw, uh),
        cornerRadius = CornerRadius(12f * zoom),
      )
      drawRoundRect(
        color = border,
        topLeft = tl,
        size = Size(uw, uh),
        cornerRadius = CornerRadius(12f * zoom),
        style = Stroke(width = 2f * zoom, pathEffect = placeholderDash),
      )
      if (zoom >= 0.38f) {
        val badgeText = "WEB: ${u.kind.uppercase().take(18)}"
        val bPaint = scratch.presencePaint
        bPaint.textSize = 9f * zoom * density
        val pillW = bPaint.measureText(badgeText) + 12f * zoom
        val pillH = 15f * zoom * density
        drawRoundRect(
          color = border,
          topLeft = Offset(tl.x + 6f * zoom, tl.y - pillH / 2f),
          size = Size(pillW, pillH),
          cornerRadius = CornerRadius(6f * zoom),
        )
        nativeCanvas.drawText(
          badgeText,
          tl.x + 12f * zoom,
          tl.y + pillH * 0.24f,
          bPaint,
        )
        val lblPaint = scratch.edgeLabelPaint
        lblPaint.textSize = 11.5f * zoom * density
        lblPaint.color =
          (if (isDarkSurface) Color(0xFFFDE68A) else Color(0xFF78350F)).toArgb()
        nativeCanvas.drawText(
          u.label.take(28),
          center.x,
          center.y + 4f * zoom * density,
          lblPaint,
        )
      }
    }
  }

  // 5c. Live Remote Cursors (forwarded from Web or mobile peers publishing cursorX/cursorY)
  if (remotePresence.isNotEmpty() && zoom >= 0.35f) {
    remotePresence.forEach { peer ->
      val cxW = peer.cursorX?.toFloat() ?: return@forEach
      val cyW = peer.cursorY?.toFloat() ?: return@forEach
      val tip = toScreen(cxW, cyW)
      if (tip.x < -60f || tip.x > size.width + 60f || tip.y < -60f || tip.y > size.height + 60f) {
        return@forEach
      }
      val cColor = parseHexColor(peer.colorHex, Color(0xFF2563EB))
      val cursorPath =
        Path().apply {
          moveTo(tip.x, tip.y)
          lineTo(tip.x + 14f, tip.y + 6f)
          lineTo(tip.x + 7f, tip.y + 9f)
          lineTo(tip.x + 5f, tip.y + 16f)
          close()
        }
      drawPath(cursorPath, color = cColor)
      drawPath(cursorPath, color = Color.White, style = Stroke(width = 1.4f))
      val pPaint = scratch.presencePaint
      pPaint.textSize = 10f * density
      val nameTag = peer.displayName.take(14)
      val pillW = pPaint.measureText(nameTag) + 12f
      val pillH = 16f * density
      drawRoundRect(
        color = cColor,
        topLeft = Offset(tip.x + 12f, tip.y + 12f),
        size = Size(pillW, pillH),
        cornerRadius = CornerRadius(6f),
      )
      nativeCanvas.drawText(nameTag, tip.x + 18f, tip.y + 12f + pillH * 0.72f, pPaint)
    }
  }

  // Active in-progress freehand stroke
  if (activeStroke.size >= 2) {
    val activePath = Path()
    val first = toScreen(activeStroke.first().x, activeStroke.first().y)
    activePath.moveTo(first.x, first.y)
    for (i in 1 until activeStroke.size) {
      val p = toScreen(activeStroke[i].x, activeStroke[i].y)
      activePath.lineTo(p.x, p.y)
    }
    drawPath(
      path = activePath,
      color = if (activeStrokeIsHighlighter) Color(0xFFFACC15).copy(alpha = 0.45f) else Color(0xFFEF4444),
      style = Stroke(width = (if (activeStrokeIsHighlighter) 18f else 4f) * zoom, cap = StrokeCap.Round),
    )
  }
}

private fun DrawScope.drawArrowHead(
  from: Offset,
  tip: Offset,
  color: Color,
  zoom: Float,
  edgeWidth: Float = 2.4f,
) {
  val angle = atan2((tip.y - from.y).toDouble(), (tip.x - from.x).toDouble())
  val arrowLen = max(13f, edgeWidth * 4.5f) * zoom
  val spread = 0.48
  val p1 =
    Offset(
      (tip.x - arrowLen * cos(angle - spread)).toFloat(),
      (tip.y - arrowLen * sin(angle - spread)).toFloat(),
    )
  val p2 =
    Offset(
      (tip.x - arrowLen * cos(angle + spread)).toFloat(),
      (tip.y - arrowLen * sin(angle + spread)).toFloat(),
    )
  val path =
    Path().apply {
      moveTo(tip.x, tip.y)
      lineTo(p1.x, p1.y)
      lineTo(p2.x, p2.y)
      close()
    }
  drawPath(path, color = color)
  drawPath(path, color = color, style = Stroke(width = 1.2f * zoom, cap = StrokeCap.Round))
}

private fun DrawScope.drawNodeShape(
  shape: NodeShape,
  topLeft: Offset,
  size: Size,
  fillColor: Color,
  borderColor: Color,
  borderWidth: Float,
  zoom: Float,
) {
  val l = topLeft.x
  val t = topLeft.y
  val w = size.width
  val h = size.height
  val cx = l + w / 2f
  val cy = t + h / 2f

  when (shape) {
    NodeShape.RECTANGLE, NodeShape.SQUARE, NodeShape.TABLE -> {
      drawRect(fillColor, topLeft, size)
      drawRect(borderColor, topLeft, size, style = Stroke(borderWidth))
    }
    NodeShape.ROUNDED, NodeShape.PROGRESS_BAR -> {
      val r = CornerRadius(14f * zoom)
      drawRoundRect(fillColor, topLeft, size, r)
      drawRoundRect(borderColor, topLeft, size, r, style = Stroke(borderWidth))
    }
    NodeShape.TEXT -> {
      drawRoundRect(fillColor.copy(alpha = 0.2f), topLeft, size, CornerRadius(8f * zoom))
    }
    NodeShape.CIRCLE -> {
      drawOval(fillColor, topLeft, size)
      drawOval(borderColor, topLeft, size, style = Stroke(borderWidth))
    }
    NodeShape.DIAMOND -> {
      val path =
        Path().apply {
          moveTo(cx, t)
          lineTo(l + w, cy)
          lineTo(cx, t + h)
          lineTo(l, cy)
          close()
        }
      drawPath(path, fillColor)
      drawPath(path, borderColor, style = Stroke(borderWidth))
    }
    NodeShape.TRIANGLE -> {
      val path =
        Path().apply {
          moveTo(cx, t)
          lineTo(l + w, t + h)
          lineTo(l, t + h)
          close()
        }
      drawPath(path, fillColor)
      drawPath(path, borderColor, style = Stroke(borderWidth))
    }
    NodeShape.HEXAGON -> {
      val inset = w * 0.16f
      val path =
        Path().apply {
          moveTo(l + inset, t)
          lineTo(l + w - inset, t)
          lineTo(l + w, cy)
          lineTo(l + w - inset, t + h)
          lineTo(l + inset, t + h)
          lineTo(l, cy)
          close()
        }
      drawPath(path, fillColor)
      drawPath(path, borderColor, style = Stroke(borderWidth))
    }
    NodeShape.PARALLELOGRAM -> {
      val skew = w * 0.15f
      val path =
        Path().apply {
          moveTo(l + skew, t)
          lineTo(l + w, t)
          lineTo(l + w - skew, t + h)
          lineTo(l, t + h)
          close()
        }
      drawPath(path, fillColor)
      drawPath(path, borderColor, style = Stroke(borderWidth))
    }
    NodeShape.STAR -> {
      val outerRx = w / 2f
      val outerRy = h / 2f
      val innerRx = outerRx * 0.45f
      val innerRy = outerRy * 0.45f
      val path = Path()
      for (i in 0 until 10) {
        val angle = -PI / 2.0 + i * PI / 5.0
        val rx = if (i % 2 == 0) outerRx else innerRx
        val ry = if (i % 2 == 0) outerRy else innerRy
        val px = cx + (rx * cos(angle)).toFloat()
        val py = cy + (ry * sin(angle)).toFloat()
        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
      }
      path.close()
      drawPath(path, fillColor)
      drawPath(path, borderColor, style = Stroke(borderWidth))
    }
    NodeShape.CYLINDER -> {
      val capH = h * 0.22f
      drawRoundRect(fillColor, Offset(l, t + capH / 2f), Size(w, h - capH), CornerRadius(6f * zoom))
      drawOval(fillColor, Offset(l, t + h - capH), Size(w, capH))
      drawOval(fillColor.copy(alpha = (fillColor.alpha * 0.9f)), Offset(l, t), Size(w, capH))
      drawOval(borderColor, Offset(l, t), Size(w, capH), style = Stroke(borderWidth))
      drawLine(borderColor, Offset(l, t + capH / 2f), Offset(l, t + h - capH / 2f), borderWidth)
      drawLine(borderColor, Offset(l + w, t + capH / 2f), Offset(l + w, t + h - capH / 2f), borderWidth)
    }
    NodeShape.CLOUD -> {
      val r = CornerRadius(h * 0.45f)
      drawRoundRect(fillColor, Offset(l, t + h * 0.18f), Size(w, h * 0.72f), r)
      drawCircle(fillColor, radius = h * 0.34f, center = Offset(l + w * 0.34f, t + h * 0.34f))
      drawCircle(fillColor, radius = h * 0.38f, center = Offset(l + w * 0.62f, t + h * 0.30f))
      drawRoundRect(borderColor, Offset(l, t + h * 0.18f), Size(w, h * 0.72f), r, style = Stroke(borderWidth))
    }
    NodeShape.CALLOUT -> {
      val bodyH = h * 0.82f
      val r = CornerRadius(12f * zoom)
      drawRoundRect(fillColor, topLeft, Size(w, bodyH), r)
      val tail =
        Path().apply {
          moveTo(l + w * 0.22f, t + bodyH)
          lineTo(l + w * 0.14f, t + h)
          lineTo(l + w * 0.36f, t + bodyH)
          close()
        }
      drawPath(tail, fillColor)
      drawRoundRect(borderColor, topLeft, Size(w, bodyH), r, style = Stroke(borderWidth))
    }
  }
}

private fun DrawScope.drawPreparedTableNodeContent(
  table: PreparedTableCellText,
  scratch: CanvasScratchPaints,
  topLeft: Offset,
  size: Size,
  zoom: Float,
  alpha: Float,
) {
  val l = topLeft.x
  val t = topLeft.y
  val w = size.width
  val h = size.height
  val cols = max(1, table.headers.size)
  val totalRows = 2 + table.rows.size
  val rowH = h / totalRows
  val textScale = zoom / TEXT_CACHE_WORLD_SCALE

  drawRect(Color(0xFF1E3A8A).copy(alpha = alpha), Offset(l, t), Size(w, rowH))
  val titlePaint = scratch.tableTitlePaint
  titlePaint.textSize = table.titleSizePx * textScale
  titlePaint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
  drawContext.canvas.nativeCanvas.drawText(table.title, l + w / 2f, t + rowH * 0.68f, titlePaint)

  drawRect(Color(0xFFDBEAFE).copy(alpha = alpha), Offset(l, t + rowH), Size(w, rowH))
  val cellPaint = scratch.tableCellPaint
  cellPaint.textSize = table.cellSizePx * textScale
  cellPaint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
  val colW = w / cols
  table.headers.forEachIndexed { c, hText ->
    drawContext.canvas.nativeCanvas.drawText(
      hText,
      l + colW * c + colW / 2f,
      t + rowH + rowH * 0.68f,
      cellPaint,
    )
  }
  table.rows.forEachIndexed { rIdx, row ->
    val y = t + rowH * (2 + rIdx)
    drawLine(Color(0xFFCBD5E1).copy(alpha = alpha), Offset(l, y), Offset(l + w, y), 1f)
    row.forEachIndexed { c, cell ->
      drawContext.canvas.nativeCanvas.drawText(
        cell,
        l + colW * c + colW / 2f,
        y + rowH * 0.68f,
        cellPaint,
      )
    }
  }
}

fun minimapPointToWorld(
  touch: Offset,
  minimapSize: Size,
  prepared: PreparedGraphRenderState,
): Offset {
  if (minimapSize.width <= 0f || minimapSize.height <= 0f) return Offset.Zero
  val scale = minOf(minimapSize.width / prepared.worldWidth, minimapSize.height / prepared.worldHeight)
    .coerceAtLeast(0.001f)
  return Offset(
    x = prepared.worldMinX + touch.x / scale,
    y = prepared.worldMinY + touch.y / scale,
  )
}

fun DrawScope.renderMinimap(
  prepared: PreparedGraphRenderState,
  pan: Offset,
  zoom: Float,
  mainWidth: Float,
  mainHeight: Float,
) {
  if ((prepared.doc.nodes.isEmpty() && prepared.doc.unsupportedElements.isEmpty()) || mainWidth <= 0f || mainHeight <= 0f) return
  val minX = prepared.worldMinX
  val minY = prepared.worldMinY
  val scale = minOf(size.width / prepared.worldWidth, size.height / prepared.worldHeight)

  fun mapPt(wx: Float, wy: Float): Offset = Offset((wx - minX) * scale, (wy - minY) * scale)

  prepared.edgeEntries.forEach { e ->
    drawLine(
      Color(0xFF64748B).copy(alpha = 0.5f),
      mapPt(e.fromNode.x, e.fromNode.y),
      mapPt(e.toNode.x, e.toNode.y),
      1.5f,
    )
  }
  prepared.visibleNodes.forEach { n ->
    drawCircle(parseHexColor(n.colorHex, Color(0xFF38BDF8)), radius = 4f, center = mapPt(n.x, n.y))
  }
  prepared.doc.unsupportedElements.forEach { u ->
    drawCircle(Color(0xFFF59E0B), radius = 3.5f, center = mapPt(u.x, u.y))
  }

  val vpLeftWorld = (-mainWidth / 2f - pan.x) / zoom
  val vpTopWorld = (-mainHeight / 2f - pan.y) / zoom
  val vpWWorld = mainWidth / zoom
  val vpHWorld = mainHeight / zoom
  drawRect(
    color = Color(0xFFEF4444),
    topLeft = mapPt(vpLeftWorld, vpTopWorld),
    size = Size(vpWWorld * scale, vpHWorld * scale),
    style = Stroke(width = 2f),
  )
}
