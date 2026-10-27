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

package dev.patrickgold.florisboard.ime.ai

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.keyboard3.LocalImeController
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.jetpref.datastore.model.collectAsState
import org.florisboard.lib.compose.stringRes
import org.florisboard.lib.snygg.ui.SnyggBox
import org.florisboard.lib.snygg.ui.SnyggButton
import org.florisboard.lib.snygg.ui.SnyggText

/**
 * Shown in place of the keyboard while an AI request is running or its result is being reviewed.
 *
 * It never changes the user's text by itself: only the Accept button does, and Accept is disabled as
 * soon as the selection no longer matches the one the request was made for.
 */
@Composable
fun AiPreviewPanel() {
    val prefs by FlorisPreferenceStore
    val context = LocalContext.current
    val imeController = LocalImeController.current
    val aiAssist = imeController.aiAssist

    val aiState by aiAssist.state.collectAsState()
    val imeState by imeController.activeState.collectAsState()
    val provider by prefs.ai.provider.collectAsState()
    // What the privacy line says depends on where the text goes: nowhere, or a named host.
    val destination = remember(provider) {
        if (provider.isRemote) {
            AiProviderConfig.hostOrNull(prefs.ai.remote(provider).endpointUrl.get()) ?: "?"
        } else {
            null
        }
    }

    // Same theme element the keyboard tiles use for their text, so the text matches the active theme.
    val textElement = "${FlorisImeUi.SmartbarActionTile.elementName}-text"

    val operation: AiOperation = when (val s = aiState) {
        is AiAssistState.Idle -> return
        is AiAssistState.Loading -> s.operation
        is AiAssistState.Ready -> s.operation
        is AiAssistState.Failed -> s.operation
    }
    val currentTone: RewriteTone? = when (val s = aiState) {
        is AiAssistState.Loading -> s.tone
        is AiAssistState.Ready -> s.tone
        is AiAssistState.Failed -> s.tone
        else -> null
    }

    // Retrying needs a finished attempt (so never while loading) and an original selection to retry on.
    val canRetry = aiState is AiAssistState.Ready || (aiState as? AiAssistState.Failed)?.original != null

    SnyggBox(
        elementName = FlorisImeUi.SmartbarActionsOverflow.elementName,
        modifier = Modifier
            .fillMaxWidth()
            .height(FlorisImeSizing.keyboardUiHeight()),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SnyggText(
                elementName = textElement,
                modifier = Modifier.semantics { heading() },
                text = stringRes(
                    when (operation) {
                        AiOperation.FIX_GRAMMAR -> R.string.ai__panel_title_fix_grammar
                        AiOperation.REWRITE -> R.string.ai__panel_title_rewrite
                    }
                ),
            )

            if (operation == AiOperation.REWRITE) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (tone in listOf<RewriteTone?>(null) + RewriteTone.entries) {
                        AiPanelButton(
                            text = toneLabel(tone),
                            // The active tone is shown as the disabled one; the others re-run the request.
                            enabled = canRetry && tone != currentTone,
                            onClick = { aiAssist.retry(tone, changeTone = true) },
                        )
                    }
                }
            }

            // Status area. A polite live region lets screen readers announce loading, results and errors.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                when (val s = aiState) {
                    is AiAssistState.Loading -> {
                        val loadingText = stringRes(R.string.ai__panel_loading)
                        Row(
                            modifier = Modifier.semantics { contentDescription = loadingText },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            SnyggText(elementName = textElement, text = loadingText)
                        }
                    }
                    is AiAssistState.Ready -> {
                        val resultDescription = stringRes(R.string.ai__panel_result_description)
                        SnyggText(
                            elementName = textElement,
                            modifier = Modifier.semantics { contentDescription = "$resultDescription: ${s.result}" },
                            text = s.result,
                        )
                    }
                    is AiAssistState.Failed -> {
                        SnyggText(elementName = textElement, text = aiErrorMessage(s.error, s.httpStatus))
                    }
                    is AiAssistState.Idle -> {}
                }
            }

            // Always visible while a request exists, so it is clear whether the text leaves the device.
            if (aiState is AiAssistState.Loading || aiState is AiAssistState.Ready) {
                SnyggText(
                    elementName = textElement,
                    text = if (destination == null) {
                        stringRes(R.string.ai__panel_on_device)
                    } else {
                        stringRes(R.string.ai__panel_sends_to, "host" to destination)
                    },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                AiPanelButton(
                    text = stringRes(R.string.ai__button_cancel),
                    onClick = { aiAssist.cancel() },
                )
                when (val s = aiState) {
                    is AiAssistState.Ready -> {
                        // Compare against the live selection so a stale result can never be applied.
                        val selection = imeState.content.selection
                        val isStale = minOf(selection.start, selection.end) != s.original.start ||
                            maxOf(selection.start, selection.end) != s.original.end
                        AiPanelButton(
                            text = stringRes(R.string.ai__button_retry),
                            onClick = { aiAssist.retry() },
                        )
                        AiPanelButton(
                            text = stringRes(R.string.ai__button_copy),
                            onClick = {
                                val clipboard = context.getSystemService(ClipboardManager::class.java)
                                clipboard?.setPrimaryClip(ClipData.newPlainText(null, s.result))
                            },
                        )
                        AiPanelButton(
                            text = stringRes(R.string.ai__button_accept),
                            enabled = !isStale,
                            onClick = { aiAssist.accept(imeController.aiEditorBridge) },
                        )
                    }
                    is AiAssistState.Failed -> {
                        // Errors that are fixed in settings (download the model, add a key, pick another
                        // provider) get a shortcut. Nothing is changed or sent automatically.
                        if (s.error in errorsFixedInSettings) {
                            AiPanelButton(
                                text = stringRes(R.string.ai__button_open_settings),
                                onClick = { FlorisImeService.launchSettings() },
                            )
                        }
                        if (s.original != null) {
                            AiPanelButton(
                                text = stringRes(R.string.ai__button_retry),
                                onClick = { aiAssist.retry() },
                            )
                        }
                    }
                    else -> {}
                }
            }
        }
    }
}

