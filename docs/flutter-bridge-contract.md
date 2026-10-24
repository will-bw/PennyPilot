# Shared ledger contract and Android bridge

Production uses `CrossPlatformLedgerRepository`: Android Room remains the storage
adapter; iOS uses `PortableLedgerStore` through sqflite. Controllers and UI use the
same commands. DeepSeek/Qwen parsing, constrained ledger queries and reports run
in shared Dart on both platforms. Android ChatGPT retains its existing native
account storage and client; iOS uses Dart OAuth with Keychain credentials.

MethodChannel: `pennypilot/ledger`. EventChannel: `pennypilot/events`.
All requests are maps. All amounts are integer cents, timestamps Unix milliseconds.
`snapshot` is the source of truth. Every ledger mutation MUST carry the `revision`
from its opening snapshot; `STALE_LEDGER` means discard drafts and reload.
Errors use `PlatformException(code, message)` with a user-facing Chinese message.

## Snapshot

`snapshot {}` → map:

- `revision`: int
- `transactions`: all rows including ignored; `{id, amount, type, categoryId, note, merchant, accountTag, occurredAt, createdAt, source, status, duplicateOf}`. **Stored amount is positive for EXPENSE and negative for INCOME.** UI displays absolute amount. Types: EXPENSE/INCOME. Status: CONFIRMED/PENDING/IGNORED. Source: MANUAL/AI_TEXT/AI_VOICE/AI_IMAGE/BILL_IMPORT/NOTIFICATION/ACCESSIBILITY. Rows additionally carry businessKind, metadataJson, operationKey and editVersion.
- `categories`: `{id,name,icon,colorIndex,type,sortOrder,enabled,isPreset}`
- `rules`: `{id,appPackage,regexAmount,regexMerchant,directionHint,enabled,priority}`
- `reports`: `{id,yearMonth,totalExpense,totalIncome,byCategoryJson,llmSummary,generatedAt}`; income totals are nonnegative cents; net expense can be negative when refunds exceed spending.
- `settings`: `{onboardingDone,notificationCaptureEnabled,screenCaptureEnabled,notificationCloudParsingEnabled,lastBackupAt,budgetCents,themeMode,activeProvider,deepSeekModel,qwenModel,qwenBaseUrl,hasDeepSeekKey,hasQwenKey,needsReconfiguration,chatGptModel}`. themeMode = system/light/dark; budgetCents=0 disables budget. Secret keys are never returned.
- `capabilities`: `{platform:"android",notificationListenerGranted,accessibilityGranted,remindersEnabled,microphoneGranted,batteryExempt,speechAvailable,notificationServiceConnected,accessibilityServiceConnected,todayCaptureCount,duplicateCount,lastCaptureAt,lastError,lastScreenEventTime,lastScreenReadTime,lastScreenPackage,screenCaptureStatus,screenReadDiagnostics,screenReadHistory}`. Page diagnostics contain timestamps, a supported package name, a status and numeric node counters only; never page text. `screenReadDiagnostics` optionally contains `visitedNodes`, `textNodes`, `editableNodes`, `passwordNodes`, `missingChildren`, `hiddenNodes`, and `foreignNodes`. Status codes: `waiting`, `disabled`, `disconnected`, `event_received`, `unreadable`, `input_page`, `password_page`, `incomplete_tree`, `traversal_limit`, `text_limit`, `empty_page`, `protected` (legacy), `not_receipt`, `parsed`, `saved`, `duplicate`, `error`. `screenReadHistory` optionally contains at most eight in-memory entries `{status,timestamp,packageName,diagnostics}` in occurrence order; adjacent same-status/package samples are coalesced, and transient `parsed` updates are omitted from history. Read/save outcomes carry the originating request package even if a new event arrives while saving. This keeps the receipt result visible after returning to a chat input page. It is not included in ledger backups. Connection/authorization alone does not imply `screenCaptureEnabled`.
- `auth`: `{accounts:[{id,email,canUsePlan,isSignedIn}],activeAccountId,isAuthorizing,loginUrl,error,showWelcome}`. No tokens cross the bridge.
- `launch`: nullable `{transactionId,route}`; consumed once. Notification IDs are validated against capture identity.

## Ledger commands

