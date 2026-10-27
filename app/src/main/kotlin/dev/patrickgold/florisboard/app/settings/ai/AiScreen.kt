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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import dev.patrickgold.florisboard.app.FlorisPreferenceModel
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.enumDisplayEntriesOf
import dev.patrickgold.florisboard.ime.ai.AiCredentialStore
import dev.patrickgold.florisboard.ime.ai.AiError
import dev.patrickgold.florisboard.ime.ai.AiProcessorFactory
import dev.patrickgold.florisboard.ime.ai.AiProviderConfig
import dev.patrickgold.florisboard.ime.ai.AiProviderType
import dev.patrickgold.florisboard.ime.ai.ConnectionTestResult
import dev.patrickgold.florisboard.ime.ai.KeystoreAiCredentialStore
import dev.patrickgold.florisboard.ime.ai.aiErrorMessage
import dev.patrickgold.florisboard.ime.ai.local.LocalModelCatalog
import dev.patrickgold.florisboard.ime.ai.local.LocalModelSpec
import dev.patrickgold.florisboard.ime.ai.local.LocalModels
import dev.patrickgold.florisboard.ime.ai.local.ModelDownloadError
import dev.patrickgold.florisboard.ime.ai.local.ModelInstallState
import dev.patrickgold.florisboard.ime.keyboard3.ImeActions
import dev.patrickgold.florisboard.ime.smartbar.quickaction.QuickAction
import dev.patrickgold.florisboard.ime.smartbar.quickaction.QuickActionArrangement
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.jetpref.datastore.model.PreferenceData
import dev.patrickgold.jetpref.datastore.model.collectAsState
import dev.patrickgold.jetpref.datastore.ui.DialogSliderPreference
import dev.patrickgold.jetpref.datastore.ui.ExperimentalJetPrefDatastoreUi
import dev.patrickgold.jetpref.datastore.ui.ListPreference
import dev.patrickgold.jetpref.datastore.ui.Preference
import dev.patrickgold.jetpref.datastore.ui.PreferenceGroup
import dev.patrickgold.jetpref.datastore.ui.PreferenceUiScope
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

    val isEnabled by prefs.ai.enabled.collectAsState()
    val provider by prefs.ai.provider.collectAsState()

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
        ListPreference(
            prefs.ai.provider,
            icon = Icons.Default.Language,
            title = stringRes(R.string.pref__ai__provider__label),
            entries = enumDisplayEntriesOf(AiProviderType::class),
        )

        if (provider == AiProviderType.LOCAL) {
            Text(
                text = stringRes(R.string.pref__ai__privacy_notice_local),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LocalModelSection(credentialStore)
        } else {
            RemoteProviderSection(provider, credentialStore)
        }
    }
}

