package com.example.data.local

import com.example.model.*

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class ProjectTemplate(val label: String, val description: String) {
  BLANK("Blank Canvas", "Empty workspace ready for custom diagrams"),
  MICROSERVICES("Microservices Blueprint", "API Gateway, Auth, Cache, DB & Data Table"),
  OSINT_INVESTIGATION("OSINT Threat Map", "Domain, IPs, Crypto & Timeline metadata"),
  MINDMAP_TREE("Collapsible Mindmap", "NotebookLM-style expandable tree nodes"),
}

object StarterTemplates {

  private fun isoUtc(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
      timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(epochMillis))

  fun buildDocument(template: ProjectTemplate): GraphDocument =
    when (template) {
      ProjectTemplate.BLANK -> GraphDocument()
      ProjectTemplate.MICROSERVICES -> microservicesDoc()
      ProjectTemplate.OSINT_INVESTIGATION -> osintDoc()
      ProjectTemplate.MINDMAP_TREE -> mindmapDoc()
    }

  private fun microservicesDoc(): GraphDocument {
    val now = System.currentTimeMillis()
    val nodes =
      listOf(
        GraphNode(
          id = "n1",
          x = 0f,
          y = -180f,
          w = 170f,
          h = 72f,
          text = "Mobile & Web Clients",
          shape = NodeShape.CLOUD,
          colorHex = "#E0F2FE",
          icon = "📱",
          iconPosition = IconPosition.LEFT,
          tags = listOf("edge", "client"),
          metadata = mapOf("protocol" to "HTTPS/2", "timestamp" to isoUtc(now - 7200_000L)),
          createdAt = now - 7200_000L,
          updatedAt = now - 7200_000L,
        ),
        GraphNode(
          id = "n2",
          x = 0f,
          y = -40f,
          w = 180f,
          h = 74f,
          text = "Core API Gateway",
          shape = NodeShape.HEXAGON,
          colorHex = "#DBEAFE",
          icon = "🌐",
          iconPosition = IconPosition.INLINE,
          tags = listOf("gateway", "critical"),
          progress = 92,
          metadata = mapOf("ip" to "10.0.1.12", "latency" to "18ms", "timestamp" to isoUtc(now - 5400_000L)),
          createdAt = now - 5400_000L,
          updatedAt = now - 5400_000L,
          richNotes =
            RichNodeNote(
              text = "Rate limiting & JWT verification layer.",
              checklist =
                listOf(
                  ChecklistItem(text = "Configure TLS 1.3", checked = true),
                  ChecklistItem(text = "Enable circuit breaker", checked = false),
                ),
            ),
        ),
        GraphNode(
          id = "n3",
          x = -190f,
          y = 120f,
          w = 160f,
          h = 68f,
          text = "Auth Service",
          shape = NodeShape.ROUNDED,
          colorHex = "#DCFCE7",
          icon = "🔐",
          tags = listOf("security", "backend"),
          progress = 100,
          metadata = mapOf("latency" to "12ms", "timestamp" to isoUtc(now - 3600_000L)),
          createdAt = now - 3600_000L,
          updatedAt = now - 3600_000L,
        ),
        GraphNode(
          id = "n4",
          x = 0f,
          y = 120f,
          w = 160f,
          h = 68f,
          text = "Graph Analytics",
          shape = NodeShape.PROGRESS_BAR,
          colorHex = "#EDE9FE",
          icon = "⚡",
          progress = 76,
          tags = listOf("compute"),
          metadata = mapOf("latency" to "45ms", "timestamp" to isoUtc(now - 1800_000L)),
          createdAt = now - 1800_000L,
          updatedAt = now - 1800_000L,
        ),
        GraphNode(
          id = "n5",
          x = 200f,
          y = 120f,
          w = 160f,
          h = 76f,
          text = "Primary Postgres",
          shape = NodeShape.CYLINDER,
          colorHex = "#FEF3C7",
          icon = "🗄️",
          tags = listOf("database", "storage"),
          metadata = mapOf("replicas" to "3", "timestamp" to isoUtc(now - 600_000L)),
          createdAt = now - 600_000L,
          updatedAt = now - 600_000L,
        ),
        GraphNode(
          id = "n6",
          x = 0f,
          y = 280f,
          w = 240f,
          h = 120f,
          text = "SLA Telemetry",
          shape = NodeShape.TABLE,
          colorHex = "#F8FAFC",
          tags = listOf("metrics"),
          tableData =
            TableData(
              headers = listOf("Service", "P95", "Uptime"),
              rows =
                listOf(
                  listOf("Gateway", "18ms", "99.98%"),
                  listOf("Auth", "12ms", "99.99%"),
                  listOf("Analytics", "45ms", "99.92%"),
                ),
            ),
        ),
      )

    val edges =
      listOf(
        GraphEdge(id = "e1", fromId = "n1", toId = "n2", label = "TLS 1.3", weight = 1f, type = EdgeType.CURVED),
        GraphEdge(id = "e2", fromId = "n2", toId = "n3", label = "Verify", weight = 1.2f, type = EdgeType.CURVED),
        GraphEdge(id = "e3", fromId = "n2", toId = "n4", label = "Solve", weight = 2.0f, type = EdgeType.STRAIGHT),
        GraphEdge(id = "e4", fromId = "n2", toId = "n5", label = "Query", weight = 1.5f, type = EdgeType.CURVED),
        GraphEdge(id = "e5", fromId = "n4", toId = "n5", label = "Snapshot", weight = 2.5f, type = EdgeType.ORTHOGONAL, lineStyle = LineStyle.DASHED),
        GraphEdge(id = "e6", fromId = "n4", toId = "n6", label = "Metrics", weight = 1f, type = EdgeType.ELBOW),
      )

    val stickies =
      listOf(
        StickyNote(
          id = "s1",
          x = 220f,
          y = -130f,
          w = 175f,
          h = 110f,
          text = "Verify failover configuration with secondary replica before release.",
          colorHex = "#FEF08A",
        )
      )

    return GraphDocument(
      nodes = nodes,
      edges = edges,
      stickyNotes = stickies,
      settings = GraphSettings(gridSnap = true, gridSize = 24, flowAnimation = true),
    )
  }

