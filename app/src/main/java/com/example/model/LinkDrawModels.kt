package com.example.model

import java.util.UUID

enum class AppThemeMode(val label: String) {
  LIGHT("Light"),
  DARK("Dark"),
  HIGH_CONTRAST("High Contrast"),
}

enum class NodeShape(val id: String, val label: String) {
  ROUNDED("rounded", "Rounded"),
  RECTANGLE("rectangle", "Rectangle"),
  CIRCLE("circle", "Circle"),
  SQUARE("square", "Square"),
  DIAMOND("diamond", "Diamond"),
  TRIANGLE("triangle", "Triangle"),
  HEXAGON("hexagon", "Hexagon"),
  STAR("star", "Star"),
  CYLINDER("cylinder", "Cylinder"),
  CLOUD("cloud", "Cloud"),
  CALLOUT("callout", "Callout"),
  PARALLELOGRAM("parallelogram", "Parallelogram"),
  TABLE("table", "Data Table"),
  PROGRESS_BAR("progress_bar", "Progress"),
  TEXT("text", "Text Label");

  companion object {
    fun fromId(id: String): NodeShape = entries.firstOrNull { it.id == id } ?: ROUNDED
  }
}

enum class IconPosition(val id: String, val label: String) {
  INLINE("inline", "Inline"),
  TOP("top", "Top"),
  LEFT("left", "Left");

  companion object {
    fun fromId(id: String): IconPosition = entries.firstOrNull { it.id == id } ?: INLINE
  }
}

enum class EdgeType(val id: String, val label: String) {
  STRAIGHT("straight", "Straight"),
  CURVED("curved", "Curved"),
  ELBOW("elbow", "Elbow"),
  ORTHOGONAL("orthogonal", "Orthogonal");

  companion object {
    fun fromId(id: String): EdgeType = entries.firstOrNull { it.id == id } ?: CURVED
  }
}

enum class ArrowType(val id: String, val label: String) {
  NONE("none", "None"),
  FORWARD("forward", "Forward"),
  BACKWARD("backward", "Backward"),
  BOTH("both", "Both");

  companion object {
    fun fromId(id: String): ArrowType = entries.firstOrNull { it.id == id } ?: FORWARD
  }
}

enum class LineStyle(val id: String, val label: String) {
  SOLID("solid", "Solid"),
  DASHED("dashed", "Dashed"),
  DOTTED("dotted", "Dotted");

  companion object {
    fun fromId(id: String): LineStyle = entries.firstOrNull { it.id == id } ?: SOLID
  }
}

enum class CanvasLayerName(val id: String, val label: String) {
  BACKGROUND("Background", "Background"),
  DATA("Data", "Data"),
  ANNOTATIONS("Annotations", "Annotations");

  companion object {
    fun fromId(id: String): CanvasLayerName =
      entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: DATA
  }
}

data class ChecklistItem(
  val id: String = UUID.randomUUID().toString(),
  val text: String,
  val checked: Boolean = false,
)

data class RichNodeNote(
  val text: String = "",
  val checklist: List<ChecklistItem> = emptyList(),
)

data class TableData(
  val headers: List<String> = listOf("Col 1", "Col 2"),
  val rows: List<List<String>> = listOf(listOf("Val A", "Val B")),
)

data class GraphNode(
  val id: String = "n_${UUID.randomUUID().toString().take(8)}",
  val x: Float = 0f,
  val y: Float = 0f,
  val w: Float = 150f,
  val h: Float = 68f,
  val text: String = "New Node",
  val shape: NodeShape = NodeShape.ROUNDED,
  val colorHex: String = "#DBEAFE",
  val textColorHex: String = "#0F172A",
  val fontSize: Float = 13f,
  val icon: String = "",
  val iconPosition: IconPosition = IconPosition.INLINE,
  val tags: List<String> = emptyList(),
  val metadata: Map<String, String> = emptyMap(),
  val richNotes: RichNodeNote = RichNodeNote(),
  val collapsed: Boolean = false,
  val tableData: TableData? = null,
  val progress: Int? = null,
  val badge: String? = null,
  val layer: CanvasLayerName = CanvasLayerName.DATA,
  val locked: Boolean = false,
  val createdAt: Long = System.currentTimeMillis(),
  val updatedAt: Long = System.currentTimeMillis(),
  val rawShapeId: String? = null,
  val extraPayload: Map<String, Any> = emptyMap(),
)

data class GraphEdge(
  val id: String = "e_${UUID.randomUUID().toString().take(8)}",
  val fromId: String,
  val toId: String,
  val label: String = "",
  val colorHex: String = "#475569",
  val width: Float = 2.4f,
  val arrow: ArrowType = ArrowType.FORWARD,
  val type: EdgeType = EdgeType.CURVED,
  val lineStyle: LineStyle = LineStyle.SOLID,
  val weight: Float = 1.0f,
  val layer: CanvasLayerName = CanvasLayerName.DATA,
  val createdAt: Long = System.currentTimeMillis(),
  val updatedAt: Long = System.currentTimeMillis(),
  val rawTypeId: String? = null,
  val rawArrowId: String? = null,
  val rawLineStyleId: String? = null,
  val extraPayload: Map<String, Any> = emptyMap(),
)