@OptIn(ExperimentalJetPrefDatastoreUi::class)
@Composable
private fun PreferenceUiScope<FlorisPreferenceModel>.LocalModelSection(credentialStore: AiCredentialStore) {
    val prefs by FlorisPreferenceStore
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember { LocalModels.manager(context) }
    val selectedId by prefs.ai.localModelId.collectAsState()

    var confirmDownload by remember { mutableStateOf<LocalModelSpec?>(null) }
    var showTokenDialog by remember { mutableStateOf(false) }
    var hasToken by remember { mutableStateOf(credentialStore.getSecret(AiCredentialStore.SLOT_HUGGING_FACE) != null) }

    for (spec in LocalModelCatalog.all) {
        val state by manager.stateOf(spec).collectAsState()
        val isSelected = spec.id == LocalModelCatalog.byId(selectedId).id
        PreferenceGroup(title = spec.displayName) {
            Preference(
                icon = Icons.Default.CloudDownload,
                title = installStatusText(state, spec),
                summary = stringRes(
                    R.string.pref__ai__local_model_details,
                    "size" to megabytes(spec.sizeBytes),
                    "license" to spec.licenseName,
                    "ram" to gigabytes(spec.minTotalRamBytes),
                ),
                onClick = {},
            )
            when (val s = state) {
                is ModelInstallState.Downloading, ModelInstallState.Verifying -> {
                    if (s is ModelInstallState.Downloading) {
                        Preference(
                            icon = Icons.Default.Close,
                            title = stringRes(R.string.pref__ai__local_cancel),
                            onClick = { manager.cancel(spec) },
                        )
                    }
                }
                ModelInstallState.Installed -> {
                    if (!isSelected) {
                        Preference(
                            icon = Icons.Default.Check,
                            title = stringRes(R.string.pref__ai__local_use_model),
                            onClick = { scope.launch { prefs.ai.localModelId.set(spec.id) } },
                        )
                    }
                    Preference(
                        icon = Icons.Default.Delete,
                        title = stringRes(R.string.pref__ai__local_delete),
                        onClick = { LocalModels.delete(context, spec) },
                    )
                }
                else -> {
                    val hasPartial = (s is ModelInstallState.NotInstalled && s.partialBytes > 0) ||
                        (s is ModelInstallState.Failed && s.partialBytes > 0)
                    Preference(
                        icon = Icons.Default.CloudDownload,
                        title = stringRes(if (hasPartial) R.string.pref__ai__local_resume else R.string.pref__ai__local_download),
                        onClick = { confirmDownload = spec },
                    )
                }
            }
        }
    }

    PreferenceGroup(title = stringRes(R.string.pref__ai__hf_token__label)) {
        Preference(
            icon = Icons.Default.Lock,
            title = stringRes(R.string.pref__ai__hf_token__label),
            summary = stringRes(if (hasToken) R.string.pref__ai__api_key__set else R.string.pref__ai__api_key__not_set),
            onClick = { showTokenDialog = true },
        )
    }

    confirmDownload?.let { spec ->
        val token = credentialStore.getSecret(AiCredentialStore.SLOT_HUGGING_FACE)
        val missingToken = spec.requiresAuthToken && token == null
        AlertDialog(
            onDismissRequest = { confirmDownload = null },
            title = { Text(stringRes(R.string.pref__ai__local_download_dialog_title)) },
            text = {
                Text(
                    stringRes(
                        R.string.pref__ai__local_download_dialog_message,
                        "size" to megabytes(spec.sizeBytes),
                        "model" to spec.displayName,
                        "license" to "${spec.licenseName} (${spec.licenseUrl})",
                    ) + if (missingToken) {
                        "\n\n" + stringRes(R.string.pref__ai__local_download_dialog_gated, "url" to spec.modelPageUrl)
                    } else {
                        ""
                    }
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !missingToken,
                    onClick = {
                        // Only an explicit tap here ever starts a download.
                        manager.start(spec, if (spec.requiresAuthToken) token else null)
                        confirmDownload = null
                    },
                ) { Text(stringRes(R.string.pref__ai__local_download_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDownload = null }) { Text(stringRes(R.string.pref__ai__dialog_cancel)) }
            },
        )
    }

    if (showTokenDialog) {
        AiTextDialog(
            title = stringRes(R.string.pref__ai__hf_token__label),
            supportingText = stringRes(R.string.pref__ai__hf_token__hint),
            initialValue = "",
            isSecret = true,
            keyboardType = KeyboardType.Password,
            clearLabel = if (hasToken) stringRes(R.string.pref__ai__dialog_clear) else null,
            onClear = {
                credentialStore.setSecret(AiCredentialStore.SLOT_HUGGING_FACE, null)
                hasToken = false
            },
            onSave = { value ->
                credentialStore.setSecret(AiCredentialStore.SLOT_HUGGING_FACE, value)
                hasToken = credentialStore.getSecret(AiCredentialStore.SLOT_HUGGING_FACE) != null
            },
            onDismiss = { showTokenDialog = false },
        )
    }
}

@OptIn(ExperimentalJetPrefDatastoreUi::class)
@Composable
private fun PreferenceUiScope<FlorisPreferenceModel>.RemoteProviderSection(
    provider: AiProviderType,
    credentialStore: AiCredentialStore,
) {
    val prefs by FlorisPreferenceStore
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val remote = prefs.ai.remote(provider)

    val endpointUrl by remote.endpointUrl.collectAsState()
    val model by remote.model.collectAsState()

    var hasApiKey by remember(provider) { mutableStateOf(credentialStore.getSecret(provider.credentialSlot) != null) }
    var showEndpointDialog by remember { mutableStateOf(false) }
    var showModelDialog by remember { mutableStateOf(false) }
    var showApiKeyDialog by remember { mutableStateOf(false) }
    var testing by remember(provider) { mutableStateOf(false) }
    var testResult by remember(provider) { mutableStateOf<ConnectionTestResult?>(null) }

    val host = AiProviderConfig.hostOrNull(endpointUrl)
    Text(
        text = if (host != null) {
            stringRes(R.string.pref__ai__privacy_notice_remote, "host" to host)
        } else {
            stringRes(R.string.pref__ai__privacy_notice_remote_unset)
        },
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )

    PreferenceGroup(title = stringRes(R.string.settings__ai__title)) {
        Preference(
            icon = Icons.Default.Language,
            title = stringRes(R.string.pref__ai__endpoint__label),
            summary = endpointUrl.ifBlank { stringRes(R.string.pref__ai__api_key__not_set) },
            onClick = { showEndpointDialog = true },
        )
        Preference(
            icon = Icons.Default.AutoFixHigh,
            title = stringRes(R.string.pref__ai__model__label),
            summary = model.ifBlank { stringRes(R.string.pref__ai__api_key__not_set) },
            onClick = { showModelDialog = true },
        )
        Preference(
            icon = Icons.Default.Lock,
            title = stringRes(R.string.pref__ai__api_key__label),
            summary = stringRes(if (hasApiKey) R.string.pref__ai__api_key__set else R.string.pref__ai__api_key__not_set),
            onClick = { showApiKeyDialog = true },
        )
        DialogSliderPreference(
            prefs.ai.connectTimeoutSeconds,
            title = stringRes(R.string.pref__ai__timeout_connect__label),
            valueLabel = { stringRes(R.string.pref__ai__timeout_value, "v" to it) },
            min = 3,
            max = 60,
            stepIncrement = 1,
        )
        DialogSliderPreference(
            prefs.ai.requestTimeoutSeconds,
            title = stringRes(R.string.pref__ai__timeout_request__label),
            valueLabel = { stringRes(R.string.pref__ai__timeout_value, "v" to it) },
            min = 5,
            max = 120,
            stepIncrement = 5,
        )
        Preference(
            icon = Icons.Default.NetworkCheck,
            title = stringRes(R.string.pref__ai__test_connection__label),
            summary = when {
                testing -> stringRes(R.string.pref__ai__test_connection__running)
                testResult != null -> testResultText(testResult!!)
                else -> stringRes(R.string.pref__ai__test_connection__summary)
            },
            onClick = {
                if (!testing) {
                    testing = true
                    testResult = null
                    scope.launch {
                        // A test is an explicit user action, so it works even while the main switch is off.
                        // It sends no user text: only a request for the model list.
                        testResult = AiProcessorFactory.remote(context, provider, requireEnabledSwitch = false)
                            .testConnection()
                        testing = false
                    }
                }
            },
        )
    }
    Text(
        text = stringRes(R.string.pref__ai__key_security_notice),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )

    if (showEndpointDialog) {
        AiTextDialog(
            title = stringRes(R.string.pref__ai__endpoint__label),
            supportingText = stringRes(R.string.pref__ai__endpoint__hint),
            initialValue = endpointUrl,
            keyboardType = KeyboardType.Uri,
            onSave = { value -> scope.launch { remote.endpointUrl.set(value.trim()) } },
            onDismiss = { showEndpointDialog = false },
        )
    }
    if (showModelDialog) {
        AiTextDialog(
            title = stringRes(R.string.pref__ai__model__label),
            initialValue = model,
            onSave = { value -> scope.launch { remote.model.set(value.trim()) } },
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
                credentialStore.setSecret(provider.credentialSlot, null)
                hasApiKey = false
            },
            onSave = { value ->
                credentialStore.setSecret(provider.credentialSlot, value)
                hasApiKey = credentialStore.getSecret(provider.credentialSlot) != null
            },
            onDismiss = { showApiKeyDialog = false },
        )
    }
}

