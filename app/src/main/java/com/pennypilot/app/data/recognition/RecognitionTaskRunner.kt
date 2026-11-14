package com.pennypilot.app.data.recognition

import com.pennypilot.app.data.llm.LLMClient
import com.pennypilot.app.data.llm.ModelRole
import com.pennypilot.app.data.parser.NotificationParseResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** Durable orchestration is separate from the writer; model responses never access the database. */
class RecognitionTaskRunner(
    private val store: RecognitionTaskStore,
    private val client: LLMClient,
    private val isAllowed: suspend (RecognitionTask) -> Boolean,
    private val commit: suspend (RecognitionTask, PaymentProposal, NotificationParseResult, () -> Boolean) -> Boolean,
    private val report: (String) -> Unit,
    private val onState: (RecognitionTask, String) -> Unit = { _, _ -> },
    private val semanticContext: suspend (RecognitionTask) -> CaptureSemanticContext = { CaptureSemanticContext() },
    private val verifiedPayment: suspend (RecognitionTask) -> NotificationParseResult? = { null }
) {
    /** Superseded work finishes successfully so an appended successor can run without backoff. */
    suspend fun run(id: String): Boolean {
        val task = try { store.get(id) } catch (_: Exception) {
            store.clear(); report("加密识别缓存不可用，请重新识别本次支付"); return true
        } ?: return true
        if (superseded(task)) return true
        return try {
            if (task.state == RecognitionTaskState.QUEUED && task.attempts < MAX_ATTEMPTS)
                store.incrementAttempts(id, task.evidenceVersion)
            val role = if (task.images.isEmpty()) ModelRole.TEXT else ModelRole.VISION
            suspend fun current() = isAllowed(task) && task.configurationId == client.operationIdentity(role)
            val allowedBeforeContext = current()
            if (superseded(task)) return true
            if (!allowedBeforeContext) {
                onState(task, "model_discarded")
                store.remove(id, task.evidenceVersion); report("识别权限、账本或 AI 配置已改变，旧任务已停止"); return true
            }
            if (task.state != RecognitionTaskState.QUEUED) return true
            if (task.attempts >= MAX_ATTEMPTS) return pause(task, RecognitionFailure.NETWORK)
            val semantics = semanticContext(task).bounded()
            val verified = if (task.semanticFingerprint != null || task.receiptImageMode != null) verifiedPayment(task) else null
            val operation = client.forRole(role)
            // Pin the original endpoint/account before the final pre-send authorization check.
            val allowedBeforeSend = current()
            if (superseded(task)) return true
            val requiresSavedReceipt = task.semanticFingerprint != null || task.receiptImageMode == ReceiptImageMode.VERIFIED_RECEIPT
            if (!allowedBeforeSend || requiresSavedReceipt && verified == null) {
                onState(task, "model_discarded")
                store.remove(id, task.evidenceVersion); report("AI 配置已改变，旧任务已停止"); return true
            }
            onState(task, "model_processing")
            val (proposal, parsed) = withTimeout(60_000) {
                val engine = PaymentRecognitionEngine(operation)
                when {
                    task.receiptImageMode != null ->
                        engine.readReceiptImage(task.packageName, task.images.single(), task.observedAt, verified, semantics)
                    verified == null -> engine.propose(task.packageName, task.text, task.images, task.structure, semantics)
                    else -> engine.classifyVerified(verified, task.images, semantics) to verified
                }
            }
            val allowedAfterResponse = current()
            if (superseded(task)) return true
            if (!allowedAfterResponse) {
                onState(task, "model_discarded")
                store.remove(id, task.evidenceVersion); report("识别权限、账本或 AI 配置已改变，旧任务已停止"); return true
            }
            if (parsed == null) {
                onState(task, "model_rejected")
                // A positive receipt with contradictory fields can be reviewed. A model's
                // explicit non-receipt rejection is discarded rather than archived as finance data.
                if (proposal.valid) return pause(task, RecognitionFailure.INCOMPLETE)
                store.remove(id, task.evidenceVersion); report("未识别到已完成的支付凭证，未生成账目"); return true
            }
            val saved = commit(task, proposal, parsed) { store.get(id)?.let {
                it.evidenceVersion == task.evidenceVersion && it.state == RecognitionTaskState.QUEUED
            } == true }
            if (superseded(task)) return true
            val finished = saved || !isAllowed(task)
            if (superseded(task)) return true
            if (finished) {
                store.remove(id, task.evidenceVersion)
                onState(task, if (saved) "model_completed" else "model_discarded")
            }
            if (!finished && task.attempts + 1 >= MAX_ATTEMPTS) pause(task, RecognitionFailure.SAVE_FAILED) else finished
        } catch (_: TimeoutCancellationException) {
            if (superseded(task)) return true
            if (task.attempts + 1 >= MAX_ATTEMPTS) return pause(task, RecognitionFailure.TIMEOUT)
            onState(task, "model_failed")
            report("支付识别超时，已保留任务等待重试"); false
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (superseded(task)) return true
            if (task.attempts + 1 >= MAX_ATTEMPTS) return pause(task, RecognitionFailure.NETWORK)
            onState(task, "model_failed"); report("支付识别暂时失败，已保留任务等待重试"); false
        }
    }

    private suspend fun pause(task: RecognitionTask, failure: RecognitionFailure): Boolean {
        val allowed = isAllowed(task)
        if (superseded(task)) return true
        if (!allowed) {
            store.remove(task.id, task.evidenceVersion)
            onState(task, "model_discarded")
            report("识别权限、账本或 AI 配置已改变，旧任务已停止")
            return true
        }
        val alreadySaved = !task.isUnrecognized || verifiedPayment(task) != null
        if (superseded(task)) return true
        if (alreadySaved) store.remove(task.id, task.evidenceVersion)
        else store.needsAttention(task.id, task.evidenceVersion, failure)
        onState(task, "model_exhausted")
        report(if (!alreadySaved) "支付识别未完成，可在未识别凭证中查看、重试或补录"
            else "账目已保存，部分信息未能补全；可打开账目核对")
        return true
    }

    // A newer snapshot already has its own appended WorkRequest. Retrying this predecessor
    // would hold the entire chain behind network backoff, including the fresh evidence.
    private fun superseded(task: RecognitionTask): Boolean {
        val replaced = store.get(task.id)?.evidenceVersion != task.evidenceVersion
        if (replaced) onState(task, "model_superseded")
        return replaced
    }

    private companion object { const val MAX_ATTEMPTS = RecognitionTaskStore.MAX_AUTOMATIC_ATTEMPTS }
}
