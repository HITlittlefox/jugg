package com.sickworm.intellij.jugg.ai.mcp.actions

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert
import org.junit.Test

class LayoutJsonConverterTest {
    private val converter = LayoutJsonConverter()

    @Test
    fun testNodeLinesRoundTripWithoutSplittingFieldsOrText() {
        val source = parse("""{"windows":[{"title":"窗口","root":{"className":"FrameLayout",
            "bounds":[0,0,360,800],"children":[{"className":"TextView",
            "text":"登录🙂\n\"children\":[{测试}]","padding":[1,2,3,4]},
            {"className":"View","children":[{"className":"Button","text":"确认"}]}]}},
            {"title":"popup","root":{"className":"View"}}],"warnings":["保持\\n诊断"],"truncated":true}""")
        val text = converter.serialize(source)
        Assert.assertEquals(source, parse(text))
        val nodeLines = text.lines().filter { it.startsWith("{\"className\":") }
        Assert.assertEquals(5, nodeLines.size)
        Assert.assertTrue(nodeLines[0].contains("\"bounds\":[0,0,360,800]"))
        Assert.assertTrue(nodeLines[1].contains("\"padding\":[1,2,3,4]"))
        Assert.assertTrue(nodeLines[1].contains("登录🙂"))
        Assert.assertTrue(text.lines().all { it.length == it.trimStart().length })
    }

    @Test
    fun testInteractionPromotesChildrenAndKeepsEverySemanticNodeInOrder() {
        val source = parse("""{"windows":[{"root":{"className":"FrameLayout","id":"_vir_id_root",
            "children":[{"className":"CustomWrapper","id":"_vir_id_wrapper","children":[
              {"className":"TextView","id":"_vir_id_text","text":"<登录> & \"继续\"","alpha":0},
              {"className":"View","id":"real_id"},
              {"className":"ImageView","contentDesc":"头像"},
              {"className":"View","id":"_vir_id_click","clickable":true},
              {"className":"ComposeView","id":"_vir_id_error","message":"Missing <tooling>"},
              {"className":"ImageView","bounds":[0,0,40,40]}]}]}}]}""")
        val output = converter.convert(source, "interaction")
        val root = output.getAsJsonArray("windows")[0].asJsonObject.getAsJsonObject("root")
        Assert.assertEquals("_vir_id_root", root["id"].asString)
        val children = root.getAsJsonArray("children")
        Assert.assertEquals(5, children.size())
        Assert.assertEquals("_vir_id_text", children[0].asJsonObject["id"].asString)
        Assert.assertEquals("<登录> & \"继续\"", children[0].asJsonObject["text"].asString)
        Assert.assertEquals(0, children[0].asJsonObject["alpha"].asInt)
        Assert.assertEquals("real_id", children[1].asJsonObject["id"].asString)
        Assert.assertEquals("头像", children[2].asJsonObject["contentDesc"].asString)
        Assert.assertTrue(children[3].asJsonObject["clickable"].asBoolean)
        Assert.assertEquals("Missing <tooling>", children[4].asJsonObject["message"].asString)
        Assert.assertEquals(1, source.getAsJsonArray("windows")[0].asJsonObject
            .getAsJsonObject("root").getAsJsonArray("children").size())
    }

    @Test
    fun testUiPreservesCapturedFieldsAndLeavesSourceUnchanged() {
        val source = parse("""{"truncated":true,"warnings":["partial"],"rootLayout":"panel",
            "deviceInfo":{"density":3,"scaledDensity":3.5},
            "windows":[{"windowType":"subtree","title":"panel","root":{"className":"View",
            "id":"_vir_id_1","bounds":[1,2,3,4],"children":[{"className":"ImageView",
            "tag":"truncated:node_limit","visibility":"invisible","alpha":0.5,
            "enabled":false,"padding":[1,2,3,4],"textColor":"#FFFF0000"}]}}]}""")
        val expected = source.deepCopy().apply { addProperty("mode", "ui") }
        Assert.assertEquals(expected, converter.convert(source, "ui"))
        Assert.assertFalse(source.has("mode"))
    }

    @Test
    fun testPruningPreservesMetadataAndRemovesEmptyChildren() {
        val source = parse("""{"truncated":false,"warnings":["partial"],"rootLayout":"content",
            "deviceInfo":{"density":2},"windows":[
            {"windowType":"popup","title":"Dialog","root":{"className":"View","children":[
            {"className":"View","id":"_vir_id_1"}]}},
            {"windowType":"activity","title":"Main","root":{"className":"View"}}]}""")
        val output = converter.convert(source, "interaction")
        Assert.assertFalse(output["truncated"].asBoolean)
        Assert.assertEquals(source["warnings"], output["warnings"])
        Assert.assertEquals(source["deviceInfo"], output["deviceInfo"])
        Assert.assertEquals("content", output["rootLayout"].asString)
        val windows = output.getAsJsonArray("windows")
        Assert.assertEquals(2, windows.size())
        Assert.assertEquals("popup", windows[0].asJsonObject["windowType"].asString)
        Assert.assertFalse(windows[0].asJsonObject.getAsJsonObject("root").has("children"))
        Assert.assertEquals(source.getAsJsonArray("windows")[1], windows[1])
    }

    private fun parse(json: String): JsonObject = JsonParser.parseString(json).asJsonObject
}