- `saveTransaction {revision,id?:0,amount,type,categoryId,note?,merchant?,accountTag?,occurredAt,source?:MANUAL}` → `{id}`. Input amount is **positive absolute cents**. Existing rows preserve source/status/capture identity. New rows are CONFIRMED; source limited to MANUAL/AI_TEXT/AI_VOICE/AI_IMAGE/BILL_IMPORT. Optional businessKind, metadataJson and operationKey retain recognition provenance. Existing updates accept editVersion and reject stale edits. confirmPending:true atomically edits and confirms a pending row after its unknown purpose has been resolved.
- `saveTransactions {revision,transactions:[same fields as saveTransaction without revision]}` → `{ids}`. Atomic batch for reviewed AI drafts; no partial-save duplication.
- `deleteTransaction {revision,id}` → null.
- `setTransactionStatus {revision,id,status:CONFIRMED|IGNORED}` → null. Only pending rows can transition; unknown businessKind must be resolved before confirmation.
- `saveCategory {revision,id?:0,name,icon,type,enabled?:true,sortOrder?}` → `{id}`. Existing type cannot change; preset category cannot be deleted. Name <=20 characters and unique within type.
- `deleteCategory {revision,id}` → null. Moves transactions atomically to same-type “其他”.
- `saveRule {revision,id?:0,appPackage,regexAmount,regexMerchant?,directionHint,enabled?:true,priority?:0}` → `{id}`. Regex must have capture group; appPackage required.
- `deleteRule {revision,id}` / `resetRules {revision}` → null. Reset replaces all rules with presets.
- `generateReport {revision,yearMonth:"2026-09"}` → `{queued:true}`; only completed months. Events include report generation state.

## AI, settings and platform

- `parseText {revision,text}` → `{transactions:[{amount,type,categoryId,categoryName,note,merchant,occurredAt}],error}`. No data saved. Error may be non-null. Positive amounts.
- `ask {revision,question,history?:[{role:user|assistant,content}]}` → `{answer,isError,chartData?:{labels,values,highlightIndex,unit}}`.
- `updateSettings {onboardingDone?,notificationCaptureEnabled?,screenCaptureEnabled?,notificationCloudParsingEnabled?,budgetCents?,themeMode?,activeProvider?,deepSeekKey?,deepSeekModel?,qwenKey?,qwenModel?,qwenBaseUrl?,chatGptModel?}` → null. Omit API key to preserve; empty key explicitly removes. Screen capture and cloud parsing should be enabled only after UI disclosure.
- `exportBackup {}` → `{cancelled:bool}` after system save picker finishes.
- `restoreBackup {revision}` → `{cancelled:bool}` after system multi-file picker and restore finish. UI confirms destructive replacement before calling. Restore invalidates all drafts via revision event.
- `startSpeech {}` → `{text}` after final recognition. Requests RECORD_AUDIO first if needed. `stopSpeech {}` stops listening and waits for recognition; `cancelSpeech {}` cancels pending request with CANCELLED.
- `openSystemSettings {target:notificationListener|accessibility|notifications|battery|app|microphone}` → null. `rebindNotificationService {}` → null. `requestNotificationPermission {}` → `{granted:bool}`.
- `signIn {accountId?,requestPlanPermission?:false}` → null; starts external browser. `cancelSignIn {}` / `selectAccount {id}` / `signOut {id}` / `dismissWelcome {}` → null.
- `listModels {}` → `[{slug,displayName}]` for active signed-in account.

## Events

Every event is a map with `type` and `revision`.
- `{type:"changed",revision}`: reload snapshot (Room/settings/auth/service changes).
- `{type:"restored",revision}`: clear drafts, navigation, pending AI results; reload.
- `{type:"launch",revision,transactionId?,route?}`: validated notification navigation.
- `{type:"speech",revision,text}`: live partial transcription.
- `{type:"speechState",revision,state:preparing|recording|transcribing|idle}`: shared cloud transcription lifecycle. A completed transcript is returned by `startSpeech`; the entry UI then starts recognition and applies the separately enabled active-input save policy.
- `{type:"report",revision,yearMonth,state}`: ENQUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED/BLOCKED.

Android owns its Room persistence and capture services. Dart never directly opens
`pennypilot.db`; iOS opens its own private SQLite store. Credentials are kept in
Android encrypted preferences or iOS Keychain. Only `has…Key` flags enter snapshots.
`readAiCredential {key:deepSeekKey|qwenKey}` is an app-internal Android adapter call:
it reads the selected API key into transient Dart memory for a request. It must
never be logged, returned to UI state, exported, or exposed to AI tools.

## Shared voice configuration and transcription

The Flutter application routes `startSpeech`, `stopSpeech` and `cancelSpeech` to
`VoiceTranscriptionService` on both Android and iOS. The old native recognizer
commands remain internal legacy interfaces; the current UI does not depend on
an installed system recognition service. Direct microphone PCM is wrapped as
16 kHz mono WAV in memory; recording is bounded to 60 seconds and requests to
45 seconds. Cancellation drops the session and late results.

- `saveSpeechSettings {provider:qwen|compatible,baseUrl,model,apiKey?:""}` saves one encrypted configuration; an empty key retains the previous key. Changing the endpoint origin requires a replacement key.
- `removeSpeechKey {}` removes the saved key while retaining model and endpoint.
- Shared snapshots add `speechProvider`, `speechBaseUrl`, `speechModel`, `hasSpeechKey` and optionally `speechConfigurationError` to settings. Keys and audio never enter snapshots or ledger backups.
- Capabilities add `speechUsesCloud:true`; `speechAvailable` reflects whether a key is configured, not proof that a remote endpoint or account is working. The user can perform a real recording test in settings.
- The Qwen adapter posts input-audio data URLs to `/chat/completions`; compatible services receive a multipart WAV at `/audio/transcriptions`. HTTPS is required, redirects are refused, and responses are bounded and sanitized.

