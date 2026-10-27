# AI writing tools (Fix grammar and Rewrite)

This fork adds two optional smartbar actions:

- **Fix grammar**: corrects spelling, grammar, punctuation and capitalization of the selected text.
- **Rewrite**: makes the selected text clearer and more natural (optional tone: Professional, Casual, Concise, Simple).

Both are **off by default** and never send anything until you turn them on, add an API key, and tap an action.

## How to use

1. Open FlorisBoard settings, then **AI writing tools**.
2. Turn on **Enable AI writing tools**. This also moves the two actions to the front of the smartbar.
3. Set the **API endpoint** (default: `https://api.openai.com/v1/chat/completions`), the **Model**, and the **API key**.
4. In any app, select some text, then tap **Fix grammar** (spell-check icon) or **Rewrite** (magic-wand icon) in the smartbar.
   If the icons are not visible, open the smartbar's overflow panel.
5. A preview replaces the keyboard. Choose **Accept**, **Cancel**, **Retry** or **Copy**. For Rewrite you can also pick a tone, which re-runs the request.

Your text is only changed when you tap **Accept**.

## Provider requirements

The endpoint must be an **https** URL that speaks the OpenAI chat completions format
(`POST`, `Authorization: Bearer <key>`, answer in `choices[0].message.content`). That covers OpenAI, OpenRouter,
Groq, Gemini's OpenAI-compatible endpoint, and most self-hosted gateways (LiteLLM, vLLM or Ollama behind HTTPS).
Cleartext `http://` is refused on purpose, because the request carries both your text and your key.

## Data flow

```
tap action ──▶ read selected text (InputConnection) ──▶ HTTPS POST to your endpoint
                                                             │
preview panel ◀── validate / clean answer ◀──────────────────┘
      │
      └─ Accept ─▶ re-check selection is unchanged ─▶ replace selection
```

What is sent: the system prompt, and **only the currently selected text** (max 4000 characters), inside a delimiter block.
Surrounding text, other fields, and other apps are never read or sent.

What is **not** done: no text, prompt or answer is logged, cached or stored by the keyboard; nothing is sent in the
background; there are no analytics. The provider you configure receives the text and handles it under its own policy.

## Safety behaviour

- **Sensitive fields**: the actions are disabled in password, visible-password, web-password and numeric-PIN fields,
  and are not offered in non-text fields (number, phone, date).
- **Stale selection**: the request remembers the exact selection (text, position, and field). **Accept** is disabled as soon
  as the selection no longer matches, and is re-verified atomically right before writing. If it does not match you are
  asked to select the text again; nothing is overwritten.
- **Prompt injection**: the selected text is wrapped in delimiters and the model is told to treat it as content only.
  This reduces but cannot eliminate the risk, which is why nothing is applied without your review.
- **Output validation**: empty, truncated, malformed, or implausibly long answers are rejected. Quotes, code fences and
  stray tags added by a model are stripped. Surrounding whitespace of your selection is preserved.
- **Failures never crash the keyboard**: timeouts, network errors, 401/403, 429, 5xx and invalid answers show a message.
  Transient failures are retried once automatically; auth and request errors are not retried.

## API key handling and its limits

- The key is stored **encrypted** (AES-GCM, key held in the Android Keystore) in app-private storage, and is kept **out of**
  the preference datastore. The backup rules only include listed paths, so the key is **not** in Android backups or
  FlorisBoard's settings export. It is never shown again after saving and is never logged.
- No key is in the source code or build files. Nothing needs to be set at build time.
- **Important:** encryption at rest protects the key on the device, but a key that lives on a user's phone is not secret
  from that user, and a rooted device can use it. Do not ship an APK containing a shared key. For a distributed app, point the
  endpoint at **your own authenticated backend/proxy** that holds the real provider key and enforces per-user limits.
  This repo deliberately does not include a backend.

## Code map

| Area | Files |
| --- | --- |
| Provider abstraction | `ime/ai/AiTextProcessor.kt` (interface, errors), `LlmAiTextProcessor.kt` (OpenAI-compatible), `AiHttpTransport.kt`, `AiPrompts.kt`, `AiOutputSanitizer.kt`, `AiProviderConfig.kt`, `AiProcessorFactory.kt` |
| Key storage | `ime/ai/AiCredentialStore.kt` |
| Session logic | `ime/ai/AiAssistController.kt`, `AiEligibility.kt` |
| UI | `ime/ai/AiPreviewPanel.kt`, `ime/text/TextInputLayout.kt`, `app/settings/ai/AiScreen.kt` |
| Wiring | `ImeActions`, `ImeController`, `ImeEditor`, `QuickAction*`, `DerivedImeStates`, `Icon3`, `displays-implied.xml`, `AppPrefs`, `Routes`, `HomeScreen`, `AndroidManifest.xml` (`INTERNET`) |

To add a local-inference backend, implement `AiTextProcessor` and return it from `AiProcessorFactory.create`.

## Build and test

Requires the toolchain of upstream FlorisBoard (JDK 21+, Android SDK, and the Rust toolchain for `libnative`; see `README.md`/`CONTRIBUTING.md`).

```bash
./gradlew :app:testDebugUnitTest --tests 'dev.patrickgold.florisboard.ime.ai.*'   # AI unit tests (no network, no key)
./gradlew :app:testDebugUnitTest                                                   # all unit tests
./gradlew :app:lintDebug                                                           # Android lint
./gradlew :app:assembleDebug                                                       # build the APK
adb install -r app/build/outputs/apk/debug/*.apk
```

Automated tests use fake transports and fake editors; they need no API key or network.

## Manual test checklist (needs a device or emulator)

- [ ] Settings: AI screen opens from Home; enabling moves the two actions to the front of the smartbar.
- [ ] With AI off, both actions are greyed out and typing is unchanged.
- [ ] Select text in a normal app, tap **Fix grammar**: loading state, then preview; **Accept** replaces only the selection.
- [ ] Same for **Rewrite**, including each tone chip.
- [ ] **Cancel** (during loading and in preview) leaves the text untouched; **Retry** sends a new request; **Copy** fills the clipboard.
- [ ] Tap an action with nothing selected: helpful message, no request.
- [ ] Move the cursor/selection while the preview is open: **Accept** becomes disabled.
- [ ] Password, visible-password and PIN fields: actions are disabled.
- [ ] Wrong key, no network, airplane mode, bad endpoint: readable error, keyboard keeps working.
- [ ] Try apps with unusual editors (browser text areas, chat apps, terminal emulators); note any that refuse the edit.
- [ ] Dark/light themes, landscape, floating/compact window, large font size, TalkBack announcements.
