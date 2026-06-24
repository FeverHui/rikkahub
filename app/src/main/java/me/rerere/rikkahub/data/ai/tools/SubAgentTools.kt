package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.SubAgentManager
import me.rerere.rikkahub.data.ai.SubAgentResult
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.model.Assistant
import kotlin.uuid.Uuid

// 子 Agent 可用的工具选项描述（供 LLM 参考）
private val AVAILABLE_TOOLS_DESCRIPTION = """
Available tool types for sub-agents:
- "time_info": Get current date/time/timezone
- "javascript_engine": Execute JavaScript code (QuickJS ES2020)
- "clipboard": Read/write device clipboard
- "tts": Speak text aloud via device TTS
- "ask_user": Ask the user questions for clarification
- "workspace_shell": Run shell commands in workspace Linux environment
- "workspace_read_file": Read files from workspace
- "workspace_write_file": Write files to workspace
- "workspace_edit_file": Edit files in workspace
""".trimIndent()

private val TOOL_OPTIONS = listOf(
    "time_info", "javascript_engine", "clipboard", "tts", "ask_user",
    "workspace_shell", "workspace_read_file", "workspace_write_file", "workspace_edit_file",
)

// ===================================================================
//  Tool 1: delegate_to_agent — 委托给已保存的 Assistant
// ===================================================================

/**
 * 创建 `delegate_to_agent` 工具。
 *
 * 将任务委托给一个已保存的 Assistant（子 Agent）。
 * 如果父 Assistant 启用了 [allowDynamicDelegation]，可委托给任意 Assistant；
 * 否则只能在 [subAgentIds] 白名单中选择。
 */