@Composable
private fun testResultText(result: ConnectionTestResult): String {
    return when (result) {
        ConnectionTestResult.Success -> stringRes(R.string.pref__ai__test_connection__success)
        is ConnectionTestResult.Inconclusive ->
            stringRes(R.string.pref__ai__test_connection__inconclusive, "status" to result.httpStatus)
        is ConnectionTestResult.Failed -> aiErrorMessage(result.error, result.httpStatus)
    }
}

@Composable
private fun installStatusText(state: ModelInstallState, spec: LocalModelSpec): String {
    return when (state) {
        is ModelInstallState.NotInstalled -> if (state.partialBytes > 0) {
            stringRes(R.string.pref__ai__local_status_partial, "percent" to (state.partialBytes * 100 / spec.sizeBytes))
        } else {
            stringRes(R.string.pref__ai__local_status_not_installed)
        }
        is ModelInstallState.Downloading -> stringRes(
            R.string.pref__ai__local_status_downloading,
            "percent" to (if (state.totalBytes > 0) state.downloadedBytes * 100 / state.totalBytes else 0),
        )
        ModelInstallState.Verifying -> stringRes(R.string.pref__ai__local_status_verifying)
        ModelInstallState.Installed -> stringRes(R.string.pref__ai__local_status_installed)
        is ModelInstallState.Failed -> stringRes(
            R.string.pref__ai__local_status_failed,
            "reason" to stringRes(
                when (state.reason) {
                    ModelDownloadError.INSUFFICIENT_STORAGE -> R.string.pref__ai__local_error_insufficient_storage
                    ModelDownloadError.NETWORK -> R.string.pref__ai__local_error_network
                    ModelDownloadError.AUTH_REQUIRED -> R.string.pref__ai__local_error_auth
                    ModelDownloadError.HTTP_ERROR -> R.string.pref__ai__local_error_http
                    ModelDownloadError.SIZE_MISMATCH -> R.string.pref__ai__local_error_size
                    ModelDownloadError.INTEGRITY -> R.string.pref__ai__local_error_integrity
                    ModelDownloadError.IO -> R.string.pref__ai__local_error_io
                }
            ),
        )
    }
}

private fun megabytes(bytes: Long) = "${bytes / 1_000_000} MB"

private fun gigabytes(bytes: Long) = "${bytes / (1024L * 1024L * 1024L)} GB"

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