## Portable backup and report adapter commands (0.3.0)

- `exportBackupData {}` returns `{format:"pennypilot",version:1,categories,transactions,rules,reports,captureEvents}`. Rows use the same camelCase wire fields. Backup transactions additionally retain `rawPayload` and `captureEventKey`.
- `restoreBackupData {revision,data}` validates all rows before atomically replacing the ledger and advancing revision. Invalid data leaves the original ledger unchanged.
- `saveGeneratedReport {revision,id?:0,yearMonth,totalExpense,totalIncome,byCategoryJson,llmSummary,generatedAt}` persists a completed-month report. Same month replaces the previous report.
- At the shared repository layer, `exportBackup` / `restoreBackup` use system JSON file pickers (20 MiB maximum) on both Android and iOS.
- iOS returns `platform:"ios"`, `automaticCaptureSupported:false`, `speechRecognitionGranted` and real microphone permission state. It does not offer Android capture/system-permission commands.
- iOS ChatGPT uses the system in-app browser, loopback callback, PKCE, state, nonce and signed ID-token verification; Android continues its established system-browser flow. Signing credentials and OAuth are not part of backups.

## Android source boundary

`accessibilityGranted` reflects the current system-saved authorization for the
exact PennyPilot service component, independently of `accessibilityServiceConnected`.
The latter reflects this process's service connection callback. After background
cleanup or process restart, authorization can remain true while connection is
false. An empty system authorization list revokes the grant; it is never inferred
from a cached app preference. The manager service list is a fallback only when
the system setting cannot be read. A battery exemption does not establish OEM
autostart or protection from one-tap cleanup.

`android/app` hosts the new `MainActivity` and `NativeLedgerBridge`. Its
`prepareNativeCore` Gradle Sync task tracks and copies the existing Kotlin backend
into a generated build directory, excluding the previous Compose UI, previous
`MainActivity`, and Compose-specific `CategoryColors`. Both Kotlin and KSP read
this same filtered tree, so Hilt does not generate factories for removed UI
ViewModels. The original `app/` remains the owning source for Room, services,
authentication and use cases; generated copies are never edited or committed.

## Recognition protocol (0.5.0)

- `recognitionChat {revision,modelRole:text|vision|review,messages}` returns structured JSON text, never writes the ledger. Content supports text or OpenAI-style text/image parts. Image requests require cloudImageParsingEnabled and a vision/review role. The selected role pins its provider/model and stored credentials.
- `pickRecognitionImage {}` / `consumeSharedImage {}` return `{base64,mimeType,ocrText}` or null; Android uses a system picker or image share intent, normalizes orientation, bounds size and runs local Chinese OCR. Shared images launch route `recognition_image`.
- `pickBillFile {}` returns `{name,text}` or null; Android reads UTF-8 or GB18030 CSV through the system picker. Parsing and import previews stay local.
- `armCaptureDiagnostic {}` / `readCaptureDiagnostic {}` / `clearCaptureDiagnostic {}` return `{state:idle|waiting|ready,sample?}`. One user-armed sample expires after ten minutes. Unsafe pages only contribute numeric reasons; safe receipts may include editable redacted text, so users preview before copying.
- Settings add `screenCloudParsingEnabled`, `screenVisualCaptureEnabled`, `cloudImageParsingEnabled`, `captureAutoRecordEnabled`, `activeInputAutoSaveEnabled` (all false by default). Text/image/voice permissions remain independent.
- Settings add `recognitionTextProvider`, `recognitionVisionProvider`, `recognitionReviewProvider` (empty reuses active provider; otherwise chatgpt/deepseek/qwen), and the corresponding `recognitionTextModel`, `recognitionVisionModel`, `recognitionReviewModel` (empty reuses that provider's default model).
- `recognitionPreferencesJson` is a bounded array of explicit merchant/category corrections. It contains no credentials and is not a substitute for amount, direction or payment evidence.
- `businessKind` is standard/expense/income/transfer/receivable/payable/refund/unknown. expense requires EXPENSE; income/refund require INCOME. Transfers, receivables, payables and unknown purpose do not inflate income/expense. Refunds offset expense with their signed negative amount.
- Stable operationKey prevents request retries creating extra rows. Delete saves tombstones; late active-input saves or capture events cannot recreate the same operation. Portable backups include optional captureTombstones and recognition fields while retaining v1 compatibility.
- Android schema v6 and portable SQLite v2 add provenance, edit versions and tombstones. Restoring advances a durable ledger generation and clears encrypted recognition tasks. Ordinary diagnostic history remains transient.
- Screen status adds `visual_unavailable`, `model_queued`, `model_processing`, `model_rejected`, `model_failed`. Background candidates use an encrypted, bounded 24-hour queue and never require the Flutter screen to remain alive.