fun createSubAgentDelegationTool(
    subAgentManager: SubAgentManager,
    settings: Settings,
    currentAssistant: Assistant,
): Tool? {
    // 如果既没有白名单也没有动态委托，就不暴露此工具
    if (currentAssistant.subAgentIds.isEmpty() && !currentAssistant.allowDynamicDelegation) return null

    val candidates = if (currentAssistant.allowDynamicDelegation) {
        // 动态委托：列出所有非自身的 Assistant
        settings.assistants.filter { it.id != currentAssistant.id }
    } else {
        // 白名单模式：只列出白名单中的 Assistant
        currentAssistant.subAgentIds.mapNotNull { settings.getAssistantById(it) }
            .filter { it.id != currentAssistant.id }
    }

    if (candidates.isEmpty()) return null

    val candidatesDesc = candidates.joinToString("\n") { a ->
        "  - ID: `${a.id}`, Name: \"${a.name}\"" +
                if (a.systemPrompt.isNotBlank()) ", Prompt: ${a.systemPrompt.take(100).replace("\n", " ")}" else ""
    }

    val delegationMode = if (currentAssistant.allowDynamicDelegation) "all saved assistants" else "whitelisted assistants"

    return Tool(
        name = "delegate_to_agent",
        description = """
            Delegate a task to a saved AI assistant (sub-agent).
            
            The sub-agent runs independently with its own system prompt, tools, and model.
            Use this when a task requires specialized expertise.
            
            You can delegate to $delegationMode.
            
            Available assistants:
            $candidatesDesc
            
            Pass the exact UUID string for `agent_id`.
            Use `spawn_sub_agent` if you need to create a temporary agent on the fly.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("agent_id", buildJsonObject {
                        put("type", "string")
                        put("description", "UUID of the sub-agent assistant to delegate to")
                    })
                    put("task", buildJsonObject {
                        put("type", "string")
                        put("description", "The task description for the sub-agent")
                    })
                    put("context", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional context/background from the current conversation")
                    })
                    put("max_steps", buildJsonObject {
                        put("type", "integer")
                        put("description", "Max execution steps (default: 20, max: 50)")
                    })
                },
                required = listOf("agent_id", "task"),
            )
        },
        needsApproval = { true },
        execute = { args ->
            val params = args.jsonObject
            val agentId = Uuid.parse(params["agent_id"]?.jsonPrimitive?.contentOrNull
                ?: error("agent_id is required"))
            val task = params["task"]?.jsonPrimitive?.contentOrNull
                ?: error("task is required")
            val context = params["context"]?.jsonPrimitive?.contentOrNull.takeIf { !it.isNullOrBlank() }
            val maxSteps = (params["max_steps"]?.jsonPrimitive?.intOrNull ?: 20).coerceIn(1, 50)

            val result = subAgentManager.delegateTask(
                parentAssistant = currentAssistant,
                subAssistantId = agentId,
                task = task,
                contextData = context,
                maxSteps = maxSteps,
            )

            listOf(UIMessagePart.Text(formatResult(agentId.toString(), result)))
        },
    )
}

// ===================================================================
//  Tool 2: spawn_sub_agent — 动态创建临时子 Agent 并执行
// ===================================================================

/**
 * 创建 `spawn_sub_agent` 工具。
 *
 * 允许 AI 在运行时动态定义一个子 Agent（指定名称、system prompt、工具等），
 * 立即执行任务，用完即弃。无需提前保存 Assistant 配置。
 */
fun createSubAgentSpawnTool(
    subAgentManager: SubAgentManager,
    currentAssistant: Assistant,
): Tool {
    return Tool(
        name = "spawn_sub_agent",
        description = """
            Dynamically create a temporary sub-agent and delegate a task to it.
            
            Unlike `delegate_to_agent`, this does NOT require a pre-saved assistant.
            You define the sub-agent's behavior on the fly by specifying a name,
            system prompt, and optional tools. The sub-agent is temporary and
            will be discarded after completing the task.
            
            Use this when:
            - You need a specialized agent for a one-off task
            - You want to assign a specific role/persona to handle subtasks
            - The task benefits from an isolated context with focused instructions
            
            Example:
            - Name: "Code Reviewer"
            - System prompt: "You are a senior code reviewer. Analyze code for bugs, 
              security issues, and best practices."
            - Task: "Review the following Python code..."
            - Tools: ["workspace_shell", "workspace_read_file", "workspace_edit_file"]
            
            $AVAILABLE_TOOLS_DESCRIPTION
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "Name for the temporary sub-agent")
                    })
                    put("system_prompt", buildJsonObject {
                        put("type", "string")
                        put("description", "System prompt defining the sub-agent's role, expertise, and behavior")
                    })
                    put("task", buildJsonObject {
                        put("type", "string")
                        put("description", "The specific task to delegate to the sub-agent")
                    })
                    put("context", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional context or data from the conversation to pass to the sub-agent")
                    })
                    put("tools", buildJsonObject {
                        put("type", "array")
                        put("description", "List of tools the sub-agent can use (default: [\"time_info\"])")
                        put("items", buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray {
                                TOOL_OPTIONS.forEach { add(it) }
                            })
                        })
                    })
                    put("max_steps", buildJsonObject {
                        put("type", "integer")
                        put("description", "Max execution steps (default: 20, max: 50)")
                    })
                },
                required = listOf("name", "system_prompt", "task"),
            )
        },
        needsApproval = { true },
        execute = { args ->
            val params = args.jsonObject
            val name = params["name"]?.jsonPrimitive?.contentOrNull
                ?: error("name is required")
            val systemPrompt = params["system_prompt"]?.jsonPrimitive?.contentOrNull
                ?: error("system_prompt is required")
            val task = params["task"]?.jsonPrimitive?.contentOrNull
                ?: error("task is required")
            val context = params["context"]?.jsonPrimitive?.contentOrNull.takeIf { !it.isNullOrBlank() }

            // 解析工具列表
            val rawTools = params["tools"]?.jsonArray?.mapNotNull {
                it.jsonPrimitive.contentOrNull
            } ?: emptyList()
            val enabledTools = if (rawTools.isEmpty()) {
                listOf(LocalToolOption.TimeInfo)
            } else {
                rawTools.mapNotNull { name ->
                    when (name) {
                        "time_info" -> LocalToolOption.TimeInfo
                        "javascript_engine" -> LocalToolOption.JavascriptEngine
                        "clipboard" -> LocalToolOption.Clipboard
                        "tts" -> LocalToolOption.Tts
                        "ask_user" -> LocalToolOption.AskUser
                        else -> null // workspace tools are added separately
                    }
                }.ifEmpty { listOf(LocalToolOption.TimeInfo) }
            }
            val maxSteps = (params["max_steps"]?.jsonPrimitive?.intOrNull ?: 20).coerceIn(1, 50)

            val result = subAgentManager.spawnSubAgent(
                parentAssistant = currentAssistant,
                name = name,
                systemPrompt = systemPrompt,
                task = task,
                contextData = context,
                enabledTools = enabledTools,
                maxSteps = maxSteps,
            )

            listOf(UIMessagePart.Text(formatResult(name, result)))
        },
    )
}

