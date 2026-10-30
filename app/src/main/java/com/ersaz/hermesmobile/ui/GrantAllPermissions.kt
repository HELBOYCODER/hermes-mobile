package com.ersaz.hermesmobile.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.ersaz.hermesmobile.ui.theme.LocalSkin

/**
 * One "Grant all permissions" button for the first page. It triggers a
 * single RequestMultiplePermissions batch with every runtime permission
 * Hermes actually declares in AndroidManifest.xml (checked 2026-10-30).
 * Android itself still grants or denies EACH permission individually —
 * the button only starts the batch; a denial here changes nothing else.
 *
 * Requested by this button (6):
 *   POST_NOTIFICATIONS (notifications), RECORD_AUDIO (voice input),
 *   READ_CALENDAR + WRITE_CALENDAR (calendar tools),
 *   READ_CONTACTS (contacts), ACCESS_COARSE_LOCATION (weather/location).
 *
 * Deliberately NOT requested:
 *   - SMS (SEND_SMS/READ_SMS/RECEIVE_SMS): not declared in the manifest
 *     and not used anywhere in the app — adding it would be a new
 *     permission Hermes does not need.
 *   - CAMERA, ACCESS_FINE_LOCATION, READ_MEDIA_IMAGES/VIDEO/AUDIO:
 *     not declared.
 *   - WRITE_EXTERNAL_STORAGE: legacy permission capped at SDK 28 in the
 *     manifest; not runtime-requestable on modern Android. File access
 *     goes through the system file pickers and the All-files-access
 *     setting instead.
 *   - MANAGE_EXTERNAL_STORAGE, REQUEST_INSTALL_PACKAGES,
 *     REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, QUERY_ALL_PACKAGES: special
 *     access that Android does not grant through a permission dialog at
 *     all — each needs its own Settings screen (All-files access is
 *     already opened from Settings → Sandbox). The note below says so.
 * Normal permissions (INTERNET, ACCESS_NETWORK_STATE, WAKE_LOCK,
 * RECEIVE_BOOT_COMPLETED, VIBRATE, SET_ALARM, FOREGROUND_SERVICE) are
 * granted automatically at install and need no request.
 */
val GRANT_ALL_PERMISSIONS: List<String> = listOf(
    Manifest.permission.POST_NOTIFICATIONS,
    Manifest.permission.RECORD_AUDIO,
    Manifest.permission.READ_CALENDAR,
    Manifest.permission.WRITE_CALENDAR,
    Manifest.permission.READ_CONTACTS,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

private fun permissionLabel(p: String): String = when (p) {
    Manifest.permission.POST_NOTIFICATIONS -> "Notifications"
    Manifest.permission.RECORD_AUDIO -> "Microphone (voice input)"
    Manifest.permission.READ_CALENDAR -> "Read calendar"
    Manifest.permission.WRITE_CALENDAR -> "Write calendar"
    Manifest.permission.READ_CONTACTS -> "Contacts"
    Manifest.permission.ACCESS_COARSE_LOCATION -> "Approximate location (weather)"
    else -> p.substringAfterLast('.')
}

@Composable
fun GrantAllPermissionsButton() {
    val skin = LocalSkin.current
    val ctx = LocalContext.current
    var results by remember { mutableStateOf<Map<String, Boolean>?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results = it }
    fun isGranted(p: String) =
        results?.get(p) ?: (ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED)
    Column {
        Button(onClick = { launcher.launch(GRANT_ALL_PERMISSIONS.toTypedArray()) }) { Text("Grant all permissions") }
        if (results != null) {
            Column(Modifier.padding(top = 6.dp)) {
                GRANT_ALL_PERMISSIONS.forEach { p ->
                    Text(
                        "${if (isGranted(p)) "✓" else "✗"} ${permissionLabel(p)}",
                        color = if (isGranted(p)) skin.ok else skin.warn, fontSize = 12.5.sp,
                    )
                }
            }
            Text(
                "Android decided each permission above individually; denied ones can be retried here or in system Settings. All-files access, installing apps, and battery-optimization exemption are special settings Android never grants from a button like this — they are opened from Settings when actually needed, and SMS is never requested (Hermes does not declare or use it).",
                color = skin.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
            )
        } else {
            Text(
                "Requests notifications, microphone, calendar, contacts, and approximate location in one batch — Android still asks you about each one separately. SMS is not requested: Hermes does not declare or use it.",
                color = skin.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
