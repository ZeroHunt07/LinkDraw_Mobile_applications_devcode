package com.example.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.GraphJsonCodec
import com.example.data.local.AppUserPreferences
import com.example.data.local.UserAccountEntity
import com.example.engine.*
import com.example.model.*
import com.example.viewmodel.AlgorithmHighlight

@Composable
fun UtilitiesScreen(
  user: UserAccountEntity?,
  projectTitle: String,
  document: GraphDocument,
  preferences: AppUserPreferences,
  algoHighlight: AlgorithmHighlight,
  centralityResults: List<NodeCentrality>,
  osintExtracted: List<OsintEntity>,
  osintTransformResult: OsintTransformResult?,
  osintBusy: Boolean,
  formulaCells: Map<String, String>,
  evaluatedFormulas: Map<String, String>,
  onApplyLayout: (LayoutAlgorithm) -> Unit,
  onRunShortestPath: (String, String) -> Unit,
  onRunBfs: (String) -> Unit,
  onRunDfs: (String) -> Unit,
  onRunMst: () -> Unit,
  onRunTopologicalSort: () -> Unit,
  onRunCycleDetection: () -> Unit,
  onRunConnectedComponents: () -> Unit,
  onRunCentrality: (Boolean) -> Unit,
  onFocusCanvasNode: (String) -> Unit,
  onFocusCanvasEdge: (String) -> Unit,
  onExtractOsint: (String) -> Unit,
  onRunOsintTransform: (OsintEntity) -> Unit,
  onSpawnOsintToCanvas: (OsintTransformResult) -> Unit,
  onUpdateFormulaCell: (String, String) -> Unit,
  onResetFormulaSheet: () -> Unit,
  onSetThemeMode: (AppThemeMode) -> Unit,
  onSetGridSnap: (Boolean) -> Unit,
  onSetGridSize: (Int) -> Unit,
  onToggleFlowAnimation: () -> Unit,
  onSignOut: () -> Unit,
  onShowToast: (String) -> Unit,
) {
  var selectedSubTab by remember { mutableIntStateOf(0) }
  val subTabs = listOf("Algos & Layouts", "OSINT Lab", "Formulas", "Export & Settings")

  Column(modifier = Modifier.fillMaxSize()) {
    Surface(tonalElevation = 2.dp) {
      Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(
          "Utilities & Intelligence Suite",
          style = MaterialTheme.typography.headlineSmall,
          modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(8.dp))
        ScrollableTabRow(selectedTabIndex = selectedSubTab, edgePadding = 12.dp) {
          subTabs.forEachIndexed { idx, label ->
            Tab(
              selected = selectedSubTab == idx,
              onClick = { selectedSubTab = idx },
              text = { Text(label) },
              modifier = Modifier.testTag("util_tab_$idx"),
            )
          }
        }
      }
    }

    when (selectedSubTab) {
      0 ->
        AlgorithmsAndLayoutsTab(
          document = document,
          algoHighlight = algoHighlight,
          centralityResults = centralityResults,
          onApplyLayout = onApplyLayout,
          onRunShortestPath = onRunShortestPath,
          onRunBfs = onRunBfs,
          onRunDfs = onRunDfs,
          onRunMst = onRunMst,
          onRunTopologicalSort = onRunTopologicalSort,
          onRunCycleDetection = onRunCycleDetection,
          onRunConnectedComponents = onRunConnectedComponents,
          onRunCentrality = onRunCentrality,
          onFocusCanvasNode = onFocusCanvasNode,
          onFocusCanvasEdge = onFocusCanvasEdge,
        )
      1 ->
        OsintWorkbenchTab(
          document = document,
          extracted = osintExtracted,
          transformResult = osintTransformResult,
          isBusy = osintBusy,
          onExtract = onExtractOsint,
          onRunTransform = onRunOsintTransform,
          onSpawnToCanvas = onSpawnOsintToCanvas,
        )
      2 ->
        FormulaSpreadsheetTab(
          cells = formulaCells,
          evaluated = evaluatedFormulas,
          onUpdateCell = onUpdateFormulaCell,
          onResetSheet = onResetFormulaSheet,
        )
      3 ->
        ExportAndSettingsTab(
          user = user,
          projectTitle = projectTitle,
          document = document,
          preferences = preferences,
          onSetThemeMode = onSetThemeMode,
          onSetGridSnap = onSetGridSnap,
          onSetGridSize = onSetGridSize,
          onToggleFlowAnimation = onToggleFlowAnimation,
          onSignOut = onSignOut,
          onShowToast = onShowToast,
        )
    }
  }
}

