package com.example.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.GraphProjectEntity
import com.example.data.local.ProjectTemplate
import com.example.data.local.UserAccountEntity
import com.example.model.CollabBoard
import com.example.model.UiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class WorkspaceFilterTab(val label: String) {
  ALL("All"),
  SHARED("Shared Live"),
  PERSONAL("Personal"),
}

@Composable
fun ProjectsScreen(
  user: UserAccountEntity?,
  projects: List<GraphProjectEntity>,
  activeProjectId: String?,
  sharedBoardsState: UiState<List<CollabBoard>>,
  activeSharedBoardId: String?,
  collabBusy: Boolean,
  onOpenProject: (String) -> Unit,
  onCreateProject: (title: String, desc: String, template: ProjectTemplate, isShared: Boolean) -> Unit,
  onDuplicateProject: (String) -> Unit,
  onDeleteProject: (String) -> Unit,
  onShareProjectLive: (GraphProjectEntity) -> Unit,
  onOpenSharedBoard: (String) -> Unit,
  onJoinSharedBoard: (String) -> Unit,
  onLeaveSharedBoard: (String) -> Unit,
  onDeleteSharedBoard: (CollabBoard) -> Unit,
  onImportJson: (title: String, json: String) -> Unit,
  onSignOut: () -> Unit,
) {
  val context = LocalContext.current
  var searchQuery by remember { mutableStateOf("") }
  var showCreateDialog by remember { mutableStateOf(false) }
  var showJoinDialog by remember { mutableStateOf(false) }
  var filterTab by remember { mutableStateOf(WorkspaceFilterTab.ALL) }

  val importLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) {
        runCatching {
          val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
          if (!text.isNullOrBlank()) {
            onImportJson("Imported Graph", text)
          }
        }
      }
    }

  val filteredPersonal =
    remember(projects, searchQuery) {
      if (searchQuery.isBlank()) projects
      else {
        projects.filter {
          it.title.contains(searchQuery, ignoreCase = true) ||
            it.description.contains(searchQuery, ignoreCase = true)
        }
      }
    }

  val sharedList =
    when (sharedBoardsState) {
      is UiState.Success -> sharedBoardsState.data
      else -> emptyList()
    }
  val filteredShared =
    remember(sharedList, searchQuery) {
      if (searchQuery.isBlank()) sharedList
      else {
        sharedList.filter {
          it.title.contains(searchQuery, ignoreCase = true) ||
            it.description.contains(searchQuery, ignoreCase = true) ||
            it.inviteCode.contains(searchQuery, ignoreCase = true)
        }
      }
    }

  Scaffold(
    contentWindowInsets = WindowInsets(0, 0, 0, 0),
    floatingActionButton = {
      ExtendedFloatingActionButton(
        onClick = { showCreateDialog = true },
        icon = { Icon(Icons.Default.Add, contentDescription = null) },
        text = { Text("New Graph") },
        modifier = Modifier.testTag("new_project_fab"),
      )
    },
  ) { innerPadding ->
    Column(
      modifier =
        Modifier.fillMaxSize()
          .padding(innerPadding)
          .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
      // Header
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "Workspaces",
            style = MaterialTheme.typography.headlineMedium,
          )
          Text(
            text = "Signed in as ${user?.displayName ?: "Analyst"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        FilledTonalButton(
          onClick = { showJoinDialog = true },
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
          modifier = Modifier.testTag("join_board_button"),
        ) {
          Icon(Icons.Default.GroupAdd, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(6.dp))
          Text("Join")
        }
        Spacer(Modifier.width(6.dp))
        FilledTonalIconButton(
          onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
          modifier = Modifier.minimumInteractiveComponentSize().testTag("import_json_button"),
        ) {
          Icon(Icons.Default.FileOpen, contentDescription = "Import LinkDraw JSON")
        }
        Spacer(Modifier.width(4.dp))
        OutlinedIconButton(
          onClick = onSignOut,
          modifier = Modifier.minimumInteractiveComponentSize().testTag("header_signout_button"),
        ) {
          Icon(Icons.Default.Logout, contentDescription = "Sign Out")
        }
      }

      Spacer(Modifier.height(10.dp))

      OutlinedTextField(
        value = searchQuery,
        onValueChange = { searchQuery = it },
        placeholder = { Text("Search personal & shared boards...") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
          if (searchQuery.isNotEmpty()) {
            IconButton(onClick = { searchQuery = "" }) {
              Icon(Icons.Default.Clear, contentDescription = "Clear search")
            }
          }
        },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().testTag("projects_search_input"),
      )

      Spacer(Modifier.height(10.dp))

      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        WorkspaceFilterTab.entries.forEach { tab ->
          FilterChip(
            selected = filterTab == tab,
            onClick = { filterTab = tab },
            label = {
              val count =
                when (tab) {
                  WorkspaceFilterTab.ALL -> filteredShared.size + filteredPersonal.size
                  WorkspaceFilterTab.SHARED -> filteredShared.size
                  WorkspaceFilterTab.PERSONAL -> filteredPersonal.size
                }
              Text("${tab.label} ($count)")
            },
            modifier = Modifier.testTag("workspace_filter_${tab.name.lowercase()}"),
          )
        }
      }

      if (collabBusy) {
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
      }

      Spacer(Modifier.height(8.dp))

      val showShared = filterTab == WorkspaceFilterTab.ALL || filterTab == WorkspaceFilterTab.SHARED
      val showPersonal = filterTab == WorkspaceFilterTab.ALL || filterTab == WorkspaceFilterTab.PERSONAL

      LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 88.dp),
      ) {
        if (showShared) {
          item(key = "header_shared") {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(
                Icons.Default.Groups,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
              )
              Spacer(Modifier.width(6.dp))
              Text("Collaborative Live Boards", style = MaterialTheme.typography.titleSmall)
            }
          }

          when (sharedBoardsState) {
            is UiState.Loading -> {
              item(key = "shared_loading") {
                Surface(
                  shape = RoundedCornerShape(14.dp),
                  color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                  modifier = Modifier.fillMaxWidth(),
                ) {
                  Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Syncing collaborative boards...", style = MaterialTheme.typography.bodySmall)
                  }
                }
              }
            }
            is UiState.Error -> {
              item(key = "shared_error") {
                Surface(
                  shape = RoundedCornerShape(14.dp),
                  color = MaterialTheme.colorScheme.errorContainer,
                  modifier = Modifier.fillMaxWidth(),
                ) {
                  Text(
                    text = sharedBoardsState.message,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                  )
                }
              }
            }
            is UiState.Success -> {
              if (filteredShared.isEmpty()) {
                item(key = "shared_empty") {
                  OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                      modifier = Modifier.padding(14.dp),
                      verticalAlignment = Alignment.CenterVertically,
                    ) {
                      Column(modifier = Modifier.weight(1f)) {
                        Text("No shared boards yet", style = MaterialTheme.typography.titleSmall)
                        Text(
                          "Tap \"Join\" with an invite code or share any graph below to collaborate live.",
                          style = MaterialTheme.typography.bodySmall,
                          color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                      }
                      TextButton(onClick = { showJoinDialog = true }) { Text("Enter Code") }
                    }
                  }
                }
              } else {
                items(filteredShared, key = { "shared_${it.id}" }) { board ->
                  SharedBoardCard(
                    board = board,
                    currentUserId = user?.id ?: "",
                    isActive = board.id == activeSharedBoardId,
                    onOpen = { onOpenSharedBoard(board.id) },
                    onShareInvite = {
                      val sendIntent =
                        Intent(Intent.ACTION_SEND).apply {
                          type = "text/plain"
                          putExtra(Intent.EXTRA_SUBJECT, "Join LinkDraw Board: ${board.title}")
                          putExtra(
                            Intent.EXTRA_TEXT,
                            "Join my live LinkDraw board \"${board.title}\" with Invite Code: ${board.inviteCode}",
                          )
                        }
                      context.startActivity(Intent.createChooser(sendIntent, "Share Board Invite"))
                    },
                    onLeave = { onLeaveSharedBoard(board.id) },
                    onDelete = { onDeleteSharedBoard(board) },
                  )
                }
              }
            }
          }
        }

        if (showPersonal) {
          item(key = "header_personal") {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.padding(top = if (showShared) 6.dp else 0.dp),
            ) {
              Icon(
                Icons.Default.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(18.dp),
              )
              Spacer(Modifier.width(6.dp))
              Text("Personal Graphs", style = MaterialTheme.typography.titleSmall)
            }
          }

          if (filteredPersonal.isEmpty()) {
            item(key = "personal_empty") {
              Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                contentAlignment = Alignment.Center,
              ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                  Icon(
                    imageVector = Icons.Outlined.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                  Spacer(Modifier.height(8.dp))
                  Text("No personal projects match", style = MaterialTheme.typography.titleMedium)
                }
              }
            }
          } else {
            items(filteredPersonal, key = { it.id }) { proj ->
              val isActive = activeSharedBoardId == null && proj.id == activeProjectId
              ProjectCard(
                project = proj,
                isActive = isActive,
                onOpen = { onOpenProject(proj.id) },
                onCollaborate = { onShareProjectLive(proj) },
                onDuplicate = { onDuplicateProject(proj.id) },
                onShareJson = {
                  val sendIntent =
                    Intent(Intent.ACTION_SEND).apply {
                      type = "application/json"
                      putExtra(Intent.EXTRA_SUBJECT, "${proj.title}.linkdraw.json")
                      putExtra(Intent.EXTRA_TEXT, proj.documentJson)
                    }
                  context.startActivity(Intent.createChooser(sendIntent, "Share Graph JSON"))
                },
                onDelete = { onDeleteProject(proj.id) },
              )
            }
          }
        }
      }
    }
  }

  if (showCreateDialog) {
    CreateProjectDialog(
      onDismiss = { showCreateDialog = false },
      onCreate = { title, desc, template, isShared ->
        onCreateProject(title, desc, template, isShared)
        showCreateDialog = false
      },
    )
  }

  if (showJoinDialog) {
    JoinBoardDialog(
      busy = collabBusy,
      onDismiss = { showJoinDialog = false },
      onJoin = { code ->
        onJoinSharedBoard(code)
        showJoinDialog = false
      },
    )
  }
}

