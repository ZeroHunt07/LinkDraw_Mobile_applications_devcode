package com.example.engine

import com.example.model.EdgeType
import com.example.model.GraphDocument
import com.example.model.GraphNode
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

enum class LayoutAlgorithm(val label: String, val description: String) {
  FORCE_DIRECTED("Force-Directed", "Fruchterman-Reingold spring & repulsion physics"),
  CIRCULAR("Circular", "Equidistant ring distribution"),
  GRID("Grid Matrix", "Uniform rows and columns"),
  HIERARCHICAL("Hierarchical DAG", "Layered topological flow"),
  RADIAL_TREE("Radial Concentric", "Hub-and-spoke depth rings"),
  ORTHOGONAL("Orthogonal Grid", "Grid-snapped nodes with 90° edges"),
}

object GraphLayouts {

  fun applyLayout(doc: GraphDocument, layout: LayoutAlgorithm): GraphDocument {
    if (doc.nodes.isEmpty()) return doc
    return when (layout) {
      LayoutAlgorithm.FORCE_DIRECTED -> doc.copy(nodes = forceDirected(doc))
      LayoutAlgorithm.CIRCULAR -> doc.copy(nodes = circular(doc.nodes))
      LayoutAlgorithm.GRID -> doc.copy(nodes = grid(doc.nodes))
      LayoutAlgorithm.HIERARCHICAL -> doc.copy(nodes = hierarchical(doc))
      LayoutAlgorithm.RADIAL_TREE -> doc.copy(nodes = radial(doc))
      LayoutAlgorithm.ORTHOGONAL -> {
        val snapped =
          grid(doc.nodes).map { n ->
            n.copy(x = round(n.x / 48f) * 48f, y = round(n.y / 48f) * 48f)
          }
        doc.copy(
          nodes = snapped,
          edges = doc.edges.map { it.copy(type = EdgeType.ORTHOGONAL) },
        )
      }
    }
  }

  private fun circular(nodes: List<GraphNode>): List<GraphNode> {
    val n = nodes.size
    val radius = max(180f, 52f * n)
    return nodes.mapIndexed { idx, node ->
      val angle = (2.0 * PI * idx) / n
      node.copy(
        x = (radius * cos(angle)).toFloat(),
        y = (radius * sin(angle)).toFloat(),
      )
    }
  }

  private fun grid(nodes: List<GraphNode>): List<GraphNode> {
    val cols = max(1, ceil(sqrt(nodes.size.toDouble())).toInt())
    val rows = ceil(nodes.size.toDouble() / cols).toInt()
    val stepX = 230f
    val stepY = 150f
    val offsetX = ((cols - 1) * stepX) / 2f
    val offsetY = ((rows - 1) * stepY) / 2f
    return nodes.mapIndexed { idx, node ->
      val c = idx % cols
      val r = idx / cols
      node.copy(x = c * stepX - offsetX, y = r * stepY - offsetY)
    }
  }

  private fun forceDirected(doc: GraphDocument): List<GraphNode> {
    val nodes = doc.nodes
    val n = nodes.size
    if (n <= 1) return nodes

    val posX = FloatArray(n) { i ->
      val angle = (2.0 * PI * i) / n
      if (nodes[i].x == 0f && nodes[i].y == 0f) (220f * cos(angle)).toFloat() else nodes[i].x
    }
    val posY = FloatArray(n) { i ->
      val angle = (2.0 * PI * i) / n
      if (nodes[i].x == 0f && nodes[i].y == 0f) (220f * sin(angle)).toFloat() else nodes[i].y
    }
    val idToIdx = nodes.mapIndexed { idx, node -> node.id to idx }.toMap()
    val area = 900f * 900f
    val k = sqrt(area / n) * 0.9f
    var temp = 220f

    val dispX = FloatArray(n)
    val dispY = FloatArray(n)

    repeat(75) {
      dispX.fill(0f)
      dispY.fill(0f)

      // Repulsion
      for (i in 0 until n) {
        for (j in i + 1 until n) {
          val dx = posX[i] - posX[j]
          val dy = posY[i] - posY[j]
          val dist = hypot(dx, dy).coerceAtLeast(1f)
          val force = (k * k) / dist
          val fx = (dx / dist) * force
          val fy = (dy / dist) * force
          dispX[i] += fx
          dispY[i] += fy
          dispX[j] -= fx
          dispY[j] -= fy
        }
      }

      // Attraction
      doc.edges.forEach { e ->
        val u = idToIdx[e.fromId] ?: return@forEach
        val v = idToIdx[e.toId] ?: return@forEach
        if (u == v) return@forEach
        val dx = posX[u] - posX[v]
        val dy = posY[u] - posY[v]
        val dist = hypot(dx, dy).coerceAtLeast(1f)
        val force = (dist * dist) / k
        val fx = (dx / dist) * force
        val fy = (dy / dist) * force
        dispX[u] -= fx
        dispY[u] -= fy
        dispX[v] += fx
        dispY[v] += fy
      }

      for (i in 0 until n) {
        val d = hypot(dispX[i], dispY[i]).coerceAtLeast(1f)
        val step = min(d, temp)
        posX[i] += (dispX[i] / d) * step
        posY[i] += (dispY[i] / d) * step
      }
      temp *= 0.94f
    }

    val meanX = posX.average().toFloat()
    val meanY = posY.average().toFloat()
    return nodes.mapIndexed { idx, node ->
      node.copy(x = posX[idx] - meanX, y = posY[idx] - meanY)
    }
  }