@Composable
private fun AlgorithmsAndLayoutsTab(
  document: GraphDocument,
  algoHighlight: AlgorithmHighlight,
  centralityResults: List<NodeCentrality>,
  onApplyLayout: (LayoutAlgorithm) -> Unit,
  onRunShortestPath: (String, String) -> Unit,
  onRunBfs: (String) -> Unit,
  onRunDfs: (String) -> Unit,
  onRunMst: () -> Unit,
  onRunTopologicalSort: () -> Unit,
  onRunCycleDetection: () -> Unit,
  onRunConnectedComponents: () -> Unit,
  onRunCentrality: (Boolean) -> Unit,
  onFocusCanvasNode: (String) -> Unit,
  onFocusCanvasEdge: (String) -> Unit,
) {
  val metrics = remember(document) { GraphAlgorithms.computeGlobalMetrics(document) }
  var sourceId by remember { mutableStateOf(document.nodes.firstOrNull()?.id ?: "") }
  var targetId by remember { mutableStateOf(document.nodes.lastOrNull()?.id ?: "") }
  LaunchedEffect(document.nodes) {
    if (document.nodes.none { it.id == sourceId }) {
      sourceId = document.nodes.firstOrNull()?.id ?: ""
    }
    if (document.nodes.none { it.id == targetId }) {
      targetId = document.nodes.lastOrNull()?.id ?: ""
    }
  }
  var showMatrixDialog by remember { mutableStateOf(false) }
  var showTimelineDialog by remember { mutableStateOf(false) }

  Column(
    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    // Global Metrics Card
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Graph Telemetry Summary", style = MaterialTheme.typography.titleMedium)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          MetricBadge("Nodes", "${metrics.nodeCount}")
          MetricBadge("Edges", "${metrics.edgeCount}")
          MetricBadge("Density", String.format("%.2f", metrics.density))
          MetricBadge("Clusters", "${metrics.connectedComponents}")
        }
        Text(
          "Avg Degree: ${String.format("%.2f", metrics.averageDegree)} • Top Hub: ${metrics.topHubLabel} • Cycles: ${if (metrics.hasCycles) "Yes" else "None"}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(onClick = { showMatrixDialog = true }, modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.GridOn, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Adjacency Matrix")
          }
          OutlinedButton(onClick = { showTimelineDialog = true }, modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.Timeline, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Timeline View")
          }
        }
      }
    }

    // Layouts
    Text("Automated Graph Layouts", style = MaterialTheme.typography.titleMedium)
    Row(
      modifier = Modifier.horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      LayoutAlgorithm.entries.forEach { layout ->
        FilledTonalButton(
          onClick = { onApplyLayout(layout) },
          modifier = Modifier.testTag("layout_${layout.name.lowercase()}"),
        ) { Text(layout.label) }
      }
    }

    // Node Pickers for Shortest Path / BFS / DFS
    if (document.nodes.isNotEmpty()) {
      Text("Path & Traversal Solvers", style = MaterialTheme.typography.titleMedium)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NodeDropdown(
          label = "Source",
          nodes = document.nodes,
          selectedId = sourceId,
          onSelect = { sourceId = it },
          modifier = Modifier.weight(1f),
        )
        NodeDropdown(
          label = "Target",
          nodes = document.nodes,
          selectedId = targetId,
          onSelect = { targetId = it },
          modifier = Modifier.weight(1f),
        )
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
          onClick = { if (sourceId.isNotBlank() && targetId.isNotBlank()) onRunShortestPath(sourceId, targetId) },
          modifier = Modifier.weight(1f).testTag("algo_shortest_path"),
        ) { Text("Shortest Path") }
        OutlinedButton(
          onClick = { if (sourceId.isNotBlank()) onRunBfs(sourceId) },
          modifier = Modifier.weight(1f),
        ) { Text("BFS") }
        OutlinedButton(
          onClick = { if (sourceId.isNotBlank()) onRunDfs(sourceId) },
          modifier = Modifier.weight(1f),
        ) { Text("DFS") }
      }
    }

    // Structural Solvers
    Text("Network Science Solvers", style = MaterialTheme.typography.titleMedium)
    Row(
      modifier = Modifier.horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      FilledTonalButton(onClick = onRunMst) { Text("Minimum Spanning Tree") }
      FilledTonalButton(onClick = onRunTopologicalSort) { Text("Topological Sort") }
      FilledTonalButton(onClick = onRunCycleDetection) { Text("Detect Cycles") }
      FilledTonalButton(onClick = onRunConnectedComponents) { Text("Components") }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(onClick = { onRunCentrality(false) }, modifier = Modifier.weight(1f)) {
        Text("Compute Centrality")
      }
      OutlinedButton(onClick = { onRunCentrality(true) }, modifier = Modifier.weight(1f)) {
        Text("Auto-Size Nodes")
      }
    }

    if (algoHighlight.title.isNotBlank()) {
      ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(modifier = Modifier.padding(12.dp)) {
          Text(algoHighlight.title, style = MaterialTheme.typography.titleSmall)
          Text(algoHighlight.summary, style = MaterialTheme.typography.bodySmall)
        }
      }
    }

    if (centralityResults.isNotEmpty()) {
      Text("Centrality & PageRank Leaderboard", style = MaterialTheme.typography.titleSmall)
      centralityResults.take(8).forEach { c ->
        Surface(
          shape = RoundedCornerShape(10.dp),
          color = MaterialTheme.colorScheme.surfaceVariant,
          modifier = Modifier.fillMaxWidth().clickable { onFocusCanvasNode(c.nodeId) },
        ) {
          Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(c.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
              "Deg ${c.degree} • PR ${String.format("%.3f", c.pageRank)} • Bet ${String.format("%.2f", c.betweenness)}",
              style = MaterialTheme.typography.labelSmall,
            )
          }
        }
      }
    }
  }

  if (showMatrixDialog) {
    AdjacencyMatrixDialog(
      document = document,
      onFocusEdge = { edgeId ->
        showMatrixDialog = false
        onFocusCanvasEdge(edgeId)
      },
      onDismiss = { showMatrixDialog = false },
    )
  }
  if (showTimelineDialog) {
    TimelineViewDialog(
      document = document,
      onFocusNode = {
        showTimelineDialog = false
        onFocusCanvasNode(it)
      },
      onFocusEdge = {
        showTimelineDialog = false
        onFocusCanvasEdge(it)
      },
      onDismiss = { showTimelineDialog = false },
    )
  }
}