@Composable
private fun SharedBoardCard(
  board: CollabBoard,
  currentUserId: String,
  isActive: Boolean,
  onOpen: () -> Unit,
  onShareInvite: () -> Unit,
  onLeave: () -> Unit,
  onDelete: () -> Unit,
) {
  val isOwner = board.ownerId == currentUserId
  val fmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
  val timeText =
    (board.updatedAt ?: board.createdAt)?.toDate()?.let { fmt.format(it) } ?: "Syncing..."

  ElevatedCard(
    onClick = onOpen,
    modifier = Modifier.fillMaxWidth().testTag("shared_board_card_${board.id}"),
    colors =
      CardDefaults.elevatedCardColors(
        containerColor =
          if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
          else MaterialTheme.colorScheme.surface,
      ),
  ) {
    Column(modifier = Modifier.padding(16.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          imageVector = Icons.Default.Hub,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = board.title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = board.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
        AssistChip(
          onClick = onShareInvite,
          label = { Text(board.inviteCode, fontWeight = FontWeight.Bold) },
          leadingIcon = { Icon(Icons.Default.VpnKey, null, Modifier.size(14.dp)) },
        )
      }

      Spacer(Modifier.height(10.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text =
            "LIVE • ${board.memberUids.size} member(s) • ${if (isOwner) "Owner" else "By ${board.ownerName}"} • $timeText",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onShareInvite) {
          Icon(Icons.Default.Share, contentDescription = "Share Invite Code", modifier = Modifier.size(20.dp))
        }
        if (isOwner) {
          IconButton(onClick = onDelete) {
            Icon(
              Icons.Default.DeleteOutline,
              contentDescription = "Delete Shared Board",
              tint = MaterialTheme.colorScheme.error,
              modifier = Modifier.size(20.dp),
            )
          }
        } else {
          IconButton(onClick = onLeave) {
            Icon(
              Icons.Default.ExitToApp,
              contentDescription = "Leave Board",
              tint = MaterialTheme.colorScheme.error,
              modifier = Modifier.size(20.dp),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun ProjectCard(
  project: GraphProjectEntity,
  isActive: Boolean,
  onOpen: () -> Unit,
  onCollaborate: () -> Unit,
  onDuplicate: () -> Unit,
  onShareJson: () -> Unit,
  onDelete: () -> Unit,
) {
  val fmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
  ElevatedCard(
    onClick = onOpen,
    modifier = Modifier.fillMaxWidth().testTag("project_card_${project.id}"),
    colors =
      CardDefaults.elevatedCardColors(
        containerColor =
          if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
          else MaterialTheme.colorScheme.surface,
      ),
  ) {
    Column(modifier = Modifier.padding(16.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          imageVector = Icons.Default.AccountTree,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = project.title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = project.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
        if (isActive) {
          AssistChip(onClick = onOpen, label = { Text("Active") })
        }
      }

      Spacer(Modifier.height(10.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "${project.nodeCount} nodes • ${project.edgeCount} edges • ${fmt.format(Date(project.updatedAt))}",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onCollaborate, modifier = Modifier.testTag("collaborate_project_${project.id}")) {
          Icon(
            Icons.Default.GroupAdd,
            contentDescription = "Publish as Shared Collaborative Board",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
          )
        }
        IconButton(onClick = onShareJson) {
          Icon(Icons.Default.Share, contentDescription = "Share JSON", modifier = Modifier.size(20.dp))
        }
        IconButton(onClick = onDuplicate) {
          Icon(Icons.Default.ContentCopy, contentDescription = "Duplicate", modifier = Modifier.size(20.dp))
        }
        IconButton(onClick = onDelete) {
          Icon(
            Icons.Default.DeleteOutline,
            contentDescription = "Delete",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
          )
        }
      }
    }
  }
}

@Composable
private fun CreateProjectDialog(
  onDismiss: () -> Unit,
  onCreate: (String, String, ProjectTemplate, Boolean) -> Unit,
) {
  var title by remember { mutableStateOf("New Network Graph") }
  var desc by remember { mutableStateOf("Custom LinkDraw workspace") }
  var selectedTemplate by remember { mutableStateOf(ProjectTemplate.MICROSERVICES) }
  var createAsShared by remember { mutableStateOf(true) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Create Graph Workspace") },
    text = {
      Column(
        modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        OutlinedTextField(
          value = title,
          onValueChange = { title = it },
          label = { Text("Workspace Title") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("create_project_title"),
        )
        OutlinedTextField(
          value = desc,
          onValueChange = { desc = it },
          label = { Text("Description") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text("Shared Live Board", style = MaterialTheme.typography.titleSmall)
            Text(
              "Enable real-time team editing with an invite code",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Switch(checked = createAsShared, onCheckedChange = { createAsShared = it })
        }
        Text("Starter Template", style = MaterialTheme.typography.labelLarge)
        ProjectTemplate.entries.forEach { tpl ->
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            RadioButton(
              selected = selectedTemplate == tpl,
              onClick = { selectedTemplate = tpl },
            )
            Column(modifier = Modifier.padding(start = 6.dp)) {
              Text(tpl.label, style = MaterialTheme.typography.bodyMedium)
              Text(
                tpl.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }
    },
    confirmButton = {
      Button(
        onClick = { onCreate(title, desc, selectedTemplate, createAsShared) },
        modifier = Modifier.testTag("confirm_create_project"),
      ) { Text("Create") }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}
