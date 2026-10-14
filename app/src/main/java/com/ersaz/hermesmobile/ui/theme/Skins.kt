package com.ersaz.hermesmobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Hermes skins (hermes_cli/skin_engine.py), ported 1:1: palette, spinner faces/verbs,
 * branding and tool prefix. Each maps onto a Material 3 color scheme for the app chrome.
 */
data class Skin(
    val name: String,
    val description: String,
    val light: Boolean,
    val bg: Color,            // completion_menu_bg / status_bar_bg family
    val surface: Color,       // completion_menu_current_bg
    val selection: Color,
    val border: Color,        // banner_border
    val title: Color,         // banner_title
    val accent: Color,        // ui_accent / banner_accent
    val dim: Color,           // banner_dim
    val text: Color,          // banner_text
    val label: Color,         // ui_label
    val ok: Color, val error: Color, val warn: Color,
    val responseBorder: Color,
    val shellDollar: Color,
    val agentName: String = "Hermes Agent",
    val symbol: String = "☤",
    val promptSymbol: String = "❯",
    val toolPrefix: String = "┊",
    val goodbye: String = "Goodbye! ☤",
    val waitingFaces: List<String> = DEFAULT_WAITING,
    val thinkingFaces: List<String> = DEFAULT_THINKING,
    val thinkingVerbs: List<String> = DEFAULT_VERBS,
    val wings: List<Pair<String, String>> = emptyList(),
) {
    companion object {
        val DEFAULT_WAITING = listOf("(｡◕‿◕｡)", "(◕‿◕✿)", "٩(◕‿◕｡)۶", "(✿◠‿◠)", "( ˘▽˘)っ", "♪(´ε` )", "(◕ᴗ◕✿)", "ヾ(＾∇＾)", "(≧◡≦)", "(★ω★)")
        val DEFAULT_THINKING = listOf("(｡•́︿•̀｡)", "(◔_◔)", "(¬‿¬)", "( •_•)>⌐■-■", "(⌐■_■)", "(´･_･`)", "◉_◉", "(°ロ°)", "( ˘⌣˘)♡", "ヽ(>∀<☆)☆", "٩(๑❛ᴗ❛๑)۶", "(⊙_⊙)", "(¬_¬)", "( ͡° ͜ʖ ͡°)", "ಠ_ಠ")
        val DEFAULT_VERBS = listOf("pondering", "contemplating", "musing", "cogitating", "ruminating", "deliberating", "mulling", "reflecting", "processing", "reasoning", "analyzing", "computing", "synthesizing", "formulating", "brainstorming")
    }
}

private fun c(hex: String) = Color(android.graphics.Color.parseColor(hex))
private fun wings(vararg g: String) = g.map { "⟪$it" to "$it⟫" }