@Composable
private fun MetricBadge(label: String, value: String) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
private fun NodeDropdown(
  label: String,
  nodes: List<GraphNode>,
  selectedId: String,
  onSelect: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  var expanded by remember { mutableStateOf(false) }
  val selectedLabel = nodes.firstOrNull { it.id == selectedId }?.text ?: "Select"
  Box(modifier = modifier) {
    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
      Text("$label: $selectedLabel", maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      nodes.forEach { n ->
        DropdownMenuItem(
          text = { Text(n.text) },
          onClick = {
            onSelect(n.id)
            expanded = false
          },
        )
      }
    }
  }
}

@Composable
private fun AdjacencyMatrixDialog(
  document: GraphDocument,
  onFocusEdge: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  var directed by remember { mutableStateOf(true) }
  var showWeights by remember { mutableStateOf(true) }
  var selectedCell by remember { mutableStateOf<AdjacencyCell?>(null) }

  val matrix = remember(document, directed) {
    GraphAlgorithms.computeAdjacencyMatrix(document, directed = directed)
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Adjacency Matrix & Metrics (${matrix.nodes.size}×${matrix.nodes.size})") },
    text = {
      if (matrix.nodes.isEmpty()) {
        Text("Graph has no nodes. Add nodes on the Canvas to inspect adjacency metrics.")
      } else {
        Column(
          modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          // Real Adjacency Metrics Summary
          Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Density: ${String.format("%.1f%%", matrix.density * 100.0)}", style = MaterialTheme.typography.labelMedium)
                Text("Sparsity: ${String.format("%.1f%%", matrix.sparsity * 100.0)}", style = MaterialTheme.typography.labelMedium)
              }
              Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Reciprocity: ${String.format("%.1f%%", matrix.reciprocity * 100.0)}", style = MaterialTheme.typography.labelMedium)
                Text("Total Wt: ${formatWeight(matrix.totalWeight)}", style = MaterialTheme.typography.labelMedium)
              }
              Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Avg W-Deg: ${String.format("%.2f", matrix.avgWeightedDegree)}", style = MaterialTheme.typography.labelMedium)
                Text("Self-Loops: ${matrix.selfLoopCount}", style = MaterialTheme.typography.labelMedium)
              }
            }
          }

          // Mode Toggles
          Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
              selected = directed,
              onClick = { directed = true; selectedCell = null },
              label = { Text("Directed") },
            )
            FilterChip(
              selected = !directed,
              onClick = { directed = false; selectedCell = null },
              label = { Text("Undirected") },
            )
            FilterChip(
              selected = showWeights,
              onClick = { showWeights = true },
              label = { Text("Weight") },
            )
            FilterChip(
              selected = !showWeights,
              onClick = { showWeights = false },
              label = { Text("Count") },
            )
          }

          // Scrollable 2D Matrix Grid
          Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Box(Modifier.width(84.dp))
              matrix.nodes.forEach { n ->
                Text(
                  n.text.take(7),
                  style = MaterialTheme.typography.labelSmall,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                  modifier = Modifier.width(44.dp),
                )
              }
              Text(
                if (directed) "Σ Out" else "Σ Deg",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(48.dp),
              )
            }
            Spacer(Modifier.height(4.dp))
            matrix.nodes.forEachIndexed { rIdx, rowNode ->
              Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                  rowNode.text.take(10),
                  style = MaterialTheme.typography.labelSmall,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                  modifier = Modifier.width(84.dp),
                )
                matrix.cells[rIdx].forEach { cell ->
                  val hasEdges = cell.edgeCount > 0
                  val intensity =
                    if (!hasEdges) 0f
                    else if (showWeights) {
                      (cell.totalWeight / matrix.maxCellWeight.coerceAtLeast(1f)).coerceIn(0.25f, 1f)
                    } else {
                      (cell.edgeCount.toFloat() / matrix.maxCellCount.coerceAtLeast(1)).coerceIn(0.25f, 1f)
                    }
                  val isSel = selectedCell?.rowIndex == cell.rowIndex && selectedCell?.colIndex == cell.colIndex
                  val cellBg =
                    when {
                      isSel -> MaterialTheme.colorScheme.secondaryContainer
                      hasEdges -> MaterialTheme.colorScheme.primary.copy(alpha = 0.16f + 0.54f * intensity)
                      cell.rowIndex == cell.colIndex -> MaterialTheme.colorScheme.surfaceContainerHighest
                      else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                  val labelText =
                    if (!hasEdges) "0"
                    else if (showWeights) formatWeight(cell.totalWeight)
                    else "${cell.edgeCount}"
                  Box(
                    modifier =
                      Modifier.size(42.dp, 30.dp)
                        .padding(1.5.dp)
                        .background(cellBg, RoundedCornerShape(4.dp))
                        .then(
                          if (isSel) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                          else Modifier
                        )
                        .clickable(enabled = hasEdges) { selectedCell = cell },
                    contentAlignment = Alignment.Center,
                  ) {
                    Text(
                      labelText,
                      style = MaterialTheme.typography.labelSmall,
                      fontWeight = if (hasEdges) FontWeight.SemiBold else FontWeight.Normal,
                    )
                  }
                }
                val rowSumText =
                  if (showWeights) formatWeight(matrix.rowOutWeight[rIdx])
                  else "${matrix.rowOutDegree[rIdx]}"
                Text(
                  rowSumText,
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.primary,
                  fontWeight = FontWeight.Bold,
                  modifier = Modifier.width(48.dp).padding(start = 6.dp),
                )
              }
            }
            if (directed) {
              Spacer(Modifier.height(4.dp))
              Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                  "Σ In",
                  style = MaterialTheme.typography.labelSmall,
                  fontWeight = FontWeight.Bold,
                  modifier = Modifier.width(84.dp),
                )
                matrix.nodes.indices.forEach { cIdx ->
                  val colSumText =
                    if (showWeights) formatWeight(matrix.colInWeight[cIdx])
                    else "${matrix.colInDegree[cIdx]}"
                  Text(
                    colSumText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(44.dp),
                  )
                }
              }
            }
          }

          selectedCell?.let { cell ->
            val srcName = matrix.nodes[cell.rowIndex].text
            val dstName = matrix.nodes[cell.colIndex].text
            Surface(
              shape = RoundedCornerShape(10.dp),
              color = MaterialTheme.colorScheme.secondaryContainer,
              modifier = Modifier.fillMaxWidth(),
            ) {
              Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                  "$srcName → $dstName (${cell.edgeCount} edge(s), total wt ${formatWeight(cell.totalWeight)})",
                  style = MaterialTheme.typography.labelLarge,
                )
                cell.edges.forEach { edge ->
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                  ) {
                    val lbl = edge.label.ifBlank { edge.type.label }
                    Text(
                      "• $lbl (wt ${formatWeight(edge.weight)}, ${edge.arrow.label})",
                      style = MaterialTheme.typography.bodySmall,
                      modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onFocusEdge(edge.id) }) {
                      Text("Focus")
                    }
                  }
                }
              }
            }
          }
        }
      }
    },
    confirmButton = { Button(onClick = onDismiss) { Text("Close") } },
  )
}

