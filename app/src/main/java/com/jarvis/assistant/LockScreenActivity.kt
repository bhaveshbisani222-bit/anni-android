package com.jarvis.assistant

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.data.AssistantMode
import com.jarvis.assistant.data.JarvisRuntimeState
import com.jarvis.assistant.service.JarvisWakeService

/**
 * Overlay HUD displayed even when the phone is locked upon hearing "Hey Jarvis" or Assist trigger.
 */
class LockScreenActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableLockScreenFlags()

        if (intent?.getBooleanExtra(EXTRA_TRIGGER_LISTEN, false) == true) {
            JarvisWakeService.startService(this, JarvisWakeService.ACTION_MANUAL_LISTEN)
        }

        setContent {
            Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF050811)) {
                LockScreenHud(
                    onSpeakAgain = {
                        JarvisWakeService.startService(this, JarvisWakeService.ACTION_MANUAL_LISTEN)
                    },
                    onDismiss = { finish() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_TRIGGER_LISTEN, false)) {
            JarvisWakeService.startService(this, JarvisWakeService.ACTION_MANUAL_LISTEN)
        }
    }

    @Suppress("DEPRECATION")
    private fun enableLockScreenFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    companion object {
        const val EXTRA_TRIGGER_LISTEN = "extra_trigger_listen"
    }
}

@Composable
fun LockScreenHud(
    onSpeakAgain: () -> Unit,
    onDismiss: () -> Unit
) {
    val ui by JarvisRuntimeState.uiState.collectAsState()

    val accentColor = when (ui.mode) {
        AssistantMode.LISTENING_COMMAND -> Color(0xFF10B981)
        AssistantMode.VERIFYING_VOICE -> Color(0xFFF59E0B)
        AssistantMode.THINKING -> Color(0xFF8B5CF6)
        AssistantMode.SPEAKING -> Color(0xFF38BDF8)
        AssistantMode.DENIED -> Color(0xFFEF4444)
        else -> Color(0xFF06B6D4)
    }

    val pulse by rememberInfiniteTransition(label = "orbPulse").animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF060A14), Color(0xFF0A1428), Color(0xFF050811))
                )
            )
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "J.A.R.V.I.S. • LOCK-SCREEN HUD",
                color = accentColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0x22FFFFFF)),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text(
                    text = ui.voiceStatusBadge,
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(190.dp)) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val baseR = (size.minDimension / 2f) * 0.68f * pulse
                    drawCircle(
                        color = accentColor.copy(alpha = 0.22f),
                        radius = baseR * 1.25f
                    )
                    drawCircle(
                        color = accentColor,
                        radius = baseR,
                        style = Stroke(width = 6f)
                    )
                    drawCircle(
                        color = accentColor.copy(alpha = 0.6f),
                        radius = baseR * 0.72f,
                        style = Stroke(width = 3f)
                    )
                }
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "JARVIS Mic",
                    tint = Color.White,
                    modifier = Modifier.size(44.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = ui.statusText,
                color = accentColor,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                textAlign = TextAlign.Center
            )

            if (ui.lastHeardText.isNotBlank()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "\"${ui.lastHeardText}\"",
                    color = Color(0xFF94A3B8),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF111C33)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = ui.lastReplyText,
                    color = Color.White,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) {
                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.size(6.dp))
                Text("Dismiss")
            }

            Button(
                onClick = onSpeakAgain,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = accentColor, contentColor = Color.Black)
            ) {
                Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.size(6.dp))
                Text("Speak Again", fontWeight = FontWeight.Bold)
            }
        }
    }
}
