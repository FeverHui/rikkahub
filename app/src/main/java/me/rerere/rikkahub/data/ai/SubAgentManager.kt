package me.rerere.rikkahub.data.ai

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.ai.tools.createSkillTools
import me.rerere.rikkahub.data.ai.tools.createWorkspaceTools
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceShellStatus
import kotlin.uuid.Uuid

private const val TAG = "SubAgentManager"

/**
 * 最大子 Agent 嵌套深度
 */
private const val MAX_DEPTH = 5

/**
 * 子 Agent 执行结果
 */
data class SubAgentResult(
    val success: Boolean,
    val output: String,
    val messages: List<UIMessage>,
    val error: String? = null,
    val stepsUsed: Int = 0,
    val usage: SubAgentUsage? = null,
)

data class SubAgentUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val totalTokens: Int = 0,
)

/**
 * SubAgentManager 负责管理子 Agent 的完整生命周期。
 *
 * 支持两种子 Agent 模式：
 * 1. 预设模式：使用已保存的 Assistant（通过 delegateTask，可选白名单检查）
 * 2. 内联模式：动态创建临时子 Agent（通过 delegateTaskInline，无需保存）
 */
class SubAgentManager(
    private val generationHandler: GenerationHandler,
    private val settingsStore: SettingsStore,
    private val localTools: LocalTools,
    private val mcpManager: McpManager,
    private val workspaceRepository: WorkspaceRepository,
    private val skillManager: SkillManager,
    private val memoryRepository: MemoryRepository,
) {

    // ==================== 预设模式 ====================

    /**
     * 委托任务给已保存的 Assistant（子 Agent）。
     *
     * 安全检查：
     * - 递归深度 ≤ MAX_DEPTH
     * - 不委托给自己
     * - Assistant 必须存在
     * - 如果 parentAssistant.allowDynamicDelegation=true，跳过白名单检查
     * - 否则必须位于 parentAssistant.subAgentIds 白名单中
     */
    suspend fun delegateTask(
        parentAssistant: Assistant,
        subAssistantId: Uuid,
        task: String,
        contextData: String? = null,
        maxSteps: Int = 20,
        currentDepth: Int = 0,
    ): SubAgentResult {
        // 深度检查
        if (currentDepth >= MAX_DEPTH) {
            return SubAgentResult(false, "", emptyList(),
                error = "Sub-agent delegation rejected: maximum nesting depth ($MAX_DEPTH) exceeded.")
        }
        // 自委托检查
        if (subAssistantId == parentAssistant.id) {
            return SubAgentResult(false, "", emptyList(),
                error = "Sub-agent delegation rejected: cannot delegate to self.")
        }

        val settings = settingsStore.settingsFlow.first()
        val subAssistant = settings.getAssistantById(subAssistantId)
            ?: return SubAgentResult(false, "", emptyList(),
                error = "Sub-agent not found: ID '$subAssistantId'. " +
                        "Use `create_agent` first to create and save a new assistant.")

        // 白名单检查（除非允许动态委托）
        if (!parentAssistant.allowDynamicDelegation && subAssistantId !in parentAssistant.subAgentIds) {
            return SubAgentResult(false, "", emptyList(),
                error = "Sub-agent '${subAssistant.name}' is not in the allowed sub-agents list. " +
                        "Either add it to subAgentIds, enable allowDynamicDelegation on the parent, " +
                        "or use `spawn_sub_agent` to create a temporary sub-agent.")
        }

        Log.i(TAG, "delegateTask: ${parentAssistant.name} -> ${subAssistant.name} (depth=$currentDepth)")

        val model = resolveModel(settings, subAssistant.chatModelId ?: parentAssistant.chatModelId)
            ?: return SubAgentResult(false, "", emptyList(),
                error = "No model configured for sub-agent '${subAssistant.name}'.")

        val messages = buildSubAgentMessages(task, contextData)
        val tools = buildSubAgentTools(subAssistant, settings)
        val memories = resolveMemories(subAssistant)

        return executeGeneration(settings, model, messages, subAssistant, tools, memories, maxSteps)
    }

    // ==================== 内联模式 ====================

    /**
     * 动态创建临时子 Agent 并执行任务。
     *
     * 与 delegateTask 不同，此方法直接使用传入的 Assistant 对象，
     * 无需提前保存到 Settings，也无需白名单检查。
     * 临时子 Agent 用完即弃，不会持久化。
     *
     * @param parentAssistant 主 Agent（用于继承模型）
     * @param name            子 Agent 名称
     * @param systemPrompt    子 Agent 的 system prompt
     * @param task            要执行的任务
     * @param contextData     可选的上下文
     * @param enabledTools    子 Agent 可用的工具列表（null = 默认仅 time_info）
     * @param maxSteps        最大步数
     * @param currentDepth    当前嵌套深度
     */
    suspend fun spawnSubAgent(
        parentAssistant: Assistant,
        name: String,
        systemPrompt: String,
        task: String,
        contextData: String? = null,
        enabledTools: List<LocalToolOption>? = null,
        maxSteps: Int = 20,
        currentDepth: Int = 0,
    ): SubAgentResult {
        // 深度检查
        if (currentDepth >= MAX_DEPTH) {
            return SubAgentResult(false, "", emptyList(),
                error = "Sub-agent spawning rejected: maximum nesting depth ($MAX_DEPTH) exceeded.")
        }

        val settings = settingsStore.settingsFlow.first()

        // 动态创建临时 Assistant（不保存）
        val tempAssistant = Assistant(
            id = Uuid.random(),
            name = name,
            systemPrompt = systemPrompt,
            localTools = enabledTools ?: listOf(LocalToolOption.TimeInfo),
            workspaceId = parentAssistant.workspaceId, // 继承父的 workspace
        )

        Log.i(TAG, "spawnSubAgent: ${parentAssistant.name} -> $name (depth=$currentDepth)")

        val model = resolveModel(settings, parentAssistant.chatModelId)
            ?: return SubAgentResult(false, "", emptyList(),
                error = "No model available for sub-agent.")

        val messages = buildSubAgentMessages(task, contextData)
        val tools = buildSubAgentTools(tempAssistant, settings)
        val memories = null // 临时子 Agent 默认无记忆

        return executeGeneration(settings, model, messages, tempAssistant, tools, memories, maxSteps)
    }

    // ==================== 创建并保存 Assistant ====================

    /**
     * 创建一个新的 Assistant 并保存到 Settings。
     * 返回创建后的 Assistant，包含生成的 ID。
     */
    suspend fun createAssistant(
        name: String,
        systemPrompt: String,
        localTools: List<LocalToolOption> = listOf(LocalToolOption.TimeInfo),
    ): Result<Assistant> = runCatching {
        val newAssistant = Assistant(
            id = Uuid.random(),
            name = name,
            systemPrompt = systemPrompt,
            localTools = localTools,
        )
        settingsStore.update { settings ->
            settings.copy(
                assistants = settings.assistants + newAssistant
            )
        }
        Log.i(TAG, "createAssistant: created '$name' (id=${newAssistant.id})")
        newAssistant
    }

    // ==================== 内部方法 ====================

    private suspend fun resolveModel(
        settings: Settings,
        modelId: Uuid?,
    ): Model? {
        return settings.findModelById(modelId ?: settings.chatModelId)
    }

    private fun buildSubAgentMessages(task: String, contextData: String?): List<UIMessage> {
        return buildList {
            if (!contextData.isNullOrBlank()) {
                add(UIMessage.system("Context from parent agent:\n$contextData"))
            }
            add(UIMessage.user(task))
        }
    }

    private suspend fun resolveMemories(subAssistant: Assistant): List<AssistantMemory>? {
        if (!subAssistant.enableMemory) return null
        return if (subAssistant.useGlobalMemory) {
            memoryRepository.getGlobalMemories()
        } else {
            memoryRepository.getMemoriesOfAssistant(subAssistant.id.toString())
        }
    }

    private suspend fun buildSubAgentTools(
        subAssistant: Assistant,
        settings: Settings,
    ): List<Tool> {
        return buildList {
            // 1. 本地工具
            addAll(localTools.getTools(subAssistant.localTools))

            // 2. Workspace 工具
            if (subAssistant.workspaceId != null) {
                val ws = workspaceRepository.getById(subAssistant.workspaceId.toString())
                if (ws != null && ws.shellStatus == WorkspaceShellStatus.READY.name) {
                    addAll(createWorkspaceTools(
                        workspaceId = subAssistant.workspaceId.toString(),
                        workspaceRepository = workspaceRepository,
                        cwd = null,
                    ))
                }
            }

            // 3. Skills
            if (subAssistant.enabledSkills.isNotEmpty()) {
                addAll(createSkillTools(
                    enabledSkills = subAssistant.enabledSkills,
                    allSkills = skillManager.listSkills(),
                    skillManager = skillManager,
                ))
            }

            // 4. MCP 工具
            settings.mcpServers
                .filter { it.commonOptions.enable && it.id in subAssistant.mcpServers }
                .forEach { server ->
                    server.commonOptions.tools.filter { it.enable }.forEach { tool ->
                        add(Tool(
                            name = "mcp__${server.commonOptions.name}__${tool.name}",
                            description = tool.description ?: "",
                            parameters = { tool.inputSchema },
                            needsApproval = { tool.needsApproval },
                            execute = { args ->
                                mcpManager.callTool(server.id, tool.name, args.jsonObject)
                            },
                        ))
                    }
                }
        }
    }

    private suspend fun executeGeneration(
        settings: Settings,
        model: Model,
        messages: List<UIMessage>,
        assistant: Assistant,
        tools: List<Tool>,
        memories: List<AssistantMemory>?,
        maxSteps: Int,
    ): SubAgentResult {
        val resultMessages = mutableListOf<UIMessage>()
        val processingStatus = MutableStateFlow<String?>(null)
        var stepsUsed = 0

        try {
            generationHandler.generateText(
                settings = settings,
                model = model,
                messages = messages,
                assistant = assistant,
                tools = tools,
                maxSteps = maxSteps,
                processingStatus = processingStatus,
                workspaceCwd = null,
                memories = memories,
            ).collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Messages -> {
                        resultMessages.clear()
                        resultMessages.addAll(chunk.messages)
                        stepsUsed++
                    }
                }
            }

            val output = resultMessages.lastOrNull()?.toText() ?: ""
            val usage = aggregateUsage(resultMessages)

            Log.i(TAG, "executeGeneration: ${assistant.name} done (steps=$stepsUsed, output=${output.take(80).replace("\n", " ")}...)")

            return SubAgentResult(
                success = true,
                output = output,
                messages = resultMessages,
                stepsUsed = stepsUsed,
                usage = usage,
            )
        } catch (e: CancellationException) {
            Log.i(TAG, "executeGeneration cancelled: ${assistant.name}")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "executeGeneration failed: ${assistant.name}", e)
            return SubAgentResult(
                success = false,
                output = "",
                messages = resultMessages,
                error = e.message,
                stepsUsed = stepsUsed,
            )
        }
    }

    private fun aggregateUsage(messages: List<UIMessage>): SubAgentUsage? {
        val total = messages.sumOf { it.usage?.totalTokens ?: 0 }
        if (total == 0) return null
        return SubAgentUsage(
            inputTokens = messages.sumOf { it.usage?.inputTokens ?: 0 },
            outputTokens = messages.sumOf { it.usage?.outputTokens ?: 0 },
            totalTokens = total,
        )
    }
}
