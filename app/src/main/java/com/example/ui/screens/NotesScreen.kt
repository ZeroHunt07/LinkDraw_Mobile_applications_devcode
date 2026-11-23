package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.*

@Composable
fun NotesScreen(
  notes: List<StudioNote>,
  editingNote: StudioNote?,
  onOpenEditor: (StudioNote?) -> Unit,
  onSaveNote: (StudioNote) -> Unit,
  onDeleteNote: (String) -> Unit,
  onFocusCanvasNode: (String) -> Unit,
  onCreateCanvasNodeFromNote: (StudioNote) -> Unit,
  activeSharedBoardTitle: String? = null,
  isCloudSynced: Boolean = true,
) {
  if (editingNote != null) {
    BackHandler { onOpenEditor(null) }
    NoteEditorView(
      note = editingNote,
      onBack = { onOpenEditor(null) },
      onSave = { updated ->
        onSaveNote(updated)
        onOpenEditor(null)
      },
      onDelete = {
        onDeleteNote(editingNote.id)
        onOpenEditor(null)
      },
      onCreateOrJumpCanvas = { updated ->
        onSaveNote(updated)
        onCreateCanvasNodeFromNote(updated)
      },
    )
    return
  }

  var searchQuery by remember { mutableStateOf("") }
  var selectedFilter by remember { mutableStateOf("All") }

  val folders =
    remember(notes) {
      val custom = notes.map { it.folder.ifBlank { "General" } }.distinct().sorted()
      listOf("All", "Pinned", "Archived") + custom
    }

  val filtered =
    remember(notes, searchQuery, selectedFilter) {
      notes.filter { note ->
        val matchesFilter =
          when (selectedFilter) {
            "All" -> !note.archived
            "Pinned" -> note.pinned && !note.archived
            "Archived" -> note.archived
            else -> !note.archived && note.folder.equals(selectedFilter, ignoreCase = true)
          }
        val matchesSearch =
          searchQuery.isBlank() ||
            note.title.contains(searchQuery, ignoreCase = true) ||
            note.body.contains(searchQuery, ignoreCase = true) ||
            note.tags.any { it.contains(searchQuery, ignoreCase = true) }
        matchesFilter && matchesSearch
      }
    }

  Scaffold(
    contentWindowInsets = WindowInsets(0, 0, 0, 0),
    floatingActionButton = {
      ExtendedFloatingActionButton(
        onClick = { onOpenEditor(StudioNote()) },
        icon = { Icon(Icons.Default.NoteAdd, null) },
        text = { Text("New Note") },
        modifier = Modifier.testTag("notes_new_fab"),
      )
    },
  ) { innerPadding ->
    Column(
      modifier =
        Modifier.fillMaxSize()
          .padding(innerPadding)
          .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text("Document Studio", style = MaterialTheme.typography.headlineMedium)
          Text(
            if (activeSharedBoardTitle != null) {
              "Shared board notes • synced across Web & Android"
            } else {
              "Standalone & canvas-linked intelligence notes"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Spacer(Modifier.width(8.dp))
        Surface(
          shape = RoundedCornerShape(999.dp),
          color =
            if (activeSharedBoardTitle != null) MaterialTheme.colorScheme.tertiaryContainer
            else if (isCloudSynced) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
          modifier = Modifier.testTag("notes_sync_badge"),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
          ) {
            Icon(
              imageVector =
                when {
                  activeSharedBoardTitle != null -> Icons.Default.Groups
                  isCloudSynced -> Icons.Default.CloudDone
                  else -> Icons.Default.CloudSync
                },
              contentDescription = "Cloud sync status",
              modifier = Modifier.size(14.dp),
              tint =
                if (activeSharedBoardTitle != null) MaterialTheme.colorScheme.onTertiaryContainer
                else if (isCloudSynced) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
              text =
                when {
                  activeSharedBoardTitle != null -> "Board: ${activeSharedBoardTitle.take(14)}"
                  isCloudSynced -> "Cloud Sync"
                  else -> "Syncing..."
                },
              style = MaterialTheme.typography.labelSmall,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              color =
                if (activeSharedBoardTitle != null) MaterialTheme.colorScheme.onTertiaryContainer
                else if (isCloudSynced) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }

      Spacer(Modifier.height(10.dp))

      OutlinedTextField(
        value = searchQuery,
        onValueChange = { searchQuery = it },
        placeholder = { Text("Search notes, tags, or findings...") },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().testTag("notes_search_input"),
      )

      Spacer(Modifier.height(10.dp))

      Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        folders.forEach { folder ->
          FilterChip(
            selected = selectedFilter == folder,
            onClick = { selectedFilter = folder },
            label = { Text(folder) },
          )
        }
      }

      Spacer(Modifier.height(10.dp))

      if (filtered.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
              Icons.Outlined.Description,
              contentDescription = null,
              modifier = Modifier.size(56.dp),
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text("No notes in $selectedFilter", style = MaterialTheme.typography.titleMedium)
          }
        }
      } else {
        LazyColumn(
          verticalArrangement = Arrangement.spacedBy(10.dp),
          contentPadding = PaddingValues(bottom = 88.dp),
        ) {
          items(filtered, key = { it.id }) { note ->
            NoteCardItem(
              note = note,
              onClick = { onOpenEditor(note) },
              onTogglePin = { onSaveNote(note.copy(pinned = !note.pinned)) },
              onJumpToNode = { note.linkedNodeId?.let(onFocusCanvasNode) },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun NoteCardItem(
  note: StudioNote,
  onClick: () -> Unit,
  onTogglePin: () -> Unit,
  onJumpToNode: () -> Unit,
) {
  val ribbonColor = parseHexColor(note.priority.ribbonHex, MaterialTheme.colorScheme.primary)
  ElevatedCard(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth().testTag("note_card_${note.id}"),
  ) {
    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
      Box(modifier = Modifier.width(6.dp).fillMaxHeight().background(ribbonColor))
      Column(modifier = Modifier.padding(14.dp).weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          AssistChip(
            onClick = {},
            label = { Text(note.noteType.label, style = MaterialTheme.typography.labelSmall) },
          )
          Spacer(Modifier.width(6.dp))
          Text(
            text = note.folder,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
          )
          IconButton(onClick = onTogglePin) {
            Icon(
              if (note.pinned) Icons.Default.PushPin else Icons.Default.BookmarkBorder,
              contentDescription = "Pin Note",
              tint = if (note.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        Text(note.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (note.body.isNotBlank()) {
          Spacer(Modifier.height(4.dp))
          Text(
            note.body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          val doneCount = note.checklist.count { it.checked }
          if (note.checklist.isNotEmpty()) {
            Text(
              "☑ $doneCount/${note.checklist.size}",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.tertiary,
            )
          }
          Text(
            "${note.wordCount} words",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(Modifier.weight(1f))
          if (note.linkedNodeId != null) {
            FilledTonalButton(
              onClick = onJumpToNode,
              contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) {
              Icon(Icons.Default.Hub, null, Modifier.size(14.dp))
              Spacer(Modifier.width(4.dp))
              Text(note.linkedNodeName ?: "Node", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun NoteEditorView(
  note: StudioNote,
  onBack: () -> Unit,
  onSave: (StudioNote) -> Unit,
  onDelete: () -> Unit,
  onCreateOrJumpCanvas: (StudioNote) -> Unit,
) {
  var title by remember(note.id) { mutableStateOf(note.title) }
  var body by remember(note.id) { mutableStateOf(note.body) }
  var folder by remember(note.id) { mutableStateOf(note.folder) }
  var noteType by remember(note.id) { mutableStateOf(note.noteType) }
  var priority by remember(note.id) { mutableStateOf(note.priority) }
  var colorHex by remember(note.id) { mutableStateOf(note.colorHex) }
  var tagsText by remember(note.id) { mutableStateOf(note.tags.joinToString(", ")) }
  var sourceUrl by remember(note.id) { mutableStateOf(note.sourceUrl) }
  var pinned by remember(note.id) { mutableStateOf(note.pinned) }
  var archived by remember(note.id) { mutableStateOf(note.archived) }
  var checklist by remember(note.id) { mutableStateOf(note.checklist) }
  var newTaskText by remember { mutableStateOf("") }

  fun buildNote(): StudioNote =
    note.copy(
      title = title.ifBlank { "Untitled Note" },
      body = body,
      folder = folder.ifBlank { "General" },
      noteType = noteType,
      priority = priority,
      colorHex = colorHex,
      tags = tagsText.split(",").map { it.trim() }.filter { it.isNotEmpty() },
      sourceUrl = sourceUrl,
      pinned = pinned,
      archived = archived,
      checklist = checklist,
    )

  Column(modifier = Modifier.fillMaxSize()) {
    Surface(tonalElevation = 2.dp) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        IconButton(onClick = onBack) {
          Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Notes")
        }
        Text("Edit Document", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = { pinned = !pinned }) {
          Icon(
            Icons.Default.PushPin,
            contentDescription = "Toggle Pin",
            tint = if (pinned) MaterialTheme.colorScheme.primary else LocalContentColor.current,
          )
        }
        IconButton(onClick = { archived = !archived }) {
          Icon(
            Icons.Default.Archive,
            contentDescription = "Toggle Archive",
            tint = if (archived) MaterialTheme.colorScheme.secondary else LocalContentColor.current,
          )
        }
        IconButton(onClick = onDelete) {
          Icon(Icons.Default.DeleteOutline, contentDescription = "Delete Note", tint = MaterialTheme.colorScheme.error)
        }
        Button(
          onClick = { onSave(buildNote()) },
          modifier = Modifier.testTag("note_save_button"),
        ) { Text("Save") }
      }
    }

    Column(
      modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      OutlinedTextField(
        value = title,
        onValueChange = { title = it },
        label = { Text("Title") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("note_title_input"),
      )

      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
          value = folder,
          onValueChange = { folder = it },
          label = { Text("Folder") },
          singleLine = true,
          modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
          value = tagsText,
          onValueChange = { tagsText = it },
          label = { Text("Tags") },
          singleLine = true,
          modifier = Modifier.weight(1f),
        )
      }

      Text("Priority & Type", style = MaterialTheme.typography.labelLarge)
      Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        NotePriority.entries.forEach { p ->
          FilterChip(selected = priority == p, onClick = { priority = p }, label = { Text(p.label) })
        }
      }
      Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        NoteType.entries.forEach { t ->
          FilterChip(selected = noteType == t, onClick = { noteType = t }, label = { Text(t.label) })
        }
      }

      // Color Ribbon Swatches
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Card Tint:", style = MaterialTheme.typography.labelMedium)
        listOf("#EEF2FF", "#FFE4E6", "#FEF3C7", "#DCFCE7", "#E0F2FE").forEach { hex ->
          Box(
            modifier =
              Modifier.size(28.dp)
                .clip(CircleShape)
                .background(parseHexColor(hex))
                .border(
                  if (colorHex == hex) 2.5.dp else 1.dp,
                  if (colorHex == hex) MaterialTheme.colorScheme.primary else Color.Gray,
                  CircleShape,
                )
                .clickable { colorHex = hex },
          )
        }
      }

      // Markdown Quick Format Toolbar
      Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        FilledTonalButton(onClick = { body += "\n## Section\n" }) { Text("## Heading") }
        FilledTonalButton(onClick = { body += "**bold**" }) { Text("Bold") }
        FilledTonalButton(onClick = { body += "\n- Bullet point" }) { Text("• List") }
      }

      OutlinedTextField(
        value = body,
        onValueChange = { body = it },
        label = { Text("Markdown Body") },
        minLines = 5,
        modifier = Modifier.fillMaxWidth().testTag("note_body_input"),
      )

      OutlinedTextField(
        value = sourceUrl,
        onValueChange = { sourceUrl = it },
        label = { Text("Source URL (optional)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )

      // Interactive Checklist
      Text("Interactive Checklist", style = MaterialTheme.typography.titleSmall)
      checklist.forEachIndexed { idx, item ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
          Checkbox(
            checked = item.checked,
            onCheckedChange = { c ->
              checklist = checklist.mapIndexed { i, it -> if (i == idx) it.copy(checked = c) else it }
            },
          )
          Text(
            text = item.text,
            textDecoration = if (item.checked) TextDecoration.LineThrough else TextDecoration.None,
            modifier = Modifier.weight(1f),
          )
          IconButton(onClick = { checklist = checklist.filterIndexed { i, _ -> i != idx } }) {
            Icon(Icons.Default.Close, contentDescription = "Remove task", modifier = Modifier.size(18.dp))
          }
        }
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
          value = newTaskText,
          onValueChange = { newTaskText = it },
          label = { Text("New checklist task") },
          singleLine = true,
          modifier = Modifier.weight(1f),
        )
        FilledTonalButton(
          onClick = {
            if (newTaskText.isNotBlank()) {
              checklist = checklist + ChecklistItem(text = newTaskText.trim())
              newTaskText = ""
            }
          }
        ) { Text("Add") }
      }

      // Bi-directional Canvas Action
      OutlinedButton(
        onClick = { onCreateOrJumpCanvas(buildNote()) },
        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("note_canvas_link_button"),
      ) {
        Icon(Icons.Default.Hub, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(
          if (note.linkedNodeId != null) "Go to Linked Canvas Node (${note.linkedNodeName ?: "Node"})"
          else "Create Linked Node on Canvas"
        )
      }
    }
  }
}