  private fun osintDoc(): GraphDocument {
    val now = System.currentTimeMillis()
    val nodes =
      listOf(
        GraphNode(
          id = "o1",
          x = 0f,
          y = -150f,
          w = 190f,
          h = 70f,
          text = "cdn-node-alpha.example.com",
          shape = NodeShape.DIAMOND,
          colorHex = "#FFE4E6",
          icon = "🌐",
          tags = listOf("domain", "suspicious"),
          metadata = mapOf("registrar" to "ExampleReg", "timestamp" to isoUtc(now - 9000_000L)),
          createdAt = now - 9000_000L,
          updatedAt = now - 9000_000L,
        ),
        GraphNode(
          id = "o2",
          x = -170f,
          y = 10f,
          w = 165f,
          h = 66f,
          text = "198.51.100.42",
          shape = NodeShape.ROUNDED,
          colorHex = "#DBEAFE",
          icon = "🖥️",
          tags = listOf("ipv4", "c2"),
          metadata = mapOf("asn" to "AS64500", "timestamp" to isoUtc(now - 7200_000L)),
          createdAt = now - 7200_000L,
          updatedAt = now - 7200_000L,
        ),
        GraphNode(
          id = "o3",
          x = 170f,
          y = 10f,
          w = 175f,
          h = 66f,
          text = "admin@example.com",
          shape = NodeShape.ROUNDED,
          colorHex = "#FEF3C7",
          icon = "📧",
          tags = listOf("email", "contact"),
          metadata = mapOf("timestamp" to isoUtc(now - 5400_000L)),
          createdAt = now - 5400_000L,
          updatedAt = now - 5400_000L,
        ),
        GraphNode(
          id = "o4",
          x = -170f,
          y = 165f,
          w = 165f,
          h = 64f,
          text = "198.51.100.0/24",
          shape = NodeShape.PARALLELOGRAM,
          colorHex = "#E0E7FF",
          icon = "📡",
          tags = listOf("subnet"),
          metadata = mapOf("timestamp" to isoUtc(now - 3600_000L)),
          createdAt = now - 3600_000L,
          updatedAt = now - 3600_000L,
        ),
        GraphNode(
          id = "o5",
          x = 170f,
          y = 165f,
          w = 175f,
          h = 64f,
          text = "1A1zP1eP5QGefi2DMPTfTL",
          shape = NodeShape.STAR,
          colorHex = "#FFEDD5",
          icon = "₿",
          tags = listOf("wallet", "btc"),
          metadata = mapOf("timestamp" to isoUtc(now - 1200_000L)),
          createdAt = now - 1200_000L,
          updatedAt = now - 1200_000L,
        ),
      )

    val edges =
      listOf(
        GraphEdge(id = "oe1", fromId = "o1", toId = "o2", label = "DNS A", weight = 1f),
        GraphEdge(id = "oe2", fromId = "o1", toId = "o3", label = "WHOIS", weight = 1.5f),
        GraphEdge(id = "oe3", fromId = "o2", toId = "o4", label = "CIDR", weight = 1f),
        GraphEdge(id = "oe4", fromId = "o3", toId = "o5", label = "Linked", weight = 2f, lineStyle = LineStyle.DASHED),
      )

    return GraphDocument(nodes = nodes, edges = edges)
  }

