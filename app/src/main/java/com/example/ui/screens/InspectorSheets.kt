package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.model.*

private val PaletteSwatches =
  listOf("#DBEAFE", "#DCFCE7", "#FEF3C7", "#FFE4E6", "#EDE9FE", "#E0F2FE", "#FFEDD5", "#F1F5F9")
private val IconPresets = listOf("", "🌐", "🖥️", "🔐", "⚡", "🗄️", "📱", "🧠", "📝", "📡", "₿", "🛡️")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeInspectorSheet(
  node: GraphNode,
  onUpdate: (GraphNode) -> Unit,
  onOpenLinkedNote: (GraphNode) -> Unit,
  onDuplicate: () -> Unit,
  onDelete: () -> Unit,
  onDismiss: () -> Unit,
) {
  var text by remember(node.id) { mutableStateOf(node.text) }
  var shape by remember(node.id) { mutableStateOf(node.shape) }
  var colorHex by remember(node.id) { mutableStateOf(node.colorHex) }
  var icon by remember(node.id) { mutableStateOf(node.icon) }
  var iconPos by remember(node.id) { mutableStateOf(node.iconPosition) }
  var tagsText by remember(node.id) { mutableStateOf(node.tags.joinToString(", ")) }
  var hasProgress by remember(node.id) { mutableStateOf(node.progress != null) }
  var progressVal by remember(node.id) { mutableStateOf((node.progress ?: 60).toFloat()) }
  var nodeWidth by remember(node.id) { mutableStateOf(node.w.coerceIn(80f, 360f)) }
  var nodeHeight by remember(node.id) { mutableStateOf(node.h.coerceIn(44f, 220f)) }
  var collapsed by remember(node.id) { mutableStateOf(node.collapsed) }
  var locked by remember(node.id) { mutableStateOf(node.locked) }
  var metaEntries by remember(node.id) { mutableStateOf(node.metadata.entries.map { it.key to it.value }) }
  var tableData by remember(node.id) { mutableStateOf(node.tableData) }
  var newMetaKey by remember { mutableStateOf("") }
  var newMetaVal by remember { mutableStateOf("") }
  var showTableDialog by remember { mutableStateOf(false) }

  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(
      modifier =
        Modifier.fillMaxWidth()
          .imePadding()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 20.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("Node Inspector", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = onDuplicate) {
          Icon(Icons.Default.ContentCopy, contentDescription = "Duplicate Node")
        }
        IconButton(onClick = onDelete) {
          Icon(Icons.Default.Delete, contentDescription = "Delete Node", tint = MaterialTheme.colorScheme.error)
        }
      }

      OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text("Label") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("inspector_node_label"),
      )

      Text("Shape", style = MaterialTheme.typography.labelLarge)
      Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        NodeShape.entries.forEach { s ->
          FilterChip(
            selected = shape == s,
            onClick = { shape = s },
            label = { Text(s.label) },
          )
        }
      }

      Text("Fill Color", style = MaterialTheme.typography.labelLarge)
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PaletteSwatches.forEach { hex ->
          val selected = colorHex.equals(hex, ignoreCase = true)
          Box(
            modifier =
              Modifier.size(34.dp)
                .clip(CircleShape)
                .background(parseHexColor(hex))
                .border(
                  width = if (selected) 3.dp else 1.dp,
                  color = if (selected) MaterialTheme.colorScheme.primary else Color(0xFF94A3B8),
                  shape = CircleShape,
                )
                .clickable { colorHex = hex },
          )
        }
      }

      Text("Icon & Placement", style = MaterialTheme.typography.labelLarge)
      Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        IconPresets.forEach { ic ->
          FilterChip(
            selected = icon == ic,
            onClick = { icon = ic },
            label = { Text(if (ic.isEmpty()) "None" else ic) },
          )
        }
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IconPosition.entries.forEach { pos ->
          FilterChip(
            selected = iconPos == pos,
            onClick = { iconPos = pos },
            label = { Text(pos.label) },
          )
        }
      }

      OutlinedTextField(
        value = tagsText,
        onValueChange = { tagsText = it },
        label = { Text("Tags (comma separated)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )

      // Progress Bar Toggle + Slider
      Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = hasProgress, onCheckedChange = { hasProgress = it })
        Text("Progress Overlay (${progressVal.toInt()}%)", style = MaterialTheme.typography.bodyMedium)
      }
      if (hasProgress) {
        Slider(
          value = progressVal,
          onValueChange = { progressVal = it },
          valueRange = 0f..100f,
        )
      }

      // Node Size Controls
      Text("Node Size (${nodeWidth.toInt()} × ${nodeHeight.toInt()})", style = MaterialTheme.typography.labelLarge)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { nodeWidth = 115f; nodeHeight = 52f }, modifier = Modifier.weight(1f)) {
          Text("Compact")
        }
        OutlinedButton(onClick = { nodeWidth = 160f; nodeHeight = 70f }, modifier = Modifier.weight(1f)) {
          Text("Standard")
        }
        OutlinedButton(onClick = { nodeWidth = 230f; nodeHeight = 104f }, modifier = Modifier.weight(1f)) {
          Text("Large")
        }
      }
      Slider(value = nodeWidth, onValueChange = { nodeWidth = it }, valueRange = 80f..360f)
      Slider(value = nodeHeight, onValueChange = { nodeHeight = it }, valueRange = 44f..220f)

      // Tree Collapse & Lock
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Switch(checked = collapsed, onCheckedChange = { collapsed = it })
          Spacer(Modifier.width(6.dp))
          Text("Fold Children", style = MaterialTheme.typography.bodyMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
          Switch(checked = locked, onCheckedChange = { locked = it })
          Spacer(Modifier.width(6.dp))
          Text("Lock Pos", style = MaterialTheme.typography.bodyMedium)
        }
      }

      // Embedded Data Table Button
      if (shape == NodeShape.TABLE || tableData != null) {
        OutlinedButton(
          onClick = { showTableDialog = true },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Icon(Icons.Default.TableChart, contentDescription = null)
          Spacer(Modifier.width(8.dp))
          Text("Edit Embedded Data Table")
        }
      }

      // Metadata Key-Value Editor
      Text("Metadata Key-Value Attributes", style = MaterialTheme.typography.labelLarge)
      metaEntries.forEachIndexed { idx, (k, v) ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          Text("$k: $v", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
          IconButton(onClick = { metaEntries = metaEntries.filterIndexed { i, _ -> i != idx } }) {
            Icon(Icons.Default.Close, contentDescription = "Remove metadata", modifier = Modifier.size(16.dp))
          }
        }
      }
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
          value = newMetaKey,
          onValueChange = { newMetaKey = it },
          label = { Text("Key") },
          singleLine = true,
          modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
          value = newMetaVal,
          onValueChange = { newMetaVal = it },
          label = { Text("Value") },
          singleLine = true,
          modifier = Modifier.weight(1f),
        )
        FilledTonalIconButton(
          onClick = {
            if (newMetaKey.isNotBlank()) {
              metaEntries = metaEntries.filterNot { it.first == newMetaKey.trim() } + (newMetaKey.trim() to newMetaVal.trim())
              newMetaKey = ""
              newMetaVal = ""
            }
          }
        ) {
          Icon(Icons.Default.Add, contentDescription = "Add Metadata")
        }
      }

      // Bi-directional Note Link
      OutlinedButton(
        onClick = {
          val updated =
            node.copy(
              text = text.ifBlank { "Node" },
              shape = shape,
              colorHex = colorHex,
              w = nodeWidth,
              h = nodeHeight,
              icon = icon,
              iconPosition = iconPos,
              tags = tagsText.split(",").map { it.trim() }.filter { it.isNotEmpty() },
              progress = if (hasProgress) progressVal.toInt() else null,
              collapsed = collapsed,
              locked = locked,
              metadata = metaEntries.toMap(),
              tableData = if (shape == NodeShape.TABLE && tableData == null) TableData() else tableData,
            )
          onUpdate(updated)
          onOpenLinkedNote(updated)
        },
        modifier = Modifier.fillMaxWidth().testTag("inspector_open_note_button"),
      ) {
        Icon(Icons.Default.Description, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Open Linked Document in Notes Studio")
      }

      Button(
        onClick = {
          onUpdate(
            node.copy(
              text = text.ifBlank { "Node" },
              shape = shape,
              colorHex = colorHex,
              w = nodeWidth,
              h = nodeHeight,
              icon = icon,
              iconPosition = iconPos,
              tags = tagsText.split(",").map { it.trim() }.filter { it.isNotEmpty() },
              progress = if (hasProgress) progressVal.toInt() else null,
              collapsed = collapsed,
              locked = locked,
              metadata = metaEntries.toMap(),
              tableData = if (shape == NodeShape.TABLE && tableData == null) TableData() else tableData,
            )
          )
          onDismiss()
        },
        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("inspector_apply_button"),
      ) {
        Text("Apply Changes")
      }
      Spacer(Modifier.height(16.dp))
    }
  }

  if (showTableDialog) {
    DataTableEditorDialog(
      initial = tableData ?: TableData(),
      onDismiss = { showTableDialog = false },
      onSave = { td ->
        tableData = td
        shape = NodeShape.TABLE
        onUpdate(node.copy(shape = NodeShape.TABLE, tableData = td))
        showTableDialog = false
      },
    )
  }
}

