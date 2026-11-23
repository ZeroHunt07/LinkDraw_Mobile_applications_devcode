package com.example.engine

import com.example.model.ArrowType
import com.example.model.GraphDocument
import com.example.model.GraphEdge
import com.example.model.GraphNode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.PriorityQueue
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.max

data class ShortestPathResult(
  val found: Boolean,
  val nodeIds: List<String>,
  val edgeIds: List<String>,
  val totalWeight: Float,
)

data class NodeCentrality(
  val nodeId: String,
  val label: String,
  val degree: Int,
  val closeness: Double,
  val betweenness: Double,
  val pageRank: Double,
)

data class GlobalGraphMetrics(
  val nodeCount: Int,
  val edgeCount: Int,
  val density: Double,
  val averageDegree: Double,
  val connectedComponents: Int,
  val hasCycles: Boolean,
  val topHubLabel: String,
)

enum class TimelineEventKind(val label: String) {
  METADATA_EVENT("Event Timestamp"),
  NODE_UPDATED("Node Updated"),
  NODE_CREATED("Node Created"),
  EDGE_CREATED("Connection"),
}

data class TimelineEntry(
  val id: String,
  val title: String,
  val subtitle: String,
  val timestampMillis: Long,
  val formattedTime: String,
  val deltaLabel: String,
  val kind: TimelineEventKind,
  val targetNodeId: String? = null,
  val targetEdgeId: String? = null,
  val icon: String = "",
)

data class AdjacencyCell(
  val rowIndex: Int,
  val colIndex: Int,
  val edgeCount: Int,
  val totalWeight: Float,
  val edges: List<GraphEdge>,
)

data class AdjacencyMatrixData(
  val nodes: List<GraphNode>,
  val directed: Boolean,
  val cells: List<List<AdjacencyCell>>,
  val rowOutDegree: List<Int>,
  val rowOutWeight: List<Float>,
  val colInDegree: List<Int>,
  val colInWeight: List<Float>,
  val maxCellWeight: Float,
  val maxCellCount: Int,
  val totalWeight: Float,
  val activeDirectedLinks: Int,
  val density: Double,
  val sparsity: Double,
  val reciprocity: Double,
  val selfLoopCount: Int,
  val avgWeightedDegree: Double,
)

object GraphAlgorithms {

  fun getCollapsedHiddenSubtree(doc: GraphDocument): Set<String> {
    val collapsedRoots = doc.nodes.filter { it.collapsed }.map { it.id }
    if (collapsedRoots.isEmpty()) return emptySet()
    val adj = mutableMapOf<String, MutableList<String>>()
    doc.edges.forEach { e -> adj.getOrPut(e.fromId) { mutableListOf() }.add(e.toId) }

    val hidden = mutableSetOf<String>()
    val queue = ArrayDeque<String>()
    collapsedRoots.forEach { root ->
      adj[root]?.forEach { child ->
        if (child != root && child !in collapsedRoots && hidden.add(child)) {
          queue.addLast(child)
        }
      }
    }
    while (queue.isNotEmpty()) {
      val cur = queue.removeFirst()
      adj[cur]?.forEach { next ->
        if (next !in collapsedRoots && hidden.add(next)) {
          queue.addLast(next)
        }
      }
    }
    return hidden
  }

  fun countHiddenDescendants(doc: GraphDocument, rootId: String): Int {
    val adj = mutableMapOf<String, MutableList<String>>()
    doc.edges.forEach { e -> adj.getOrPut(e.fromId) { mutableListOf() }.add(e.toId) }
    val visited = mutableSetOf(rootId)
    val queue = ArrayDeque<String>()
    queue.addLast(rootId)
    while (queue.isNotEmpty()) {
      val cur = queue.removeFirst()
      adj[cur]?.forEach { next ->
        if (visited.add(next)) queue.addLast(next)
      }
    }
    return (visited.size - 1).coerceAtLeast(0)
  }

