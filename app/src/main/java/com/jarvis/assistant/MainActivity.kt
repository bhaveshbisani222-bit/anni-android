package com.jarvis.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.jarvis.assistant.audio.JarvisAudioEngine
import com.jarvis.assistant.brain.CommandRouter
import com.jarvis.assistant.data.AssistantMode
import com.jarvis.assistant.data.JarvisPrefs
import com.jarvis.assistant.data.JarvisRuntimeState
import com.jarvis.assistant.service.JarvisWakeService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = JarvisPrefs(this)
        JarvisRuntimeState.setEnrolledCount(prefs.getEnrolledSampleCount())

        if (prefs.wakeWordEnabled && JarvisApp.hasMicPermission(this)) {
            JarvisWakeService.startService(this)
        }

        setContent {
            Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF070B14)) {
                JarvisDashboardScreen(prefs = prefs)
            }
        }
    }
}

@Composable
fun JarvisDashboardScreen(prefs: JarvisPrefs) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ui by JarvisRuntimeState.uiState.collectAsState()
    val commandRouter = remember { CommandRouter(context) }

    var userName by remember { mutableStateOf(prefs.userName) }
    var backendUrl by remember { mutableStateOf(prefs.backendUrl) }
    var geminiKey by remember { mutableStateOf(prefs.geminiApiKey) }
    var voiceLockEnabled by remember { mutableStateOf(prefs.voiceLockEnabled) }
    var voiceThreshold by remember { mutableStateOf(prefs.voiceThreshold) }
    var textCommand by remember { mutableStateOf("") }
    var resumeTick by remember { mutableStateOf(0) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                resumeTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val requiredPerms = remember {
        buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.CALL_PHONE)
            add(Manifest.permission.READ_CONTACTS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    val permsGranted = remember(resumeTick) {
        requiredPerms.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        resumeTick++
        if (JarvisApp.hasMicPermission(context)) {
            prefs.wakeWordEnabled = true
            JarvisWakeService.startService(context)
        } else {
            Toast.makeText(context, "Mic permission zaroori hai!", Toast.LENGTH_SHORT).show()
        }
    }

    val accentColor = when (ui.mode) {
        AssistantMode.LISTENING_COMMAND -> Color(0xFF10B981)
        AssistantMode.VERIFYING_VOICE -> Color(0xFFF59E0B)
        AssistantMode.THINKING -> Color(0xFF8B5CF6)
        AssistantMode.SPEAKING -> Color(0xFF38BDF8)
        AssistantMode.DENIED -> Color(0xFFEF4444)
        AssistantMode.PASSIVE_WAKE -> Color(0xFF06B6D4)
        AssistantMode.STOPPED -> Color(0xFF64748B)
    }

    val pulse by rememberInfiniteTransition(label = "hudPulse").animateFloat(
        initialValue = 0.9f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "hudPulseScale"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF070B14), Color(0xFF0D172A), Color(0xFF070B14))
                )
            )
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "A.N.N.I. ANDROID",
                    color = Color(0xFF06B6D4),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    letterSpacing = 1.5.sp
                )
                Text(
                    text = "Siri-Style Lock Screen & Voiceprint AI",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp
                )
            }
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (ui.isServiceRunning) Color(0x2210B981) else Color(0x22EF4444)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    text = if (ui.isServiceRunning) "SHIELD ON" else "OFFLINE",
                    color = if (ui.isServiceRunning) Color(0xFF10B981) else Color(0xFFEF4444),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        // Central Orb + Live Dialogue
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F192E)),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(140.dp)
                        .clip(CircleShape)
                        .clickable {
                            if (!JarvisApp.hasMicPermission(context)) {
                                permLauncher.launch(requiredPerms)
                            } else {
                                JarvisWakeService.startService(context, JarvisWakeService.ACTION_MANUAL_LISTEN)
                            }
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val r = (size.minDimension / 2f) * 0.72f * pulse
                        drawCircle(color = accentColor.copy(alpha = 0.2f), radius = r * 1.2f)
                        drawCircle(color = accentColor, radius = r, style = Stroke(width = 5f))
                        drawCircle(color = accentColor.copy(alpha = 0.55f), radius = r * 0.7f, style = Stroke(width = 2.5f))
                    }
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Speak",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = ui.statusText,
                    color = accentColor,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = ui.lastReplyText,
                    color = Color.White,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            if (!JarvisApp.hasMicPermission(context)) {
                                permLauncher.launch(requiredPerms)
                            } else if (ui.isServiceRunning) {
                                prefs.wakeWordEnabled = false
                                JarvisWakeService.startService(context, JarvisWakeService.ACTION_STOP)
                            } else {
                                prefs.wakeWordEnabled = true
                                JarvisWakeService.startService(context, JarvisWakeService.ACTION_START)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (ui.isServiceRunning) Color(0xFF334155) else Color(0xFF06B6D4),
                            contentColor = if (ui.isServiceRunning) Color.White else Color.Black
                        )
                    ) {
                        Icon(Icons.Default.Shield, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.size(6.dp))
                        Text(if (ui.isServiceRunning) "Stop Shield" else "Start Shield", fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = {
                            if (!JarvisApp.hasMicPermission(context)) {
                                permLauncher.launch(requiredPerms)
                            } else {
                                JarvisWakeService.startService(context, JarvisWakeService.ACTION_MANUAL_LISTEN)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF38BDF8))
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.size(6.dp))
                        Text("Speak Now")
                    }
                }
            }
        }

        // Quick Text Command Box
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = textCommand,
                onValueChange = { textCommand = it },
                placeholder = { Text("Try: Torch on karo / Alarm 7 AM / Naye message padho", fontSize = 12.sp) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF06B6D4),
                    unfocusedBorderColor = Color(0xFF334155)
                )
            )
            IconButton(
                onClick = {
                    val cmd = textCommand.trim()
                    if (cmd.isNotBlank()) {
                        textCommand = ""
                        scope.launch {
                            val outcome = commandRouter.handleTextCommand(cmd, prefs)
                            JarvisRuntimeState.appendInteraction(outcome.userTranscript, outcome.replyText)
                        }
                    }
                }
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send", tint = Color(0xFF06B6D4))
            }
        }

        // Voiceprint Security ("Sirf Meri Awaz Pe")
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F192E)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = Color(0xFFF59E0B))
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = "Voiceprint Lock (Sirf Meri Awaz Pe)",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }

                val totalSteps = JarvisRuntimeState.ENROLL_PROMPTS.size
                val currentStepIdx = ui.enrolledSamples.coerceAtMost(totalSteps - 1)
                val nextPrompt = JarvisRuntimeState.ENROLL_PROMPTS[currentStepIdx]

                Text(
                    text = if (ui.enrolledSamples >= totalSteps) {
                        "Voice Match Complete (${ui.enrolledSamples}/$totalSteps) ✅ Sirf aapki awaz pe Anni respond karegi."
                    } else {
                        "Siri & Gemini Voice Match: Step ${ui.enrolledSamples + 1}/$totalSteps — neeche diya phrase saaf bolein:"
                    },
                    color = if (ui.enrolledSamples >= totalSteps) Color(0xFF10B981) else Color(0xFF94A3B8),
                    fontSize = 12.sp
                )

                // 5-Step Progress Dots (Siri / Gemini style)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (step in 0 until totalSteps) {
                        val done = step < ui.enrolledSamples
                        val active = step == ui.enrolledSamples && ui.isEnrolling
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(6.dp)
                                .background(
                                    color = when {
                                        done -> Color(0xFF10B981)
                                        active -> Color(0xFFF59E0B)
                                        else -> Color(0xFF1E293B)
                                    },
                                    shape = RoundedCornerShape(3.dp)
                                )
                        )
                    }
                }

                // Guided Phrase Box
                Surface(
                    color = Color(0xFF0A1120),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = if (ui.enrolledSamples >= totalSteps) "Enrolled Voiceprint Ready" else "Boliye (Step ${currentStepIdx + 1}/$totalSteps):",
                            color = Color(0xFF64748B),
                            fontSize = 11.sp
                        )
                        Text(
                            text = if (ui.enrolledSamples >= totalSteps) "Aap chahein toh aur samples add ya reset kar sakte hain." else nextPrompt,
                            color = Color(0xFF38BDF8),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        if (ui.enrollFeedback.isNotBlank()) {
                            Text(
                                text = ui.enrollFeedback,
                                color = Color(0xFFF59E0B),
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Only Respond to My Voice", color = Color.White, fontSize = 14.sp)
                    Switch(
                        checked = voiceLockEnabled,
                        onCheckedChange = {
                            voiceLockEnabled = it
                            prefs.voiceLockEnabled = it
                        }
                    )
                }

                Text(
                    text = "Voice Match Strictness: ${(voiceThreshold * 100).toInt()}%",
                    color = Color(0xFFCBD5E1),
                    fontSize = 12.sp
                )
                Slider(
                    value = voiceThreshold,
                    onValueChange = {
                        voiceThreshold = it
                        prefs.voiceThreshold = it
                    },
                    valueRange = 0.25f..0.65f,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFF06B6D4), activeTrackColor = Color(0xFF06B6D4))
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            if (!JarvisApp.hasMicPermission(context)) {
                                permLauncher.launch(requiredPerms)
                            } else {
                                JarvisWakeService.startService(context, JarvisWakeService.ACTION_ENROLL_SAMPLE)
                                Toast.makeText(
                                    context,
                                    "Chime ke baad bolein: $nextPrompt",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        },
                        enabled = !ui.isEnrolling,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B), contentColor = Color.Black)
                    ) {
                        val btnLabel = when {
                            ui.isEnrolling -> "Listening..."
                            ui.enrolledSamples >= totalSteps -> "Add Extra Sample"
                            else -> "Record Step ${ui.enrolledSamples + 1}/$totalSteps"
                        }
                        Text(btnLabel, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            prefs.clearOwnerEmbeddings()
                            JarvisAudioEngine.reloadVoiceprints(prefs)
                            JarvisRuntimeState.setEnrolledCount(0)
                            JarvisRuntimeState.setEnrolling(false, "")
                            JarvisRuntimeState.setVoiceBadge("Voiceprint Cleared")
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444))
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        // Permissions & Lock Screen Checklist
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F192E)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Lock Screen & Siri Permissions Setup",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )

                PermissionActionRow(
                    title = "1. Mic, Call, Contacts & Notifications",
                    granted = permsGranted,
                    onClick = { permLauncher.launch(requiredPerms) }
                )

                val overlayOk = remember(resumeTick) {
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
                }
                PermissionActionRow(
                    title = "2. Display Over Lock Screen (Overlay)",
                    granted = overlayOk,
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        }
                    }
                )

                val pm = context.getSystemService(PowerManager::class.java)
                val batteryOk = remember(resumeTick) {
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                        pm.isIgnoringBatteryOptimizations(context.packageName)
                }
                PermissionActionRow(
                    title = "3. Keep Alive 24x7 (Battery Unrestricted)",
                    granted = batteryOk,
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        }
                    }
                )

                OutlinedButton(
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("4. Enable Notification Reader (WhatsApp/SMS)", fontSize = 12.sp, color = Color(0xFF38BDF8))
                }

                OutlinedButton(
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("5. Set A.N.N.I. as Default Digital Assistant", fontSize = 12.sp, color = Color(0xFF38BDF8))
                }
            }
        }

        // Backend & Gemini Key Settings
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F192E)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Settings, contentDescription = null, tint = Color(0xFF06B6D4))
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("AI Brain & Cloud Settings", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }

                OutlinedTextField(
                    value = userName,
                    onValueChange = { userName = it },
                    label = { Text("Your Name (e.g. Sir / Bhavesh)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                )

                OutlinedTextField(
                    value = backendUrl,
                    onValueChange = { backendUrl = it },
                    label = { Text("A.N.N.I. Cloud URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                )

                OutlinedTextField(
                    value = geminiKey,
                    onValueChange = { geminiKey = it },
                    label = { Text("Gemini API Key (Recommended for Lock-Screen Audio)") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                )

                Button(
                    onClick = {
                        prefs.userName = userName
                        prefs.backendUrl = backendUrl
                        prefs.geminiApiKey = geminiKey
                        Toast.makeText(context, "Settings saved!", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF06B6D4), contentColor = Color.Black)
                ) {
                    Text("Save Settings", fontWeight = FontWeight.Bold)
                }
            }
        }

        // Conversation History
        if (ui.history.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F192E)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Recent Activity", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Divider(color = Color(0xFF1E293B))
                    ui.history.takeLast(8).forEach { item ->
                        Text(
                            text = "${item.speaker}: ${item.text}",
                            color = if (item.speaker == "You") Color(0xFF38BDF8) else Color(0xFFE2E8F0),
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun PermissionActionRow(
    title: String,
    granted: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, color = Color(0xFFE2E8F0), fontSize = 13.sp, modifier = Modifier.weight(1f))
        Icon(
            imageVector = if (granted) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = null,
            tint = if (granted) Color(0xFF10B981) else Color(0xFFF59E0B),
            modifier = Modifier.size(20.dp)
        )
    }
}
