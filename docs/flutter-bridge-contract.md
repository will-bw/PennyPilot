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
- `transactions`: all rows including ignored; `{id, amount, type, categoryId, note, merchant, accountTag, occurredAt, createdAt, source, status, duplicateOf}`. **Stored amount is positive for EXPENSE and negative for INCOME.** UI displays absolute amount. Types: EXPENSE/INCOME. Status: CONFIRMED/PENDING/IGNORED. Source: MANUAL/AI_TEXT/AI_VOICE/NOTIFICATION/ACCESSIBILITY.
- `categories`: `{id,name,icon,colorIndex,type,sortOrder,enabled,isPreset}`
- `rules`: `{id,appPackage,regexAmount,regexMerchant,directionHint,enabled,priority}`
- `reports`: `{id,yearMonth,totalExpense,totalIncome,byCategoryJson,llmSummary,generatedAt}`; report totals are positive cents.
- `settings`: `{onboardingDone,notificationCaptureEnabled,screenCaptureEnabled,notificationCloudParsingEnabled,lastBackupAt,budgetCents,themeMode,activeProvider,deepSeekModel,qwenModel,qwenBaseUrl,hasDeepSeekKey,hasQwenKey,needsReconfiguration,chatGptModel}`. themeMode = system/light/dark; budgetCents=0 disables budget. Secret keys are never returned.
- `capabilities`: `{platform:"android",notificationListenerGranted,accessibilityGranted,remindersEnabled,microphoneGranted,batteryExempt,speechAvailable,notificationServiceConnected,accessibilityServiceConnected,todayCaptureCount,duplicateCount,lastCaptureAt,lastError}`
- `auth`: `{accounts:[{id,email,canUsePlan,isSignedIn}],activeAccountId,isAuthorizing,loginUrl,error,showWelcome}`. No tokens cross the bridge.
- `launch`: nullable `{transactionId,route}`; consumed once. Notification IDs are validated against capture identity.

## Ledger commands

- `saveTransaction {revision,id?:0,amount,type,categoryId,note?,merchant?,accountTag?,occurredAt,source?:MANUAL}` → `{id}`. Input amount is **positive absolute cents**. Existing rows preserve source/status/capture identity. New rows are CONFIRMED; source limited to MANUAL/AI_TEXT/AI_VOICE.
- `saveTransactions {revision,transactions:[same fields as saveTransaction without revision]}` → `{ids}`. Atomic batch for reviewed AI drafts; no partial-save duplication.
- `deleteTransaction {revision,id}` → null.
- `setTransactionStatus {revision,id,status:CONFIRMED|IGNORED}` → null. Only pending rows can transition.
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
- `{type:"report",revision,yearMonth,state}`: ENQUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED/BLOCKED.

Android owns its Room persistence and capture services. Dart never directly opens
`pennypilot.db`; iOS opens its own private SQLite store. Credentials are kept in
Android encrypted preferences or iOS Keychain. Only `has…Key` flags enter snapshots.
`readAiCredential {key:deepSeekKey|qwenKey}` is an app-internal Android adapter call:
it reads the selected API key into transient Dart memory for a request. It must
never be logged, returned to UI state, exported, or exposed to AI tools.

## Portable backup and report adapter commands (0.3.0)

- `exportBackupData {}` returns `{format:"pennypilot",version:1,categories,transactions,rules,reports,captureEvents}`. Rows use the same camelCase wire fields. Backup transactions additionally retain `rawPayload` and `captureEventKey`.
- `restoreBackupData {revision,data}` validates all rows before atomically replacing the ledger and advancing revision. Invalid data leaves the original ledger unchanged.
- `saveGeneratedReport {revision,id?:0,yearMonth,totalExpense,totalIncome,byCategoryJson,llmSummary,generatedAt}` persists a completed-month report. Same month replaces the previous report.
- At the shared repository layer, `exportBackup` / `restoreBackup` use system JSON file pickers (20 MiB maximum) on both Android and iOS.
- iOS returns `platform:"ios"`, `automaticCaptureSupported:false`, `speechRecognitionGranted` and real microphone permission state. It does not offer Android capture/system-permission commands.
- iOS ChatGPT uses the system in-app browser, loopback callback, PKCE, state, nonce and signed ID-token verification; Android continues its established system-browser flow. Signing credentials and OAuth are not part of backups.

## Android source boundary

`android/app` hosts the new `MainActivity` and `NativeLedgerBridge`. Its
`prepareNativeCore` Gradle Sync task tracks and copies the existing Kotlin backend
into a generated build directory, excluding the previous Compose UI, previous
`MainActivity`, and Compose-specific `CategoryColors`. Both Kotlin and KSP read
this same filtered tree, so Hilt does not generate factories for removed UI
ViewModels. The original `app/` remains the owning source for Room, services,
authentication and use cases; generated copies are never edited or committed.