  fun shortestPathDijkstra(
    doc: GraphDocument,
    sourceId: String,
    targetId: String,
  ): ShortestPathResult {
    if (sourceId == targetId) {
      return ShortestPathResult(true, listOf(sourceId), emptyList(), 0f)
    }
    val adj = mutableMapOf<String, MutableList<Pair<String, GraphEdge>>>()
    doc.edges.forEach { e ->
      adj.getOrPut(e.fromId) { mutableListOf() }.add(e.toId to e)
      adj.getOrPut(e.toId) { mutableListOf() }.add(e.fromId to e)
    }
    val dist = mutableMapOf<String, Float>().withDefault { Float.POSITIVE_INFINITY }
    val prevNode = mutableMapOf<String, String>()
    val prevEdge = mutableMapOf<String, String>()
    val pq = PriorityQueue<Pair<String, Float>>(compareBy { it.second })

    dist[sourceId] = 0f
    pq.add(sourceId to 0f)

    while (pq.isNotEmpty()) {
      val (u, d) = pq.poll()!!
      if (d > dist.getValue(u)) continue
      if (u == targetId) break
      adj[u].orEmpty().forEach { (v, edge) ->
        val w = edge.weight.coerceAtLeast(0.1f)
        val alt = d + w
        if (alt < dist.getValue(v)) {
          dist[v] = alt
          prevNode[v] = u
          prevEdge[v] = edge.id
          pq.add(v to alt)
        }
      }
    }

    if (!dist.containsKey(targetId)) {
      return ShortestPathResult(false, emptyList(), emptyList(), 0f)
    }
    val pathNodes = mutableListOf<String>()
    val pathEdges = mutableListOf<String>()
    var curr: String? = targetId
    while (curr != null) {
      pathNodes.add(curr)
      val edgeId = prevEdge[curr]
      if (edgeId != null) pathEdges.add(edgeId)
      curr = prevNode[curr]
    }
    pathNodes.reverse()
    pathEdges.reverse()
    return ShortestPathResult(true, pathNodes, pathEdges, dist.getValue(targetId))
  }

  fun bfsTraversal(doc: GraphDocument, startId: String): Pair<List<String>, List<String>> {
    val adj = mutableMapOf<String, MutableList<Pair<String, String>>>()
    doc.edges.forEach { e ->
      adj.getOrPut(e.fromId) { mutableListOf() }.add(e.toId to e.id)
      adj.getOrPut(e.toId) { mutableListOf() }.add(e.fromId to e.id)
    }
    val visited = linkedSetOf<String>()
    val usedEdges = mutableListOf<String>()
    val queue = ArrayDeque<String>()
    visited.add(startId)
    queue.addLast(startId)
    while (queue.isNotEmpty()) {
      val u = queue.removeFirst()
      adj[u].orEmpty().forEach { (v, eid) ->
        if (visited.add(v)) {
          usedEdges.add(eid)
          queue.addLast(v)
        }
      }
    }
    return visited.toList() to usedEdges
  }

  fun dfsTraversal(doc: GraphDocument, startId: String): Pair<List<String>, List<String>> {
    val adj = mutableMapOf<String, MutableList<Pair<String, String>>>()
    doc.edges.forEach { e ->
      adj.getOrPut(e.fromId) { mutableListOf() }.add(e.toId to e.id)
      adj.getOrPut(e.toId) { mutableListOf() }.add(e.fromId to e.id)
    }
    val visited = linkedSetOf<String>()
    val usedEdges = mutableListOf<String>()
    fun dfs(u: String) {
      visited.add(u)
      adj[u].orEmpty().forEach { (v, eid) ->
        if (v !in visited) {
          usedEdges.add(eid)
          dfs(v)
        }
      }
    }
    dfs(startId)
    return visited.toList() to usedEdges
  }

  fun minimumSpanningTreeKruskal(doc: GraphDocument): Pair<Set<String>, Float> {
    val parent = doc.nodes.associate { it.id to it.id }.toMutableMap()
    fun find(x: String): String {
      var cur = x
      while (parent[cur] != cur) {
        parent[cur] = parent[parent[cur]] ?: cur
        cur = parent[cur] ?: cur
      }
      return cur
    }
    fun union(a: String, b: String): Boolean {
      val ra = find(a)
      val rb = find(b)
      if (ra == rb) return false
      parent[ra] = rb
      return true
    }

    val sortedEdges =
      doc.edges
        .filter { it.fromId != it.toId && parent.containsKey(it.fromId) && parent.containsKey(it.toId) }
        .sortedBy { it.weight }
    val mstEdges = mutableSetOf<String>()
    var totalCost = 0f
    sortedEdges.forEach { e ->
      if (union(e.fromId, e.toId)) {
        mstEdges.add(e.id)
        totalCost += e.weight
      }
    }
    return mstEdges to totalCost
  }

