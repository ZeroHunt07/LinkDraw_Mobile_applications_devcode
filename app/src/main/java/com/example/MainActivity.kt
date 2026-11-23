package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.auth.authStateFlow
import com.example.auth.signOutGoogle
import com.example.data.local.LinkDrawDatabase
import com.example.data.local.LinkDrawRepository
import com.example.data.local.SessionPreferences
import com.example.data.remote.CollaborationRepository
import com.example.ui.screens.*
import com.example.ui.theme.LinkDrawTheme
import com.example.viewmodel.AppDestination
import com.example.viewmodel.LinkDrawViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent { LinkDrawAppRoot() }
  }
}

@Composable
private fun LinkDrawAppRoot() {
  val auth = Firebase.auth
  val currentFirebaseUser by
    auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)

  val user = currentFirebaseUser
  if (user == null) {
    LinkDrawTheme {
      AuthScreen(
        onAuthSuccess = {
          // AuthStateListener updates currentFirebaseUser reactively
        }
      )
    }
  } else {
    val uid = user.uid
    key(uid) {
      val vm: LinkDrawViewModel =
        viewModel(
          key = uid,
          factory =
            viewModelFactory {
              initializer {
                val app =
                  checkNotNull(this[APPLICATION_KEY]) {
                    "APPLICATION_KEY missing from CreationExtras"
                  }
                val databaseId = app.getString(R.string.firestore_database_id)
                val firestore = FirebaseFirestore.getInstance(databaseId)
                val db = LinkDrawDatabase.getInstance(app)
                val prefs = SessionPreferences(app)
                val localRepo = LinkDrawRepository(db.linkDrawDao(), prefs)
                val collabRepo = CollaborationRepository(firestore, uid)
                LinkDrawViewModel(
                  repo = localRepo,
                  collabRepo = collabRepo,
                  currentUserId = uid,
                  currentUserName =
                    user.displayName ?: user.email?.substringBefore("@") ?: "Analyst",
                  currentUserEmail = user.email ?: "",
                )
              }
            },
        )
      val prefs by vm.userPreferences.collectAsStateWithLifecycle()
      LinkDrawTheme(themeMode = prefs.themeMode) {
        LinkDrawWorkspace(vm = vm)
      }
    }
  }
}