private fun formatWeight(w: Float): String =
  if (w % 1f == 0f) w.toInt().toString() else String.format("%.1f", w)

@Composable
private fun TimelineViewDialog(
  document: GraphDocument,
  onFocusNode: (String) -> Unit,
  onFocusEdge: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  var filterIndex by remember { mutableIntStateOf(0) }
  val filterOptions = listOf("All Activity", "Nodes", "Connections", "Tagged Events")
  val allEntries = remember(document) { GraphAlgorithms.buildRealTimeline(document) }
  val filteredEntries =
    remember(allEntries, filterIndex) {
      when (filterIndex) {
        1 -> allEntries.filter { it.kind == TimelineEventKind.NODE_CREATED || it.kind == TimelineEventKind.NODE_UPDATED || it.kind == TimelineEventKind.METADATA_EVENT }
        2 -> allEntries.filter { it.kind == TimelineEventKind.EDGE_CREATED }
        3 -> allEntries.filter { it.kind == TimelineEventKind.METADATA_EVENT }
        else -> allEntries
      }
    }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Live Graph Timeline (${filteredEntries.size})") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
          modifier = Modifier.horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          filterOptions.forEachIndexed { idx, label ->
            FilterChip(
              selected = filterIndex == idx,
              onClick = { filterIndex = idx },
              label = { Text(label) },
            )
          }
        }

        if (filteredEntries.isEmpty()) {
          Text(
            if (document.nodes.isEmpty()) {
              "Graph has no nodes or connections yet. Create nodes on the Canvas to populate the live timeline."
            } else {
              "No events match this filter. Try 'All Activity' or add a 'timestamp' metadata key in Node Inspector."
            },
            style = MaterialTheme.typography.bodySmall,
          )
        } else {
          Column(
            modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            filteredEntries.forEach { entry ->
              Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier =
                  Modifier.fillMaxWidth().clickable {
                    when {
                      entry.targetEdgeId != null -> onFocusEdge(entry.targetEdgeId)
                      entry.targetNodeId != null -> onFocusNode(entry.targetNodeId)
                    }
                  },
              ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    Text(
                      entry.formattedTime,
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                      "${entry.kind.label} • ${entry.deltaLabel}",
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.tertiary,
                    )
                  }
                  Text("${entry.icon} ${entry.title}", style = MaterialTheme.typography.titleSmall)
                  if (entry.subtitle.isNotBlank()) {
                    Text(
                      entry.subtitle,
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                }
              }
            }
          }
        }
      }
    },
    confirmButton = { Button(onClick = onDismiss) { Text("Close") } },
  )
}