private val errorsFixedInSettings = setOf(
    AiError.MODEL_NOT_INSTALLED,
    AiError.MODEL_INIT_FAILED,
    AiError.UNSUPPORTED_DEVICE,
    AiError.NOT_CONFIGURED,
    AiError.INVALID_ENDPOINT,
    AiError.AUTH,
    AiError.PROVIDER_REJECTED,
    AiError.FEATURE_DISABLED,
)

@Composable
private fun AiPanelButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    SnyggButton(
        elementName = FlorisImeUi.SmartbarActionsOverflowCustomizeButton.elementName,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
    ) {
        SnyggText(text = text)
    }
}

@Composable
private fun toneLabel(tone: RewriteTone?): String {
    return stringRes(
        when (tone) {
            null -> R.string.ai__tone_auto
            RewriteTone.PROFESSIONAL -> R.string.ai__tone_professional
            RewriteTone.CASUAL -> R.string.ai__tone_casual
            RewriteTone.CONCISE -> R.string.ai__tone_concise
            RewriteTone.SIMPLE -> R.string.ai__tone_simple
        }
    )
}

@Composable
internal fun aiErrorMessage(error: AiError, httpStatus: Int?): String {
    return when (error) {
        AiError.PROVIDER_REJECTED -> stringRes(R.string.ai__error_provider_rejected, "status" to (httpStatus ?: 0))
        else -> stringRes(
            when (error) {
                AiError.FEATURE_DISABLED -> R.string.ai__error_feature_disabled
                AiError.SENSITIVE_FIELD -> R.string.ai__error_sensitive_field
                AiError.UNSUPPORTED_EDITOR -> R.string.ai__error_unsupported_editor
                AiError.NO_SELECTION -> R.string.ai__error_no_selection
                AiError.SELECTION_CHANGED -> R.string.ai__error_selection_changed
                AiError.APPLY_FAILED -> R.string.ai__error_apply_failed
                AiError.NOT_CONFIGURED -> R.string.ai__error_not_configured
                AiError.INVALID_ENDPOINT -> R.string.ai__error_invalid_endpoint
                AiError.EMPTY_INPUT -> R.string.ai__error_empty_input
                AiError.INPUT_TOO_LONG -> R.string.ai__error_input_too_long
                AiError.NETWORK -> R.string.ai__error_network
                AiError.TIMEOUT -> R.string.ai__error_timeout
                AiError.AUTH -> R.string.ai__error_auth
                AiError.RATE_LIMITED -> R.string.ai__error_rate_limited
                AiError.SERVER -> R.string.ai__error_server
                AiError.INVALID_RESPONSE -> R.string.ai__error_invalid_response
                AiError.EMPTY_RESPONSE -> R.string.ai__error_empty_response
                AiError.RESPONSE_TOO_LARGE -> R.string.ai__error_response_too_large
                AiError.MODEL_NOT_INSTALLED -> R.string.ai__error_model_not_installed
                AiError.MODEL_INIT_FAILED -> R.string.ai__error_model_init_failed
                AiError.INSUFFICIENT_MEMORY -> R.string.ai__error_insufficient_memory
                AiError.UNSUPPORTED_DEVICE -> R.string.ai__error_unsupported_device
                AiError.INFERENCE_FAILED -> R.string.ai__error_inference_failed
                AiError.PROVIDER_REJECTED, AiError.UNKNOWN -> R.string.ai__error_unknown
            }
        )
    }
}
