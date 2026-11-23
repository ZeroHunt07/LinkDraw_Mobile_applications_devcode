package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.model.*
import com.example.viewmodel.AlgorithmHighlight
import com.example.viewmodel.CanvasToolMode
import kotlin.math.hypot
import kotlinx.coroutines.isActive

private enum class CanvasGestureMode {
  PENDING,
  TRANSFORM,
  DRAG_NODE,
  DRAG_STICKY,
  PAN,
  DRAW_STROKE,
}

@Composable
fun CanvasScreen(
  projectTitle: String,
  document: GraphDocument,
  toolMode: CanvasToolMode,
  selectedNodeIds: Set<String>,
  selectedEdgeId: String?,
  selectedStickyId: String?,
  connectSourceId: String?,
  multiSelectEnabled: Boolean,
  panOffset: Offset,
  zoomScale: Float,
  searchQuery: String,
  showLegend: Boolean,
  showMinimap: Boolean,
  canUndo: Boolean,
  canRedo: Boolean,
  algoHighlight: AlgorithmHighlight,
  onSetToolMode: (CanvasToolMode) -> Unit,
  onToggleMultiSelect: () -> Unit,
  onUpdateViewport: (Offset, Float) -> Unit,
  onZoomBy: (Float) -> Unit,
  onResetViewport: () -> Unit,
  onFitAllContent: (Float, Float) -> Unit = { _, _ -> },
  onSearchQueryChange: (String) -> Unit,
  onToggleLegend: () -> Unit,
  onToggleMinimap: () -> Unit,
  onToggleFlowAnimation: () -> Unit,
  onUndo: () -> Unit,
  onRedo: () -> Unit,
  onSelectNode: (String) -> Unit,
  onSelectEdge: (String) -> Unit,
  onSelectSticky: (String) -> Unit,
  onClearSelection: () -> Unit,
  onConnectTap: (String) -> Unit,
  onAddNodeAt: (Float, Float) -> Unit,
  onAddStickyAt: (Float, Float) -> Unit,
  onAddStroke: (List<StrokePoint>, Boolean) -> Unit,
  onCommitNodeDragDelta: (Float, Float) -> Unit,
  onCommitStickyDragDelta: (String, Float, Float) -> Unit,
  onToggleNodeCollapsed: (String) -> Unit,
  onUpdateNode: (GraphNode) -> Unit,
  onUpdateEdge: (GraphEdge) -> Unit,
  onUpdateSticky: (StickyNote) -> Unit,
  onDuplicateSelected: () -> Unit,
  onDeleteSelection: () -> Unit,
  onOpenLinkedNote: (GraphNode) -> Unit,
  onToggleLayerVisibility: (CanvasLayerName) -> Unit,
  onToggleLayerLock: (CanvasLayerName) -> Unit,
  onAlignSelection: (String) -> Unit,
  onClearFreehand: () -> Unit,
  onClearAlgoHighlight: () -> Unit,
  activeSharedBoard: CollabBoard? = null,
  remotePresence: List<BoardPresence> = emptyList(),
  boardComments: List<BoardComment> = emptyList(),
  compatibilityReport: FeatureCompatibilityReport = FeatureCompatibilityReport(),
  currentUserId: String = "",
  onPublishCurrentAsSharedBoard: () -> Unit = {},
  onSendComment: (String, Boolean) -> Unit = { _, _ -> },
  onDeleteComment: (String) -> Unit = {},
  onRemoveBoardMember: (String) -> Unit = {},
  onLeaveSharedBoard: () -> Unit = {},
  onDeleteSharedBoard: () -> Unit = {},
  onRenameSharedBoard: (String, String) -> Unit = { _, _ -> },
  onFocusNodeById: (String) -> Unit = {},
  onShowToast: (String) -> Unit = {},
) {
  val densityPx = LocalDensity.current.density
  val prepared =
    remember(document, densityPx) { buildPreparedGraphRenderState(document, densityPx) }
  val scratchPaints = remember { CanvasScratchPaints() }

  var canvasSize by remember { mutableStateOf(IntSize(800, 1200)) }
  var showSearchBar by remember { mutableStateOf(false) }
  var showLayersSheet by remember { mutableStateOf(false) }
  var showCollabSheet by remember { mutableStateOf(false) }
  var showCommentsSheet by remember { mutableStateOf(false) }
  var showCompatibilitySheet by remember { mutableStateOf(false) }
  var inspectNodeId by remember { mutableStateOf<String?>(null) }
  var inspectEdgeId by remember { mutableStateOf<String?>(null) }
  var inspectStickyId by remember { mutableStateOf<String?>(null) }

  // Ephemeral high-frequency viewport & drag states kept local so pan/zoom/drag run at 60-120fps
  // with zero parent recompositions or background formula re-evaluations.
  var localPan by remember { mutableStateOf(panOffset) }
  var localZoom by remember { mutableFloatStateOf(zoomScale) }
  var isGesturing by remember { mutableStateOf(false) }
  var activeNodeDragDelta by remember { mutableStateOf(Offset.Zero) }
  var activeStickyDragId by remember { mutableStateOf<String?>(null) }
  var activeStickyDragDelta by remember { mutableStateOf(Offset.Zero) }
  var activeStroke by remember { mutableStateOf<List<StrokePoint>>(emptyList()) }

  LaunchedEffect(panOffset, zoomScale, isGesturing) {
    if (!isGesturing) {
      localPan = panOffset
      localZoom = zoomScale
    }
  }

  // Smooth 60-120fps frame-synced edge flow phase; read strictly inside DrawScope
  val flowPhaseState = remember { mutableFloatStateOf(0f) }
  LaunchedEffect(document.settings.flowAnimation, document.edges.isNotEmpty()) {
    if (!document.settings.flowAnimation || document.edges.isEmpty()) return@LaunchedEffect
    var lastFrameMs = 0L
    while (isActive) {
      withInfiniteAnimationFrameMillis { frameMs ->
        if (lastFrameMs == 0L) lastFrameMs = frameMs
        val dtSec = ((frameMs - lastFrameMs).coerceIn(0L, 64L)) / 1000f
        lastFrameMs = frameMs
        flowPhaseState.floatValue = (flowPhaseState.floatValue + dtSec * 36f) % 20f
      }
    }
  }

  val isDarkSurface = MaterialTheme.colorScheme.background.red < 0.3f
  val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
  val selectionColor = MaterialTheme.colorScheme.primary
  val highlightColor = MaterialTheme.colorScheme.tertiary
  val defaultTextColor = MaterialTheme.colorScheme.onSurface

  val currentDoc by rememberUpdatedState(document)
  val currentPrepared by rememberUpdatedState(prepared)
  val currentTool by rememberUpdatedState(toolMode)
  val currentSelNodes by rememberUpdatedState(selectedNodeIds)
  val otherPresence =
    remember(remotePresence, currentUserId) { remotePresence.filter { it.uid != currentUserId } }

  fun screenToWorld(sx: Float, sy: Float, pan: Offset, zoom: Float): Offset {
    val cx = canvasSize.width / 2f + pan.x
    val cy = canvasSize.height / 2f + pan.y
    return Offset((sx - cx) / zoom, (sy - cy) / zoom)
  }

  fun hitTestNodeCollapseBadge(wx: Float, wy: Float): GraphNode? {
    val prep = currentPrepared
    return prep.visibleNodes.lastOrNull { n ->
      if (n.id !in prep.nodesWithChildren) return@lastOrNull false
      val bx = n.x + n.w / 2f
      val by = n.y
      hypot(wx - bx, wy - by) <= 22f
    }
  }

  fun hitTestNode(wx: Float, wy: Float): GraphNode? {
    return currentPrepared.visibleNodes.lastOrNull { n ->
      wx >= n.x - n.w / 2f - 6f &&
        wx <= n.x + n.w / 2f + 6f &&
        wy >= n.y - n.h / 2f - 6f &&
        wy <= n.y + n.h / 2f + 6f
    }
  }

  fun hitTestSticky(wx: Float, wy: Float): StickyNote? {
    if (currentPrepared.layerVisible[CanvasLayerName.ANNOTATIONS] == false) return null
    return currentDoc.stickyNotes.lastOrNull { s ->
      wx >= s.x - s.w / 2f && wx <= s.x + s.w / 2f && wy >= s.y - s.h / 2f && wy <= s.y + s.h / 2f
    }
  }

  fun hitTestEdge(wx: Float, wy: Float, zoom: Float): GraphEdge? =
    EdgeGeometryHelper.hitTestEdge(currentDoc, wx, wy, zoom)

  Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    // Main Infinite 2D Canvas with unified conflict-free multi-touch gesture handling
    Canvas(
      modifier =
        Modifier.fillMaxSize()
          .onSizeChanged { canvasSize = it }
          .pointerInput(Unit) {
            var lastTapTimeMs = 0L
            var lastTapPos = Offset.Zero

            awaitEachGesture {
              val firstDown = awaitFirstDown(requireUnconsumed = false)
              val touchSlop = viewConfiguration.touchSlop
              val doubleTapTimeout = viewConfiguration.doubleTapTimeoutMillis
              val doubleTapSlop = (touchSlop * 4.5f).coerceAtLeast(48.dp.toPx())

              var mode = CanvasGestureMode.PENDING
              val downPos = firstDown.position
              var dragStickyId: String? = null
              val strokePts = mutableListOf<StrokePoint>()
              var isHighlighterStroke = false

              isGesturing = true
              try {
                while (true) {
                  val event = awaitPointerEvent()
                  val pressedChanges = event.changes.filter { it.pressed }
                  if (pressedChanges.isEmpty()) break

                  if (pressedChanges.size >= 2) {
                    // Transition cleanly to focal-point pinch-zoom + 2-finger pan
                    if (mode == CanvasGestureMode.DRAG_NODE && activeNodeDragDelta != Offset.Zero) {
                      onCommitNodeDragDelta(activeNodeDragDelta.x, activeNodeDragDelta.y)
                      activeNodeDragDelta = Offset.Zero
                    } else if (
                      mode == CanvasGestureMode.DRAG_STICKY &&
                        dragStickyId != null &&
                        activeStickyDragDelta != Offset.Zero
                    ) {
                      onCommitStickyDragDelta(
                        dragStickyId!!,
                        activeStickyDragDelta.x,
                        activeStickyDragDelta.y,
                      )
                      activeStickyDragDelta = Offset.Zero
                      activeStickyDragId = null
                      dragStickyId = null
                    } else if (mode == CanvasGestureMode.DRAW_STROKE) {
                      strokePts.clear()
                      activeStroke = emptyList()
                    }
                    mode = CanvasGestureMode.TRANSFORM

                    val zoomChange = event.calculateZoom()
                    val panChange = event.calculatePan()
                    val centroid = event.calculateCentroid(useCurrent = true)
                    if (centroid != Offset.Unspecified) {
                      val center = Offset(canvasSize.width / 2f, canvasSize.height / 2f)
                      val next =
                        computeFocalZoomTransform(
                          currentPan = localPan,
                          currentZoom = localZoom,
                          zoomChange = zoomChange,
                          panChange = panChange,
                          focalScreenPoint = centroid,
                          canvasCenter = center,
                        )
                      localPan = next.pan
                      localZoom = next.zoom
                    } else if (panChange != Offset.Zero) {
                      localPan += panChange
                    }
                    event.changes.forEach { it.consume() }
                  } else {
                    val change = pressedChanges.first()
                    when (mode) {
                      CanvasGestureMode.TRANSFORM -> {
                        // One finger lifted after pinch: allow smooth single-finger pan continuation
                        val delta = change.positionChange()
                        if (delta != Offset.Zero) {
                          localPan += delta
                          change.consume()
                        }
                      }
                      CanvasGestureMode.PENDING -> {
                        val moveFromDown = change.position - downPos
                        if (moveFromDown.getDistance() > touchSlop) {
                          val tool = currentTool
                          if (tool == CanvasToolMode.PEN || tool == CanvasToolMode.HIGHLIGHTER) {
                            mode = CanvasGestureMode.DRAW_STROKE
                            isHighlighterStroke = tool == CanvasToolMode.HIGHLIGHTER
                            val w0 = screenToWorld(downPos.x, downPos.y, localPan, localZoom)
                            val w1 =
                              screenToWorld(change.position.x, change.position.y, localPan, localZoom)
                            strokePts.clear()
                            strokePts.add(StrokePoint(w0.x, w0.y))
                            strokePts.add(StrokePoint(w1.x, w1.y))
                            activeStroke = strokePts.toList()
                          } else if (tool == CanvasToolMode.SELECT) {
                            val wDown = screenToWorld(downPos.x, downPos.y, localPan, localZoom)
                            val lockedMap = currentDoc.layers.associate { it.name to it.locked }
                            val hitNode = hitTestNode(wDown.x, wDown.y)
                            if (hitNode != null && !hitNode.locked && lockedMap[hitNode.layer] != true) {
                              if (hitNode.id !in currentSelNodes) {
                                onSelectNode(hitNode.id)
                              }
                              mode = CanvasGestureMode.DRAG_NODE
                              activeNodeDragDelta =
                                Offset(moveFromDown.x / localZoom, moveFromDown.y / localZoom)
                            } else {
                              val hitSticky = hitTestSticky(wDown.x, wDown.y)
                              if (hitSticky != null && lockedMap[hitSticky.layer] != true) {
                                onSelectSticky(hitSticky.id)
                                mode = CanvasGestureMode.DRAG_STICKY
                                dragStickyId = hitSticky.id
                                activeStickyDragId = hitSticky.id
                                activeStickyDragDelta =
                                  Offset(moveFromDown.x / localZoom, moveFromDown.y / localZoom)
                              } else {
                                mode = CanvasGestureMode.PAN
                                localPan += moveFromDown
                              }
                            }
                          } else {
                            mode = CanvasGestureMode.PAN
                            localPan += moveFromDown
                          }
                          change.consume()
                        }
                      }
                      CanvasGestureMode.DRAG_NODE -> {
                        val delta = change.positionChange()
                        if (delta != Offset.Zero) {
                          activeNodeDragDelta += Offset(delta.x / localZoom, delta.y / localZoom)
                          change.consume()
                        }
                      }
                      CanvasGestureMode.DRAG_STICKY -> {
                        val delta = change.positionChange()
                        if (delta != Offset.Zero) {
                          activeStickyDragDelta += Offset(delta.x / localZoom, delta.y / localZoom)
                          change.consume()
                        }
                      }
                      CanvasGestureMode.PAN -> {
                        val delta = change.positionChange()
                        if (delta != Offset.Zero) {
                          localPan += delta
                          change.consume()
                        }
                      }
                      CanvasGestureMode.DRAW_STROKE -> {
                        val delta = change.positionChange()
                        if (delta != Offset.Zero) {
                          val w =
                            screenToWorld(change.position.x, change.position.y, localPan, localZoom)
                          strokePts.add(StrokePoint(w.x, w.y))
                          activeStroke = strokePts.toList()
                          change.consume()
                        }
                      }
                    }
                  }
                }

                // Pointer(s) lifted — commit gesture or handle tap / double-tap
                when (mode) {
                  CanvasGestureMode.PENDING -> {
                    val now = System.currentTimeMillis()
                    val isDoubleTap =
                      (now - lastTapTimeMs <= doubleTapTimeout) &&
                        ((downPos - lastTapPos).getDistance() <= doubleTapSlop)
                    lastTapTimeMs = if (isDoubleTap) 0L else now
                    lastTapPos = downPos

                    val w = screenToWorld(downPos.x, downPos.y, localPan, localZoom)
                    if (isDoubleTap) {
                      hitTestNode(w.x, w.y)?.let {
                        onSelectNode(it.id)
                        inspectNodeId = it.id
                      }
                        ?: hitTestSticky(w.x, w.y)?.let {
                          onSelectSticky(it.id)
                          inspectStickyId = it.id
                        }
                        ?: hitTestEdge(w.x, w.y, localZoom)?.let {
                          onSelectEdge(it.id)
                          inspectEdgeId = it.id
                        }
                    } else {
                      when (currentTool) {
                        CanvasToolMode.ADD_NODE -> onAddNodeAt(w.x, w.y)
                        CanvasToolMode.STICKY -> onAddStickyAt(w.x, w.y)
                        CanvasToolMode.CONNECT -> {
                          hitTestNode(w.x, w.y)?.let { onConnectTap(it.id) }
                        }
                        CanvasToolMode.SELECT -> {
                          val badgeHit = hitTestNodeCollapseBadge(w.x, w.y)
                          if (badgeHit != null) {
                            onToggleNodeCollapsed(badgeHit.id)
                          } else {
                            val nodeHit = hitTestNode(w.x, w.y)
                            if (nodeHit != null) {
                              onSelectNode(nodeHit.id)
                            } else {
                              val stickyHit = hitTestSticky(w.x, w.y)
                              if (stickyHit != null) {
                                onSelectSticky(stickyHit.id)
                              } else {
                                val edgeHit = hitTestEdge(w.x, w.y, localZoom)
                                if (edgeHit != null) {
                                  onSelectEdge(edgeHit.id)
                                } else {
                                  onClearSelection()
                                }
                              }
                            }
                          }
                        }
                        else -> Unit
                      }
                    }
                  }
                  CanvasGestureMode.DRAG_NODE -> {
                    if (activeNodeDragDelta != Offset.Zero) {
                      onCommitNodeDragDelta(activeNodeDragDelta.x, activeNodeDragDelta.y)
                      activeNodeDragDelta = Offset.Zero
                    }
                  }
                  CanvasGestureMode.DRAG_STICKY -> {
                    val sid = dragStickyId
                    if (sid != null && activeStickyDragDelta != Offset.Zero) {
                      onCommitStickyDragDelta(sid, activeStickyDragDelta.x, activeStickyDragDelta.y)
                    }
                    activeStickyDragDelta = Offset.Zero
                    activeStickyDragId = null
                    dragStickyId = null
                  }
                  CanvasGestureMode.PAN, CanvasGestureMode.TRANSFORM -> {
                    onUpdateViewport(localPan, localZoom)
                  }
                  CanvasGestureMode.DRAW_STROKE -> {
                    if (strokePts.size >= 2) {
                      onAddStroke(strokePts.toList(), isHighlighterStroke)
                    }
                    strokePts.clear()
                    activeStroke = emptyList()
                  }
                }
              } finally {
                isGesturing = false
                activeNodeDragDelta = Offset.Zero
                activeStickyDragDelta = Offset.Zero
                activeStickyDragId = null
                activeStroke = emptyList()
              }
            }
          }
          .testTag("infinite_graph_canvas"),
    ) {
      renderGraphCanvas(
        prepared = prepared,
        scratch = scratchPaints,
        pan = localPan,
        zoom = localZoom,
        selectedNodeIds = selectedNodeIds,
        selectedEdgeId = selectedEdgeId,
        selectedStickyId = selectedStickyId,
        connectSourceId = connectSourceId,
        algoHighlight = algoHighlight,
        searchQuery = searchQuery,
        flowPhase = flowPhaseState.floatValue,
        activeNodeDragDelta = activeNodeDragDelta,
        activeStickyDragId = activeStickyDragId,
        activeStickyDragDelta = activeStickyDragDelta,
        activeStroke = activeStroke,
        activeStrokeIsHighlighter = toolMode == CanvasToolMode.HIGHLIGHTER,
        gridColor = gridColor,
        selectionColor = selectionColor,
        highlightNodeColor = highlightColor,
        defaultTextColor = defaultTextColor,
        isDarkSurface = isDarkSurface,
        remotePresence = otherPresence,
      )
    }

    // Top Floating Header & Tool Dock
    Column(
      modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(10.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Surface(
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 4.dp,
        shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
              text = activeSharedBoard?.title ?: projectTitle,
              style = MaterialTheme.typography.titleSmall,
              maxLines = 1,
              modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            if (activeSharedBoard != null) {
              FilledTonalIconButton(
                onClick = { showCollabSheet = true },
                modifier = Modifier.size(36.dp).testTag("canvas_collab_button"),
              ) {
                Icon(
                  Icons.Default.Groups,
                  contentDescription = "Collaborators & Invite Code",
                  modifier = Modifier.size(18.dp),
                )
              }
              Spacer(Modifier.width(2.dp))
              BadgedBox(
                badge = {
                  if (boardComments.isNotEmpty()) {
                    Badge { Text("${boardComments.size}") }
                  }
                }
              ) {
                IconButton(
                  onClick = { showCommentsSheet = true },
                  modifier = Modifier.size(38.dp).testTag("canvas_comments_button"),
                ) {
                  Icon(
                    Icons.Default.Forum,
                    contentDescription = "Team Comments",
                    modifier = Modifier.size(19.dp),
                  )
                }
              }
            } else {
              IconButton(
                onClick = onPublishCurrentAsSharedBoard,
                modifier = Modifier.testTag("canvas_share_live_button"),
              ) {
                Icon(Icons.Default.GroupAdd, contentDescription = "Collaborate Live")
              }
            }
            IconButton(onClick = onUndo, enabled = canUndo, modifier = Modifier.testTag("canvas_undo")) {
              Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
            }
            IconButton(onClick = onRedo, enabled = canRedo, modifier = Modifier.testTag("canvas_redo")) {
              Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
            }
            IconButton(
              onClick = { showSearchBar = !showSearchBar },
              modifier = Modifier.testTag("canvas_search_toggle"),
            ) {
              Icon(Icons.Default.Search, contentDescription = "Search Graph")
            }
            IconButton(
              onClick = onToggleFlowAnimation,
              modifier = Modifier.testTag("canvas_flow_toggle"),
            ) {
              Icon(
                Icons.Default.Animation,
                contentDescription = "Toggle Flow Animation",
                tint =
                  if (document.settings.flowAnimation) MaterialTheme.colorScheme.primary
                  else LocalContentColor.current,
              )
            }
            IconButton(onClick = { showLayersSheet = true }, modifier = Modifier.testTag("canvas_layers_button")) {
              Icon(Icons.Default.Layers, contentDescription = "Layers & Align")
            }
          }

          if (activeSharedBoard != null) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Surface(
                color = Color(0xFF10B981).copy(alpha = 0.16f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.clickable { showCollabSheet = true },
              ) {
                Text(
                  text =
                    "● LIVE SYNC • ${remotePresence.size.coerceAtLeast(1)} online • Code ${activeSharedBoard.inviteCode}",
                  style = MaterialTheme.typography.labelSmall,
                  color = Color(0xFF047857),
                  modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
              }
              Surface(
                color =
                  if (compatibilityReport.hasFindings) Color(0xFFF59E0B).copy(alpha = 0.22f)
                  else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                shape = RoundedCornerShape(8.dp),
                modifier =
                  Modifier.clickable { showCompatibilitySheet = true }
                    .testTag("canvas_compat_pill"),
              ) {
                Text(
                  text =
                    if (compatibilityReport.hasFindings) {
                      "⚠ ${compatibilityReport.findings.size} Web Feature(s)"
                    } else {
                      "✓ Sync v${compatibilityReport.boardSchemaVersion}"
                    },
                  style = MaterialTheme.typography.labelSmall,
                  color =
                    if (compatibilityReport.hasFindings) Color(0xFFB45309)
                    else MaterialTheme.colorScheme.onSecondaryContainer,
                  modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
              }
            }
          }

          // Mode Bar
          Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            CanvasToolMode.entries.forEach { m ->
              FilterChip(
                selected = toolMode == m,
                onClick = { onSetToolMode(m) },
                label = { Text(m.label) },
                modifier = Modifier.testTag("tool_${m.name.lowercase()}"),
              )
            }
            FilterChip(
              selected = multiSelectEnabled,
              onClick = onToggleMultiSelect,
              label = { Text("Multi") },
              leadingIcon = { Icon(Icons.Default.Checklist, null, Modifier.size(16.dp)) },
            )
            FilterChip(
              selected = showLegend,
              onClick = onToggleLegend,
              label = { Text("Legend") },
            )
          }
        }
      }

      AnimatedVisibility(visible = showSearchBar) {
        Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 4.dp) {
          OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = { Text("Filter nodes by label, tag, or metadata...") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
              if (searchQuery.isNotEmpty()) {
                IconButton(onClick = { onSearchQueryChange("") }) {
                  Icon(Icons.Default.Clear, contentDescription = "Clear")
                }
              }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(6.dp),
          )
        }
      }

      if (toolMode == CanvasToolMode.CONNECT) {
        Surface(
          color = MaterialTheme.colorScheme.primaryContainer,
          shape = RoundedCornerShape(12.dp),
        ) {
          Text(
            text =
              if (connectSourceId == null) "Connect mode: Tap source node"
              else "Source selected — now tap target node (or same node for self-loop)",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
          )
        }
      } else if (algoHighlight.title.isNotBlank()) {
        Surface(
          color = MaterialTheme.colorScheme.tertiaryContainer,
          shape = RoundedCornerShape(12.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(algoHighlight.title, style = MaterialTheme.typography.labelLarge)
              Text(algoHighlight.summary, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onClearAlgoHighlight) {
              Icon(Icons.Default.Close, contentDescription = "Clear Highlight")
            }
          }
        }
      }
    }

    // Dynamic Canvas Legend Card (Bottom-Left)
    if (showLegend) {
      Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 4.dp,
        modifier =
          Modifier.align(Alignment.BottomStart)
            .padding(start = 12.dp, bottom = 76.dp)
            .widthIn(max = 180.dp),
      ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text("Legend", style = MaterialTheme.typography.labelLarge)
          val shapesPresent =
            remember(document) { document.nodes.map { it.shape }.distinct().take(4) }
          shapesPresent.forEach { s ->
            Text("• ${s.label}", style = MaterialTheme.typography.bodySmall)
          }
          val tagsPresent =
            remember(document) { document.nodes.flatMap { it.tags }.distinct().take(5) }
          if (tagsPresent.isNotEmpty()) {
            Text(
              "Tags: ${tagsPresent.joinToString(", ")}",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }

    // Interactive Minimap Radar (Tap or drag to pan camera across large graphs)
    if (showMinimap && document.nodes.isNotEmpty()) {
      var minimapSize by remember { mutableStateOf(IntSize(136, 92)) }
      Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 4.dp,
        shadowElevation = 4.dp,
        modifier =
          Modifier.align(Alignment.BottomEnd)
            .padding(end = 12.dp, bottom = 76.dp)
            .size(width = 136.dp, height = 92.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .onSizeChanged { minimapSize = it }
            .pointerInput(prepared) {
              awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                isGesturing = true
                try {
                  fun jumpTo(pos: Offset) {
                    val pad = 6.dp.toPx()
                    val inner =
                      Size(
                        (minimapSize.width - pad * 2f).coerceAtLeast(1f),
                        (minimapSize.height - pad * 2f).coerceAtLeast(1f),
                      )
                    val w =
                      minimapPointToWorld(
                        touch = Offset(pos.x - pad, pos.y - pad),
                        minimapSize = inner,
                        prepared = prepared,
                      )
                    localPan = Offset(-w.x * localZoom, -w.y * localZoom)
                  }
                  jumpTo(down.position)
                  down.consume()
                  while (true) {
                    val ev = awaitPointerEvent()
                    val active = ev.changes.firstOrNull { it.pressed } ?: break
                    jumpTo(active.position)
                    active.consume()
                  }
                  onUpdateViewport(localPan, localZoom)
                } finally {
                  isGesturing = false
                }
              }
            },
      ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(6.dp)) {
          renderMinimap(
            prepared = prepared,
            pan = localPan,
            zoom = localZoom,
            mainWidth = canvasSize.width.toFloat(),
            mainHeight = canvasSize.height.toFloat(),
          )
        }
      }
    }

    // Bottom Contextual Action Bar + Zoom Telemetry
    Surface(
      shape = RoundedCornerShape(16.dp),
      tonalElevation = 4.dp,
      shadowElevation = 4.dp,
      modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth(),
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        val singleNode =
          if (selectedNodeIds.size == 1) document.nodes.firstOrNull { it.id == selectedNodeIds.first() }
          else null
        val selEdge = selectedEdgeId?.let { id -> document.edges.firstOrNull { it.id == id } }
        val selSticky = selectedStickyId?.let { id -> document.stickyNotes.firstOrNull { it.id == id } }

        if (singleNode != null || selEdge != null || selSticky != null || selectedNodeIds.isNotEmpty()) {
          FilledTonalButton(
            onClick = {
              when {
                singleNode != null -> inspectNodeId = singleNode.id
                selEdge != null -> inspectEdgeId = selEdge.id
                selSticky != null -> inspectStickyId = selSticky.id
              }
            },
            modifier = Modifier.testTag("canvas_inspect_button"),
          ) {
            Icon(Icons.Default.Edit, null, Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Inspect")
          }
          Spacer(Modifier.width(6.dp))
          if (selectedNodeIds.isNotEmpty()) {
            IconButton(onClick = onDuplicateSelected) {
              Icon(Icons.Default.ContentCopy, contentDescription = "Duplicate Selection")
            }
          }
          IconButton(onClick = onDeleteSelection, modifier = Modifier.testTag("canvas_delete_button")) {
            Icon(
              Icons.Default.DeleteOutline,
              contentDescription = "Delete Selection",
              tint = MaterialTheme.colorScheme.error,
            )
          }
        } else {
          Text(
            text = "${document.nodes.size} nodes • ${document.edges.size} edges • ${(localZoom * 100).toInt()}%",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 6.dp),
          )
        }

        Spacer(Modifier.weight(1f))

        IconButton(
          onClick = {
            onUpdateViewport(localPan, localZoom)
            onZoomBy(0.85f)
          }
        ) {
          Icon(Icons.Default.ZoomOut, contentDescription = "Zoom Out")
        }
        IconButton(
          onClick = {
            onFitAllContent(canvasSize.width.toFloat(), canvasSize.height.toFloat())
          },
          modifier = Modifier.testTag("canvas_fit_all_button"),
        ) {
          Icon(Icons.Default.FitScreen, contentDescription = "Fit All Elements")
        }
        IconButton(onClick = onResetViewport) {
          Icon(Icons.Default.CenterFocusStrong, contentDescription = "Reset Camera")
        }
        IconButton(
          onClick = {
            onUpdateViewport(localPan, localZoom)
            onZoomBy(1.18f)
          }
        ) {
          Icon(Icons.Default.ZoomIn, contentDescription = "Zoom In")
        }
        IconButton(onClick = onToggleMinimap) {
          Icon(Icons.Default.Map, contentDescription = "Toggle Minimap")
        }
      }
    }
  }

  inspectNodeId?.let { nodeId ->
    val node = document.nodes.firstOrNull { it.id == nodeId }
    if (node != null) {
      NodeInspectorSheet(
        node = node,
        onUpdate = onUpdateNode,
        onOpenLinkedNote = onOpenLinkedNote,
        onDuplicate = {
          onDuplicateSelected()
          inspectNodeId = null
        },
        onDelete = {
          onDeleteSelection()
          inspectNodeId = null
        },
        onDismiss = { inspectNodeId = null },
      )
    } else {
      inspectNodeId = null
    }
  }

  inspectEdgeId?.let { edgeId ->
    val edge = document.edges.firstOrNull { it.id == edgeId }
    if (edge != null) {
      EdgeInspectorSheet(
        edge = edge,
        onUpdate = onUpdateEdge,
        onDelete = {
          onDeleteSelection()
          inspectEdgeId = null
        },
        onDismiss = { inspectEdgeId = null },
      )
    } else {
      inspectEdgeId = null
    }
  }

  inspectStickyId?.let { stickyId ->
    val sticky = document.stickyNotes.firstOrNull { it.id == stickyId }
    if (sticky != null) {
      StickyInspectorSheet(
        sticky = sticky,
        onUpdate = onUpdateSticky,
        onDelete = {
          onDeleteSelection()
          inspectStickyId = null
        },
        onDismiss = { inspectStickyId = null },
      )
    } else {
      inspectStickyId = null
    }
  }

  if (showLayersSheet) {
    LayersAndToolsSheet(
      layers = document.layers,
      onToggleVisibility = onToggleLayerVisibility,
      onToggleLock = onToggleLayerLock,
      onAlignSelection = onAlignSelection,
      onClearFreehand = onClearFreehand,
      onDismiss = { showLayersSheet = false },
    )
  }

  if (showCollabSheet && activeSharedBoard != null) {
    BoardCollaboratorsSheet(
      board = activeSharedBoard,
      currentUserId = currentUserId,
      presence = remotePresence,
      onCopyToast = onShowToast,
      onRenameBoard = onRenameSharedBoard,
      onRemoveMember = onRemoveBoardMember,
      onLeaveBoard = onLeaveSharedBoard,
      onDeleteBoard = onDeleteSharedBoard,
      onDismiss = { showCollabSheet = false },
    )
  }

  if (showCommentsSheet && activeSharedBoard != null) {
    val selNode =
      if (selectedNodeIds.size == 1) document.nodes.firstOrNull { it.id == selectedNodeIds.first() }
      else null
    BoardCommentsSheet(
      board = activeSharedBoard,
      comments = boardComments,
      selectedNode = selNode,
      currentUserId = currentUserId,
      onSendComment = onSendComment,
      onDeleteComment = onDeleteComment,
      onFocusNode = onFocusNodeById,
      onDismiss = { showCommentsSheet = false },
    )
  }

  if (showCompatibilitySheet && activeSharedBoard != null) {
    FeatureCompatibilitySheet(
      board = activeSharedBoard,
      report = compatibilityReport,
      onFocusCoordinates = { wx, wy ->
        onUpdateViewport(Offset(-wx * localZoom, -wy * localZoom), localZoom)
      },
      onCopyToast = onShowToast,
      onDismiss = { showCompatibilitySheet = false },
    )
  }
}
