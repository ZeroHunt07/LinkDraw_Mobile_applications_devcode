package com.example

import com.example.data.GraphJsonCodec
import com.example.data.local.AuthCrypto
import com.example.data.local.ProjectTemplate
import com.example.data.local.StarterTemplates
import com.example.engine.*
import com.example.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LinkDrawEngineTest {

  @Test
  fun jsonCodecRoundTripPreservesNodesEdgesStickiesAndTables() {
    val doc = StarterTemplates.buildDocument(ProjectTemplate.MICROSERVICES)
    val encoded = GraphJsonCodec.encodeDocument(doc)
    val decoded = GraphJsonCodec.decodeDocument(encoded)

    assertEquals(doc.nodes.size, decoded.nodes.size)
    assertEquals(doc.edges.size, decoded.edges.size)
    assertEquals(doc.stickyNotes.size, decoded.stickyNotes.size)
    val tableNode = decoded.nodes.firstOrNull { it.shape == NodeShape.TABLE }
    assertNotNull(tableNode?.tableData)
    assertEquals(3, tableNode?.tableData?.headers?.size)
  }

  @Test
  fun shortestPathMstTopologicalSortAndCentralityWorkAccurately() {
    val doc = StarterTemplates.buildDocument(ProjectTemplate.MICROSERVICES)
    val path = GraphAlgorithms.shortestPathDijkstra(doc, "n1", "n6")
    assertTrue(path.found)
    assertEquals(listOf("n1", "n2", "n4", "n6"), path.nodeIds)

    val (mstEdges, totalWeight) = GraphAlgorithms.minimumSpanningTreeKruskal(doc)
    assertEquals(5, mstEdges.size)
    assertTrue(totalWeight > 0f)

    val topo = GraphAlgorithms.topologicalSortKahn(doc)
    assertNotNull(topo)
    assertEquals(doc.nodes.size, topo!!.size)

    val centrality = GraphAlgorithms.computeCentralityMetrics(doc)
    assertEquals(doc.nodes.size, centrality.size)
    assertEquals("Core API Gateway", centrality.first().label)
  }

  @Test
  fun collapsedSubtreeFoldsDownstreamChildren() {
    val doc = StarterTemplates.buildDocument(ProjectTemplate.MINDMAP_TREE)
    val collapsedDoc =
      doc.copy(nodes = doc.nodes.map { if (it.id == "m2") it.copy(collapsed = true) else it })
    val hidden = GraphAlgorithms.getCollapsedHiddenSubtree(collapsedDoc)
    assertEquals(setOf("m4", "m5"), hidden)
  }

  @Test
  fun layoutsProduceValidFiniteCoordinates() {
    val doc = StarterTemplates.buildDocument(ProjectTemplate.OSINT_INVESTIGATION)
    LayoutAlgorithm.entries.forEach { algo ->
      val laidOut = GraphLayouts.applyLayout(doc, algo)
      assertEquals(doc.nodes.size, laidOut.nodes.size)
      laidOut.nodes.forEach { n ->
        assertTrue(n.x.isFinite() && n.y.isFinite())
      }
    }
  }

  @Test
  fun formulaEngineEvaluatesLiveGraphAndRanges() {
    val doc = StarterTemplates.buildDocument(ProjectTemplate.MICROSERVICES)
    val evaluated = FormulaEngine.evaluateAll(FormulaEngine.defaultSheet(), doc)
    assertEquals("6", evaluated["B1"]) // 6 nodes
    assertEquals("6", evaluated["B2"]) // 6 edges
    assertEquals("92", evaluated["B3"]) // Gateway progress
    assertEquals("268", evaluated["B7"]) // 92 + 100 + 76
    assertEquals("Healthy", evaluated["B8"]) // Avg 89.33 > 80
  }

  @Test
  fun osintExtractorAndAuthCryptoWork() {
    val entities = OsintEngine.extractEntities("Host 198.51.100.42 on example.com email admin@example.com")
    assertTrue(entities.any { it.type == OsintEntityType.IP_ADDRESS && it.value == "198.51.100.42" })
    assertTrue(entities.any { it.type == OsintEntityType.EMAIL && it.value == "admin@example.com" })
    assertTrue(entities.any { it.type == OsintEntityType.DOMAIN && it.value == "example.com" })

    val salt = AuthCrypto.generateSalt()
    val hash = AuthCrypto.hashPassword("secret123", salt)
    assertTrue(AuthCrypto.verifyPassword("secret123", salt, hash))
    assertFalse(AuthCrypto.verifyPassword("wrong", salt, hash))
  }

  @Test
  fun svgDotGraphMlCsvExportersProduceValidOutput() {
    val doc = StarterTemplates.buildDocument(ProjectTemplate.OSINT_INVESTIGATION)
    assertTrue(GraphJsonCodec.exportSvg(doc, "Test").contains("<svg"))
    assertTrue(GraphJsonCodec.exportDot(doc, "Test").contains("digraph"))
    assertTrue(GraphJsonCodec.exportGraphMl(doc).contains("<graphml"))
    assertTrue(GraphJsonCodec.exportCsv(doc).contains("# NODES"))
  }

  @Test
  fun edgeHitTestingDetectsCurvedElbowOrthogonalAndSelfLoopConnections() {
    val n1 = GraphNode(id = "a", x = 0f, y = 0f, w = 140f, h = 60f, text = "A")
    val n2 = GraphNode(id = "b", x = 300f, y = 200f, w = 140f, h = 60f, text = "B")

    // Curved edge: midpoint at (150, 100) + perpendicular curve displacement
    val curved = GraphEdge(id = "ec", fromId = "a", toId = "b", type = EdgeType.CURVED)
    val curvedDoc = GraphDocument(nodes = listOf(n1, n2), edges = listOf(curved))
    val geomCurved = com.example.ui.screens.EdgeGeometryHelper.computeWorldGeometry(curved, n1, n2, 0, 1)
    val hitCurved =
      com.example.ui.screens.EdgeGeometryHelper.hitTestEdge(
        curvedDoc,
        geomCurved.labelPos.x,
        geomCurved.labelPos.y,
        1.0f,
      )
    assertEquals("ec", hitCurved?.id)

    // Elbow edge: corner at (300, 0)
    val elbow = GraphEdge(id = "ee", fromId = "a", toId = "b", type = EdgeType.ELBOW)
    val elbowDoc = GraphDocument(nodes = listOf(n1, n2), edges = listOf(elbow))
    assertEquals("ee", com.example.ui.screens.EdgeGeometryHelper.hitTestEdge(elbowDoc, 295f, 4f, 0.8f)?.id)

    // Orthogonal edge: vertical segment at midX = 150
    val ortho = GraphEdge(id = "eo", fromId = "a", toId = "b", type = EdgeType.ORTHOGONAL)
    val orthoDoc = GraphDocument(nodes = listOf(n1, n2), edges = listOf(ortho))
    assertEquals("eo", com.example.ui.screens.EdgeGeometryHelper.hitTestEdge(orthoDoc, 152f, 100f, 1.0f)?.id)

    // Self-loop above node a (y = -30 - 48 = -78)
    val loop = GraphEdge(id = "el", fromId = "a", toId = "a")
    val loopDoc = GraphDocument(nodes = listOf(n1), edges = listOf(loop))
    assertEquals("el", com.example.ui.screens.EdgeGeometryHelper.hitTestEdge(loopDoc, 0f, -76f, 1.0f)?.id)
  }

  @Test
  fun boundaryArrowTipsSitAtOuterNodeBorderOutsideFill() {
    val src = GraphNode(id = "s", x = 0f, y = 0f, w = 160f, h = 70f, shape = NodeShape.ROUNDED)
    val dst = GraphNode(id = "d", x = 300f, y = 0f, w = 180f, h = 70f, shape = NodeShape.RECTANGLE)
    val edge = GraphEdge(id = "e", fromId = "s", toId = "d", type = EdgeType.STRAIGHT, arrow = ArrowType.FORWARD)
    val geom = com.example.ui.screens.EdgeGeometryHelper.computeWorldGeometry(edge, src, dst, 0, 1)
    val arrow = com.example.ui.screens.EdgeGeometryHelper.findBoundaryArrow(geom.points, dst)
    assertNotNull(arrow)
    val tip = arrow!!.second
    // dst left border is at x = 300 - 90 = 210; with 2.5f margin, tip.x should be <= 207.6f (never inside dst!)
    assertTrue("Tip x=${tip.x} should be outside dst left border (210)", tip.x <= 208f)
    assertTrue("Tip x=${tip.x} should be near dst border", tip.x >= 206f)

    // Test curved edge entering a diamond
    val diamond = GraphNode(id = "dm", x = 0f, y = 240f, w = 140f, h = 100f, shape = NodeShape.DIAMOND)
    val curvedEdge = GraphEdge(id = "ec", fromId = "s", toId = "dm", type = EdgeType.CURVED)
    val curvedGeom = com.example.ui.screens.EdgeGeometryHelper.computeWorldGeometry(curvedEdge, src, diamond, 0, 1)
    val diamondArrow = com.example.ui.screens.EdgeGeometryHelper.findBoundaryArrow(curvedGeom.points, diamond)
    assertNotNull(diamondArrow)
    assertFalse(
      com.example.ui.screens.EdgeGeometryHelper.isPointInsideNode(
        diamond,
        diamondArrow!!.second.x,
        diamondArrow.second.y,
        margin = 0.5f,
      )
    )
  }

  @Test
  fun realTimelineAndAdjacencyMatrixComputeAccurateMetrics() {
    val n1 = GraphNode(id = "n1", text = "Alpha", createdAt = 1_700_000_000_000L, updatedAt = 1_700_000_000_000L)
    val n2 = GraphNode(id = "n2", text = "Beta", createdAt = 1_700_000_060_000L, updatedAt = 1_700_000_120_000L)
    val n3 = GraphNode(id = "n3", text = "Gamma", metadata = mapOf("timestamp" to "2026-01-15T10:30:00Z"))
    val e1 = GraphEdge(id = "e1", fromId = "n1", toId = "n2", weight = 1.5f, arrow = ArrowType.FORWARD, createdAt = 1_700_000_090_000L)
    val e2 = GraphEdge(id = "e2", fromId = "n2", toId = "n1", weight = 2.5f, arrow = ArrowType.FORWARD, createdAt = 1_700_000_150_000L)
    val e3 = GraphEdge(id = "e3", fromId = "n2", toId = "n3", weight = 1.0f, arrow = ArrowType.BOTH, createdAt = 1_700_000_180_000L)
    val doc = GraphDocument(nodes = listOf(n1, n2, n3), edges = listOf(e1, e2, e3))

    val timeline = GraphAlgorithms.buildRealTimeline(doc)
    assertEquals(6, timeline.size) // 3 nodes + 3 edges
    assertTrue(timeline.zipWithNext().all { (a, b) -> a.timestampMillis <= b.timestampMillis })
    assertEquals("Alpha", timeline.first().title)

    val directedMatrix = GraphAlgorithms.computeAdjacencyMatrix(doc, directed = true)
    assertEquals(3, directedMatrix.nodes.size)
    assertEquals(1.5f, directedMatrix.cells[0][1].totalWeight, 0.01f)
    assertEquals(2.5f, directedMatrix.cells[1][0].totalWeight, 0.01f)
    // BOTH edge between n2 and n3 populates both (1,2) and (2,1)
    assertEquals(1.0f, directedMatrix.cells[1][2].totalWeight, 0.01f)
    assertEquals(1.0f, directedMatrix.cells[2][1].totalWeight, 0.01f)
    assertEquals(1.0, directedMatrix.reciprocity, 0.01) // All 4 directed off-diag links are reciprocal
    assertEquals(5.0f, directedMatrix.totalWeight, 0.01f)
    assertEquals(4.0 / 6.0, directedMatrix.density, 0.01)
  }

  @Test
  fun collabElementMapperRoundTripsGraphDocumentWithoutNestedArrays() {
    val doc = StarterTemplates.buildDocument(ProjectTemplate.MICROSERVICES)
    val entries = com.example.data.remote.CollabElementMapper.documentElementEntries(doc)
    assertTrue(entries.isNotEmpty())

    // Ensure no top-level List values contain nested Lists (Firestore rejects nested arrays)
    entries.values.forEach { (_, payload) ->
      payload.values.forEach { value ->
        if (value is List<*>) {
          value.forEach { elem -> assertFalse("Nested list found in Firestore payload", elem is List<*>) }
        }
      }
    }

    val board =
      CollabBoard(
        id = "brd_test",
        ownerId = "uid_1",
        ownerName = "Alice",
        title = "Microservices",
        inviteCode = "ABC123",
        memberUids = listOf("uid_1"),
        memberNames = mapOf("uid_1" to "Alice"),
        gridSnap = true,
        gridSize = 32L,
        flowAnimation = true,
      )
    val elements =
      entries.map { (id, pair) ->
        BoardElement(id = id, kind = pair.first, payload = pair.second, updatedByUid = "uid_1")
      }
    val assembled = com.example.data.remote.CollabElementMapper.assembleDocument(board, elements)
    assertEquals(doc.nodes.size, assembled.nodes.size)
    assertEquals(doc.edges.size, assembled.edges.size)
    assertEquals(doc.stickyNotes.size, assembled.stickyNotes.size)
    assertTrue(assembled.settings.flowAnimation)
    assertEquals(32, assembled.settings.gridSize)
    val tableNode = assembled.nodes.firstOrNull { it.shape == NodeShape.TABLE }
    assertNotNull(tableNode?.tableData)
    assertEquals(3, tableNode?.tableData?.headers?.size)
  }

  @Test
  fun focalZoomKeepsWorldPointFixedUnderCentroid() {
    val center = androidx.compose.ui.geometry.Offset(500f, 800f)
    val centroid = androidx.compose.ui.geometry.Offset(720f, 440f)
    val oldPan = androidx.compose.ui.geometry.Offset(-120f, 95f)
    val oldZoom = 0.95f

    // World point originally under centroid
    val worldBefore =
      androidx.compose.ui.geometry.Offset(
        (centroid.x - (center.x + oldPan.x)) / oldZoom,
        (centroid.y - (center.y + oldPan.y)) / oldZoom,
      )

    val next =
      com.example.ui.screens.computeFocalZoomTransform(
        currentPan = oldPan,
        currentZoom = oldZoom,
        zoomChange = 1.65f,
        panChange = androidx.compose.ui.geometry.Offset.Zero,
        focalScreenPoint = centroid,
        canvasCenter = center,
      )

    // World point mapped back to screen after focal zoom must still sit at centroid
    val screenAfter =
      androidx.compose.ui.geometry.Offset(
        center.x + next.pan.x + worldBefore.x * next.zoom,
        center.y + next.pan.y + worldBefore.y * next.zoom,
      )
    assertEquals(centroid.x, screenAfter.x, 0.05f)
    assertEquals(centroid.y, screenAfter.y, 0.05f)
  }

  @Test
  fun preparedRenderStateCachesLayoutsAndBounds() {
    val doc = StarterTemplates.buildDocument(ProjectTemplate.MICROSERVICES)
    val prepared = com.example.ui.screens.buildPreparedGraphRenderState(doc, density = 2f)
    assertEquals(doc.nodes.size, prepared.visibleNodes.size)
    assertEquals(doc.edges.size, prepared.edgeEntries.size)
    assertTrue(prepared.worldWidth > 100f)
    assertTrue(prepared.worldHeight > 100f)
    val tableNode = doc.nodes.first { it.shape == NodeShape.TABLE }
    assertTrue(tableNode.id in prepared.tableTextCache)
    val regularNodes = doc.nodes.filter { it.shape != NodeShape.TABLE }
    regularNodes.forEach { n -> assertTrue(n.id in prepared.nodeTextCache) }
  }
}