  private fun hierarchical(doc: GraphDocument): List<GraphNode> {
    val inDeg = doc.nodes.associate { it.id to 0 }.toMutableMap()
    val adj = mutableMapOf<String, MutableList<String>>()
    doc.edges.forEach { e ->
      if (e.fromId in inDeg && e.toId in inDeg && e.fromId != e.toId) {
        adj.getOrPut(e.fromId) { mutableListOf() }.add(e.toId)
        inDeg[e.toId] = (inDeg[e.toId] ?: 0) + 1
      }
    }
    val rank = mutableMapOf<String, Int>()
    val q = ArrayDeque<String>()
    inDeg.filter { it.value == 0 }.keys.forEach {
      rank[it] = 0
      q.addLast(it)
    }
    while (q.isNotEmpty()) {
      val u = q.removeFirst()
      val ru = rank[u] ?: 0
      adj[u].orEmpty().forEach { v ->
        rank[v] = max(rank[v] ?: 0, ru + 1)
        val next = (inDeg[v] ?: 1) - 1
        inDeg[v] = next
        if (next <= 0 && v !in q) q.addLast(v)
      }
    }
    doc.nodes.forEach { if (!rank.containsKey(it.id)) rank[it.id] = 0 }

    val byLayer = doc.nodes.groupBy { rank[it.id] ?: 0 }.toSortedMap()
    val maxLayer = byLayer.keys.maxOrNull() ?: 0
    val layerGap = 155f
    val colGap = 215f
    val posMap = mutableMapOf<String, Pair<Float, Float>>()

    byLayer.forEach { (layerIdx, layerNodes) ->
      val y = (layerIdx - maxLayer / 2f) * layerGap
      val totalW = (layerNodes.size - 1) * colGap
      layerNodes.forEachIndexed { colIdx, node ->
        val x = colIdx * colGap - totalW / 2f
        posMap[node.id] = x to y
      }
    }

    return doc.nodes.map { n ->
      val (x, y) = posMap[n.id] ?: (n.x to n.y)
      n.copy(x = x, y = y)
    }
  }

  private fun radial(doc: GraphDocument): List<GraphNode> {
    val adj = mutableMapOf<String, MutableList<String>>()
    val inDeg = doc.nodes.associate { it.id to 0 }.toMutableMap()
    doc.edges.forEach { e ->
      if (e.fromId != e.toId) {
        adj.getOrPut(e.fromId) { mutableListOf() }.add(e.toId)
        adj.getOrPut(e.toId) { mutableListOf() }.add(e.fromId)
        inDeg[e.toId] = (inDeg[e.toId] ?: 0) + 1
      }
    }
    val root = doc.nodes.minByOrNull { inDeg[it.id] ?: 0 }?.id ?: return doc.nodes
    val depth = mutableMapOf(root to 0)
    val q = ArrayDeque<String>()
    q.addLast(root)
    while (q.isNotEmpty()) {
      val u = q.removeFirst()
      val d = depth[u] ?: 0
      adj[u].orEmpty().forEach { v ->
        if (!depth.containsKey(v)) {
          depth[v] = d + 1
          q.addLast(v)
        }
      }
    }
    doc.nodes.forEach { if (!depth.containsKey(it.id)) depth[it.id] = 1 }

    val byRing = doc.nodes.groupBy { depth[it.id] ?: 0 }
    val posMap = mutableMapOf<String, Pair<Float, Float>>()
    byRing.forEach { (ring, ringNodes) ->
      if (ring == 0 && ringNodes.size == 1) {
        posMap[ringNodes.first().id] = 0f to 0f
      } else {
        val radius = max(140f, ring * 175f)
        ringNodes.forEachIndexed { idx, node ->
          val angle = (2.0 * PI * idx) / ringNodes.size
          posMap[node.id] = (radius * cos(angle)).toFloat() to (radius * sin(angle)).toFloat()
        }
      }
    }
    return doc.nodes.map { n ->
      val (x, y) = posMap[n.id] ?: (n.x to n.y)
      n.copy(x = x, y = y)
    }
  }
}
