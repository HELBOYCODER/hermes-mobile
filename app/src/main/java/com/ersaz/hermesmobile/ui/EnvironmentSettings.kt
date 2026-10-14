package com.ersaz.hermesmobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.ersaz.hermesmobile.core.EnvironmentVariables
import com.ersaz.hermesmobile.core.Settings

@Composable
fun EnvironmentSettings(settings: Settings) {
    val version by settings.version.collectAsState()
    val entries = remember(version) { settings.envVars() }
    var original by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    SectionTitle("Environment · متغیرهای محیطی")
    Hint("کلیدها و مقدارها را اینجا ذخیره کنید، نه در چت. برای فرمان‌های جدید لینوکس اعمال می‌شوند؛ تب ترمینال یا سرویس در حال اجرا را دوباره باز کنید.\nSave keys here, outside chat. New Linux processes receive them; restart existing terminals/services.")
    entries.forEach { (key, _) ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(modifier = Modifier.weight(1f), onClick = { original = key; name = key; value = entries[key].orEmpty(); show = false; message = "" }) {
                Text("$key  ••••••••", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            TextButton(onClick = {
                runCatching { settings.sandboxEnv = EnvironmentVariables.serialize(entries - key) }
                    .onSuccess { if (original == key) { original = null; name = ""; value = "" }; message = "Deleted · حذف شد" }
                    .onFailure { message = "Could not save · ذخیره نشد" }
            }) { Text("Delete · حذف") }
        }
    }
    OutlinedTextField(name, { name = it }, label = { Text("Name · نام، مثل API_KEY") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value, { value = it }, label = { Text("Value · مقدار") }, singleLine = true,
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { show = !show }) { Text(if (show) "Hide · پنهان" else "Show · نمایش") }
        Button(onClick = {
            val key = name.trim()
            message = when {
                !EnvironmentVariables.validName(key) -> "نام باید با حرف انگلیسی یا _ شروع شود؛ فقط حروف، عدد و _ مجازند."
                '\n' in value || '\r' in value || '\u0000' in value -> "مقدار باید یک خط باشد."
                key != original && entries.containsKey(key) -> "این نام وجود دارد؛ برای ویرایش روی آن بزنید."
                else -> runCatching {
                    val updated = entries.toMutableMap()
                    original?.let { updated.remove(it) }
                    updated[key] = value
                    settings.sandboxEnv = EnvironmentVariables.serialize(updated)
                    original = null; name = ""; value = ""; show = false
                    "Saved · ذخیره شد"
                }.getOrElse { "Could not save · ذخیره نشد" }
            }
        }) { Text(if (original == null) "Add · افزودن" else "Save · ذخیره") }
        if (original != null) TextButton(onClick = { original = null; name = ""; value = ""; show = false }) { Text("Cancel · لغو") }
    }
    if (message.isNotBlank()) Text(message)
    Hint("مقدارها با Android Keystore رمزگذاری می‌شوند و در بکاپ معمولی قرار نمی‌گیرند. نام متغیر را در فرمان استفاده کنید، مثل \$API_KEY. ابزارها همچنان می‌توانند از کلیدها برای درخواست‌های شما استفاده کنند.\nValues are encrypted on this device and excluded from ordinary backups. Use variable names in commands, such as \$API_KEY.")
}
