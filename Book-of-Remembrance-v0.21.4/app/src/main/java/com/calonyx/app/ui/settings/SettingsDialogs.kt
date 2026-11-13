package com.calonyx.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.calonyx.app.data.EventType
import com.calonyx.app.data.RelationshipTag

@Composable
fun EventTypeRow(
    type: EventType,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(Color(type.colorArgb))
        )
        Text(
            text = type.name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        )
        // v0.21: user-added types are theme-agnostic, so they're
        // labelled as Custom here.
        if (!type.isDefault) {
            Text(
                text = "Custom",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 4.dp)
            )
        }
        TextButton(onClick = onEdit) { Text("Edit") }
        TextButton(onClick = onDelete) {
            Text("Delete", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
fun RelationshipTagRow(
    tag: RelationshipTag,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = tag.name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp)
        )
        // v0.19.2: compact arrow buttons. The v0.19.0 padding trim was a
        // visual no-op — TextButton enforces a 48dp minimum touch target,
        // so the glyph-to-glyph gap never moved. These size to content +
        // padding (~34dp wide), putting the ↑/↓ glyphs ~24dp apart instead
        // of ~38dp (~35% tighter), while staying comfortably tappable.
        // Rename/Delete stay as TextButtons.
        CompactArrowButton(label = "↑", enabled = onMoveUp != null, onClick = { onMoveUp?.invoke() })
        CompactArrowButton(label = "↓", enabled = onMoveDown != null, onClick = { onMoveDown?.invoke() })
        TextButton(onClick = onEdit) { Text("Rename") }
        TextButton(onClick = onDelete) {
            Text("Delete", color = MaterialTheme.colorScheme.error)
        }
    }
}

/**
 * Compact reorder arrow (v0.19.2). A plain clickable Box instead of a
 * TextButton: TextButton's 48dp minimum touch target made the arrow pairs
 * look cavernous, and no padding tweak could shrink them. This sizes to
 * the glyph plus padding, with a ripple and a disabled tint matching
 * TextButton's conventions.
 */
@Composable
private fun CompactArrowButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        )
    }
}

/** Add / edit dialog for an event type: name, color (swatches + hex), default duration. */
@Composable
fun EventTypeDialog(
    existing: EventType?,
    swatches: List<Int>,
    onDismiss: () -> Unit,
    onSave: (name: String, argb: Int, durationMinutes: Int) -> Unit,
    /** v0.21: a brand-new type inherits the current accent color. */
    initialColor: Int = 0xFFBB86FC.toInt()
) {
    // v0.20.7: TextFieldValue state so the keyboard's composing
    // region survives recompositions (backspace space-eating fix).
    var name by remember(existing) { mutableStateOf(TextFieldValue(existing?.name ?: "")) }
    var color by remember(existing) { mutableStateOf(existing?.colorArgb ?: initialColor) }
    var showWheel by remember { mutableStateOf(false) }
    var durationText by remember(existing) {
        mutableStateOf(existing?.defaultDurationMinutes?.toString() ?: "60")
    }
    var nameError by remember { mutableStateOf(false) }
    var durationError by remember { mutableStateOf(false) }

    fun trySave() {
        val duration = durationText.toIntOrNull()
        var ok = true
        if (name.text.isBlank()) {
            nameError = true
            ok = false
        }
        if (duration == null || duration !in 1..1440) {
            durationError = true
            ok = false
        }
        if (ok) onSave(name.text.trim(), color, duration!!)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add event type" else "Edit event type") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; nameError = false },
                    keyboardOptions = KeyboardOptions(autoCorrect = false),
                    label = { Text("Name") },
                    singleLine = true,
                    isError = nameError,
                    modifier = Modifier.fillMaxWidth()
                )
                if (nameError) {
                    Text(
                        "Name can't be empty",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text("Color", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                swatches.chunked(7).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { c ->
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(Color(c))
                                    .clickable { color = c }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedButton(onClick = { showWheel = true }) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(Color(color))
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Choose color")
                }
                if (showWheel) {
                    ColorWheelDialog(
                        initialArgb = color,
                        onDismiss = { showWheel = false },
                        onConfirm = { argb ->
                            showWheel = false
                            color = argb
                        }
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color(color))
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(name.text.ifBlank { "Preview" }, style = MaterialTheme.typography.bodyLarge)
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = durationText,
                    onValueChange = {
                        durationText = it.filter { c -> c.isDigit() }.take(4)
                        durationError = false
                    },
                    label = { Text("Default duration (minutes)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = durationError,
                    modifier = Modifier.fillMaxWidth()
                )
                if (durationError) {
                    Text(
                        "Enter 1–1440 minutes",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = ::trySave) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** Add / rename dialog for a relationship tag (name only). */
@Composable
fun TagDialog(
    existing: RelationshipTag?,
    onDismiss: () -> Unit,
    onSave: (name: String) -> Unit
) {
    // v0.20.7: TextFieldValue state so the keyboard's composing
    // region survives recompositions (backspace space-eating fix).
    var name by remember(existing) { mutableStateOf(TextFieldValue(existing?.name ?: "")) }
    var nameError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add tag" else "Rename tag") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; nameError = false },
                    keyboardOptions = KeyboardOptions(autoCorrect = false),
                    label = { Text("Name") },
                    singleLine = true,
                    isError = nameError,
                    modifier = Modifier.fillMaxWidth()
                )
                if (nameError) {
                    Text(
                        "Name can't be empty",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.text.isBlank()) {
                    nameError = true
                } else {
                    onSave(name.text.trim())
                }
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