@Composable
private fun LinkDrawWorkspace(vm: LinkDrawViewModel) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val credentialManager = remember(context) { CredentialManager.create(context) }

  val currentTab by vm.currentTab.collectAsStateWithLifecycle()
  val currentUser by vm.currentUser.collectAsStateWithLifecycle()
  val prefs by vm.userPreferences.collectAsStateWithLifecycle()
  val projects by vm.projects.collectAsStateWithLifecycle()
  val activeProject by vm.activeProject.collectAsStateWithLifecycle()
  val sharedBoardsState by vm.sharedBoardsState.collectAsStateWithLifecycle()
  val activeSharedBoardId by vm.activeSharedBoardId.collectAsStateWithLifecycle()
  val activeSharedBoard by vm.activeSharedBoard.collectAsStateWithLifecycle()
  val boardPresence by vm.boardPresence.collectAsStateWithLifecycle()
  val boardComments by vm.boardComments.collectAsStateWithLifecycle()
  val collabBusy by vm.collabBusy.collectAsStateWithLifecycle()
  val compatibilityReport by vm.compatibilityReport.collectAsStateWithLifecycle()

  val document by vm.document.collectAsStateWithLifecycle()
  val notes by vm.notes.collectAsStateWithLifecycle()
  val isNotesCloudSynced by vm.isNotesCloudSynced.collectAsStateWithLifecycle()
  val editingNote by vm.editingNote.collectAsStateWithLifecycle()

  val toolMode by vm.toolMode.collectAsStateWithLifecycle()
  val selectedNodeIds by vm.selectedNodeIds.collectAsStateWithLifecycle()
  val selectedEdgeId by vm.selectedEdgeId.collectAsStateWithLifecycle()
  val selectedStickyId by vm.selectedStickyId.collectAsStateWithLifecycle()
  val connectSourceId by vm.connectSourceId.collectAsStateWithLifecycle()
  val multiSelectEnabled by vm.multiSelectEnabled.collectAsStateWithLifecycle()
  val panOffset by vm.panOffset.collectAsStateWithLifecycle()
  val zoomScale by vm.zoomScale.collectAsStateWithLifecycle()
  val searchQuery by vm.searchQuery.collectAsStateWithLifecycle()
  val showLegend by vm.showLegend.collectAsStateWithLifecycle()
  val showMinimap by vm.showMinimap.collectAsStateWithLifecycle()
  val canUndo by vm.canUndo.collectAsStateWithLifecycle()
  val canRedo by vm.canRedo.collectAsStateWithLifecycle()
  val algoHighlight by vm.algoHighlight.collectAsStateWithLifecycle()
  val centralityResults by vm.centralityResults.collectAsStateWithLifecycle()

  val osintExtracted by vm.osintExtracted.collectAsStateWithLifecycle()
  val osintTransformResult by vm.osintTransformResult.collectAsStateWithLifecycle()
  val osintBusy by vm.osintBusy.collectAsStateWithLifecycle()

  val formulaCells by vm.formulaCells.collectAsStateWithLifecycle()
  val evaluatedFormulas by vm.evaluatedFormulas.collectAsStateWithLifecycle()
  val toastMsg by vm.toastMessage.collectAsStateWithLifecycle()

  val onPerformSignOut: () -> Unit = {
    signOutGoogle(
      credentialManager = credentialManager,
      scope = scope,
      onBeforeSignOut = { vm.prepareSignOut() },
    )
  }

  val snackbarHostState = remember { SnackbarHostState() }
  LaunchedEffect(toastMsg) {
    toastMsg?.let {
      snackbarHostState.showSnackbar(it)
      vm.clearToast()
    }
  }

  BackHandler(enabled = currentTab != AppDestination.PROJECTS) {
    vm.navigateTo(AppDestination.PROJECTS)
  }

  val navItems =
    listOf(
      Triple(AppDestination.PROJECTS, Icons.Filled.Folder, Icons.Outlined.Folder),
      Triple(AppDestination.CANVAS, Icons.Filled.Hub, Icons.Outlined.Hub),
      Triple(AppDestination.NOTES, Icons.Filled.Description, Icons.Outlined.Description),
      Triple(AppDestination.UTILITIES, Icons.Filled.Tune, Icons.Outlined.Tune),
    )

  BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val isWideScreen = maxWidth >= 600.dp

    if (isWideScreen) {
      Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets.safeDrawing,
      ) { innerPadding ->
        Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
          NavigationRail {
            Spacer(Modifier.height(12.dp))
            navItems.forEach { (dest, activeIcon, inactiveIcon) ->
              val selected = currentTab == dest
              NavigationRailItem(
                selected = selected,
                onClick = { vm.navigateTo(dest) },
                icon = { Icon(if (selected) activeIcon else inactiveIcon, contentDescription = dest.label) },
                label = { Text(dest.label) },
                modifier = Modifier.testTag("nav_${dest.route}"),
              )
            }
          }
          Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            WorkspaceTabContent(
              currentTab = currentTab,
              vm = vm,
              currentUser = currentUser,
              prefs = prefs,
              projects = projects,
              activeProject = activeProject,
              sharedBoardsState = sharedBoardsState,
              activeSharedBoardId = activeSharedBoardId,
              activeSharedBoard = activeSharedBoard,
              boardPresence = boardPresence,
              boardComments = boardComments,
              collabBusy = collabBusy,
              compatibilityReport = compatibilityReport,
              document = document,
              notes = notes,
              isNotesCloudSynced = isNotesCloudSynced,
              editingNote = editingNote,
              toolMode = toolMode,
              selectedNodeIds = selectedNodeIds,
              selectedEdgeId = selectedEdgeId,
              selectedStickyId = selectedStickyId,
              connectSourceId = connectSourceId,
              multiSelectEnabled = multiSelectEnabled,
              panOffset = panOffset,
              zoomScale = zoomScale,
              searchQuery = searchQuery,
              showLegend = showLegend,
              showMinimap = showMinimap,
              canUndo = canUndo,
              canRedo = canRedo,
              algoHighlight = algoHighlight,
              centralityResults = centralityResults,
              osintExtracted = osintExtracted,
              osintTransformResult = osintTransformResult,
              osintBusy = osintBusy,
              formulaCells = formulaCells,
              evaluatedFormulas = evaluatedFormulas,
              onSignOut = onPerformSignOut,
            )
          }
        }
      }
    } else {
      Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
          NavigationBar {
            navItems.forEach { (dest, activeIcon, inactiveIcon) ->
              val selected = currentTab == dest
              NavigationBarItem(
                selected = selected,
                onClick = { vm.navigateTo(dest) },
                icon = { Icon(if (selected) activeIcon else inactiveIcon, contentDescription = dest.label) },
                label = { Text(dest.label) },
                modifier = Modifier.testTag("nav_${dest.route}"),
              )
            }
          }
        },
      ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
          WorkspaceTabContent(
            currentTab = currentTab,
            vm = vm,
            currentUser = currentUser,
            prefs = prefs,
            projects = projects,
            activeProject = activeProject,
            sharedBoardsState = sharedBoardsState,
            activeSharedBoardId = activeSharedBoardId,
            activeSharedBoard = activeSharedBoard,
            boardPresence = boardPresence,
            boardComments = boardComments,
            collabBusy = collabBusy,
            compatibilityReport = compatibilityReport,
            document = document,
            notes = notes,
            isNotesCloudSynced = isNotesCloudSynced,
            editingNote = editingNote,
            toolMode = toolMode,
            selectedNodeIds = selectedNodeIds,
            selectedEdgeId = selectedEdgeId,
            selectedStickyId = selectedStickyId,
            connectSourceId = connectSourceId,
            multiSelectEnabled = multiSelectEnabled,
            panOffset = panOffset,
            zoomScale = zoomScale,
            searchQuery = searchQuery,
            showLegend = showLegend,
            showMinimap = showMinimap,
            canUndo = canUndo,
            canRedo = canRedo,
            algoHighlight = algoHighlight,
            centralityResults = centralityResults,
            osintExtracted = osintExtracted,
            osintTransformResult = osintTransformResult,
            osintBusy = osintBusy,
            formulaCells = formulaCells,
            evaluatedFormulas = evaluatedFormulas,
            onSignOut = onPerformSignOut,
          )
        }
      }
    }
  }
}

