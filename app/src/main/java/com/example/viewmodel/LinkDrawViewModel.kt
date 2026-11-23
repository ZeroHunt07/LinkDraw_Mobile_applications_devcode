package com.example.viewmodel

import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.GraphJsonCodec
import com.example.data.local.*
import com.example.data.remote.CollabElementMapper
import com.example.data.remote.CollaborationRepository
import com.example.engine.*
import com.example.model.*
import com.example.ui.screens.computeFocalZoomTransform
import com.google.firebase.firestore.FirebaseFirestoreException
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class AppDestination(val route: String, val label: String) {
  PROJECTS("projects", "Projects"),
  CANVAS("canvas", "Canvas"),
  NOTES("notes", "Notes"),
  UTILITIES("utilities", "Utilities"),
}

enum class CanvasToolMode(val label: String) {
  SELECT("Select"),
  ADD_NODE("Add Node"),
  CONNECT("Connect"),
  STICKY("Sticky"),
  PEN("Pen"),
  HIGHLIGHTER("Highlight"),
}

data class AlgorithmHighlight(
  val title: String = "",
  val summary: String = "",
  val nodeIds: Set<String> = emptySet(),
  val edgeIds: Set<String> = emptySet(),
  val clusterColorMap: Map<String, String> = emptyMap(),
)