@Composable
private fun OsintWorkbenchTab(
  document: GraphDocument,
  extracted: List<OsintEntity>,
  transformResult: OsintTransformResult?,
  isBusy: Boolean,
  onExtract: (String) -> Unit,
  onRunTransform: (OsintEntity) -> Unit,
  onSpawnToCanvas: (OsintTransformResult) -> Unit,
) {
  var rawText by remember {
    mutableStateOf("Target infra: example.com, 198.51.100.42, admin@example.com, 1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa")
  }

  Column(
    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text("OSINT & Threat Intelligence", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
      value = rawText,
      onValueChange = { rawText = it },
      label = { Text("Paste domains, IPs, emails, hashes, or BTC addresses") },
      minLines = 3,
      modifier = Modifier.fillMaxWidth().testTag("osint_raw_input"),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(
        onClick = { onExtract(rawText) },
        modifier = Modifier.weight(1f).testTag("osint_extract_button"),
      ) { Text("Extract Entities") }
      OutlinedButton(
        onClick = {
          val graphText = document.nodes.joinToString(" ") { it.text }
          rawText = graphText
          onExtract(graphText)
        },
        modifier = Modifier.weight(1f),
      ) { Text("Scan Active Graph") }
    }

    if (extracted.isNotEmpty()) {
      Text("Detected Entities (Tap to Run Transform)", style = MaterialTheme.typography.labelLarge)
      extracted.forEach { entity ->
        ElevatedCard(
          onClick = { onRunTransform(entity) },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(entity.type.icon, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
              Text(entity.value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
              Text(entity.type.label, style = MaterialTheme.typography.labelSmall)
            }
            Icon(Icons.Default.PlayArrow, contentDescription = "Run Transform")
          }
        }
      }
    }

    if (isBusy) {
      LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }

    transformResult?.let { res ->
      ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(res.transformName, style = MaterialTheme.typography.titleSmall)
          Text(res.summary, style = MaterialTheme.typography.bodySmall)
          res.spawned.forEach { sp ->
            Text("• ${sp.type.icon} ${sp.value} (${sp.type.label})", style = MaterialTheme.typography.bodyMedium)
          }
          Button(
            onClick = { onSpawnToCanvas(res) },
            modifier = Modifier.fillMaxWidth().testTag("osint_spawn_button"),
          ) {
            Icon(Icons.Default.AddCircleOutline, null)
            Spacer(Modifier.width(6.dp))
            Text("Spawn Entities onto Canvas")
          }
        }
      }
    }
  }
}