object Skins {
    val all = listOf(
        // Hermes Desktop look (apps/desktop DESIGN.md): flat neutral surfaces, hairline strokes, one quiet accent.
        Skin("nous", "Hermes Desktop — flat dark, the new default", false,
            bg = c("#0D1117"), surface = c("#161B22"), selection = c("#1F2630"), border = c("#30363D"), title = c("#F0F6FC"),
            accent = c("#8AB4FF"), dim = c("#7D8590"), text = c("#E6EDF3"), label = c("#9DA7B3"),
            ok = c("#3FB950"), error = c("#F85149"), warn = c("#D29922"), responseBorder = c("#8AB4FF"), shellDollar = c("#8AB4FF"),
            agentName = "Hermes", toolPrefix = "·"),
        Skin("nous-light", "Hermes Desktop — flat light", true,
            bg = c("#FFFFFF"), surface = c("#F4F5F7"), selection = c("#E8EAEE"), border = c("#D0D7DE"), title = c("#0D1117"),
            accent = c("#2F5BFF"), dim = c("#6E7781"), text = c("#1F2328"), label = c("#57606A"),
            ok = c("#1A7F37"), error = c("#CF222E"), warn = c("#9A6700"), responseBorder = c("#2F5BFF"), shellDollar = c("#2F5BFF"),
            agentName = "Hermes", toolPrefix = "·"),
        Skin("default", "Classic Hermes — gold and kawaii", false,
            bg = c("#12121F"), surface = c("#1a1a2e"), selection = c("#3a3a55"), border = c("#CD7F32"), title = c("#FFD700"),
            accent = c("#FFBF00"), dim = c("#B8860B"), text = c("#FFF8DC"), label = c("#DAA520"),
            ok = c("#4caf50"), error = c("#ef5350"), warn = c("#ffa726"), responseBorder = c("#FFD700"), shellDollar = c("#4dabf7")),
        Skin("ares", "War-god theme — crimson and bronze", false,
            bg = c("#1C0B0B"), surface = c("#2A1212"), selection = c("#692620"), border = c("#A93333"), title = c("#C7A96B"),
            accent = c("#DD4A3A"), dim = c("#905151"), text = c("#F1E6CF"), label = c("#C7A96B"),
            ok = c("#4caf50"), error = c("#ef5350"), warn = c("#ffa726"), responseBorder = c("#C7A96B"), shellDollar = c("#DD4A3A"),
            agentName = "Ares Agent", symbol = "⚔", promptSymbol = "⚔", toolPrefix = "╎", goodbye = "Farewell, warrior! ⚔",
            waitingFaces = listOf("(⚔)", "(⛨)", "(▲)", "(<>)", "(/)"), thinkingFaces = listOf("(⚔)", "(⛨)", "(▲)", "(⌁)", "(<>)"),
            thinkingVerbs = listOf("forging", "marching", "sizing the field", "holding the line", "hammering plans", "tempering steel", "plotting impact", "raising the shield"),
            wings = wings("⚔", "▲", "⛨")),
        Skin("mono", "Monochrome — clean grayscale", false,
            bg = c("#161616"), surface = c("#1F1F1F"), selection = c("#505050"), border = c("#5E5E5E"), title = c("#e6edf3"),
            accent = c("#aaaaaa"), dim = c("#606060"), text = c("#c9d1d9"), label = c("#888888"),
            ok = c("#888888"), error = c("#cccccc"), warn = c("#999999"), responseBorder = c("#aaaaaa"), shellDollar = c("#aaaaaa")),
        Skin("slate", "Cool blue — developer-focused", false,
            bg = c("#0E1424"), surface = c("#151C2F"), selection = c("#3A5375"), border = c("#4169e1"), title = c("#7eb8f6"),
            accent = c("#7eb8f6"), dim = c("#545E6B"), text = c("#c9d1d9"), label = c("#8EA8FF"),
            ok = c("#63D0A6"), error = c("#F7A072"), warn = c("#e6a855"), responseBorder = c("#7eb8f6"), shellDollar = c("#7eb8f6"),
            toolPrefix = "┊"),
        Skin("daylight", "Light theme with dark text and cool blue accents", true,
            bg = c("#F8FAFC"), surface = c("#E5EDF8"), selection = c("#D3E0FB"), border = c("#2563EB"), title = c("#0F172A"),
            accent = c("#2563EB"), dim = c("#475569"), text = c("#111827"), label = c("#0F766E"),
            ok = c("#15803D"), error = c("#B91C1C"), warn = c("#B45309"), responseBorder = c("#2563EB"), shellDollar = c("#2563EB"),
            toolPrefix = "│"),
        Skin("warm-lightmode", "Warm light mode — brown/gold text on cream", true,
            bg = c("#FBF7EF"), surface = c("#F5F0E8"), selection = c("#E8DAD0"), border = c("#8B6914"), title = c("#5C3D11"),
            accent = c("#8B4513"), dim = c("#8B7355"), text = c("#2C1810"), label = c("#5C3D11"),
            ok = c("#2E7D32"), error = c("#C62828"), warn = c("#E65100"), responseBorder = c("#8B6914"), shellDollar = c("#8B4513")),
        Skin("poseidon", "Ocean-god theme — deep blue and seafoam", false,
            bg = c("#0A1A30"), surface = c("#0F2440"), selection = c("#2A587F"), border = c("#2A6FB9"), title = c("#A9DFFF"),
            accent = c("#5DB8F5"), dim = c("#44638F"), text = c("#EAF7FF"), label = c("#A9DFFF"),
            ok = c("#4caf50"), error = c("#ef5350"), warn = c("#ffa726"), responseBorder = c("#5DB8F5"), shellDollar = c("#5DB8F5"),
            agentName = "Poseidon Agent", symbol = "Ψ", promptSymbol = "Ψ", toolPrefix = "│", goodbye = "Fair winds! Ψ",
            waitingFaces = listOf("(≈)", "(Ψ)", "(∿)", "(◌)", "(◠)"), thinkingFaces = listOf("(Ψ)", "(∿)", "(≈)", "(⌁)", "(◌)"),
            thinkingVerbs = listOf("charting currents", "sounding the depth", "reading foam lines", "steering the trident", "tracking undertow", "plotting sea lanes", "calling the swell", "measuring pressure"),
            wings = wings("≈", "Ψ", "∿", "◌")),
        Skin("sisyphus", "Sisyphean theme — austere grayscale with persistence", false,
            bg = c("#181818"), surface = c("#202020"), selection = c("#666666"), border = c("#B7B7B7"), title = c("#F5F5F5"),
            accent = c("#E7E7E7"), dim = c("#5C5C5C"), text = c("#D3D3D3"), label = c("#D3D3D3"),
            ok = c("#919191"), error = c("#E7E7E7"), warn = c("#B7B7B7"), responseBorder = c("#B7B7B7"), shellDollar = c("#E7E7E7"),
            agentName = "Sisyphus Agent", symbol = "◉", promptSymbol = "◉", toolPrefix = "│", goodbye = "The boulder waits. ◉",
            waitingFaces = listOf("(◉)", "(◌)", "(◬)", "(⬤)", "(::)"), thinkingFaces = listOf("(◉)", "(◬)", "(◌)", "(○)", "(●)"),
            thinkingVerbs = listOf("finding traction", "measuring the grade", "resetting the boulder", "counting the ascent", "testing leverage", "setting the shoulder", "pushing uphill", "enduring the loop"),
            wings = wings("◉", "◬", "◌", "⬤")),
        Skin("charizard", "Volcanic theme — burnt orange and ember", false,
            bg = c("#0B0503"), surface = c("#2B160E"), selection = c("#5A260D"), border = c("#C75B1D"), title = c("#FFD39A"),
            accent = c("#F29C38"), dim = c("#C58A45"), text = c("#FFF0D4"), label = c("#FFD39A"),
            ok = c("#4caf50"), error = c("#ef5350"), warn = c("#ffa726"), responseBorder = c("#F29C38"), shellDollar = c("#F29C38"),
            agentName = "Charizard Agent", symbol = "✦", promptSymbol = "✦", toolPrefix = "│", goodbye = "Flame out! ✦",
            waitingFaces = listOf("(✦)", "(▲)", "(◇)", "(<>)", "(🔥)"), thinkingFaces = listOf("(✦)", "(▲)", "(◇)", "(⌁)", "(🔥)"),
            thinkingVerbs = listOf("banking into the draft", "measuring burn", "reading the updraft", "tracking ember fall", "setting wing angle", "holding the flame core", "plotting a hot landing", "coiling for lift"),
            wings = wings("✦", "▲", "◌", "◇")),
    )