@Composable
private fun WorkspaceTabContent(
  currentTab: AppDestination,
  vm: LinkDrawViewModel,
  currentUser: com.example.data.local.UserAccountEntity?,
  prefs: com.example.data.local.AppUserPreferences,
  projects: List<com.example.data.local.GraphProjectEntity>,
  activeProject: com.example.data.local.GraphProjectEntity?,
  sharedBoardsState: com.example.model.UiState<List<com.example.model.CollabBoard>>,
  activeSharedBoardId: String?,
  activeSharedBoard: com.example.model.CollabBoard?,
  boardPresence: List<com.example.model.BoardPresence>,
  boardComments: List<com.example.model.BoardComment>,
  collabBusy: Boolean,
  compatibilityReport: com.example.model.FeatureCompatibilityReport,
  document: com.example.model.GraphDocument,
  notes: List<com.example.model.StudioNote>,
  isNotesCloudSynced: Boolean,
  editingNote: com.example.model.StudioNote?,
  toolMode: com.example.viewmodel.CanvasToolMode,
  selectedNodeIds: Set<String>,
  selectedEdgeId: String?,
  selectedStickyId: String?,
  connectSourceId: String?,
  multiSelectEnabled: Boolean,
  panOffset: androidx.compose.ui.geometry.Offset,
  zoomScale: Float,
  searchQuery: String,
  showLegend: Boolean,
  showMinimap: Boolean,
  canUndo: Boolean,
  canRedo: Boolean,
  algoHighlight: com.example.viewmodel.AlgorithmHighlight,
  centralityResults: List<com.example.engine.NodeCentrality>,
  osintExtracted: List<com.example.model.OsintEntity>,
  osintTransformResult: com.example.model.OsintTransformResult?,
  osintBusy: Boolean,
  formulaCells: Map<String, String>,
  evaluatedFormulas: Map<String, String>,
  onSignOut: () -> Unit,
) {
  val activeTitle = activeSharedBoard?.title ?: activeProject?.title ?: "LinkDraw"

  when (currentTab) {
    AppDestination.PROJECTS ->
      ProjectsScreen(
        user = currentUser,
        projects = projects,
        activeProjectId = activeProject?.id,
        sharedBoardsState = sharedBoardsState,
        activeSharedBoardId = activeSharedBoardId,
        collabBusy = collabBusy,
        onOpenProject = { vm.openProject(it, switchToCanvas = true) },
        onCreateProject = { title, desc, template, isShared ->
          if (isShared) vm.createNewSharedBoard(title, desc, template)
          else vm.createProject(title, desc, template)
        },
        onDuplicateProject = vm::duplicateProject,
        onDeleteProject = vm::deleteProject,
        onShareProjectLive = vm::shareLocalProjectAsBoard,
        onOpenSharedBoard = { vm.openSharedBoard(it, switchToCanvas = true) },
        onJoinSharedBoard = vm::joinSharedBoard,
        onLeaveSharedBoard = vm::leaveSharedBoard,
        onDeleteSharedBoard = vm::deleteSharedBoard,
        onImportJson = vm::importJsonDocument,
        onSignOut = onSignOut,
      )
    AppDestination.CANVAS ->
      CanvasScreen(
        projectTitle = activeTitle,
        document = document,
        toolMode = toolMode,
        selectedNodeIds = selectedNodeIds,
        selectedEdgeId = selectedEdgeId,
        selectedStickyId = selectedStickyId,
        connectSourceId = connectSourceId,
        multiSelectEnabled = multiSelectEnabled,
        panOffset = panOffset,
        zoomScale = zoomScale,
        searchQuery = searchQuery,
        showLegend = showLegend,
        showMinimap = showMinimap,
        canUndo = canUndo,
        canRedo = canRedo,
        algoHighlight = algoHighlight,
        onSetToolMode = vm::setToolMode,
        onToggleMultiSelect = vm::toggleMultiSelect,
        onUpdateViewport = vm::updateViewport,
        onZoomBy = vm::zoomBy,
        onResetViewport = vm::resetViewport,
        onFitAllContent = vm::fitContentToViewport,
        onSearchQueryChange = vm::setSearchQuery,
        onToggleLegend = vm::toggleLegend,
        onToggleMinimap = vm::toggleMinimap,
        onToggleFlowAnimation = vm::toggleFlowAnimation,
        onUndo = vm::undo,
        onRedo = vm::redo,
        onSelectNode = vm::selectNode,
        onSelectEdge = vm::selectEdge,
        onSelectSticky = vm::selectSticky,
        onClearSelection = vm::clearSelection,
        onConnectTap = vm::handleConnectTap,
        onAddNodeAt = { x, y -> vm.addNodeAt(x, y) },
        onAddStickyAt = vm::addStickyAt,
        onAddStroke = vm::addFreehandStroke,
        onCommitNodeDragDelta = vm::commitNodeDragDelta,
        onCommitStickyDragDelta = vm::commitStickyDragDelta,
        onToggleNodeCollapsed = vm::toggleNodeCollapsed,
        onUpdateNode = vm::updateNode,
        onUpdateEdge = vm::updateEdge,
        onUpdateSticky = vm::updateSticky,
        onDuplicateSelected = vm::duplicateSelectedNodes,
        onDeleteSelection = vm::deleteSelection,
        onOpenLinkedNote = vm::openOrCreateNoteForNode,
        onToggleLayerVisibility = vm::toggleLayerVisibility,
        onToggleLayerLock = vm::toggleLayerLock,
        onAlignSelection = vm::alignSelection,
        onClearFreehand = vm::clearFreehandStrokes,
        onClearAlgoHighlight = vm::clearAlgoHighlight,
        activeSharedBoard = activeSharedBoard,
        remotePresence = boardPresence,
        boardComments = boardComments,
        compatibilityReport = compatibilityReport,
        currentUserId = vm.currentUserId,
        onPublishCurrentAsSharedBoard = {
          val curProj = activeProject
          if (curProj != null) vm.shareLocalProjectAsBoard(curProj)
          else vm.createNewSharedBoard(activeTitle, "Shared LinkDraw Graph", com.example.data.local.ProjectTemplate.BLANK)
        },
        onSendComment = vm::sendBoardComment,
        onDeleteComment = vm::deleteBoardComment,
        onRemoveBoardMember = { mUid ->
          activeSharedBoard?.id?.let { bId -> vm.removeBoardMember(bId, mUid) }
        },
        onLeaveSharedBoard = {
          activeSharedBoard?.id?.let { bId -> vm.leaveSharedBoard(bId) }
        },
        onDeleteSharedBoard = {
          activeSharedBoard?.let { vm.deleteSharedBoard(it) }
        },
        onRenameSharedBoard = { title, desc ->
          activeSharedBoard?.id?.let { bId -> vm.renameSharedBoard(bId, title, desc) }
        },
        onFocusNodeById = vm::focusNodeOnCanvas,
        onShowToast = vm::showToast,
      )
    AppDestination.NOTES ->
      NotesScreen(
        notes = notes,
        editingNote = editingNote,
        onOpenEditor = vm::openNoteEditor,
        onSaveNote = vm::saveNote,
        onDeleteNote = vm::deleteNote,
        onFocusCanvasNode = vm::focusNodeOnCanvas,
        onCreateCanvasNodeFromNote = vm::createCanvasNodeFromNote,
        activeSharedBoardTitle = activeSharedBoard?.title,
        isCloudSynced = isNotesCloudSynced,
      )
    AppDestination.UTILITIES ->
      UtilitiesScreen(
        user = currentUser,
        projectTitle = activeTitle,
        document = document,
        preferences = prefs,
        algoHighlight = algoHighlight,
        centralityResults = centralityResults,
        osintExtracted = osintExtracted,
        osintTransformResult = osintTransformResult,
        osintBusy = osintBusy,
        formulaCells = formulaCells,
        evaluatedFormulas = evaluatedFormulas,
        onApplyLayout = vm::applyLayout,
        onRunShortestPath = vm::runShortestPath,
        onRunBfs = vm::runBfs,
        onRunDfs = vm::runDfs,
        onRunMst = vm::runMst,
        onRunTopologicalSort = vm::runTopologicalSort,
        onRunCycleDetection = vm::runCycleDetection,
        onRunConnectedComponents = vm::runConnectedComponents,
        onRunCentrality = vm::runCentrality,
        onFocusCanvasNode = vm::focusNodeOnCanvas,
        onFocusCanvasEdge = vm::focusEdgeOnCanvas,
        onExtractOsint = vm::extractOsint,
        onRunOsintTransform = vm::runOsintTransform,
        onSpawnOsintToCanvas = vm::spawnOsintResultToCanvas,
        onUpdateFormulaCell = vm::updateFormulaCell,
        onResetFormulaSheet = vm::resetFormulaSheet,
        onSetThemeMode = vm::setThemeMode,
        onSetGridSnap = vm::setGridSnap,
        onSetGridSize = vm::setGridSize,
        onToggleFlowAnimation = vm::toggleFlowAnimation,
        onSignOut = onSignOut,
        onShowToast = vm::showToast,
      )
  }
}