  fun topologicalSortKahn(doc: GraphDocument): List<String>? {
    val inDeg = doc.nodes.associate { it.id to 0 }.toMutableMap()
    val adj = mutableMapOf<String, MutableList<String>>()
    doc.edges.forEach { e ->
      if (e.fromId in inDeg && e.toId in inDeg && e.fromId != e.toId) {
        adj.getOrPut(e.fromId) { mutableListOf() }.add(e.toId)
        inDeg[e.toId] = (inDeg[e.toId] ?: 0) + 1
      }
    }
    val queue = ArrayDeque<String>()
    inDeg.filter { it.value == 0 }.keys.forEach { queue.addLast(it) }
    val order = mutableListOf<String>()
    while (queue.isNotEmpty()) {
      val u = queue.removeFirst()
      order.add(u)
      adj[u].orEmpty().forEach { v ->
        val next = (inDeg[v] ?: 0) - 1
        inDeg[v] = next
        if (next == 0) queue.addLast(v)
      }
    }
    return if (order.size == doc.nodes.size) order else null
  }

  fun detectCycles(doc: GraphDocument): Pair<Set<String>, Set<String>> {
    val adj = mutableMapOf<String, MutableList<GraphEdge>>()
    doc.edges.forEach { e -> adj.getOrPut(e.fromId) { mutableListOf() }.add(e) }
    val state = mutableMapOf<String, Int>() // 0=unvisited, 1=visiting, 2=done
    val cycleNodes = mutableSetOf<String>()
    val cycleEdges = mutableSetOf<String>()
    val stack = mutableListOf<String>()

    fun dfs(u: String) {
      state[u] = 1
      stack.add(u)
      adj[u].orEmpty().forEach { e ->
        val v = e.toId
        when (state[v] ?: 0) {
          1 -> {
            cycleEdges.add(e.id)
            val idx = stack.indexOf(v)
            if (idx >= 0) {
              for (i in idx until stack.size) cycleNodes.add(stack[i])
            }
          }
          0 -> dfs(v)
        }
      }
      stack.removeAt(stack.lastIndex)
      state[u] = 2
    }

    doc.nodes.forEach { n ->
      if ((state[n.id] ?: 0) == 0) dfs(n.id)
    }
    return cycleNodes to cycleEdges
  }

  fun connectedComponents(doc: GraphDocument): Map<String, Int> {
    val adj = mutableMapOf<String, MutableList<String>>()
    doc.edges.forEach { e ->
      adj.getOrPut(e.fromId) { mutableListOf() }.add(e.toId)
      adj.getOrPut(e.toId) { mutableListOf() }.add(e.fromId)
    }
    val comp = mutableMapOf<String, Int>()
    var idx = 0
    doc.nodes.forEach { n ->
      if (!comp.containsKey(n.id)) {
        val q = ArrayDeque<String>()
        q.addLast(n.id)
        comp[n.id] = idx
        while (q.isNotEmpty()) {
          val u = q.removeFirst()
          adj[u].orEmpty().forEach { v ->
            if (!comp.containsKey(v)) {
              comp[v] = idx
              q.addLast(v)
            }
          }
        }
        idx++
      }
    }
    return comp
  }