    fun byName(name: String) = all.firstOrNull { it.name == name } ?: all.first()

    /** Braille caduceus from hermes_cli/banner.py, colored per line by the skin. */
    val CADUCEUS = listOf(
        "⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⢀⣀⡀⠀⣀⣀⠀⢀⣀⡀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀",
        "⠀⠀⠀⠀⠀⠀⢀⣠⣴⣾⣿⣿⣇⠸⣿⣿⠇⣸⣿⣿⣷⣦⣄⡀⠀⠀⠀⠀⠀⠀",
        "⠀⢀⣠⣴⣶⠿⠋⣩⡿⣿⡿⠻⣿⡇⢠⡄⢸⣿⠟⢿⣿⢿⣍⠙⠿⣶⣦⣄⡀⠀",
        "⠀⠀⠉⠉⠁⠶⠟⠋⠀⠉⠀⢀⣈⣁⡈⢁⣈⣁⡀⠀⠉⠀⠙⠻⠶⠈⠉⠉⠀⠀",
        "⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⣴⣿⡿⠛⢁⡈⠛⢿⣿⣦⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀",
        "⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠿⣿⣦⣤⣈⠁⢠⣴⣿⠿⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀",
        "⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠈⠉⠻⢿⣿⣦⡉⠁⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀",
        "⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠘⢷⣦⣈⠛⠃⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀",
        "⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⢠⣴⠦⠈⠙⠿⣦⡄⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀",
        "⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠸⣿⣤⡈⠁⢤⣿⠇⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀",
        "⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠉⠛⠷⠄⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀",
        "⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⢀⣀⠑⢶⣄⡀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀",
        "⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⣿⠁⢰⡆⠈⡿⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀",
        "⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠈⠳⠈⣡⠞⠁⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀",
    )
}

val LocalSkin = staticCompositionLocalOf { Skins.all.first() }

@Composable
fun HermesTheme(skin: Skin, content: @Composable () -> Unit) {
    val scheme = if (skin.light) lightColorScheme(
        primary = skin.accent, onPrimary = Color.White, secondary = skin.label, onSecondary = Color.White,
        tertiary = skin.border, background = skin.bg, onBackground = skin.text, surface = skin.bg, onSurface = skin.text,
        surfaceVariant = skin.surface, onSurfaceVariant = skin.dim, surfaceContainer = skin.surface,
        surfaceContainerHigh = skin.surface, surfaceContainerHighest = skin.selection, surfaceContainerLow = skin.bg,
        outline = skin.border, outlineVariant = skin.selection, error = skin.error, primaryContainer = skin.selection,
        onPrimaryContainer = skin.text, secondaryContainer = skin.selection, onSecondaryContainer = skin.text,
    ) else darkColorScheme(
        primary = skin.accent, onPrimary = Color.Black, secondary = skin.label, onSecondary = Color.Black,
        tertiary = skin.border, background = skin.bg, onBackground = skin.text, surface = skin.bg, onSurface = skin.text,
        surfaceVariant = skin.surface, onSurfaceVariant = skin.dim, surfaceContainer = skin.surface,
        surfaceContainerHigh = skin.surface, surfaceContainerHighest = skin.selection, surfaceContainerLow = skin.bg,
        outline = skin.border, outlineVariant = skin.selection, error = skin.error, primaryContainer = skin.selection,
        onPrimaryContainer = skin.text, secondaryContainer = skin.selection, onSecondaryContainer = skin.text,
    )
    CompositionLocalProvider(LocalSkin provides skin) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