@Composable
private fun FormulaSpreadsheetTab(
  cells: Map<String, String>,
  evaluated: Map<String, String>,
  onUpdateCell: (String, String) -> Unit,
  onResetSheet: () -> Unit,
) {
  var selectedKey by remember { mutableStateOf("B6") }
  var editText by remember(selectedKey, cells) { mutableStateOf(cells[selectedKey] ?: "") }

  Column(
    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.weight(1f)) {
        Text("Live Graph Formula Sheet", style = MaterialTheme.typography.titleMedium)
        Text(
          "Supports =SUM(B3:B5), =AVERAGE, =IF, =NODE(\"Name\",\"progress\"), =GRAPH(\"nodes\")",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      TextButton(onClick = onResetSheet) { Text("Reset") }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      OutlinedTextField(
        value = editText,
        onValueChange = { editText = it },
        label = { Text("Cell $selectedKey Formula / Value") },
        singleLine = true,
        modifier = Modifier.weight(1f).testTag("formula_cell_input"),
      )
      Button(
        onClick = { onUpdateCell(selectedKey, editText) },
        modifier = Modifier.testTag("formula_apply_button"),
      ) { Text("Set") }
    }

    // Spreadsheet Grid
    Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
      Row {
        Box(Modifier.width(34.dp))
        for (c in 0 until FormulaEngine.COLS) {
          Box(
            modifier =
              Modifier.width(96.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(0.5.dp, MaterialTheme.colorScheme.outline)
                .padding(6.dp),
            contentAlignment = Alignment.Center,
          ) {
            Text(FormulaEngine.colLetter(c), style = MaterialTheme.typography.labelLarge)
          }
        }
      }
      for (r in 0 until FormulaEngine.ROWS) {
        Row {
          Box(
            modifier =
              Modifier.width(34.dp)
                .height(42.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(0.5.dp, MaterialTheme.colorScheme.outline),
            contentAlignment = Alignment.Center,
          ) {
            Text("${r + 1}", style = MaterialTheme.typography.labelMedium)
          }
          for (c in 0 until FormulaEngine.COLS) {
            val key = FormulaEngine.cellKey(c, r)
            val isSel = key == selectedKey
            val display = evaluated[key] ?: ""
            Box(
              modifier =
                Modifier.width(96.dp)
                  .height(42.dp)
                  .background(
                    if (isSel) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface
                  )
                  .border(
                    if (isSel) 2.dp else 0.5.dp,
                    if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                  )
                  .clickable { selectedKey = key }
                  .padding(horizontal = 6.dp),
              contentAlignment = Alignment.CenterStart,
            ) {
              Text(
                display,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun ExportAndSettingsTab(
  user: UserAccountEntity?,
  projectTitle: String,
  document: GraphDocument,
  preferences: AppUserPreferences,
  onSetThemeMode: (AppThemeMode) -> Unit,
  onSetGridSnap: (Boolean) -> Unit,
  onSetGridSize: (Int) -> Unit,
  onToggleFlowAnimation: () -> Unit,
  onSignOut: () -> Unit,
  onShowToast: (String) -> Unit,
) {
  val context = LocalContext.current
  var pendingExportContent by remember { mutableStateOf("") }

  val saveFileLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
      if (uri != null && pendingExportContent.isNotBlank()) {
        runCatching {
          context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(pendingExportContent.toByteArray(Charsets.UTF_8))
          }
          onShowToast("Exported file successfully")
        }.onFailure { onShowToast("Export failed: ${it.message}") }
      }
    }

  fun shareText(mime: String, subject: String, content: String) {
    val send =
      Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, content)
      }
    context.startActivity(Intent.createChooser(send, "Share $subject"))
  }

  Column(
    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Text("Export Active Graph ($projectTitle)", style = MaterialTheme.typography.titleMedium)

    val exports =
      listOf(
        Triple("LinkDraw JSON", "linkdraw.json") { GraphJsonCodec.encodeDocument(document) },
        Triple("Vector SVG", "graph.svg") { GraphJsonCodec.exportSvg(document, projectTitle) },
        Triple("Graphviz DOT", "graph.dot") { GraphJsonCodec.exportDot(document, projectTitle) },
        Triple("GraphML XML", "graph.graphml") { GraphJsonCodec.exportGraphMl(document) },
        Triple("Nodes & Edges CSV", "graph.csv") { GraphJsonCodec.exportCsv(document) },
      )

    exports.forEach { (label, fileName, generator) ->
      Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
          TextButton(
            onClick = {
              pendingExportContent = generator()
              saveFileLauncher.launch(fileName)
            }
          ) { Text("Save File") }
          IconButton(onClick = { shareText("text/plain", fileName, generator()) }) {
            Icon(Icons.Default.Share, contentDescription = "Share $label")
          }
        }
      }
    }

    HorizontalDivider()

    Text("Appearance & Canvas Preferences", style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      AppThemeMode.entries.forEach { mode ->
        FilterChip(
          selected = preferences.themeMode == mode,
          onClick = { onSetThemeMode(mode) },
          label = { Text(mode.label) },
          modifier = Modifier.testTag("theme_${mode.name.lowercase()}"),
        )
      }
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
      Text("Snap Nodes to Grid", modifier = Modifier.weight(1f))
      Switch(checked = preferences.gridSnap, onCheckedChange = onSetGridSnap)
    }

    Text("Grid Size: ${preferences.gridSize}dp", style = MaterialTheme.typography.bodyMedium)
    Slider(
      value = preferences.gridSize.toFloat(),
      onValueChange = { onSetGridSize(it.toInt()) },
      valueRange = 12f..48f,
    )

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
      Text("Animated Edge Flow Particles", modifier = Modifier.weight(1f))
      Switch(checked = document.settings.flowAnimation, onCheckedChange = { onToggleFlowAnimation() })
    }

    HorizontalDivider()

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
      Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
          Text(user?.displayName ?: "Analyst", style = MaterialTheme.typography.titleMedium)
          Text(
            user?.email ?: "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Button(
          onClick = onSignOut,
          colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
          modifier = Modifier.testTag("settings_signout_button"),
        ) {
          Icon(Icons.Default.Logout, null, Modifier.size(18.dp))
          Spacer(Modifier.width(6.dp))
          Text("Sign Out")
        }
      }
    }
  }
}
