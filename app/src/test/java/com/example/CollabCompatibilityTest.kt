package com.example

import com.example.data.remote.CollabElementMapper
import com.example.model.BoardElement
import com.example.model.BoardPresence
import com.example.model.CollabBoard
import com.example.model.NodeShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollabCompatibilityTest {

  @Test
  fun payloadToNode_preservesUnknownFieldsAndRawShapeAcrossRoundTrip() {
    val webPayload =
      mapOf<String, Any>(
        "left" to 320.0,
        "top" to -140.0,
        "width" to 210.0,
        "height" to 92.0,
        "label" to "Web Card",
        "shape" to "kanban_card",
        "color" to "#E0E7FF",
        "rotation" to 15.0,
        "opacity" to 0.85,
      )

    val node = CollabElementMapper.payloadToNode("n_web_1", webPayload)
    assertEquals(320f, node.x)
    assertEquals(-140f, node.y)
    assertEquals(210f, node.w)
    assertEquals(92f, node.h)
    assertEquals("Web Card", node.text)
    assertEquals(NodeShape.ROUNDED, node.shape)
    assertEquals("kanban_card", node.rawShapeId)
    assertEquals(15.0, node.extraPayload["rotation"])
    assertEquals(0.85, node.extraPayload["opacity"])

    val movedPayload = CollabElementMapper.nodeToPayload(node.copy(x = 400f))
    assertEquals(400.0, movedPayload["x"])
    assertEquals("kanban_card", movedPayload["shape"])
    assertEquals(15.0, movedPayload["rotation"])
    assertEquals(0.85, movedPayload["opacity"])
  }

  @Test
  fun payloadToEdge_acceptsSourceTargetAliasesAndPreservesCustomFields() {
    val webEdge =
      mapOf<String, Any>(
        "source" to "n1",
        "target" to "n2",
        "text" to "streams",
        "edgeType" to "bezier_3d",
        "animatedDashSpeed" to 2.5,
      )
    val edge = CollabElementMapper.payloadToEdge("e_web_1", webEdge)
    assertEquals("n1", edge.fromId)
    assertEquals("n2", edge.toId)
    assertEquals("streams", edge.label)
    assertEquals("bezier_3d", edge.rawTypeId)
    assertEquals(2.5, edge.extraPayload["animatedDashSpeed"])

    val out = CollabElementMapper.edgeToPayload(edge)
    assertEquals("bezier_3d", out["type"])
    assertEquals(2.5, out["animatedDashSpeed"])
  }

  @Test
  fun assembleAndAnalyzeCompatibility_detectsWebKindsPlaceholdersAndFields() {
    val board =
      CollabBoard(
        id = "b1",
        ownerId = "u1",
        title = "Cross-Platform Board",
        inviteCode = "WEB123",
        schemaVersion = 2L,
        extensions = mapOf("timelineAxis" to true),
      )
    val elements =
      listOf(
        BoardElement(
          id = "n1",
          kind = "node",
          payload = mapOf("x" to 10.0, "y" to 20.0, "text" to "API", "shape" to "pill_3d", "glow" to true),
          sourcePlatform = "web",
        ),
        BoardElement(
          id = "frm1",
          kind = "frame",
          payload = mapOf("x" to 500.0, "y" to 300.0, "w" to 360.0, "h" to 240.0, "title" to "DMZ Group"),
          updatedByName = "Alice",
          sourcePlatform = "web",
        ),
      )
    val presence =
      listOf(
        BoardPresence(
          id = "u2",
          uid = "u2",
          displayName = "WebUser",
          activeTab = "timeline",
          cursorX = 240.0,
          cursorY = 180.0,
          sourcePlatform = "web",
        )
      )

    val doc = CollabElementMapper.assembleDocument(board, elements)
    assertEquals(1, doc.nodes.size)
    assertEquals(1, doc.unsupportedElements.size)
    assertEquals("frame", doc.unsupportedElements.first().kind)
    assertEquals("DMZ Group", doc.unsupportedElements.first().label)
    // Unsupported placeholders are never re-exported as managed elements
    val managedIds = CollabElementMapper.documentElementEntries(doc).keys
    assertTrue("n1" in managedIds)
    assertFalse("frm1" in managedIds)

    val report = CollabElementMapper.analyzeCompatibility(board, elements, presence)
    assertTrue(report.hasFindings)
    val categories = report.findings.map { it.category }.toSet()
    assertTrue("New Element Kind" in categories)
    assertTrue("New Node Shape" in categories)
    assertTrue("New Node Fields" in categories)
    assertTrue("New Studio Tab" in categories)
    assertTrue(categories.any { it.startsWith("Board Extensions") })
  }

  @Test
  fun collabNoteRoundTrip_preservesCustomTypePriorityAndExtensions() {
    val webNote =
      com.example.model.CollabNote(
        id = "note_web_1",
        title = "Web Incident Brief",
        body = "Observed lateral beacon traffic across DMZ hosts.",
        noteType = "ThreatBrief",
        folder = "IR-2026",
        tags = listOf("beacon", "dmz"),
        priority = "critical",
        colorHex = "#FFE4E6",
        pinned = true,
        checklist = listOf(mapOf("id" to "c1", "text" to "Capture PCAP", "checked" to true)),
        wordCount = 7L,
        extensions = mapOf("aiSummary" to "Beacon every 30s", "tlp" to "AMBER"),
        sourcePlatform = "web",
      )

    val domain = CollabElementMapper.collabNoteToDomain(webNote)
    assertEquals("Web Incident Brief", domain.title)
    assertEquals("ThreatBrief", domain.rawNoteType)
    assertEquals("critical", domain.rawPriority)
    assertEquals("AMBER", domain.extensions["tlp"])
    assertEquals(1, domain.checklist.size)
    assertTrue(domain.checklist.first().checked)

    val outbound = CollabElementMapper.studioNoteToFields(domain, uid = "u1", updaterName = "Analyst")
    assertEquals("ThreatBrief", outbound["noteType"])
    assertEquals("critical", outbound["priority"])
    assertEquals(7L, outbound["wordCount"])
    @Suppress("UNCHECKED_CAST")
    val extOut = outbound["extensions"] as Map<String, Any>
    assertEquals("Beacon every 30s", extOut["aiSummary"])

    val noteCompat =
      CollabElementMapper.analyzeCompatibility(
        board = null,
        elements = emptyList(),
        presence = emptyList(),
        notes = listOf(webNote),
      )
    val cats = noteCompat.findings.map { it.category }.toSet()
    assertTrue("New Note Type" in cats)
    assertTrue("New Note Priority" in cats)
    assertTrue("New Note Fields" in cats)
  }

  @Test
  fun analyzeCompatibility_acceptsWebPayloadIdWithoutFalsePositives() {
    val board =
      CollabBoard(
        id = "board_sync",
        ownerId = "u1",
        title = "testing sync across devices",
        inviteCode = "SYNC99",
        schemaVersion = 1L,
        sourcePlatform = "web",
      )
    val elements =
      listOf(
        BoardElement(
          id = "n_1",
          kind = "node",
          payload = mapOf("id" to "n_1", "x" to 0.0, "y" to 0.0, "text" to "Node"),
          sourcePlatform = "web",
        ),
        BoardElement(
          id = "e_1",
          kind = "edge",
          payload = mapOf("id" to "e_1", "fromId" to "n_1", "toId" to "n_2"),
          sourcePlatform = "web",
        ),
        BoardElement(
          id = "s_1",
          kind = "sticky",
          payload = mapOf("id" to "s_1", "x" to 50.0, "y" to 50.0, "text" to "Note"),
          sourcePlatform = "web",
        ),
      )

    val report = CollabElementMapper.analyzeCompatibility(board, elements, emptyList())
    assertFalse(report.hasFindings)
  }
}