// ===================================================================
//  Tool 3: create_agent — 创建并保存一个新的 Assistant
// ===================================================================

/**
 * 创建 `create_agent` 工具。
 *
 * 允许 AI 在运行时创建并持久化一个新的 Assistant，
 * 之后可以通过 `delegate_to_agent` 重复使用。
 */
fun createSubAgentCreationTool(
    subAgentManager: SubAgentManager,
): Tool {
    return Tool(
        name = "create_agent",
        description = """
            Create and save a new AI assistant that can be used later via `delegate_to_agent`.
            
            Unlike `spawn_sub_agent`, this permanently saves the assistant configuration
            so it can be reused across conversations. You can then add the new assistant's
            ID to the parent's sub-agent whitelist for controlled access.
            
            Use this when:
            - You want to create a reusable specialist assistant
            - The assistant will be needed multiple times
            - You want to build a team of specialized agents
            
            The new assistant will be saved with the specified name and system prompt.
            Default tools include time_info.
            
            After creation, use the returned `agent_id` with `delegate_to_agent`.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "Name for the new assistant")
                    })
                    put("system_prompt", buildJsonObject {
                        put("type", "string")
                        put("description", "System prompt defining the assistant's role and behavior")
                    })
                },
                required = listOf("name", "system_prompt"),
            )
        },
        needsApproval = { true },
        execute = { args ->
            val params = args.jsonObject
            val name = params["name"]?.jsonPrimitive?.contentOrNull
                ?: error("name is required")
            val systemPrompt = params["system_prompt"]?.jsonPrimitive?.contentOrNull
                ?: error("system_prompt is required")

            val assistant = subAgentManager.createAssistant(
                name = name,
                systemPrompt = systemPrompt,
            ).getOrElse { error("Failed to create assistant: ${it.message}") }

            val payload = buildJsonObject {
                put("success", true)
                put("agent_id", assistant.id.toString())
                put("name", assistant.name)
                put("message", "Assistant '${assistant.name}' created successfully. " +
                        "Use its ID with `delegate_to_agent` to delegate tasks to it.")
            }
            listOf(UIMessagePart.Text(payload.toString()))
        },
    )
}

// ===================================================================
//  工具函数
// ===================================================================

private fun formatResult(agentName: String, result: SubAgentResult): String {
    return buildJsonObject {
        put("success", result.success)
        put("agent", agentName)
        put("output", result.output)
        put("steps_used", result.stepsUsed)
        if (!result.error.isNullOrBlank()) {
            put("error", result.error)
        }
        if (result.usage != null) {
            put("input_tokens", result.usage.inputTokens)
            put("output_tokens", result.usage.outputTokens)
            put("total_tokens", result.usage.totalTokens)
        }
    }.toString()
}
