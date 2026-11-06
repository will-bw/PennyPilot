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
    private val semanticContext: suspend (RecognitionTask) -> CaptureSemanticContext = { CaptureSemanticContext() }
) {
    /** Superseded work finishes successfully so an appended successor can run without backoff. */
    suspend fun run(id: String): Boolean {
        val task = try { store.get(id) } catch (_: Exception) {
            store.clear(); report("加密识别缓存不可用，请重新识别本次支付"); return true
        } ?: return true
        if (superseded(task)) return true
        if (task.attempts >= 3) {
            onState(task, "model_exhausted")
            store.remove(id, task.evidenceVersion); report("支付识别重试未完成，请手动补录或导入账单核对"); return true
        }
        return try {
            store.incrementAttempts(id, task.evidenceVersion)
            val role = if (task.images.isEmpty()) ModelRole.TEXT else ModelRole.VISION
            suspend fun current() = isAllowed(task) && task.configurationId == client.operationIdentity(role)
            val allowedBeforeContext = current()
            if (superseded(task)) return true
            if (!allowedBeforeContext) {
                onState(task, "model_discarded")
                store.remove(id, task.evidenceVersion); report("识别权限、账本或 AI 配置已改变，旧任务已停止"); return true
            }
            val semantics = semanticContext(task).bounded()
            val operation = client.forRole(role)
            // Pin the original endpoint/account before the final pre-send authorization check.
            val allowedBeforeSend = current()
            if (superseded(task)) return true
            if (!allowedBeforeSend) {
                onState(task, "model_discarded")
                store.remove(id, task.evidenceVersion); report("AI 配置已改变，旧任务已停止"); return true
            }
            onState(task, "model_processing")
            val (proposal, parsed) = withTimeout(60_000) {
                PaymentRecognitionEngine(operation).propose(task.packageName, task.text, task.images, task.structure, semantics)
            }
            val allowedAfterResponse = current()
            if (superseded(task)) return true
            if (!allowedAfterResponse) {
                onState(task, "model_discarded")
                store.remove(id, task.evidenceVersion); report("识别权限、账本或 AI 配置已改变，旧任务已停止"); return true
            }
            if (parsed == null) {
                onState(task, "model_rejected")
                store.remove(id, task.evidenceVersion); report("支付凭证存在缺失或冲突，未生成账目；请补充凭证"); return true
            }
            val saved = commit(task, proposal, parsed) { store.get(id)?.evidenceVersion == task.evidenceVersion }
            if (superseded(task)) return true
            val finished = saved || !isAllowed(task)
            if (superseded(task)) return true
            if (finished) {
                store.remove(id, task.evidenceVersion)
                onState(task, if (saved) "model_completed" else "model_discarded")
            }
            finished
        } catch (_: TimeoutCancellationException) {
            if (superseded(task)) return true
            onState(task, "model_failed")
            report("支付识别超时，已保留任务等待重试"); false
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (superseded(task)) return true
            onState(task, "model_failed"); report("支付识别暂时失败，已保留任务等待重试"); false
        }
    }

    // A newer snapshot already has its own appended WorkRequest. Retrying this predecessor
    // would hold the entire chain behind network backoff, including the fresh evidence.
    private fun superseded(task: RecognitionTask): Boolean {
        val replaced = store.get(task.id)?.evidenceVersion != task.evidenceVersion
        if (replaced) onState(task, "model_superseded")
        return replaced
    }
}