  fun computeCentralityMetrics(doc: GraphDocument): List<NodeCentrality> {
    val nCount = doc.nodes.size
    if (nCount == 0) return emptyList()
    val adj = mutableMapOf<String, MutableSet<String>>()
    doc.nodes.forEach { adj[it.id] = mutableSetOf() }
    doc.edges.forEach { e ->
      if (e.fromId in adj && e.toId in adj && e.fromId != e.toId) {
        adj[e.fromId]!!.add(e.toId)
        adj[e.toId]!!.add(e.fromId)
      }
    }

    // Brandes betweenness + closeness
    val betweenness = doc.nodes.associate { it.id to 0.0 }.toMutableMap()
    val closeness = doc.nodes.associate { it.id to 0.0 }.toMutableMap()

    for (s in doc.nodes) {
      val stack = ArrayDeque<String>()
      val pred = doc.nodes.associate { it.id to mutableListOf<String>() }
      val sigma = doc.nodes.associate { it.id to 0.0 }.toMutableMap()
      val dist = doc.nodes.associate { it.id to -1 }.toMutableMap()
      sigma[s.id] = 1.0
      dist[s.id] = 0
      val queue = ArrayDeque<String>()
      queue.addLast(s.id)

      var sumDist = 0
      var reachable = 0

      while (queue.isNotEmpty()) {
        val v = queue.removeFirst()
        stack.addLast(v)
        val dv = dist[v]!!
        if (dv > 0) {
          sumDist += dv
          reachable++
        }
        for (w in adj[v].orEmpty()) {
          if (dist[w]!! < 0) {
            queue.addLast(w)
            dist[w] = dv + 1
          }
          if (dist[w] == dv + 1) {
            sigma[w] = sigma[w]!! + sigma[v]!!
            pred[w]!!.add(v)
          }
        }
      }

      if (sumDist > 0 && nCount > 1) {
        closeness[s.id] = (reachable.toDouble() / sumDist) * (reachable.toDouble() / (nCount - 1))
      }

      val delta = doc.nodes.associate { it.id to 0.0 }.toMutableMap()
      while (stack.isNotEmpty()) {
        val w = stack.removeLast()
        for (v in pred[w].orEmpty()) {
          val ratio = (sigma[v]!! / sigma[w]!!.coerceAtLeast(1e-9)) * (1.0 + delta[w]!!)
          delta[v] = delta[v]!! + ratio
        }
        if (w != s.id) {
          betweenness[w] = betweenness[w]!! + delta[w]!!
        }
      }
    }

    val normFactor = if (nCount > 2) ((nCount - 1) * (nCount - 2)).toDouble() else 1.0
    betweenness.keys.forEach { k -> betweenness[k] = betweenness[k]!! / normFactor }

    // PageRank power iteration (25 iterations)
    var pr = doc.nodes.associate { it.id to (1.0 / nCount) }
    val damping = 0.85
    repeat(25) {
      val next = doc.nodes.associate { it.id to (1.0 - damping) / nCount }.toMutableMap()
      var sinkSum = 0.0
      doc.nodes.forEach { u ->
        val deg = adj[u.id]?.size ?: 0
        if (deg == 0) {
          sinkSum += pr[u.id]!!
        } else {
          val share = damping * pr[u.id]!! / deg
          adj[u.id]!!.forEach { v -> next[v] = next[v]!! + share }
        }
      }
      val sinkAdd = damping * sinkSum / nCount
      next.keys.forEach { k -> next[k] = next[k]!! + sinkAdd }
      pr = next
    }

    return doc.nodes
      .map { n ->
        NodeCentrality(
          nodeId = n.id,
          label = n.text,
          degree = adj[n.id]?.size ?: 0,
          closeness = closeness[n.id] ?: 0.0,
          betweenness = betweenness[n.id] ?: 0.0,
          pageRank = pr[n.id] ?: 0.0,
        )
      }
      .sortedByDescending { it.pageRank }
  }

  fun computeGlobalMetrics(doc: GraphDocument): GlobalGraphMetrics {
    val n = doc.nodes.size
    val e = doc.edges.size
    val density = if (n > 1) (2.0 * e) / (n.toDouble() * (n - 1)) else 0.0
    val degMap = doc.nodes.associate { it.id to 0 }.toMutableMap()
    doc.edges.forEach { edge ->
      if (edge.fromId in degMap) degMap[edge.fromId] = degMap[edge.fromId]!! + 1
      if (edge.toId in degMap) degMap[edge.toId] = degMap[edge.toId]!! + 1
    }
    val avgDeg = if (n > 0) degMap.values.sum().toDouble() / n else 0.0
    val comps = connectedComponents(doc).values.distinct().size
    val cycles = detectCycles(doc).first.isNotEmpty()
    val topHubId = degMap.maxByOrNull { it.value }?.key
    val topHub = doc.nodes.firstOrNull { it.id == topHubId }?.text ?: "—"
    return GlobalGraphMetrics(
      nodeCount = n,
      edgeCount = e,
      density = density,
      averageDegree = avgDeg,
      connectedComponents = comps,
      hasCycles = cycles,
      topHubLabel = topHub,
    )
  }

  fun timelineNodes(doc: GraphDocument): List<Pair<GraphNode, String>> {
    return doc.nodes
      .mapNotNull { n ->
        val ts = n.metadata["timestamp"] ?: n.metadata["date"] ?: n.metadata["time"]
        if (ts.isNullOrBlank()) null else n to ts
      }
      .sortedBy { parseTimestampMillis(it.second) ?: Long.MAX_VALUE }
  }

