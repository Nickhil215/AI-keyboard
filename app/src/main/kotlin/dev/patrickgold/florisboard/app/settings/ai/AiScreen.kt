/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.app.settings.ai

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.ai.KeystoreAiCredentialStore
import dev.patrickgold.florisboard.ime.keyboard3.ImeActions
import dev.patrickgold.florisboard.ime.smartbar.quickaction.QuickAction
import dev.patrickgold.florisboard.ime.smartbar.quickaction.QuickActionArrangement
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.jetpref.datastore.model.PreferenceData
import dev.patrickgold.jetpref.datastore.model.collectAsState
import dev.patrickgold.jetpref.datastore.ui.ExperimentalJetPrefDatastoreUi
import dev.patrickgold.jetpref.datastore.ui.Preference
import dev.patrickgold.jetpref.datastore.ui.PreferenceGroup
import dev.patrickgold.jetpref.datastore.ui.SwitchPreference
import kotlinx.coroutines.launch
import org.florisboard.lib.compose.stringRes

@OptIn(ExperimentalJetPrefDatastoreUi::class)
@Composable
fun AiScreen() = FlorisScreen {
    title = stringRes(R.string.settings__ai__title)
    previewFieldVisible = true

    val prefs by FlorisPreferenceStore
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialStore = remember { KeystoreAiCredentialStore(context) }

    var hasApiKey by remember { mutableStateOf(credentialStore.getApiKey() != null) }
    var showEndpointDialog by remember { mutableStateOf(false) }
    var showModelDialog by remember { mutableStateOf(false) }
    var showApiKeyDialog by remember { mutableStateOf(false) }

    val isEnabled by prefs.ai.enabled.collectAsState()
    val endpointUrl by prefs.ai.endpointUrl.collectAsState()
    val model by prefs.ai.model.collectAsState()

    // When AI is switched on, bring the two actions to the front of the smartbar so they can be found.
    // Opening this screen with AI already on must not rearrange the user's own layout.
    var wasEnabled by remember { mutableStateOf(isEnabled) }
    LaunchedEffect(isEnabled) {
        if (isEnabled && !wasEnabled) {
            promoteAiActions(prefs.smartbar.actionArrangement)
        }
        wasEnabled = isEnabled
    }

    content {
        SwitchPreference(
            prefs.ai.enabled,
            icon = Icons.Default.AutoFixHigh,
            title = stringRes(R.string.pref__ai__enabled__label),
            summary = stringRes(R.string.pref__ai__enabled__summary),
        )
        Text(
            text = stringRes(R.string.pref__ai__privacy_notice),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        PreferenceGroup(title = stringRes(R.string.settings__ai__title)) {
            Preference(
                icon = Icons.Default.Language,
                title = stringRes(R.string.pref__ai__endpoint__label),
                summary = endpointUrl,
                onClick = { showEndpointDialog = true },
            )
            Preference(
                icon = Icons.Default.AutoFixHigh,
                title = stringRes(R.string.pref__ai__model__label),
                summary = model,
                onClick = { showModelDialog = true },
            )
            Preference(
                icon = Icons.Default.Lock,
                title = stringRes(R.string.pref__ai__api_key__label),
                summary = stringRes(
                    if (hasApiKey) R.string.pref__ai__api_key__set else R.string.pref__ai__api_key__not_set
                ),
                onClick = { showApiKeyDialog = true },
            )
        }
    }

    if (showEndpointDialog) {
        AiTextDialog(
            title = stringRes(R.string.pref__ai__endpoint__label),
            supportingText = stringRes(R.string.pref__ai__endpoint__hint),
            initialValue = endpointUrl,
            keyboardType = KeyboardType.Uri,
            onSave = { value -> scope.launch { prefs.ai.endpointUrl.set(value.trim()) } },
            onDismiss = { showEndpointDialog = false },
        )
    }
    if (showModelDialog) {
        AiTextDialog(
            title = stringRes(R.string.pref__ai__model__label),
            initialValue = model,
            onSave = { value -> scope.launch { prefs.ai.model.set(value.trim()) } },
            onDismiss = { showModelDialog = false },
        )
    }
    if (showApiKeyDialog) {
        AiTextDialog(
            title = stringRes(R.string.pref__ai__api_key__label),
            supportingText = stringRes(R.string.pref__ai__api_key__hint),
            // Never pre-filled: the stored key is not shown again once saved.
            initialValue = "",
            isSecret = true,
            keyboardType = KeyboardType.Password,
            clearLabel = if (hasApiKey) stringRes(R.string.pref__ai__dialog_clear) else null,
            onClear = {
                credentialStore.setApiKey(null)
                hasApiKey = false
            },
            onSave = { value ->
                credentialStore.setApiKey(value)
                hasApiKey = credentialStore.getApiKey() != null
            },
            onDismiss = { showApiKeyDialog = false },
        )
    }
}

private suspend fun promoteAiActions(arrangementPref: PreferenceData<QuickActionArrangement>) {
    val aiActions = listOf(
        QuickAction.InsertK3Descriptor(ImeActions.AiFixGrammar),
        QuickAction.InsertK3Descriptor(ImeActions.AiRewrite),
    )
    val current = arrangementPref.get()
    // An action the user pinned as the sticky action stays where it is.
    val toInsert = aiActions.filter { it != current.stickyAction }
    arrangementPref.set(
        QuickActionArrangement(
            stickyAction = current.stickyAction,
            dynamicActions = toInsert + current.dynamicActions.filter { it !in aiActions },
            hiddenActions = current.hiddenActions.filter { it !in aiActions },
        )
    )
}

@Composable
private fun AiTextDialog(
    title: String,
    initialValue: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    supportingText: String? = null,
    isSecret: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    clearLabel: String? = null,
    onClear: () -> Unit = {},
) {
    var value by remember { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                visualTransformation = if (isSecret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                supportingText = supportingText?.let { hint -> { Text(hint) } },
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(value)
                    onDismiss()
                },
                enabled = value.isNotBlank(),
            ) { Text(stringRes(R.string.pref__ai__dialog_save)) }
        },
        dismissButton = {
            if (clearLabel != null) {
                TextButton(onClick = { onClear(); onDismiss() }) { Text(clearLabel) }
            }
            TextButton(onClick = onDismiss) { Text(stringRes(R.string.pref__ai__dialog_cancel)) }
        },
    )
}