@Composable
fun DataTableEditorDialog(
  initial: TableData,
  onDismiss: () -> Unit,
  onSave: (TableData) -> Unit,
) {
  var headers by remember { mutableStateOf(initial.headers) }
  var rows by remember { mutableStateOf(initial.rows) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Embedded Table Editor") },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text("Headers", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          headers.forEachIndexed { c, h ->
            OutlinedTextField(
              value = h,
              onValueChange = { v -> headers = headers.mapIndexed { idx, cur -> if (idx == c) v else cur } },
              singleLine = true,
              modifier = Modifier.weight(1f),
            )
          }
        }
        Text("Rows", style = MaterialTheme.typography.labelLarge)
        rows.forEachIndexed { rIdx, row ->
          Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            headers.indices.forEach { cIdx ->
              val valStr = row.getOrElse(cIdx) { "" }
              OutlinedTextField(
                value = valStr,
                onValueChange = { v ->
                  rows =
                    rows.mapIndexed { ri, r ->
                      if (ri != rIdx) r
                      else headers.indices.map { ci -> if (ci == cIdx) v else r.getOrElse(ci) { "" } }
                    }
                },
                singleLine = true,
                modifier = Modifier.weight(1f),
              )
            }
          }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(
            onClick = { rows = rows + listOf(List(headers.size) { "" }) },
            modifier = Modifier.weight(1f),
          ) { Text("+ Row") }
          OutlinedButton(
            onClick = {
              headers = headers + "Col ${headers.size + 1}"
              rows = rows.map { it + "" }
            },
            modifier = Modifier.weight(1f),
          ) { Text("+ Column") }
        }
      }
    },
    confirmButton = { Button(onClick = { onSave(TableData(headers, rows)) }) { Text("Save Table") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EdgeInspectorSheet(
  edge: GraphEdge,
  onUpdate: (GraphEdge) -> Unit,
  onDelete: () -> Unit,
  onDismiss: () -> Unit,
) {
  var label by remember(edge.id) { mutableStateOf(edge.label) }
  var weight by remember(edge.id) { mutableStateOf(edge.weight.toString()) }
  var type by remember(edge.id) { mutableStateOf(edge.type) }
  var arrow by remember(edge.id) { mutableStateOf(edge.arrow) }
  var lineStyle by remember(edge.id) { mutableStateOf(edge.lineStyle) }

  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(
      modifier =
        Modifier.fillMaxWidth()
          .imePadding()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 20.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("Connection Inspector", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = onDelete) {
          Icon(Icons.Default.Delete, contentDescription = "Delete Edge", tint = MaterialTheme.colorScheme.error)
        }
      }
      OutlinedTextField(
        value = label,
        onValueChange = { label = it },
        label = { Text("Relationship Label") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
      OutlinedTextField(
        value = weight,
        onValueChange = { weight = it },
        label = { Text("Weight (for Dijkstra / MST)") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
      Text("Routing Type", style = MaterialTheme.typography.labelLarge)
      Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        EdgeType.entries.forEach { t ->
          FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t.label) })
        }
      }
      Text("Arrowheads", style = MaterialTheme.typography.labelLarge)
      Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        ArrowType.entries.forEach { a ->
          FilterChip(selected = arrow == a, onClick = { arrow = a }, label = { Text(a.label) })
        }
      }
      Text("Line Style", style = MaterialTheme.typography.labelLarge)
      Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        LineStyle.entries.forEach { s ->
          FilterChip(selected = lineStyle == s, onClick = { lineStyle = s }, label = { Text(s.label) })
        }
      }
      Button(
        onClick = {
          onUpdate(
            edge.copy(
              label = label,
              weight = weight.toFloatOrNull()?.coerceAtLeast(0.1f) ?: 1f,
              type = type,
              arrow = arrow,
              lineStyle = lineStyle,
            )
          )
          onDismiss()
        },
        modifier = Modifier.fillMaxWidth().height(48.dp),
      ) { Text("Apply Connection") }
      Spacer(Modifier.height(16.dp))
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StickyInspectorSheet(
  sticky: StickyNote,
  onUpdate: (StickyNote) -> Unit,
  onDelete: () -> Unit,
  onDismiss: () -> Unit,
) {
  var text by remember(sticky.id) { mutableStateOf(sticky.text) }
  var colorHex by remember(sticky.id) { mutableStateOf(sticky.colorHex) }
  val stickyColors = listOf("#FEF08A", "#BFDBFE", "#BBF7D0", "#FECDD3", "#E9D5FF")

  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(
      modifier = Modifier.fillMaxWidth().imePadding().padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Sticky Note", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = onDelete) {
          Icon(Icons.Default.Delete, contentDescription = "Delete Sticky", tint = MaterialTheme.colorScheme.error)
        }
      }
      OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text("Note Content") },
        minLines = 3,
        modifier = Modifier.fillMaxWidth(),
      )
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        stickyColors.forEach { hex ->
          Box(
            modifier =
              Modifier.size(34.dp)
                .clip(CircleShape)
                .background(parseHexColor(hex))
                .border(
                  if (colorHex == hex) 3.dp else 1.dp,
                  if (colorHex == hex) MaterialTheme.colorScheme.primary else Color.Gray,
                  CircleShape,
                )
                .clickable { colorHex = hex },
          )
        }
      }
      Button(
        onClick = {
          onUpdate(sticky.copy(text = text, colorHex = colorHex))
          onDismiss()
        },
        modifier = Modifier.fillMaxWidth().height(48.dp),
      ) { Text("Save Sticky Note") }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayersAndToolsSheet(
  layers: List<CanvasLayerState>,
  onToggleVisibility: (CanvasLayerName) -> Unit,
  onToggleLock: (CanvasLayerName) -> Unit,
  onAlignSelection: (String) -> Unit,
  onClearFreehand: () -> Unit,
  onDismiss: () -> Unit,
) {
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text("Layers & Alignment", style = MaterialTheme.typography.titleLarge)
      layers.forEach { layer ->
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = MaterialTheme.colorScheme.surfaceVariant,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(layer.name.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = { onToggleVisibility(layer.name) }) {
              Icon(
                if (layer.visible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                contentDescription = "Toggle layer visibility",
              )
            }
            IconButton(onClick = { onToggleLock(layer.name) }) {
              Icon(
                if (layer.locked) Icons.Default.Lock else Icons.Default.LockOpen,
                contentDescription = "Toggle layer lock",
              )
            }
          }
        }
      }

      Text("Align Multi-Selection", style = MaterialTheme.typography.labelLarge)
      Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        OutlinedButton(onClick = { onAlignSelection("LEFT") }) { Text("Align Left") }
        OutlinedButton(onClick = { onAlignSelection("CENTER_H") }) { Text("Center H") }
        OutlinedButton(onClick = { onAlignSelection("TOP") }) { Text("Align Top") }
        OutlinedButton(onClick = { onAlignSelection("DIST_H") }) { Text("Distribute H") }
        OutlinedButton(onClick = { onAlignSelection("DIST_V") }) { Text("Distribute V") }
      }

      OutlinedButton(
        onClick = {
          onClearFreehand()
          onDismiss()
        },
        modifier = Modifier.fillMaxWidth(),
      ) {
        Icon(Icons.Default.CleaningServices, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Clear Freehand Ink")
      }
      Spacer(Modifier.height(16.dp))
    }
  }
}