data class StickyNote(
  val id: String = "s_${UUID.randomUUID().toString().take(8)}",
  val x: Float = 0f,
  val y: Float = 0f,
  val w: Float = 170f,
  val h: Float = 120f,
  val text: String = "Sticky note",
  val colorHex: String = "#FEF08A",
  val layer: CanvasLayerName = CanvasLayerName.ANNOTATIONS,
  val createdAt: Long = System.currentTimeMillis(),
  val updatedAt: Long = System.currentTimeMillis(),
  val extraPayload: Map<String, Any> = emptyMap(),
)

data class StrokePoint(val x: Float, val y: Float)

data class FreehandStroke(
  val id: String = "f_${UUID.randomUUID().toString().take(8)}",
  val points: List<StrokePoint>,
  val colorHex: String = "#EF4444",
  val width: Float = 4f,
  val isHighlighter: Boolean = false,
  val layer: CanvasLayerName = CanvasLayerName.ANNOTATIONS,
  val extraPayload: Map<String, Any> = emptyMap(),
)

data class CanvasLayerState(
  val name: CanvasLayerName,
  val visible: Boolean = true,
  val locked: Boolean = false,
)

data class GraphSettings(
  val gridSnap: Boolean = true,
  val gridSize: Int = 24,
  val flowAnimation: Boolean = false,
)

data class GraphDocument(
  val nodes: List<GraphNode> = emptyList(),
  val edges: List<GraphEdge> = emptyList(),
  val stickyNotes: List<StickyNote> = emptyList(),
  val freehandStrokes: List<FreehandStroke> = emptyList(),
  val layers: List<CanvasLayerState> =
    listOf(
      CanvasLayerState(CanvasLayerName.BACKGROUND, visible = true, locked = false),
      CanvasLayerState(CanvasLayerName.DATA, visible = true, locked = false),
      CanvasLayerState(CanvasLayerName.ANNOTATIONS, visible = true, locked = false),
    ),
  val settings: GraphSettings = GraphSettings(),
  val unsupportedElements: List<UnsupportedElementPlaceholder> = emptyList(),
)

enum class NotePriority(val id: String, val label: String, val ribbonHex: String) {
  URGENT("urgent", "Urgent", "#EF4444"),
  HIGH("high", "High", "#F97316"),
  NORMAL("normal", "Normal", "#64748B"),
  LOW("low", "Low", "#3B82F6");

  companion object {
    fun fromId(id: String): NotePriority = entries.firstOrNull { it.id == id } ?: NORMAL
  }
}

enum class NoteType(val label: String) {
  OBSERVATION("Observation"),
  SOURCE("Source"),
  LEAD("Lead"),
  ANALYSIS("Analysis"),
  QUESTION("Question"),
  ACTION("Action");

  companion object {
    fun fromLabel(label: String): NoteType =
      entries.firstOrNull { it.label.equals(label, ignoreCase = true) } ?: OBSERVATION
  }
}

data class StudioNote(
  val id: String = UUID.randomUUID().toString(),
  val projectId: String? = null,
  val title: String = "Untitled Note",
  val body: String = "",
  val noteType: NoteType = NoteType.OBSERVATION,
  val folder: String = "General",
  val tags: List<String> = emptyList(),
  val priority: NotePriority = NotePriority.NORMAL,
  val colorHex: String = "#EEF2FF",
  val pinned: Boolean = false,
  val archived: Boolean = false,
  val linkedNodeId: String? = null,
  val linkedNodeName: String? = null,
  val sourceUrl: String = "",
  val checklist: List<ChecklistItem> = emptyList(),
  val wordCount: Int = 0,
  val updatedAt: Long = System.currentTimeMillis(),
  val createdAt: Long = System.currentTimeMillis(),
  val rawNoteType: String? = null,
  val rawPriority: String? = null,
  val extensions: Map<String, Any> = emptyMap(),
)

enum class OsintEntityType(val label: String, val icon: String, val colorHex: String) {
  IP_ADDRESS("IPv4 Address", "🖥️", "#DBEAFE"),
  DOMAIN("Domain Name", "🌐", "#DCFCE7"),
  EMAIL("Email Address", "📧", "#FEF3C7"),
  BITCOIN("Bitcoin Address", "₿", "#FFEDD5"),
  SHA256("SHA-256 Hash", "🔐", "#F3E8FF"),
  PHONE("Phone Number", "📱", "#E0F2FE"),
  SUBNET("CIDR Subnet", "📡", "#E0E7FF"),
}

data class OsintEntity(
  val value: String,
  val type: OsintEntityType,
)

data class OsintTransformResult(
  val source: OsintEntity,
  val transformName: String,
  val relationLabel: String,
  val spawned: List<OsintEntity>,
  val summary: String,
)
