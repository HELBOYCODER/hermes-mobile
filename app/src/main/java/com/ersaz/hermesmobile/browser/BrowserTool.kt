package com.ersaz.hermesmobile.browser

import com.ersaz.hermesmobile.agent.tools.Tool
import com.ersaz.hermesmobile.agent.tools.ToolContext
import com.ersaz.hermesmobile.agent.tools.bool
import com.ersaz.hermesmobile.agent.tools.fail
import com.ersaz.hermesmobile.agent.tools.int
import com.ersaz.hermesmobile.agent.tools.obj
import com.ersaz.hermesmobile.agent.tools.ok
import com.ersaz.hermesmobile.agent.tools.str
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

class BrowserTool : Tool(
    "browser", "browser",
    "Control a real web browser (Chrome engine) with a few tabs. Workflow: open/navigate → snapshot (numbers every interactive element and shows page text) → " +
        "click/type/select/press by ref number → snapshot again (refs change after the page changes). Also: text (full page text), eval (run JavaScript), " +
        "screenshot (saves a JPEG; look at it with vision_analyze), scroll, back/forward/reload, wait, tabs/new_tab/switch_tab/close_tab, " +
        "cookies (names + a file with the Cookie header for curl in the shell — values never shown), set_cookie. If a page needs a login, CAPTCHA or 2-factor, " +
        "call handover: the browser opens for the user and the call returns when they tap Done; then continue with a fresh snapshot. " +
        "Only http(s) pages. Prefer web_search/web_extract for simple reading; use this for interactive or JavaScript-heavy sites.",
    "🌐",
) {
    override val parameters = obj(
        "action" to str("What to do", listOf("open", "navigate", "snapshot", "click", "type", "select", "press", "scroll", "text", "eval", "screenshot",
            "back", "forward", "reload", "wait", "tabs", "new_tab", "switch_tab", "close_tab", "cookies", "set_cookie", "handover")),
        "url" to str("URL for open/navigate/new_tab"),
        "ref" to int("Element ref number from the latest snapshot (click/type/select)"),
        "text" to str("Text to type (type)"),
        "value" to str("Option text/value (select) or cookie value (set_cookie)"),
        "submit" to bool("type: press Enter / submit the form afterwards"),
        "clear" to bool("type: replace existing content (default true)"),
        "key" to str("press: Enter, Escape, Tab, Backspace, ArrowDown, ArrowUp, ArrowLeft, ArrowRight"),
        "direction" to str("scroll: down | up | top | bottom", listOf("down", "up", "top", "bottom")),
        "amount" to int("scroll pixels (default 800) / wait seconds (default 2, max 20)"),
        "js" to str("JavaScript expression for eval"),
        "tab" to int("Tab id (default: the active tab)"),
        "name" to str("Cookie name (set_cookie)"),
        "domain" to str("Cookie domain (set_cookie; default current host)"),
        "message" to str("handover: what the user should do, shown on screen"),
        required = listOf("action"),
    )

    override suspend fun run(args: JSONObject, ctx: ToolContext): String {
        val b = ctx.runtime.browser
        val action = args.optString("action")
        return try {
            when (action) {
                "tabs" -> JSONObject().put("active", b.activeId).put("tabs", JSONArray(b.tabList.map { JSONObject().put("id", it.id).put("title", it.title).put("url", it.url) })).toString()
                "new_tab" -> b.snapshot(b.newTab(args.optString("url").ifBlank { null }))
                "switch_tab" -> if (b.switchTo(args.optInt("tab"))) b.statusLine(b.tab(null)) else fail("No such tab")
                "close_tab" -> if (b.close(args.optInt("tab", b.activeId))) ok("closed" to true) else fail("No such tab")
                "open", "navigate" -> {
                    val url = args.optString("url").ifBlank { return fail("url is required") }
                    val t = b.tab(args.optInt("tab").takeIf { it > 0 })
                    b.navigate(t, url)
                    b.snapshot(t)
                }
                "handover" -> handover(b, args, ctx)
                else -> {
                    val t = b.tab(args.optInt("tab").takeIf { it > 0 })
                    when (action) {
                        "snapshot" -> b.snapshot(t)
                        "text" -> b.text(t, 20_000)
                        "click" -> b.click(t, args.optInt("ref", -1).also { if (it < 0) return fail("ref is required") }).let { it + "\n" + b.snapshot(t, 3500) }
                        "type" -> b.type(t, args.optInt("ref", -1).also { if (it < 0) return fail("ref is required") }, args.optString("text"), args.optBoolean("submit", false), args.optBoolean("clear", true))
                        "select" -> b.select(t, args.optInt("ref", -1), args.optString("value"))
                        "press" -> b.pressKey(t, args.optString("key").ifBlank { "Enter" })
                        "scroll" -> b.scroll(t, args.optString("direction", "down"), args.optInt("amount", 800))
                        "eval" -> b.evalJson(t, args.optString("js").ifBlank { return fail("js is required" ) }).take(20_000)
                        "back" -> b.back(t)
                        "forward" -> b.forward(t)
                        "reload" -> b.reload(t)
                        "wait" -> { delay(args.optInt("amount", 2).coerceIn(1, 20) * 1000L); b.statusLine(t) }
                        "screenshot" -> {
                            val f = b.screenshot(t)
                            ok("path" to ctx.runtime.sandbox.guestPath(f), "note" to "Use vision_analyze on this path to look at it.")
                        }
                        "cookies" -> {
                            val (names, file) = b.cookiesFile(t)
                            ok("cookie_names" to JSONArray(names), "env_file" to file?.let { ctx.runtime.sandbox.guestPath(it) },
                                "usage" to "In the shell: . <env_file> && curl -H \"Cookie: \$COOKIE_HEADER\" <url>")
                        }
                        "set_cookie" -> {
                            val ok = b.setCookie(t, args.optString("name"), args.optString("value"), args.optString("domain").ifBlank { null }, "/", true, false)
                            if (ok) ok("set" to args.optString("name")) else fail("Could not set cookie")
                        }
                        else -> fail("Unknown action '$action'")
                    }
                }
            }
        } catch (e: IllegalArgumentException) { fail(e.message ?: "bad request")
        } catch (e: IllegalStateException) { fail(e.message ?: "browser error") }
    }

    private suspend fun handover(b: BrowserManager, args: JSONObject, ctx: ToolContext): String {
        val t = b.tab(null)
        ctx.listener.onStatus("🌐 Browser opened for you — finish the step, then tap Done.")
        val result = b.requestHandover(args.optString("message").ifBlank { "Complete the step in the page, then tap Done." })
        return if (result) b.snapshot(t) else "The user did not finish within 10 minutes. ${b.statusLine(t)}"
    }

    override fun preview(args: JSONObject): String = listOf(args.optString("action"), args.optString("url").ifBlank { args.optString("text") }).filter { it.isNotBlank() }.joinToString(" ").take(90)
}