  private fun mindmapDoc(): GraphDocument {
    val nodes =
      listOf(
        GraphNode(
          id = "m1",
          x = -200f,
          y = 0f,
          w = 160f,
          h = 72f,
          text = "LinkDraw Core",
          shape = NodeShape.HEXAGON,
          colorHex = "#E0E7FF",
          icon = "🧠",
          tags = listOf("root"),
        ),
        GraphNode(
          id = "m2",
          x = 20f,
          y = -110f,
          w = 155f,
          h = 64f,
          text = "Graph Engine",
          shape = NodeShape.ROUNDED,
          colorHex = "#DBEAFE",
          icon = "⚙️",
        ),
        GraphNode(
          id = "m3",
          x = 20f,
          y = 110f,
          w = 155f,
          h = 64f,
          text = "Knowledge Base",
          shape = NodeShape.ROUNDED,
          colorHex = "#DCFCE7",
          icon = "📝",
        ),
        GraphNode(
          id = "m4",
          x = 230f,
          y = -165f,
          w = 145f,
          h = 56f,
          text = "Spatial Grid",
          shape = NodeShape.ROUNDED,
          colorHex = "#F1F5F9",
        ),
        GraphNode(
          id = "m5",
          x = 230f,
          y = -55f,
          w = 145f,
          h = 56f,
          text = "Shortest Path",
          shape = NodeShape.ROUNDED,
          colorHex = "#F1F5F9",
        ),
        GraphNode(
          id = "m6",
          x = 230f,
          y = 65f,
          w = 145f,
          h = 56f,
          text = "Checklists",
          shape = NodeShape.ROUNDED,
          colorHex = "#F1F5F9",
        ),
        GraphNode(
          id = "m7",
          x = 230f,
          y = 160f,
          w = 145f,
          h = 56f,
          text = "OSINT Transforms",
          shape = NodeShape.ROUNDED,
          colorHex = "#F1F5F9",
        ),
      )

    val edges =
      listOf(
        GraphEdge(id = "me1", fromId = "m1", toId = "m2", label = "", type = EdgeType.CURVED),
        GraphEdge(id = "me2", fromId = "m1", toId = "m3", label = "", type = EdgeType.CURVED),
        GraphEdge(id = "me3", fromId = "m2", toId = "m4", label = "", type = EdgeType.CURVED),
        GraphEdge(id = "me4", fromId = "m2", toId = "m5", label = "", type = EdgeType.CURVED),
        GraphEdge(id = "me5", fromId = "m3", toId = "m6", label = "", type = EdgeType.CURVED),
        GraphEdge(id = "me6", fromId = "m3", toId = "m7", label = "", type = EdgeType.CURVED),
      )

    return GraphDocument(nodes = nodes, edges = edges)
  }

  fun starterNotes(userId: String, primaryProjectId: String): List<StudioNoteEntity> {
    val now = System.currentTimeMillis()
    return listOf(
      StudioNoteEntity(
        id = "note_${userId}_1",
        userId = userId,
        projectId = primaryProjectId,
        title = "Gateway Triage & Failover",
        body = "## Architecture Review\n- Core API Gateway handles ingress traffic and JWT verification.\n- Verify SLA latency stays under 20ms at P95.",
        noteType = NoteType.ANALYSIS.label,
        folder = "Architecture",
        tagsCsv = "gateway,critical,sla",
        priority = NotePriority.URGENT.id,
        colorHex = "#FFE4E6",
        pinned = true,
        archived = false,
        linkedNodeId = "n2",
        linkedNodeName = "Core API Gateway",
        sourceUrl = "https://internal.docs/gateway",
        checklistJson =
          com.example.data.GraphJsonCodec.encodeChecklist(
            listOf(
              ChecklistItem(text = "Verify TLS 1.3 termination", checked = true),
              ChecklistItem(text = "Confirm Postgres replica failover", checked = false),
            )
          ),
        wordCount = 24,
        updatedAt = now,
        createdAt = now,
      ),
      StudioNoteEntity(
        id = "note_${userId}_2",
        userId = userId,
        projectId = primaryProjectId,
        title = "Auth Token Rotation Plan",
        body = "Rotate signing keys every 30 days and audit downstream Auth Service latency.",
        noteType = NoteType.ACTION.label,
        folder = "Security Ops",
        tagsCsv = "auth,security",
        priority = NotePriority.HIGH.id,
        colorHex = "#FEF3C7",
        pinned = false,
        archived = false,
        linkedNodeId = "n3",
        linkedNodeName = "Auth Service",
        sourceUrl = "",
        checklistJson =
          com.example.data.GraphJsonCodec.encodeChecklist(
            listOf(ChecklistItem(text = "Audit token TTL", checked = false))
          ),
        wordCount = 13,
        updatedAt = now - 3600_000L,
        createdAt = now - 3600_000L,
      ),
    )
  }
}
