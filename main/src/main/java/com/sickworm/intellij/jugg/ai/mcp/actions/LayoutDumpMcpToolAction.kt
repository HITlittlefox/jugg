package com.sickworm.intellij.jugg.ai.mcp.actions

import com.sickworm.intellij.jugg.ai.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.ai.mcp.McpErrorCode
import com.sickworm.intellij.jugg.ai.mcp.McpJsonSchemaObject
import com.sickworm.intellij.jugg.ai.mcp.McpJsonSchemaProperty
import com.sickworm.intellij.jugg.ai.mcp.McpToolDefinition
import com.sickworm.intellij.jugg.ai.mcp.McpToolResult
import com.sickworm.intellij.jugg.ai.mcp.McpToolStatus

/**
 * LayoutDumpMcpToolAction exposes the `layout-dump` MCP tool.
 * Core logic lives in LayoutDumpHelper and is shared with FigmaLayoutVerifyMcpToolAction.
 */
class LayoutDumpMcpToolAction : McpToolAction {
    override val toolName: String = McpToolActionRegistry.ToolNames.LAYOUT_DUMP

    override val definition: McpToolDefinition = McpToolDefinition(
        name = toolName,
        description = "Dump UI hierarchy to a sparse JSON artifact at data.file. " +
            "interaction (default) prunes nodes for page browsing; ui preserves the captured tree " +
            "for UI validation and structure/geometry references. Supports rootLayout, includeGone, and allWindows.",
        inputSchema = McpJsonSchemaObject(
            properties = mapOf(
                "projectDir" to McpToolSchemas.projectDirProperty,
                "mode" to McpJsonSchemaProperty(
                    type = "string",
                    description = "interaction prunes nodes and promotes children; ui preserves captured hierarchy. " +
                        "Both keep retained node IDs and non-default attributes. Neither guarantees clickability or complete visual reconstruction.",
                    default = "interaction",
                    `enum` = listOf("interaction", "ui"),
                ),
                "rootLayout" to McpJsonSchemaProperty(
                    type = "string",
                    description = "Optional node id to dump only that subtree. Pass the `id` value from previous layout_dump (prefer short id, e.g. \"content\").",
                ),
                "includeGone" to McpJsonSchemaProperty(
                    type = "boolean",
                    description = "When true, include GONE nodes in the output for diagnostics. Default is false.",
                ),
                "allWindows" to McpJsonSchemaProperty(
                    type = "boolean",
                    description = "When true, dump all windows instead of only the top window. Default is false.",
                ),
            ),
            required = listOf("projectDir"),
            additionalProperties = false,
        ),
        outputSchema = McpToolSchemas.baseOutputSchema,
    )

    override fun execute(arguments: Map<String, Any?>, runtime: IMcpRuntime): McpToolResult {
        val mode = arguments["mode"] ?: "interaction"
        if (mode != "interaction" && mode != "ui") {
            return McpToolResult(
                status = McpToolStatus.ERROR,
                message = "layout-dump failed. Reason: mode must be interaction or ui.",
                errorCode = McpErrorCode.INVALID_PARAMS,
            )
        }
        val rootLayout = arguments["rootLayout"] as? String
        // Accept both new ("includeGone") and legacy ("isIncludeGone") param names.
        val includeGone = (arguments["includeGone"] ?: arguments["isIncludeGone"]) as? Boolean ?: false
        val allWindows = (arguments["allWindows"] ?: arguments["isAllWindows"]) as? Boolean ?: false
        return LayoutDumpHelper.dump(runtime, toolName, rootLayout, includeGone, allWindows, mode as String)
    }
}