class LinkDrawViewModel(
  private val repo: LinkDrawRepository,
  private val collabRepo: CollaborationRepository,
  val currentUserId: String,
  val currentUserName: String,
  val currentUserEmail: String,
) : ViewModel() {

  companion object {
    private const val TAG = "LinkDrawVM"
  }

  val userPreferences: StateFlow<AppUserPreferences> =
    repo.preferences.stateIn(
      viewModelScope,
      SharingStarted.WhileSubscribed(5000),
      AppUserPreferences(),
    )

  private val _currentUser =
    MutableStateFlow<UserAccountEntity?>(
      UserAccountEntity(
        id = currentUserId,
        displayName = currentUserName.ifBlank { "Analyst" },
        email = currentUserEmail,
        passwordHash = "",
        salt = "",
      )
    )
  val currentUser: StateFlow<UserAccountEntity?> = _currentUser.asStateFlow()

  private val _currentTab = MutableStateFlow(AppDestination.PROJECTS)
  val currentTab: StateFlow<AppDestination> = _currentTab.asStateFlow()

  val projects: StateFlow<List<GraphProjectEntity>> =
    repo
      .observeProjects(currentUserId)
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  private val localRoomNotes: StateFlow<List<StudioNote>> =
    repo
      .observeNotes(currentUserId)
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val activeProject: StateFlow<GraphProjectEntity?> =
    combine(projects, userPreferences) { list, pref ->
      list.firstOrNull { it.id == pref.activeProjectId } ?: list.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

  // --- Collaboration Streams (Two-tier Flow error unwrapping) ---
  val sharedBoardsState: StateFlow<UiState<List<CollabBoard>>> =
    collabRepo
      .observeMyBoards()
      .map<List<CollabBoard>, UiState<List<CollabBoard>>> { UiState.Success(it) }
      .catch { error ->
        val firestoreError =
          generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        val rootError = firestoreError ?: error
        Log.w(TAG, "Error observing shared boards", rootError)
        emit(UiState.Error(rootError.message ?: "Failed to load shared boards"))
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = UiState.Loading,
      )

  private val _activeSharedBoardId = MutableStateFlow<String?>(null)
  val activeSharedBoardId: StateFlow<String?> = _activeSharedBoardId.asStateFlow()

  val activeSharedBoard: StateFlow<CollabBoard?> =
    combine(sharedBoardsState, _activeSharedBoardId) { state, id ->
      if (id == null) null
      else (state as? UiState.Success)?.data?.firstOrNull { it.id == id }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

  private val _boardPresence = MutableStateFlow<List<BoardPresence>>(emptyList())
  val boardPresence: StateFlow<List<BoardPresence>> = _boardPresence.asStateFlow()

  private val _boardComments = MutableStateFlow<List<BoardComment>>(emptyList())
  val boardComments: StateFlow<List<BoardComment>> = _boardComments.asStateFlow()

  private val _collabBusy = MutableStateFlow(false)
  val collabBusy: StateFlow<Boolean> = _collabBusy.asStateFlow()

  private val _personalCloudNotes = MutableStateFlow<List<StudioNote>?>(null)
  private val _boardCloudNotes = MutableStateFlow<List<StudioNote>>(emptyList())

  val isNotesCloudSynced: StateFlow<Boolean> =
    combine(_activeSharedBoardId, _personalCloudNotes) { boardId, personal ->
      boardId != null || personal != null
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), false)

  val notes: StateFlow<List<StudioNote>> =
    combine(_activeSharedBoardId, _boardCloudNotes, _personalCloudNotes, localRoomNotes) {
      boardId,
      boardNotes,
      personalCloud,
      localNotes ->
      when {
        boardId != null -> boardNotes
        personalCloud != null -> personalCloud
        else -> localNotes
      }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

  private val _compatibilityReport = MutableStateFlow(FeatureCompatibilityReport())
  val compatibilityReport: StateFlow<FeatureCompatibilityReport> =
    _compatibilityReport.asStateFlow()

  private val _document = MutableStateFlow(GraphDocument())
  val document: StateFlow<GraphDocument> = _document.asStateFlow()

  private var loadedProjectId: String? = null
  private var loadedSharedBoardId: String? = null
  private var lastSyncedRemoteDoc: GraphDocument = GraphDocument()
  private var knownRemoteElementIds: Set<String> = emptySet()
  private var lastRemoteBoard: CollabBoard? = null
  private var lastRemoteElements: List<BoardElement> = emptyList()
  private var lastRemoteNotes: List<CollabNote> = emptyList()
  private var hasSyncedInitialPersonalNotes = false
  private var lastNotifiedFindingSignature: String? = null
  private var remoteBoardObserveJob: Job? = null
  private var presencePublishJob: Job? = null

  private val undoStack = ArrayDeque<GraphDocument>()
  private val redoStack = ArrayDeque<GraphDocument>()

  private val _canUndo = MutableStateFlow(false)
  val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

  private val _canRedo = MutableStateFlow(false)
  val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

  // Canvas Interaction State
  private val _toolMode = MutableStateFlow(CanvasToolMode.SELECT)
  val toolMode: StateFlow<CanvasToolMode> = _toolMode.asStateFlow()

  private val _selectedNodeIds = MutableStateFlow<Set<String>>(emptySet())
  val selectedNodeIds: StateFlow<Set<String>> = _selectedNodeIds.asStateFlow()

  private val _selectedEdgeId = MutableStateFlow<String?>(null)
  val selectedEdgeId: StateFlow<String?> = _selectedEdgeId.asStateFlow()

  private val _selectedStickyId = MutableStateFlow<String?>(null)
  val selectedStickyId: StateFlow<String?> = _selectedStickyId.asStateFlow()

  private val _connectSourceId = MutableStateFlow<String?>(null)
  val connectSourceId: StateFlow<String?> = _connectSourceId.asStateFlow()

  private val _multiSelectEnabled = MutableStateFlow(false)
  val multiSelectEnabled: StateFlow<Boolean> = _multiSelectEnabled.asStateFlow()

  private val _panOffset = MutableStateFlow(Offset.Zero)
  val panOffset: StateFlow<Offset> = _panOffset.asStateFlow()

  private val _zoomScale = MutableStateFlow(0.95f)
  val zoomScale: StateFlow<Float> = _zoomScale.asStateFlow()

  private val _searchQuery = MutableStateFlow("")
  val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

  private val _showLegend = MutableStateFlow(false)
  val showLegend: StateFlow<Boolean> = _showLegend.asStateFlow()

  private val _showMinimap = MutableStateFlow(true)
  val showMinimap: StateFlow<Boolean> = _showMinimap.asStateFlow()

  private val _algoHighlight = MutableStateFlow(AlgorithmHighlight())
  val algoHighlight: StateFlow<AlgorithmHighlight> = _algoHighlight.asStateFlow()

  private val _centralityResults = MutableStateFlow<List<NodeCentrality>>(emptyList())
  val centralityResults: StateFlow<List<NodeCentrality>> = _centralityResults.asStateFlow()

  // OSINT Workbench State
  private val _osintExtracted = MutableStateFlow<List<OsintEntity>>(emptyList())
  val osintExtracted: StateFlow<List<OsintEntity>> = _osintExtracted.asStateFlow()

  private val _osintTransformResult = MutableStateFlow<OsintTransformResult?>(null)
  val osintTransformResult: StateFlow<OsintTransformResult?> = _osintTransformResult.asStateFlow()

  private val _osintBusy = MutableStateFlow(false)
  val osintBusy: StateFlow<Boolean> = _osintBusy.asStateFlow()

  // Formula Sheet State
  private val _formulaCells = MutableStateFlow(FormulaEngine.defaultSheet())
  val formulaCells: StateFlow<Map<String, String>> = _formulaCells.asStateFlow()

  val evaluatedFormulas: StateFlow<Map<String, String>> =
    combine(_formulaCells, _document) { cells, doc ->
      FormulaEngine.evaluateAll(cells, doc)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

  // Note Editor Target
  private val _editingNote = MutableStateFlow<StudioNote?>(null)
  val editingNote: StateFlow<StudioNote?> = _editingNote.asStateFlow()

  private val _toastMessage = MutableStateFlow<String?>(null)
  val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

  init {
    viewModelScope.launch {
      _currentUser.value =
        repo.ensureGoogleUserWorkspace(
          uid = currentUserId,
          displayName = currentUserName,
          email = currentUserEmail,
        )
      startObservingPersonalNotes()
    }

    // Observe active local project when NOT in a live shared board
    viewModelScope.launch {
      combine(activeProject, _activeSharedBoardId) { proj, sharedId -> proj to sharedId }
        .collect { (proj, sharedId) ->
          if (sharedId == null && proj == null && loadedProjectId != null) {
            loadedProjectId = null
            _compatibilityReport.value = FeatureCompatibilityReport()
            lastRemoteBoard = null
            lastRemoteElements = emptyList()
            _document.value = GraphDocument()
            undoStack.clear()
            redoStack.clear()
            updateUndoFlags()
            resetSelectionAndCamera()
          } else if (sharedId == null && proj != null && proj.id != loadedProjectId) {
            loadedProjectId = proj.id
            loadedSharedBoardId = null
            _compatibilityReport.value = FeatureCompatibilityReport()
            lastRemoteBoard = null
            lastRemoteElements = emptyList()
            _document.value =
              runCatching { GraphJsonCodec.decodeDocument(proj.documentJson) }
                .getOrDefault(GraphDocument())
            undoStack.clear()
            redoStack.clear()
            updateUndoFlags()
            resetSelectionAndCamera()
          }
        }
    }

    // Observe live presence & selection changes when on a shared board
    viewModelScope.launch {
      combine(_activeSharedBoardId, _selectedNodeIds, _currentTab) { boardId, selNodes, tab ->
        Triple(boardId, selNodes.firstOrNull(), tab)
      }.distinctUntilChanged().collect { (boardId, selNodeId, tab) ->
        if (boardId != null) {
          publishPresence(boardId, selNodeId, tab)
        }
      }
    }
  }

  private fun resetSelectionAndCamera() {
    _selectedNodeIds.value = emptySet()
    _selectedEdgeId.value = null
    _selectedStickyId.value = null
    _connectSourceId.value = null
    _algoHighlight.value = AlgorithmHighlight()
    _panOffset.value = Offset.Zero
    _zoomScale.value = 0.95f
  }

  fun clearToast() {
    _toastMessage.value = null
  }

  fun showToast(msg: String) {
    _toastMessage.value = msg
  }

  suspend fun prepareSignOut() {
    val activeBoard = _activeSharedBoardId.value
    remoteBoardObserveJob?.cancel()
    presencePublishJob?.cancel()
    if (activeBoard != null) {
      collabRepo.clearPresence(activeBoard)
    }
    repo.signOut()
  }

  // --- Navigation & Preferences ---
  fun navigateTo(dest: AppDestination) {
    _currentTab.value = dest
  }

  fun setThemeMode(mode: AppThemeMode) {
    viewModelScope.launch { repo.setThemeMode(mode) }
  }

  fun setGridSnap(enabled: Boolean) {
    viewModelScope.launch {
      repo.setGridSnap(enabled)
      updateDocument(recordUndo = false) { it.copy(settings = it.settings.copy(gridSnap = enabled)) }
    }
  }

  fun setGridSize(size: Int) {
    viewModelScope.launch {
      repo.setGridSize(size)
      updateDocument(recordUndo = false) { it.copy(settings = it.settings.copy(gridSize = size)) }
    }
  }

  fun toggleFlowAnimation() {
    val next = !_document.value.settings.flowAnimation
    updateDocument(recordUndo = false) { it.copy(settings = it.settings.copy(flowAnimation = next)) }
    viewModelScope.launch { repo.setFlowAnimation(next) }
  }

  // --- Local Projects ---
  fun openProject(projectId: String, switchToCanvas: Boolean = true) {
    val prevBoard = _activeSharedBoardId.value
    viewModelScope.launch {
      if (prevBoard != null) {
        stopObservingSharedBoard(prevBoard)
      }
      _activeSharedBoardId.value = null
      loadedProjectId = null
      repo.setActiveProject(projectId)
      if (switchToCanvas) _currentTab.value = AppDestination.CANVAS
    }
  }

  fun createProject(title: String, description: String, template: ProjectTemplate) {
    viewModelScope.launch {
      _activeSharedBoardId.value?.let { stopObservingSharedBoard(it) }
      _activeSharedBoardId.value = null
      loadedProjectId = null
      repo.createProject(currentUserId, title, description, template)
      _currentTab.value = AppDestination.CANVAS
      showToast("Created \"$title\"")
    }
  }

  fun duplicateProject(projectId: String) {
    viewModelScope.launch {
      repo.duplicateProject(currentUserId, projectId)
      showToast("Project duplicated")
    }
  }

  fun deleteProject(projectId: String) {
    viewModelScope.launch {
      repo.deleteProject(currentUserId, projectId)
      showToast("Project deleted")
    }
  }

  fun importJsonDocument(title: String, jsonText: String): Boolean {
    return runCatching {
      val doc = GraphJsonCodec.decodeDocument(jsonText)
      viewModelScope.launch {
        _activeSharedBoardId.value?.let { stopObservingSharedBoard(it) }
        _activeSharedBoardId.value = null
        loadedProjectId = null
        repo.createProjectFromDocument(currentUserId, title, "Imported LinkDraw JSON", doc)
        _currentTab.value = AppDestination.CANVAS
        showToast("Imported ${doc.nodes.size} nodes")
      }
      true
    }.getOrElse {
      showToast("Invalid JSON format: ${it.message}")
      false
    }
  }

  // --- Live Collaboration Boards ---
  fun openSharedBoard(boardId: String, switchToCanvas: Boolean = true) {
    if (boardId.isBlank()) return
    val prev = _activeSharedBoardId.value
    if (prev == boardId && loadedSharedBoardId == boardId) {
      if (switchToCanvas) _currentTab.value = AppDestination.CANVAS
      return
    }
    viewModelScope.launch {
      if (prev != null && prev != boardId) {
        stopObservingSharedBoard(prev)
      }
      _activeSharedBoardId.value = boardId
      startObservingSharedBoard(boardId)
      if (switchToCanvas) _currentTab.value = AppDestination.CANVAS
    }
  }

  private fun startObservingPersonalNotes() {
    viewModelScope.launch {
      collabRepo
        .observePersonalNotes()
        .catch { error ->
          val fe =
            generateSequence(error) { it.cause }
              .filterIsInstance<FirebaseFirestoreException>()
              .firstOrNull()
          if (fe == null && error is CancellationException) throw error
          Log.w(TAG, "Personal notes cloud sync warning", fe ?: error)
        }
        .collect { remoteNotes ->
          val mapped = remoteNotes.map(CollabElementMapper::collabNoteToDomain)
          if (!hasSyncedInitialPersonalNotes) {
            hasSyncedInitialPersonalNotes = true
            if (mapped.isEmpty()) {
              val seedLocal = repo.observeNotes(currentUserId).first()
              if (seedLocal.isNotEmpty()) {
                val updater = _currentUser.value?.displayName ?: currentUserName
                seedLocal.forEach { n -> collabRepo.upsertPersonalNote(n, updater) }
                return@collect
              }
            }
          }
          _personalCloudNotes.value = mapped
          // Keep Room warm as an offline cache without triggering redundant timestamp writes
          val localById = localRoomNotes.value.associateBy { it.id }
          mapped.forEach { remote ->
            val local = localById[remote.id]
            if (
              local == null ||
                local.title != remote.title ||
                local.body != remote.body ||
                local.folder != remote.folder ||
                local.noteType != remote.noteType ||
                local.priority != remote.priority ||
                local.colorHex != remote.colorHex ||
                local.pinned != remote.pinned ||
                local.archived != remote.archived ||
                local.tags != remote.tags ||
                local.sourceUrl != remote.sourceUrl ||
                local.linkedNodeId != remote.linkedNodeId ||
                local.checklist != remote.checklist
            ) {
              repo.saveNote(currentUserId, remote)
            }
          }
        }
    }
  }

  private fun recomputeCompatibilityReport() {
    val report =
      CollabElementMapper.analyzeCompatibility(
        board = lastRemoteBoard,
        elements = lastRemoteElements,
        presence = _boardPresence.value,
        notes = lastRemoteNotes,
      )
    _compatibilityReport.value = report
    if (report.hasFindings) {
      val sig = report.findings.joinToString("|") { "${it.category}:${it.featureKey}" }
      if (sig != lastNotifiedFindingSignature) {
        lastNotifiedFindingSignature = sig
        showToast("Web added ${report.findings.size} new feature(s) — tap Sync Alert to view")
      }
    } else {
      lastNotifiedFindingSignature = null
    }
  }

  private fun startObservingSharedBoard(boardId: String) {
    remoteBoardObserveJob?.cancel()
    loadedSharedBoardId = boardId
    loadedProjectId = null
    knownRemoteElementIds = emptySet()
    lastRemoteBoard = null
    lastRemoteElements = emptyList()
    lastRemoteNotes = emptyList()
    _boardCloudNotes.value = emptyList()
    lastNotifiedFindingSignature = null
    _compatibilityReport.value = FeatureCompatibilityReport()
    lastSyncedRemoteDoc = GraphDocument()
    undoStack.clear()
    redoStack.clear()
    updateUndoFlags()
    resetSelectionAndCamera()

    remoteBoardObserveJob =
      viewModelScope.launch {
        launch {
          combine(collabRepo.observeBoard(boardId), collabRepo.observeElements(boardId)) { b, elems ->
              b to elems
            }
            .catch { error ->
              val firestoreError =
                generateSequence(error) { it.cause }
                  .filterIsInstance<FirebaseFirestoreException>()
                  .firstOrNull()
              if (firestoreError == null && error is CancellationException) throw error
              Log.w(TAG, "Board sync interrupted", firestoreError ?: error)
              showToast(
                (firestoreError ?: error).localizedMessage
                  ?: "Live board access interrupted"
              )
            }
            .collect { (board, elements) ->
              if (board == null) {
                if (_activeSharedBoardId.value == boardId) {
                  showToast("Shared board is no longer available.")
                  _activeSharedBoardId.value = null
                  loadedSharedBoardId = null
                  _boardPresence.value = emptyList()
                  _boardComments.value = emptyList()
                  _boardCloudNotes.value = emptyList()
                  lastRemoteNotes = emptyList()
                  _compatibilityReport.value = FeatureCompatibilityReport()
                }
                return@collect
              }
              // Only track supported kinds for diff deletion so Web-only element kinds are NEVER deleted
              knownRemoteElementIds =
                elements
                  .filter { it.kind in CollabElementMapper.SUPPORTED_KINDS }
                  .map { it.id }
                  .toSet()
              lastRemoteBoard = board
              lastRemoteElements = elements
              val assembled =
                CollabElementMapper.assembleDocument(board, elements, _document.value.layers)
              lastSyncedRemoteDoc = assembled
              _document.value = assembled
              recomputeCompatibilityReport()
            }
        }
        launch {
          collabRepo
            .observePresence(boardId)
            .catch { error ->
              val fe =
                generateSequence(error) { it.cause }
                  .filterIsInstance<FirebaseFirestoreException>()
                  .firstOrNull()
              if (fe == null && error is CancellationException) throw error
              Log.w(TAG, "Presence sync warning", fe ?: error)
            }
            .collect { pres ->
              _boardPresence.value = pres
              if (lastRemoteBoard != null) recomputeCompatibilityReport()
            }
        }
        launch {
          collabRepo
            .observeComments(boardId)
            .catch { error ->
              val fe =
                generateSequence(error) { it.cause }
                  .filterIsInstance<FirebaseFirestoreException>()
                  .firstOrNull()
              if (fe == null && error is CancellationException) throw error
              Log.w(TAG, "Comments sync warning", fe ?: error)
            }
            .collect { _boardComments.value = it }
        }
        launch {
          collabRepo
            .observeBoardNotes(boardId)
            .catch { error ->
              val fe =
                generateSequence(error) { it.cause }
                  .filterIsInstance<FirebaseFirestoreException>()
                  .firstOrNull()
              if (fe == null && error is CancellationException) throw error
              Log.w(TAG, "Board notes sync warning", fe ?: error)
            }
            .collect { remoteNotes ->
              lastRemoteNotes = remoteNotes
              _boardCloudNotes.value = remoteNotes.map(CollabElementMapper::collabNoteToDomain)
              if (lastRemoteBoard != null) recomputeCompatibilityReport()
            }
        }
      }
  }

  private suspend fun stopObservingSharedBoard(boardId: String) {
    remoteBoardObserveJob?.cancel()
    presencePublishJob?.cancel()
    collabRepo.clearPresence(boardId)
    _boardPresence.value = emptyList()
    _boardComments.value = emptyList()
    _boardCloudNotes.value = emptyList()
    lastRemoteNotes = emptyList()
    _compatibilityReport.value = FeatureCompatibilityReport()
    lastRemoteBoard = null
    lastRemoteElements = emptyList()
    loadedSharedBoardId = null
  }

  private fun publishPresence(boardId: String, selectedNodeId: String?, tab: AppDestination) {
    presencePublishJob?.cancel()
    presencePublishJob =
      viewModelScope.launch {
        delay(150L)
        val hasExisting = _boardPresence.value.any { it.uid == currentUserId }
        val res =
          collabRepo.updatePresence(
            boardId = boardId,
            displayName = _currentUser.value?.displayName ?: currentUserName,
            selectedNodeId = selectedNodeId,
            activeTab = tab.route,
            hasExistingPresence = hasExisting,
          )
        res.onFailure { Log.w(TAG, "Presence update failed: ${it.message}") }
      }
  }

  fun shareLocalProjectAsBoard(project: GraphProjectEntity) {
    viewModelScope.launch {
      _collabBusy.value = true
      val doc =
        runCatching { GraphJsonCodec.decodeDocument(project.documentJson) }
          .getOrDefault(GraphDocument())
      val res =
        collabRepo.createSharedBoardFromDocument(
          title = project.title,
          description = project.description,
          ownerName = _currentUser.value?.displayName ?: currentUserName,
          document = doc,
        )
      _collabBusy.value = false
      res.fold(
        onSuccess = { board ->
          openSharedBoard(board.id, switchToCanvas = true)
          showToast("Live board ready • Invite code: ${board.inviteCode}")
        },
        onFailure = { showToast("Could not create shared board: ${it.localizedMessage}") },
      )
    }
  }

  fun createNewSharedBoard(title: String, description: String, template: ProjectTemplate) {
    viewModelScope.launch {
      _collabBusy.value = true
      val doc = StarterTemplates.buildDocument(template)
      val res =
        collabRepo.createSharedBoardFromDocument(
          title = title,
          description = description,
          ownerName = _currentUser.value?.displayName ?: currentUserName,
          document = doc,
        )
      _collabBusy.value = false
      res.fold(
        onSuccess = { board ->
          openSharedBoard(board.id, switchToCanvas = true)
          showToast("Created shared board • Code: ${board.inviteCode}")
        },
        onFailure = { showToast("Could not create shared board: ${it.localizedMessage}") },
      )
    }
  }

  fun joinSharedBoard(inviteCode: String) {
    viewModelScope.launch {
      _collabBusy.value = true
      val res =
        collabRepo.joinBoardByInviteCode(
          rawCode = inviteCode,
          displayName = _currentUser.value?.displayName ?: currentUserName,
        )
      _collabBusy.value = false
      res.fold(
        onSuccess = { board ->
          openSharedBoard(board.id, switchToCanvas = true)
          showToast("Joined \"${board.title}\"")
        },
        onFailure = { showToast(it.localizedMessage ?: "Failed to join board") },
      )
    }
  }

  fun leaveSharedBoard(boardId: String) {
    viewModelScope.launch {
      _collabBusy.value = true
      if (_activeSharedBoardId.value == boardId) {
        remoteBoardObserveJob?.cancel()
        presencePublishJob?.cancel()
        _activeSharedBoardId.value = null
        loadedSharedBoardId = null
        _boardPresence.value = emptyList()
        _boardComments.value = emptyList()
        _boardCloudNotes.value = emptyList()
        lastRemoteNotes = emptyList()
        _compatibilityReport.value = FeatureCompatibilityReport()
        lastRemoteBoard = null
        lastRemoteElements = emptyList()
        _currentTab.value = AppDestination.PROJECTS
      }
      val res = collabRepo.leaveBoard(boardId)
      _collabBusy.value = false
      res.fold(
        onSuccess = { showToast("Left shared board") },
        onFailure = { showToast(it.localizedMessage ?: "Failed to leave board") },
      )
    }
  }

  fun removeBoardMember(boardId: String, memberUid: String) {
    viewModelScope.launch {
      val res = collabRepo.removeMember(boardId, memberUid)
      res.fold(
        onSuccess = { showToast("Member removed") },
        onFailure = { showToast(it.localizedMessage ?: "Failed to remove member") },
      )
    }
  }

  fun deleteSharedBoard(board: CollabBoard) {
    viewModelScope.launch {
      _collabBusy.value = true
      if (_activeSharedBoardId.value == board.id) {
        remoteBoardObserveJob?.cancel()
        presencePublishJob?.cancel()
        _activeSharedBoardId.value = null
        loadedSharedBoardId = null
        _boardPresence.value = emptyList()
        _boardComments.value = emptyList()
        _boardCloudNotes.value = emptyList()
        lastRemoteNotes = emptyList()
        _compatibilityReport.value = FeatureCompatibilityReport()
        lastRemoteBoard = null
        lastRemoteElements = emptyList()
        _currentTab.value = AppDestination.PROJECTS
      }
      val res = collabRepo.deleteBoard(board)
      _collabBusy.value = false
      res.fold(
        onSuccess = { showToast("Deleted shared board \"${board.title}\"") },
        onFailure = { showToast(it.localizedMessage ?: "Failed to delete board") },
      )
    }
  }

  fun renameSharedBoard(boardId: String, title: String, description: String) {
    viewModelScope.launch {
      val res = collabRepo.updateBoardDetails(boardId, title, description)
      res.fold(
        onSuccess = { showToast("Board details updated") },
        onFailure = { showToast(it.localizedMessage ?: "Update failed") },
      )
    }
  }

  fun sendBoardComment(text: String, linkSelectedNode: Boolean) {
    val boardId = _activeSharedBoardId.value ?: return
    val selId = if (linkSelectedNode) _selectedNodeIds.value.firstOrNull() else null
    val selLabel = selId?.let { id -> _document.value.nodes.firstOrNull { it.id == id }?.text }
    viewModelScope.launch {
      val res =
        collabRepo.addComment(
          boardId = boardId,
          authorName = _currentUser.value?.displayName ?: currentUserName,
          text = text,
          linkedNodeId = selId,
          linkedNodeLabel = selLabel,
        )
      res.onFailure { showToast(it.localizedMessage ?: "Failed to send comment") }
    }
  }

  fun deleteBoardComment(commentId: String) {
    val boardId = _activeSharedBoardId.value ?: return
    viewModelScope.launch {
      val res = collabRepo.deleteComment(boardId, commentId)
      res.onFailure { showToast(it.localizedMessage ?: "Failed to delete comment") }
    }
  }

  // --- Canvas Viewport & Tools ---
  fun setToolMode(mode: CanvasToolMode) {
    _toolMode.value = mode
    if (mode != CanvasToolMode.CONNECT) _connectSourceId.value = null
  }

  fun toggleMultiSelect() {
    _multiSelectEnabled.value = !_multiSelectEnabled.value
  }

  fun updateViewport(pan: Offset, zoom: Float) {
    _panOffset.value = pan
    _zoomScale.value = zoom.coerceIn(0.25f, 3.5f)
  }

  fun zoomBy(factor: Float) {
    val transformed =
      computeFocalZoomTransform(
        currentPan = _panOffset.value,
        currentZoom = _zoomScale.value,
        zoomChange = factor,
        panChange = Offset.Zero,
        focalScreenPoint = Offset.Zero,
        canvasCenter = Offset.Zero,
      )
    _panOffset.value = transformed.pan
    _zoomScale.value = transformed.zoom
  }

  fun resetViewport() {
    _panOffset.value = Offset.Zero
    _zoomScale.value = 0.95f
  }

  fun fitContentToViewport(canvasWidthPx: Float, canvasHeightPx: Float) {
    val doc = _document.value
    val xs = mutableListOf<Float>()
    val ys = mutableListOf<Float>()
    doc.nodes.forEach {
      xs += it.x - it.w / 2f
      xs += it.x + it.w / 2f
      ys += it.y - it.h / 2f
      ys += it.y + it.h / 2f
    }
    doc.stickyNotes.forEach {
      xs += it.x - it.w / 2f
      xs += it.x + it.w / 2f
      ys += it.y - it.h / 2f
      ys += it.y + it.h / 2f
    }
    doc.unsupportedElements.forEach {
      xs += it.x - it.w / 2f
      xs += it.x + it.w / 2f
      ys += it.y - it.h / 2f
      ys += it.y + it.h / 2f
    }
    if (xs.isEmpty() || ys.isEmpty() || canvasWidthPx <= 0f || canvasHeightPx <= 0f) {
      resetViewport()
      return
    }
    val minX = xs.min()
    val maxX = xs.max()
    val minY = ys.min()
    val maxY = ys.max()
    val contentW = (maxX - minX).coerceAtLeast(160f)
    val contentH = (maxY - minY).coerceAtLeast(120f)
    val fitZoom =
      minOf((canvasWidthPx * 0.78f) / contentW, (canvasHeightPx * 0.68f) / contentH)
        .coerceIn(0.25f, 1.8f)
    val centerX = (minX + maxX) / 2f
    val centerY = (minY + maxY) / 2f
    _zoomScale.value = fitZoom
    _panOffset.value = Offset(-centerX * fitZoom, -centerY * fitZoom)
  }

  fun setSearchQuery(q: String) {
    _searchQuery.value = q
  }

  fun toggleLegend() {
    _showLegend.value = !_showLegend.value
  }

  fun toggleMinimap() {
    _showMinimap.value = !_showMinimap.value
  }

  fun clearAlgoHighlight() {
    _algoHighlight.value = AlgorithmHighlight()
    updateDocument(recordUndo = false) { doc ->
      doc.copy(nodes = doc.nodes.map { it.copy(badge = null) })
    }
  }

  // --- Undo / Redo & Persistence ---
  private fun updateUndoFlags() {
    _canUndo.value = undoStack.isNotEmpty()
    _canRedo.value = redoStack.isNotEmpty()
  }

  private fun updateDocument(recordUndo: Boolean = true, transform: (GraphDocument) -> GraphDocument) {
    val current = _document.value
    val next = transform(current)
    if (next == current) return
    if (recordUndo) {
      undoStack.addLast(current)
      if (undoStack.size > 40) undoStack.removeFirst()
      redoStack.clear()
      updateUndoFlags()
    }
    _document.value = next
    persistCurrentDocument(next)
  }

  private fun persistCurrentDocument(doc: GraphDocument) {
    val sharedBoardId = _activeSharedBoardId.value
    if (sharedBoardId != null) {
      val previous = lastSyncedRemoteDoc
      lastSyncedRemoteDoc = doc
      val knownSnapshot = knownRemoteElementIds
      val nextIds = CollabElementMapper.documentElementEntries(doc).keys
      knownRemoteElementIds = knownSnapshot + nextIds
      viewModelScope.launch {
        val res =
          collabRepo.syncDocumentChanges(
            boardId = sharedBoardId,
            previousDoc = previous,
            nextDoc = doc,
            knownRemoteElementIds = knownSnapshot,
            updaterName = _currentUser.value?.displayName ?: currentUserName,
          )
        res.onFailure { err ->
          showToast(err.localizedMessage ?: "Live sync error — check connection")
        }
      }
      return
    }

    val projId = activeProject.value?.id ?: return
    viewModelScope.launch { repo.saveProjectDocument(currentUserId, projId, document = doc) }
  }

  fun undo() {
    if (undoStack.isEmpty()) return
    val prev = undoStack.removeLast()
    redoStack.addLast(_document.value)
    _document.value = prev
    updateUndoFlags()
    persistCurrentDocument(prev)
  }

  fun redo() {
    if (redoStack.isEmpty()) return
    val next = redoStack.removeLast()
    undoStack.addLast(_document.value)
    _document.value = next
    updateUndoFlags()
    persistCurrentDocument(next)
  }

  // --- Selection & Canvas Actions ---
  fun clearSelection() {
    _selectedNodeIds.value = emptySet()
    _selectedEdgeId.value = null
    _selectedStickyId.value = null
    _connectSourceId.value = null
  }

  fun selectNode(nodeId: String) {
    _selectedEdgeId.value = null
    _selectedStickyId.value = null
    if (_multiSelectEnabled.value) {
      val cur = _selectedNodeIds.value
      _selectedNodeIds.value = if (nodeId in cur) cur - nodeId else cur + nodeId
    } else {
      _selectedNodeIds.value = setOf(nodeId)
    }
  }

  fun selectEdge(edgeId: String) {
    _selectedNodeIds.value = emptySet()
    _selectedStickyId.value = null
    _selectedEdgeId.value = edgeId
  }

  fun selectSticky(stickyId: String) {
    _selectedNodeIds.value = emptySet()
    _selectedEdgeId.value = null
    _selectedStickyId.value = stickyId
  }

  fun handleConnectTap(nodeId: String) {
    val src = _connectSourceId.value
    if (src == null) {
      _connectSourceId.value = nodeId
      _selectedNodeIds.value = setOf(nodeId)
    } else {
      addEdge(src, nodeId)
      _connectSourceId.value = null
      _toolMode.value = CanvasToolMode.SELECT
    }
  }

  fun addNodeAt(worldX: Float, worldY: Float, shape: NodeShape = NodeShape.ROUNDED, text: String = "New Node") {
    val doc = _document.value
    val snap = doc.settings.gridSnap
    val gs = doc.settings.gridSize.toFloat()
    val nx = if (snap) round(worldX / gs) * gs else worldX
    val ny = if (snap) round(worldY / gs) * gs else worldY
    val node =
      GraphNode(
        x = nx,
        y = ny,
        w = if (shape == NodeShape.TABLE) 220f else 155f,
        h = if (shape == NodeShape.TABLE) 115f else 68f,
        text = text,
        shape = shape,
        tableData = if (shape == NodeShape.TABLE) TableData() else null,
        progress = if (shape == NodeShape.PROGRESS_BAR) 50 else null,
      )
    updateDocument { it.copy(nodes = it.nodes + node) }
    _selectedNodeIds.value = setOf(node.id)
    _selectedEdgeId.value = null
    _selectedStickyId.value = null
    _toolMode.value = CanvasToolMode.SELECT
  }

  fun addEdge(fromId: String, toId: String, label: String = "") {
    val edge = GraphEdge(fromId = fromId, toId = toId, label = label)
    updateDocument { it.copy(edges = it.edges + edge) }
    _selectedEdgeId.value = edge.id
    _selectedNodeIds.value = emptySet()
  }

  fun addStickyAt(worldX: Float, worldY: Float) {
    val sticky = StickyNote(x = worldX, y = worldY, text = "New note...")
    updateDocument { it.copy(stickyNotes = it.stickyNotes + sticky) }
    _selectedStickyId.value = sticky.id
    _selectedNodeIds.value = emptySet()
    _toolMode.value = CanvasToolMode.SELECT
  }

  fun addFreehandStroke(points: List<StrokePoint>, isHighlighter: Boolean) {
    if (points.size < 2) return
    val stroke =
      FreehandStroke(
        points = points,
        colorHex = if (isHighlighter) "#FACC15" else "#EF4444",
        width = if (isHighlighter) 18f else 4f,
        isHighlighter = isHighlighter,
      )
    updateDocument { it.copy(freehandStrokes = it.freehandStrokes + stroke) }
  }

  fun clearFreehandStrokes() {
    updateDocument { it.copy(freehandStrokes = emptyList()) }
    showToast("Cleared freehand strokes")
  }

  fun commitNodeDragDelta(dxWorld: Float, dyWorld: Float) {
    val current = _document.value
    val sel = _selectedNodeIds.value
    if (sel.isEmpty() || (dxWorld == 0f && dyWorld == 0f)) return
    val now = System.currentTimeMillis()
    val gs = current.settings.gridSize.toFloat()
    val snap = current.settings.gridSnap
    val doc =
      current.copy(
        nodes =
          current.nodes.map { n ->
            if (n.id in sel && !n.locked) {
              val nx = n.x + dxWorld
              val ny = n.y + dyWorld
              n.copy(
                x = if (snap) round(nx / gs) * gs else nx,
                y = if (snap) round(ny / gs) * gs else ny,
                updatedAt = now,
              )
            } else n
          }
      )
    if (doc == current) return
    undoStack.addLast(current)
    if (undoStack.size > 40) undoStack.removeFirst()
    redoStack.clear()
    updateUndoFlags()
    _document.value = doc
    persistCurrentDocument(doc)
  }

  fun commitStickyDragDelta(stickyId: String, dxWorld: Float, dyWorld: Float) {
    val current = _document.value
    if (dxWorld == 0f && dyWorld == 0f) return
    val now = System.currentTimeMillis()
    val updated =
      current.copy(
        stickyNotes =
          current.stickyNotes.map { s ->
            if (s.id == stickyId) {
              s.copy(x = s.x + dxWorld, y = s.y + dyWorld, updatedAt = now)
            } else s
          }
      )
    if (updated == current) return
    undoStack.addLast(current)
    if (undoStack.size > 40) undoStack.removeFirst()
    redoStack.clear()
    updateUndoFlags()
    _document.value = updated
    persistCurrentDocument(updated)
  }

  fun updateNode(updated: GraphNode) {
    val stamped = updated.copy(updatedAt = System.currentTimeMillis())
    updateDocument { doc ->
      doc.copy(nodes = doc.nodes.map { if (it.id == stamped.id) stamped else it })
    }
  }

  fun toggleNodeCollapsed(nodeId: String) {
    val now = System.currentTimeMillis()
    updateDocument { doc ->
      doc.copy(
        nodes = doc.nodes.map { if (it.id == nodeId) it.copy(collapsed = !it.collapsed, updatedAt = now) else it }
      )
    }
  }

  fun updateEdge(updated: GraphEdge) {
    val stamped = updated.copy(updatedAt = System.currentTimeMillis())
    updateDocument { doc ->
      doc.copy(edges = doc.edges.map { if (it.id == stamped.id) stamped else it })
    }
  }

  fun updateSticky(updated: StickyNote) {
    val stamped = updated.copy(updatedAt = System.currentTimeMillis())
    updateDocument { doc ->
      doc.copy(stickyNotes = doc.stickyNotes.map { if (it.id == stamped.id) stamped else it })
    }
  }

  fun duplicateSelectedNodes() {
    val sel = _selectedNodeIds.value
    if (sel.isEmpty()) return
    val doc = _document.value
    val idMap = mutableMapOf<String, String>()
    val newNodes =
      doc.nodes
        .filter { it.id in sel }
        .map { orig ->
          val newId = "n_${UUID.randomUUID().toString().take(8)}"
          idMap[orig.id] = newId
          orig.copy(id = newId, x = orig.x + 36f, y = orig.y + 36f)
        }
    val newEdges =
      doc.edges
        .filter { it.fromId in sel && it.toId in sel }
        .map { orig ->
          orig.copy(
            id = "e_${UUID.randomUUID().toString().take(8)}",
            fromId = idMap[orig.fromId]!!,
            toId = idMap[orig.toId]!!,
          )
        }
    updateDocument { it.copy(nodes = it.nodes + newNodes, edges = it.edges + newEdges) }
    _selectedNodeIds.value = newNodes.map { it.id }.toSet()
    showToast("Duplicated ${newNodes.size} node(s)")
  }

  fun deleteSelection() {
    val selNodes = _selectedNodeIds.value
    val selEdge = _selectedEdgeId.value
    val selSticky = _selectedStickyId.value
    if (selNodes.isEmpty() && selEdge == null && selSticky == null) return
    updateDocument { doc ->
      doc.copy(
        nodes = doc.nodes.filterNot { it.id in selNodes },
        edges =
          doc.edges.filterNot {
            it.id == selEdge || it.fromId in selNodes || it.toId in selNodes
          },
        stickyNotes = doc.stickyNotes.filterNot { it.id == selSticky },
      )
    }
    clearSelection()
  }

  fun toggleLayerVisibility(layerName: CanvasLayerName) {
    updateDocument(recordUndo = false) { doc ->
      doc.copy(
        layers =
          doc.layers.map {
            if (it.name == layerName) it.copy(visible = !it.visible) else it
          }
      )
    }
  }

  fun toggleLayerLock(layerName: CanvasLayerName) {
    updateDocument(recordUndo = false) { doc ->
      doc.copy(
        layers =
          doc.layers.map {
            if (it.name == layerName) it.copy(locked = !it.locked) else it
          }
      )
    }
  }

  fun alignSelection(mode: String) {
    val sel = _selectedNodeIds.value
    if (sel.size < 2) {
      showToast("Enable Multi-Select and choose 2+ nodes")
      return
    }
    val selected = _document.value.nodes.filter { it.id in sel }
    val minX = selected.minOf { it.x }
    val maxX = selected.maxOf { it.x }
    val minY = selected.minOf { it.y }
    val maxY = selected.maxOf { it.y }
    val avgX = selected.map { it.x }.average().toFloat()
    val avgY = selected.map { it.y }.average().toFloat()

    val updatedMap = mutableMapOf<String, GraphNode>()
    when (mode) {
      "LEFT" -> selected.forEach { updatedMap[it.id] = it.copy(x = minX) }
      "CENTER_H" -> selected.forEach { updatedMap[it.id] = it.copy(x = avgX) }
      "TOP" -> selected.forEach { updatedMap[it.id] = it.copy(y = minY) }
      "CENTER_V" -> selected.forEach { updatedMap[it.id] = it.copy(y = avgY) }
      "DIST_H" -> {
        val sorted = selected.sortedBy { it.x }
        sorted.forEachIndexed { idx, node ->
          val x = minX + idx * ((maxX - minX) / (sorted.size - 1))
          updatedMap[node.id] = node.copy(x = x)
        }
      }
      "DIST_V" -> {
        val sorted = selected.sortedBy { it.y }
        sorted.forEachIndexed { idx, node ->
          val y = minY + idx * ((maxY - minY) / (sorted.size - 1))
          updatedMap[node.id] = node.copy(y = y)
        }
      }
    }
    updateDocument { doc ->
      doc.copy(nodes = doc.nodes.map { updatedMap[it.id] ?: it })
    }
  }

  // --- Layouts & Graph Algorithms ---
  fun applyLayout(layout: LayoutAlgorithm) {
    updateDocument { doc -> GraphLayouts.applyLayout(doc, layout) }
    resetViewport()
    showToast("Applied ${layout.label} layout")
  }

  fun runShortestPath(sourceId: String, targetId: String) {
    val doc = _document.value
    val res = GraphAlgorithms.shortestPathDijkstra(doc, sourceId, targetId)
    if (!res.found) {
      _algoHighlight.value = AlgorithmHighlight("Shortest Path", "No path exists between selected nodes.")
      return
    }
    val names =
      res.nodeIds.mapNotNull { id -> doc.nodes.firstOrNull { it.id == id }?.text }.joinToString(" → ")
    _algoHighlight.value =
      AlgorithmHighlight(
        title = "Dijkstra Shortest Path (cost: ${String.format("%.1f", res.totalWeight)})",
        summary = names,
        nodeIds = res.nodeIds.toSet(),
        edgeIds = res.edgeIds.toSet(),
      )
  }

  fun runBfs(startId: String) {
    val doc = _document.value
    val (nodesOrder, edgesUsed) = GraphAlgorithms.bfsTraversal(doc, startId)
    val names = nodesOrder.mapNotNull { id -> doc.nodes.firstOrNull { it.id == id }?.text }.joinToString(" → ")
    _algoHighlight.value =
      AlgorithmHighlight(
        title = "BFS Traversal (${nodesOrder.size} reachable)",
        summary = names,
        nodeIds = nodesOrder.toSet(),
        edgeIds = edgesUsed.toSet(),
      )
  }

  fun runDfs(startId: String) {
    val doc = _document.value
    val (nodesOrder, edgesUsed) = GraphAlgorithms.dfsTraversal(doc, startId)
    val names = nodesOrder.mapNotNull { id -> doc.nodes.firstOrNull { it.id == id }?.text }.joinToString(" → ")
    _algoHighlight.value =
      AlgorithmHighlight(
        title = "DFS Traversal (${nodesOrder.size} reachable)",
        summary = names,
        nodeIds = nodesOrder.toSet(),
        edgeIds = edgesUsed.toSet(),
      )
  }

  fun runMst() {
    val doc = _document.value
    val (mstEdges, total) = GraphAlgorithms.minimumSpanningTreeKruskal(doc)
    _algoHighlight.value =
      AlgorithmHighlight(
        title = "Minimum Spanning Tree (Kruskal)",
        summary = "${mstEdges.size} spanning edges • Total weight ${String.format("%.1f", total)}",
        nodeIds = doc.nodes.map { it.id }.toSet(),
        edgeIds = mstEdges,
      )
  }

  fun runTopologicalSort() {
    val doc = _document.value
    val order = GraphAlgorithms.topologicalSortKahn(doc)
    if (order == null) {
      runCycleDetection()
      return
    }
    val rankMap = order.mapIndexed { idx, id -> id to (idx + 1).toString() }.toMap()
    updateDocument(recordUndo = false) { d ->
      d.copy(nodes = d.nodes.map { n -> n.copy(badge = rankMap[n.id]) })
    }
    val names = order.mapNotNull { id -> doc.nodes.firstOrNull { it.id == id }?.text }.joinToString(" → ")
    _algoHighlight.value =
      AlgorithmHighlight(
        title = "Topological Sort (DAG Order)",
        summary = names,
        nodeIds = order.toSet(),
      )
  }

  fun runCycleDetection() {
    val doc = _document.value
    val (cNodes, cEdges) = GraphAlgorithms.detectCycles(doc)
    _algoHighlight.value =
      if (cNodes.isEmpty()) {
        AlgorithmHighlight("Cycle Detection", "Acyclic graph — zero circular dependencies found.")
      } else {
        AlgorithmHighlight(
          title = "Cycles Detected (${cNodes.size} nodes)",
          summary = "Highlighted cyclic dependency nodes & feedback edges.",
          nodeIds = cNodes,
          edgeIds = cEdges,
        )
      }
  }

  fun runConnectedComponents() {
    val doc = _document.value
    val compMap = GraphAlgorithms.connectedComponents(doc)
    val palette = listOf("#DBEAFE", "#DCFCE7", "#FEF3C7", "#FFE4E6", "#EDE9FE", "#E0F2FE")
    val colorAssign = compMap.mapValues { (_, c) -> palette[c % palette.size] }
    val count = compMap.values.distinct().size
    _algoHighlight.value =
      AlgorithmHighlight(
        title = "Connected Components ($count clusters)",
        summary = "Assigned cluster color groups across ${doc.nodes.size} nodes.",
        clusterColorMap = colorAssign,
      )
  }

  fun runCentrality(autoResizeNodes: Boolean = false) {
    val doc = _document.value
    val results = GraphAlgorithms.computeCentralityMetrics(doc)
    _centralityResults.value = results
    if (autoResizeNodes && results.isNotEmpty()) {
      val maxPr = results.maxOf { it.pageRank }.coerceAtLeast(1e-5)
      val prMap = results.associate { it.nodeId to it.pageRank }
      updateDocument { d ->
        d.copy(
          nodes =
            d.nodes.map { n ->
              val ratio = ((prMap[n.id] ?: 0.0) / maxPr).toFloat()
              n.copy(w = 125f + ratio * 90f, h = 58f + ratio * 30f)
            }
        )
      }
    }
    val top = results.firstOrNull()
    _algoHighlight.value =
      AlgorithmHighlight(
        title = "Centrality & PageRank Analysis",
        summary =
          if (top != null) "Top hub: ${top.label} (PR ${String.format("%.3f", top.pageRank)})"
          else "No nodes",
        nodeIds = results.take(2).map { it.nodeId }.toSet(),
      )
  }

  // --- Bi-Directional Canvas <-> Notes Studio ---
  fun focusNodeOnCanvas(nodeId: String) {
    val node = _document.value.nodes.firstOrNull { it.id == nodeId }
    if (node == null) {
      showToast("Node not found in active graph")
      return
    }
    _selectedEdgeId.value = null
    _selectedStickyId.value = null
    _selectedNodeIds.value = setOf(node.id)
    _zoomScale.value = 1.1f
    _panOffset.value = Offset(-node.x * 1.1f, -node.y * 1.1f)
    _currentTab.value = AppDestination.CANVAS
  }

  fun focusEdgeOnCanvas(edgeId: String) {
    val doc = _document.value
    val edge = doc.edges.firstOrNull { it.id == edgeId }
    if (edge == null) {
      showToast("Connection not found in active graph")
      return
    }
    val a = doc.nodes.firstOrNull { it.id == edge.fromId }
    val b = doc.nodes.firstOrNull { it.id == edge.toId }
    _selectedNodeIds.value = emptySet()
    _selectedStickyId.value = null
    _selectedEdgeId.value = edge.id
    if (a != null && b != null) {
      val mx = (a.x + b.x) / 2f
      val my = (a.y + b.y) / 2f
      _zoomScale.value = 1.05f
      _panOffset.value = Offset(-mx * 1.05f, -my * 1.05f)
    }
    _currentTab.value = AppDestination.CANVAS
  }

  fun saveNote(note: StudioNote) {
    val boardId = _activeSharedBoardId.value
    val updater = _currentUser.value?.displayName ?: currentUserName
    val stamped =
      note.copy(
        projectId = note.projectId ?: boardId ?: activeProject.value?.id,
        updatedAt = System.currentTimeMillis(),
      )
    viewModelScope.launch {
      if (boardId != null) {
        collabRepo.upsertBoardNote(boardId, stamped, updater).onFailure { err ->
          showToast(err.localizedMessage ?: "Failed to sync board note")
        }
      } else {
        repo.saveNote(currentUserId, stamped)
        collabRepo.upsertPersonalNote(stamped, updater).onFailure { err ->
          showToast(err.localizedMessage ?: "Saved locally; cloud sync pending")
        }
      }
    }
  }

  fun deleteNote(noteId: String) {
    val boardId = _activeSharedBoardId.value
    viewModelScope.launch {
      if (boardId != null) {
        collabRepo.deleteBoardNote(boardId, noteId).onFailure { err ->
          showToast(err.localizedMessage ?: "Failed to delete board note")
        }
      } else {
        repo.deleteNote(currentUserId, noteId)
        collabRepo.deletePersonalNote(noteId).onFailure { err ->
          showToast(err.localizedMessage ?: "Deleted locally; cloud sync error")
        }
      }
      if (_editingNote.value?.id == noteId) _editingNote.value = null
    }
  }

  fun openNoteEditor(note: StudioNote?) {
    _editingNote.value = note
  }

  fun openOrCreateNoteForNode(node: GraphNode) {
    val existing = notes.value.firstOrNull { it.linkedNodeId == node.id }
    if (existing != null) {
      _editingNote.value = existing
    } else {
      val created =
        StudioNote(
          projectId = _activeSharedBoardId.value ?: activeProject.value?.id,
          title = "Notes: ${node.text}",
          body = node.richNotes.text,
          noteType = NoteType.ANALYSIS,
          folder = "Canvas Links",
          tags = node.tags,
          linkedNodeId = node.id,
          linkedNodeName = node.text,
          checklist = node.richNotes.checklist,
        )
      saveNote(created)
      _editingNote.value = created
    }
    _currentTab.value = AppDestination.NOTES
  }

  fun createCanvasNodeFromNote(note: StudioNote) {
    val doc = _document.value
    val existing = note.linkedNodeId?.let { id -> doc.nodes.firstOrNull { it.id == id } }
    if (existing != null) {
      focusNodeOnCanvas(existing.id)
      return
    }
    val offset = (doc.nodes.size % 5) * 40f
    val newNode =
      GraphNode(
        x = offset - 40f,
        y = offset - 40f,
        w = 175f,
        h = 74f,
        text = note.title.take(36),
        shape = NodeShape.CALLOUT,
        colorHex = note.colorHex,
        icon = "📝",
        tags = note.tags,
        richNotes = RichNodeNote(text = note.body, checklist = note.checklist),
      )
    updateDocument { it.copy(nodes = it.nodes + newNode) }
    saveNote(note.copy(linkedNodeId = newNode.id, linkedNodeName = newNode.text))
    focusNodeOnCanvas(newNode.id)
    showToast("Created linked node on canvas")
  }

  // --- OSINT Workbench ---
  fun extractOsint(rawText: String) {
    _osintExtracted.value = OsintEngine.extractEntities(rawText)
    _osintTransformResult.value = null
  }

  fun runOsintTransform(entity: OsintEntity) {
    viewModelScope.launch {
      _osintBusy.value = true
      _osintTransformResult.value = OsintEngine.runTransform(entity)
      _osintBusy.value = false
    }
  }

  fun spawnOsintResultToCanvas(res: OsintTransformResult) {
    val doc = _document.value
    val existingParent = doc.nodes.firstOrNull { it.text.equals(res.source.value, ignoreCase = true) }
    val parentNode =
      existingParent
        ?: GraphNode(
          x = 0f,
          y = 0f,
          w = 185f,
          h = 68f,
          text = res.source.value,
          shape = NodeShape.DIAMOND,
          colorHex = res.source.type.colorHex,
          icon = res.source.type.icon,
          tags = listOf("osint", res.source.type.name.lowercase()),
        )

    val newNodes = mutableListOf<GraphNode>()
    if (existingParent == null) newNodes += parentNode

    val newEdges = mutableListOf<GraphEdge>()
    val count = res.spawned.size.coerceAtLeast(1)
    res.spawned.forEachIndexed { idx, child ->
      val angle = (2.0 * PI * idx) / count
      val cx = parentNode.x + (210f * cos(angle)).toFloat()
      val cy = parentNode.y + (165f * sin(angle)).toFloat()
      val childNode =
        GraphNode(
          x = cx,
          y = cy,
          w = 170f,
          h = 64f,
          text = child.value,
          shape = NodeShape.ROUNDED,
          colorHex = child.type.colorHex,
          icon = child.type.icon,
          tags = listOf("osint", child.type.name.lowercase()),
        )
      newNodes += childNode
      newEdges +=
        GraphEdge(
          fromId = parentNode.id,
          toId = childNode.id,
          label = res.relationLabel,
          type = EdgeType.CURVED,
        )
    }

    updateDocument { d ->
      d.copy(nodes = d.nodes + newNodes, edges = d.edges + newEdges)
    }
    focusNodeOnCanvas(parentNode.id)
    showToast("Spawned ${newNodes.size} OSINT node(s) to canvas")
  }

  // --- Formula Sheet ---
  fun updateFormulaCell(key: String, rawValue: String) {
    val map = _formulaCells.value.toMutableMap()
    if (rawValue.isBlank()) map.remove(key) else map[key] = rawValue
    _formulaCells.value = map
  }

  fun resetFormulaSheet() {
    _formulaCells.value = FormulaEngine.defaultSheet()
  }
}