  fun parseTimestampMillis(raw: String): Long? {
    val s = raw.trim()
    if (s.isEmpty()) return null
    s.toLongOrNull()?.let { num ->
      return if (num < 10_000_000_000L) num * 1000L else num
    }
    val patterns =
      listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'" to true,
        "yyyy-MM-dd'T'HH:mm:ss'Z'" to true,
        "yyyy-MM-dd'T'HH:mm:ssXXX" to false,
        "yyyy-MM-dd'T'HH:mm:ss" to false,
        "yyyy-MM-dd HH:mm:ss" to false,
        "yyyy-MM-dd HH:mm" to false,
        "yyyy-MM-dd" to false,
      )
    for ((pat, isUtc) in patterns) {
      val parsed =
        runCatching {
          SimpleDateFormat(pat, Locale.US).apply {
            isLenient = false
            if (isUtc) timeZone = TimeZone.getTimeZone("UTC")
          }.parse(s)
        }.getOrNull()
      if (parsed != null) return parsed.time
    }
    return null
  }

  fun formatTimelineTimestamp(millis: Long): String {
    val fmt = SimpleDateFormat("MMM dd, yyyy • HH:mm:ss", Locale.getDefault())
    return fmt.format(Date(millis))
  }

  fun formatDurationDelta(deltaMillis: Long): String {
    if (deltaMillis <= 0L) return "t = 0"
    val sec = deltaMillis / 1000L
    return when {
      sec < 60 -> "+${sec}s"
      sec < 3600 -> "+${sec / 60}m ${sec % 60}s"
      sec < 86_400 -> "+${sec / 3600}h ${(sec % 3600) / 60}m"
      else -> "+${sec / 86_400}d ${(sec % 86_400) / 3600}h"
    }
  }

  fun buildRealTimeline(doc: GraphDocument): List<TimelineEntry> {
    val nodeMap = doc.nodes.associateBy { it.id }
    val rawItems = mutableListOf<TimelineEntry>()

    doc.nodes.forEach { n ->
      val metaTs = n.metadata["timestamp"] ?: n.metadata["date"] ?: n.metadata["time"]
      val parsedMeta = metaTs?.let { parseTimestampMillis(it) }
      val deg = doc.edges.count { it.fromId == n.id || it.toId == n.id }
      val tagsPart = if (n.tags.isNotEmpty()) " • ${n.tags.joinToString(", ")}" else ""

      if (parsedMeta != null) {
        rawItems +=
          TimelineEntry(
            id = "meta_${n.id}",
            title = n.text,
            subtitle = "${n.shape.label} • $deg link(s)$tagsPart",
            timestampMillis = parsedMeta,
            formattedTime = formatTimelineTimestamp(parsedMeta),
            deltaLabel = "",
            kind = TimelineEventKind.METADATA_EVENT,
            targetNodeId = n.id,
            icon = n.icon.ifBlank { "🕒" },
          )
      } else {
        val isUpdated = n.updatedAt > n.createdAt + 1000L
        val ts = if (isUpdated) n.updatedAt else n.createdAt
        rawItems +=
          TimelineEntry(
            id = "node_${n.id}",
            title = n.text,
            subtitle = "${n.shape.label} • $deg link(s)$tagsPart",
            timestampMillis = ts,
            formattedTime = formatTimelineTimestamp(ts),
            deltaLabel = "",
            kind = if (isUpdated) TimelineEventKind.NODE_UPDATED else TimelineEventKind.NODE_CREATED,
            targetNodeId = n.id,
            icon = n.icon.ifBlank { "⬡" },
          )
      }
    }

    doc.edges.forEach { e ->
      val from = nodeMap[e.fromId]?.text ?: e.fromId
      val to = nodeMap[e.toId]?.text ?: e.toId
      val ts = max(e.updatedAt, e.createdAt)
      val edgeLabel = if (e.label.isNotBlank()) "\"${e.label}\" • " else ""
      rawItems +=
        TimelineEntry(
          id = "edge_${e.id}",
          title = "$from → $to",
          subtitle = "${edgeLabel}Weight ${String.format(Locale.US, "%.1f", e.weight)} • ${e.type.label}",
          timestampMillis = ts,
          formattedTime = formatTimelineTimestamp(ts),
          deltaLabel = "",
          kind = TimelineEventKind.EDGE_CREATED,
          targetNodeId = e.fromId,
          targetEdgeId = e.id,
          icon = "🔗",
        )
    }

    val sorted = rawItems.sortedBy { it.timestampMillis }
    return sorted.mapIndexed { idx, entry ->
      val delta =
        if (idx == 0) "Origin"
        else formatDurationDelta(entry.timestampMillis - sorted[idx - 1].timestampMillis)
      entry.copy(deltaLabel = delta)
    }
  }

  fun computeAdjacencyMatrix(doc: GraphDocument, directed: Boolean = true): AdjacencyMatrixData {
    val nodes = doc.nodes
    val n = nodes.size
    if (n == 0) {
      return AdjacencyMatrixData(
        nodes = emptyList(),
        directed = directed,
        cells = emptyList(),
        rowOutDegree = emptyList(),
        rowOutWeight = emptyList(),
        colInDegree = emptyList(),
        colInWeight = emptyList(),
        maxCellWeight = 0f,
        maxCellCount = 0,
        totalWeight = 0f,
        activeDirectedLinks = 0,
        density = 0.0,
        sparsity = 1.0,
        reciprocity = 0.0,
        selfLoopCount = 0,
        avgWeightedDegree = 0.0,
      )
    }

    val indexMap = nodes.mapIndexed { idx, node -> node.id to idx }.toMap()
    val buckets = Array(n) { Array(n) { mutableListOf<GraphEdge>() } }
    var selfLoops = 0

    doc.edges.forEach { e ->
      val u = indexMap[e.fromId] ?: return@forEach
      val v = indexMap[e.toId] ?: return@forEach
      if (u == v) {
        selfLoops++
        buckets[u][v].add(e)
        return@forEach
      }
      if (!directed) {
        buckets[u][v].add(e)
        buckets[v][u].add(e)
      } else {
        when (e.arrow) {
          ArrowType.FORWARD -> buckets[u][v].add(e)
          ArrowType.BACKWARD -> buckets[v][u].add(e)
          ArrowType.BOTH, ArrowType.NONE -> {
            buckets[u][v].add(e)
            buckets[v][u].add(e)
          }
        }
      }
    }

    var maxW = 0f
    var maxCnt = 0
    var sumAllWeights = 0f
    var activeNonZeroCells = 0
    var reciprocalCells = 0
    var offDiagActive = 0

    val cells =
      List(n) { r ->
        List(n) { c ->
          val list = buckets[r][c].toList()
          val wSum = list.sumOf { it.weight.toDouble() }.toFloat()
          if (list.isNotEmpty()) {
            activeNonZeroCells++
            if (wSum > maxW) maxW = wSum
            if (list.size > maxCnt) maxCnt = list.size
            if (r != c) {
              offDiagActive++
              if (buckets[c][r].isNotEmpty()) reciprocalCells++
            }
          }
          AdjacencyCell(
            rowIndex = r,
            colIndex = c,
            edgeCount = list.size,
            totalWeight = wSum,
            edges = list,
          )
        }
      }

    val rowOutDeg = List(n) { r -> cells[r].sumOf { it.edgeCount } }
    val rowOutW = List(n) { r -> cells[r].sumOf { it.totalWeight.toDouble() }.toFloat() }
    val colInDeg = List(n) { c -> (0 until n).sumOf { r -> cells[r][c].edgeCount } }
    val colInW = List(n) { c -> (0 until n).sumOf { r -> cells[r][c].totalWeight.toDouble() }.toFloat() }
    sumAllWeights = doc.edges.filter { it.fromId in indexMap && it.toId in indexMap }.sumOf { it.weight.toDouble() }.toFloat()

    val possiblePairs = if (n > 1) n.toDouble() * (n - 1) else 0.0
    val density =
      if (possiblePairs > 0.0) {
        (offDiagActive.toDouble() / possiblePairs).coerceIn(0.0, 1.0)
      } else 0.0
    val reciprocity =
      if (offDiagActive > 0) reciprocalCells.toDouble() / offDiagActive.toDouble() else 0.0
    val avgWeightedDeg =
      if (n > 0) rowOutW.sum().toDouble() / n else 0.0

    return AdjacencyMatrixData(
      nodes = nodes,
      directed = directed,
      cells = cells,
      rowOutDegree = rowOutDeg,
      rowOutWeight = rowOutW,
      colInDegree = colInDeg,
      colInWeight = colInW,
      maxCellWeight = maxW,
      maxCellCount = maxCnt,
      totalWeight = sumAllWeights,
      activeDirectedLinks = activeNonZeroCells,
      density = density,
      sparsity = (1.0 - density).coerceIn(0.0, 1.0),
      reciprocity = reciprocity,
      selfLoopCount = selfLoops,
      avgWeightedDegree = avgWeightedDeg,
    )
  }
}
