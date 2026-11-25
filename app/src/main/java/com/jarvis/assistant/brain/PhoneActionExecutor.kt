package com.jarvis.assistant.brain

import android.Manifest
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.jarvis.assistant.assistant.JarvisNotificationService
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Executes native Siri-style Android device commands (Calls, Alarms, Torch, Apps, Media, Settings).
 */
class PhoneActionExecutor(private val context: Context) {

    fun placeCall(target: String, userName: String): String {
        val cleanTarget = target.trim()
        if (cleanTarget.isBlank()) {
            val dial = Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(dial)
            return "Phone dialer khol diya hai, $userName."
        }

        val resolvedNumber = if (cleanTarget.any { it.isDigit() } && cleanTarget.count { it.isDigit() } >= 4) {
            cleanTarget.filter { it.isDigit() || it == '+' }
        } else {
            findContactPhone(cleanTarget)
        }

        if (resolvedNumber.isNullOrBlank()) {
            return "$cleanTarget ka number aapke phone contacts me nahi mila, $userName. Kripya contact check karein ya number bolein."
        }

        val hasCallPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        val intent = if (hasCallPerm) {
            Intent(Intent.ACTION_CALL, Uri.parse("tel:$resolvedNumber"))
        } else {
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:$resolvedNumber"))
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return try {
            context.startActivity(intent)
            "$cleanTarget ko call connect kar raha hu, $userName."
        } catch (e: Exception) {
            "Call start karne me dikkat aayi, $userName: ${e.message}"
        }
    }

    fun sendWhatsApp(target: String, message: String, userName: String): String {
        val phone = if (target.isNotBlank()) findContactPhone(target) ?: target.filter { it.isDigit() } else ""
        val encodedMsg = URLEncoder.encode(message, "UTF-8")

        val url = when {
            phone.length >= 7 -> {
                val intl = if (phone.length == 10) "91$phone" else phone.trimStart('+')
                if (message.isNotBlank()) "https://wa.me/$intl?text=$encodedMsg" else "https://wa.me/$intl"
            }
            message.isNotBlank() -> "https://api.whatsapp.com/send?text=$encodedMsg"
            else -> null
        }

        return try {
            if (url != null) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                if (target.isNotBlank()) "WhatsApp par $target ki chat khol di hai, $userName."
                else "WhatsApp message tayyar hai, $userName."
            } else {
                openApp("WhatsApp", userName)
            }
        } catch (_: Exception) {
            openApp("WhatsApp", userName)
        }
    }

    fun sendSms(target: String, message: String, userName: String): String {
        val phone = if (target.isNotBlank()) findContactPhone(target) ?: target.filter { it.isDigit() || it == '+' } else ""
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phone")).apply {
            putExtra("sms_body", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            "SMS compose screen khol diya hai, $userName."
        } catch (_: Exception) {
            "SMS app nahi khul saka, $userName."
        }
    }

