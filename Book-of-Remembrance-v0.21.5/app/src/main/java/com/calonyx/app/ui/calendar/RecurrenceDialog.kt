package com.calonyx.app.ui.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.calonyx.app.data.importexport.RecurEndChoice
import com.calonyx.app.data.importexport.RecurFreq
import com.calonyx.app.data.importexport.RecurrenceChoice
import com.calonyx.app.ui.formatDate
import com.calonyx.app.ui.toLocalDate
import com.calonyx.app.ui.toUtcPickerMillis
import com.calonyx.app.ui.utcPickerMillisToLocalDate
import java.time.LocalDate

/**
 * The Repeat dialog for the event editor: frequency (none/daily/weekly/
 * monthly/yearly), interval, and end condition (never / after N
 * occurrences / until a date). Edits a [RecurrenceChoice] in place and
 * reports it on OK.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecurrenceDialog(
    initial: RecurrenceChoice,
    onConfirm: (RecurrenceChoice) -> Unit,
    onDismiss: () -> Unit
) {
    var freq by remember { mutableStateOf(initial.freq) }
    var intervalText by remember { mutableStateOf(initial.interval.toString()) }
    var endKind by remember {
        mutableStateOf(
            when (initial.end) {
                is RecurEndChoice.Never -> EndKind.NEVER
                is RecurEndChoice.After -> EndKind.AFTER
                is RecurEndChoice.Until -> EndKind.UNTIL
            }
        )
    }
    var countText by remember {
        mutableStateOf((initial.end as? RecurEndChoice.After)?.count?.toString() ?: "10")
    }
    var untilDate by remember {
        mutableStateOf((initial.end as? RecurEndChoice.Until)?.date ?: LocalDate.now().plusMonths(3))
    }
    var pickUntil by remember { mutableStateOf(false) }

    fun build(): RecurrenceChoice {
        val f = freq ?: return RecurrenceChoice()
        val interval = intervalText.toIntOrNull()?.coerceIn(1, 999) ?: 1
        val end = when (endKind) {
            EndKind.NEVER -> RecurEndChoice.Never
            EndKind.AFTER ->
                RecurEndChoice.After(countText.toIntOrNull()?.coerceIn(1, 500) ?: 10)
            EndKind.UNTIL -> RecurEndChoice.Until(untilDate)
        }
        return RecurrenceChoice(freq = f, interval = interval, end = end)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Repeat") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Frequency", style = MaterialTheme.typography.titleSmall)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    RepeatFreqChip("None", freq == null) { freq = null }
                    RepeatFreqChip("Daily", freq == RecurFreq.DAILY) { freq = RecurFreq.DAILY }
                    RepeatFreqChip("Weekly", freq == RecurFreq.WEEKLY) { freq = RecurFreq.WEEKLY }
                    RepeatFreqChip("Monthly", freq == RecurFreq.MONTHLY) { freq = RecurFreq.MONTHLY }
                    RepeatFreqChip("Yearly", freq == RecurFreq.YEARLY) { freq = RecurFreq.YEARLY }
                }

                if (freq != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Every", style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(
                            value = intervalText,
                            onValueChange = { v ->
                                if (v.all { it.isDigit() } && v.length <= 3) intervalText = v
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.width(72.dp)
                        )
                        Text(
                            when (freq) {
                                RecurFreq.DAILY -> "day(s)"
                                RecurFreq.WEEKLY -> "week(s)"
                                RecurFreq.MONTHLY -> "month(s)"
                                else -> "year(s)"
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    Text("Ends", style = MaterialTheme.typography.titleSmall)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        RepeatFreqChip("Never", endKind == EndKind.NEVER) { endKind = EndKind.NEVER }
                        RepeatFreqChip("After", endKind == EndKind.AFTER) { endKind = EndKind.AFTER }
                        RepeatFreqChip("On date", endKind == EndKind.UNTIL) { endKind = EndKind.UNTIL }
                    }
                    when (endKind) {
                        EndKind.AFTER -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = countText,
                                    onValueChange = { v ->
                                        if (v.all { it.isDigit() } && v.length <= 3) countText = v
                                    },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    modifier = Modifier.width(72.dp)
                                )
                                Text("occurrences", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        EndKind.UNTIL -> {
                            OutlinedButton(onClick = { pickUntil = true }) {
                                Text(formatDate(untilDate.toUtcPickerMillis()))
                            }
                        }
                        EndKind.NEVER -> {}
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(build()) }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )

    if (pickUntil) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = untilDate.toUtcPickerMillis()
        )
        DatePickerDialog(
            onDismissRequest = { pickUntil = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { picked ->
                        untilDate = picked.utcPickerMillisToLocalDate()
                    }
                    pickUntil = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickUntil = false }) { Text("Cancel") } }
        ) { DatePicker(state = state) }
    }
}

private enum class EndKind { NEVER, AFTER, UNTIL }

@Composable
private fun RepeatFreqChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}