    fun setTorch(enable: Boolean, userName: String): String {
        return try {
            val camMgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = camMgr.cameraIdList.firstOrNull { id ->
                val chars = camMgr.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: camMgr.cameraIdList.firstOrNull()

            if (cameraId != null) {
                camMgr.setTorchMode(cameraId, enable)
                if (enable) "Flashlight ON kar di hai, $userName." else "Flashlight OFF kar di hai, $userName."
            } else {
                "Is device par flashlight hardware nahi mila, $userName."
            }
        } catch (e: Exception) {
            "Flashlight control karne me error aaya: ${e.message}"
        }
    }

    fun setAlarm(hour: Int, minute: Int, label: String, userName: String): String {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour.coerceIn(0, 23))
            putExtra(AlarmClock.EXTRA_MINUTES, minute.coerceIn(0, 59))
            putExtra(AlarmClock.EXTRA_MESSAGE, label.ifBlank { "Anni Alarm" })
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            val formatted = String.format(Locale.getDefault(), "%02d:%02d", hour.coerceIn(0, 23), minute.coerceIn(0, 59))
            "$formatted ka alarm set kar diya hai, $userName."
        } catch (_: Exception) {
            "Alarm app available nahi hai, $userName."
        }
    }

    fun setTimer(seconds: Int, label: String, userName: String): String {
        val safeSeconds = seconds.coerceAtLeast(5)
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, safeSeconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, label.ifBlank { "Anni Timer" })
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            val mins = safeSeconds / 60
            val remSec = safeSeconds % 60
            val durStr = if (mins > 0) "$mins minute ${if (remSec > 0) "$remSec second" else ""}" else "$remSec second"
            "$durStr ka timer start kar diya hai, $userName."
        } catch (_: Exception) {
            "Timer app available nahi hai, $userName."
        }
    }

    fun adjustVolume(action: String, userName: String): String {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return when (action.lowercase()) {
            "up", "badhao", "increase" -> {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                audio.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_RAISE, 0)
                "Volume badha diya hai, $userName."
            }
            "down", "kam", "decrease" -> {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                "Volume kam kar diya hai, $userName."
            }
            "mute", "silent" -> {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                "Audio mute kar diya hai, $userName."
            }
            "unmute" -> {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                "Audio unmute kar diya hai, $userName."
            }
            else -> "Volume adjusted, $userName."
        }
    }

    fun controlMedia(action: String, userName: String): String {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val keyCode = when (action.lowercase()) {
            "pause", "stop", "rok" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "play", "resume", "chalao" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "next", "agla" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "pichla" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        return "Media command executed, $userName."
    }

    fun playMusicQuery(query: String, userName: String): String {
        val cleanQuery = query.trim().ifBlank { "Top Hits" }
        return try {
            val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                putExtra(SearchManager.QUERY, cleanQuery)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "$cleanQuery play kar raha hu, $userName."
        } catch (_: Exception) {
            val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
            openUrl("https://music.youtube.com/search?q=$encoded")
            "YouTube Music par $cleanQuery open kar raha hu, $userName."
        }
    }

    fun openSettingsPanel(panelType: String, userName: String): String {
        val intent = when (panelType.lowercase()) {
            "wifi", "wi-fi" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Intent(Settings.Panel.ACTION_WIFI)
                } else {
                    Intent(Settings.ACTION_WIFI_SETTINGS)
                }
            }
            "internet", "data" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                } else {
                    Intent(Settings.ACTION_WIRELESS_SETTINGS)
                }
            }
            "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            else -> Intent(Settings.ACTION_SETTINGS)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return try {
            context.startActivity(intent)
            "${panelType.replaceFirstChar { it.uppercase() }} settings panel khol diya hai, $userName."
        } catch (_: Exception) {
            "Settings open nahi ho saki, $userName."
        }
    }

    fun openMapsNavigation(destination: String, userName: String): String {
        val q = URLEncoder.encode(destination.trim(), "UTF-8")
        val uri = if (destination.isNotBlank()) Uri.parse("google.navigation:q=$q") else Uri.parse("geo:0,0")
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            if (destination.isNotBlank()) "$destination ke liye Google Maps navigation shuru kar diya hai, $userName."
            else "Google Maps khol diya hai, $userName."
        } catch (_: Exception) {
            openUrl("https://www.google.com/maps/search/$q")
            "Maps open kar raha hu, $userName."
        }
    }

    fun openApp(appQuery: String, userName: String): String {
        val q = appQuery.trim().lowercase()
        if (q.isBlank()) return "Kaunsa app kholna hai, $userName?"

        if (q.contains("camera") || q.contains("photo")) {
            return try {
                val camIntent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(camIntent)
                "Camera khol diya hai, $userName."
            } catch (_: Exception) {
                launchByPackageQuery("camera", userName)
            }
        }
        return launchByPackageQuery(q, userName)
    }

    fun listInstalledAppNames(): List<String> {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(mainIntent, 0)
            .mapNotNull { it.loadLabel(pm)?.toString() }
            .distinct()
            .take(50)
    }

    private fun launchByPackageQuery(query: String, userName: String): String {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(mainIntent, 0)

        val aliases = mapOf(
            "insta" to "instagram",
            "yt" to "youtube",
            "wa" to "whatsapp",
            "chrome" to "chrome",
            "gmail" to "gmail",
            "mail" to "gmail",
            "calc" to "calculator",
            "maps" to "maps",
            "spotify" to "spotify",
            "gallery" to "photos"
        )
        val normalized = aliases[query] ?: query

        val matched = apps.firstOrNull {
            val label = it.loadLabel(pm).toString().lowercase()
            label == normalized || label.contains(normalized) || normalized.contains(label)
        }

        if (matched != null) {
            val launchIntent = pm.getLaunchIntentForPackage(matched.activityInfo.packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (launchIntent != null) {
                context.startActivity(launchIntent)
                return "${matched.loadLabel(pm)} khol diya hai, $userName."
            }
        }
        return "$query app is phone par nahi mila, $userName."
    }

    fun searchWeb(query: String, userName: String): String {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        openUrl("https://www.google.com/search?q=$encoded")
        return "Google par \"$query\" search kar raha hu, $userName."
    }

    fun readNotifications(userName: String): String {
        return JarvisNotificationService.buildSpokenSummary(userName)
    }

    fun getBatteryReport(userName: String): String {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return "$userName, phone ki battery abhi $pct percent par hai."
    }

    fun getTimeReport(userName: String): String {
        val now = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
        return "$userName, abhi samay ho raha hai $now."
    }

    fun getDateReport(userName: String): String {
        val today = SimpleDateFormat("EEEE, dd MMMM yyyy", Locale.getDefault()).format(Date())
        return "$userName, aaj ki tareekh hai $today."
    }

    fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun findContactPhone(nameQuery: String): String? {
        val hasPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPerm) return null

        val target = nameQuery.trim().lowercase()
        if (target.isBlank()) return null

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            var partialMatch: String? = null
            while (cursor.moveToNext()) {
                val displayName = cursor.getString(nameIdx)?.lowercase() ?: continue
                val number = cursor.getString(numIdx)?.trim() ?: continue
                if (displayName == target) return number
                if (partialMatch == null && (displayName.contains(target) || target.contains(displayName))) {
                    partialMatch = number
                }
            }
            return partialMatch
        }
        return null
    }
}
