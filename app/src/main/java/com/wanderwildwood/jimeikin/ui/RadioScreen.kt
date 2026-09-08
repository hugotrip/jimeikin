package com.wanderwildwood.jimeikin.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wanderwildwood.jimeikin.ExternalMediaRepository
import com.wanderwildwood.jimeikin.ExternalMediaState
import com.wanderwildwood.jimeikin.CalmMusicAccessibilityService
import com.mudita.mmd.components.bottom_sheet.ModalBottomSheetMMD
import com.mudita.mmd.components.bottom_sheet.rememberModalBottomSheetMMDState
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DecimalFormat

enum class RadioCommand { NEXT, PREVIOUS, TOGGLE_POWER, STOP, FORCE_PLAY }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadioScreen(
    onPausePlayback: () -> Unit,
    isAppPlaying: Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mediaState by ExternalMediaRepository.mediaState.collectAsState()

    var showAccessibilitySheet by remember { mutableStateOf(false) }
    var showNotificationSheet by remember { mutableStateOf(false) }

    val accessibilitySheetState = rememberModalBottomSheetMMDState()
    val notificationSheetState = rememberModalBottomSheetMMDState()

    val seenOnTuner by ExternalMediaRepository.tunedFrequency.collectAsState()
    val weTurnedItOn by ExternalMediaRepository.radioLaunched.collectAsState()

    // Told by the notification where there is one to read, and otherwise inferred: this app
    // launched the tuner and the accessibility service has read a frequency off its display,
    // which between them mean the radio is on. Without the second half, somebody who never
    // granted notification access would watch this screen go on offering to turn on a radio
    // that was already playing.
    val isRadioActive = mediaState.packageName.contains("radio", ignoreCase = true) ||
            mediaState.packageName.contains("fm", ignoreCase = true) ||
            (weTurnedItOn && seenOnTuner != null)

    // Whether this phone has a tuner at all, asked once. Everything on this screen works by
    // driving the phone's own FM app, so on a phone without one there is nothing to drive -
    // and the screen used to find that out last, after asking for the accessibility
    // permission, which is the most alarming thing this app could ask a stranger for and was
    // being asked for a radio that did not exist.
    val hasTuner = remember { findRadioPackage(context) != null }

    // Reading the tuner's notification is how this screen knows the radio is on and what it is
    // tuned to. That is worth having and it is not worth blocking on: it used to be demanded
    // before the tuner would launch at all, so somebody who granted the accessibility
    // permission - the one that actually works the tuner - and declined this one got a button
    // that did nothing, for ever. The radio turns on without it; the screen just cannot say so.
    val canReadStatus = isNotificationListenerEnabled(context)

    if (!isRadioActive) {
        EmptyRadioState(
            hasTuner = hasTuner,
            canReadStatus = canReadStatus,
            onGrantReadStatus = { showNotificationSheet = true },
            onPowerOn = {
                if (!hasTuner) {
                    // Nothing to ask for.
                } else if (!isAccessibilityServiceEnabled(context, CalmMusicAccessibilityService::class.java)) {
                    showAccessibilitySheet = true
                } else {
                    scope.launch {
                        val packageName = findRadioPackage(context)
                        if (packageName != null) {
                            if (isAppPlaying) onPausePlayback()

                            ExternalMediaRepository.setRadioLaunched(true)
                            launchSystemRadioApp(context, packageName)

                            // Come back when the radio is actually on, rather than after a
                            // fixed five seconds. A tuner being opened for the first time puts
                            // up a permission request of its own, and the old blind delay
                            // pulled the screen away mid-dialog - so the tuner never started,
                            // and trying again did exactly the same thing. If it never comes
                            // on, stay out of the way and leave the person in the tuner.
                            var waited = 0L
                            while (waited < 15000L && !ExternalMediaRepository.value.packageName.contains(packageName)) {
                                delay(500)
                                waited += 500
                            }
                            if (ExternalMediaRepository.value.packageName.contains(packageName)) {
                                val myIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                                myIntent?.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                                myIntent?.putExtra("FROM_RADIO_TUNER", true)
                                context.startActivity(myIntent)
                            }
                        }
                    }
                }
            }
        )
    } else {
        ActiveRadioState(
            context = context,
            mediaState = mediaState,
            targetPackage = mediaState.packageName
        )
    }

    // 1. Accessibility Permission Sheet
    if (showAccessibilitySheet) {
        ModalBottomSheetMMD(
            onDismissRequest = { showAccessibilitySheet = false },
            sheetState = accessibilitySheetState
        ) {
            PermissionSheetContent(
                title = "Control permission required",
                description = "Music Box needs the accessibility permission to work the FM tuner. Enable 'Music Box radio helper' in the phone's settings.",
                buttonText = "Open accessibility settings",
                onConfirm = {
                    showAccessibilitySheet = false
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                },
                onCancel = { showAccessibilitySheet = false }
            )
        }
    }

    if (showNotificationSheet) {
        ModalBottomSheetMMD(
            onDismissRequest = { showNotificationSheet = false },
            sheetState = notificationSheetState
        ) {
            PermissionSheetContent(
                title = "Read status permission",
                description = "Music Box reads the radio app's now-playing notification to show the frequency and whether it is on. Allow notification access for Music Box.",
                buttonText = "Open notification settings",
                onConfirm = {
                    showNotificationSheet = false
                    val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                },
                onCancel = { showNotificationSheet = false }
            )
        }
    }
}

@Composable
fun PermissionSheetContent(
    title: String,
    description: String,
    buttonText: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        TextMMD(
            text = title,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        TextMMD(
            text = description,
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(24.dp))

        ButtonMMD(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            onClick = onConfirm
        ) {
            TextMMD(buttonText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButtonMMD(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            onClick = onCancel
        ) {
            TextMMD("Cancel", fontSize = 18.sp, fontWeight = FontWeight.Normal)
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<*>): Boolean {
    val expectedComponentName = ComponentName(context, serviceClass)
    val enabledServicesSetting = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false

    val splitter = TextUtils.SimpleStringSplitter(':')
    splitter.setString(enabledServicesSetting)
    while (splitter.hasNext()) {
        val componentNameString = splitter.next()
        val enabledComponent = ComponentName.unflattenFromString(componentNameString)
        if (enabledComponent != null && enabledComponent == expectedComponentName) {
            return true
        }
    }
    return false
}

@Composable
fun EmptyRadioState(
    hasTuner: Boolean,
    canReadStatus: Boolean = true,
    onGrantReadStatus: () -> Unit = {},
    onPowerOn: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (!hasTuner) {
            TextMMD("No FM radio on this phone", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            TextMMD(
                "This screen works the phone's own FM tuner. Nothing here needs a network, " +
                    "and nothing here can be installed - a phone either has the radio or it does not.",
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
            )
            return@Column
        }

        // Outlined rather than filled. A 120dp black disc was the largest solid area anywhere
        // in the app, which on an e-ink panel is the slowest thing to paint and the most
        // likely to ghost; the ring reads as the same button and costs a hundredth of the ink.
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                .clickable { onPowerOn() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.PowerSettingsNew,
                "Turn the radio on",
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        TextMMD("Turn on FM Radio", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        TextMMD("Tap to launch tuner", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)

        if (!canReadStatus) {
            Spacer(modifier = Modifier.height(24.dp))
            TextMMD(
                text = "This screen cannot tell whether the radio is on. Tap to allow that.",
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.clickable { onGrantReadStatus() },
            )
        }
    }
}

@Composable
fun ActiveRadioState(
    context: Context,
    mediaState: ExternalMediaState,
    targetPackage: String
) {
    var systemFrequency by remember { mutableStateOf<Float?>(null) }
    var isScanning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // What the accessibility service read off the tuner's own display, preferred only where
    // the notification carries no number of its own.
    val seenOnTuner by ExternalMediaRepository.tunedFrequency.collectAsState()

    LaunchedEffect(isScanning) {
        if (isScanning) {
            delay(10000)
            isScanning = false
        }
    }

    LaunchedEffect(mediaState.title, mediaState.artist) {
        val rawText = "${mediaState.title} ${mediaState.artist}"
        val regex = Regex("(\\d{2,3}(?:\\.\\d)?)")
        val allMatches = regex.findAll(rawText)

        var foundMatch = false
        for (match in allMatches) {
            val parsed = match.value.toFloatOrNull()
            if (parsed != null && parsed >= 87.0f && parsed <= 108.0f) {
                foundMatch = true
                if (isScanning) {
                    if (systemFrequency == null || systemFrequency != parsed) {
                        systemFrequency = parsed
                        isScanning = false
                    }
                } else {
                    systemFrequency = parsed
                }
                break
            }
        }
        if (!foundMatch) systemFrequency = null
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Not having notification access is a supported way to use this screen, not a
            // fault: the radio turns on and tunes without it. This used to shout
            // "Notification access revoked" in red at anybody who had simply never granted it.
            if (!isNotificationListenerEnabled(context)) {
                Spacer(modifier = Modifier.height(8.dp))
                TextMMD(
                    text = "Showing what the tuner last displayed.",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            } else if (!mediaState.title.contains("FM Radio", ignoreCase = true)) {
                Spacer(modifier = Modifier.height(8.dp))
                if (!mediaState.title.matches(Regex(".*\\d{2,3}.*"))) {
                    TextMMD(mediaState.title, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = {
                    isScanning = true
                    performCommand(context, RadioCommand.PREVIOUS, targetPackage)
                }, modifier = Modifier.size(64.dp)) {
                    Icon(Icons.Outlined.SkipPrevious, "Scan down", modifier = Modifier.size(48.dp))
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextMMD(
                    text = (systemFrequency ?: seenOnTuner)
                        ?.let { DecimalFormat("0.0").format(it) }
                        ?: "FM",
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Bold,
                    color = if(isScanning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = {
                    isScanning = true
                    performCommand(context, RadioCommand.NEXT, targetPackage)
                }, modifier = Modifier.size(64.dp)) {
                    Icon(Icons.Outlined.SkipNext, "Scan up", modifier = Modifier.size(48.dp))
                }
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .clickable {
                        performCommand(context, RadioCommand.TOGGLE_POWER, targetPackage)
                        ExternalMediaRepository.setRadioLaunched(false)
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.PowerSettingsNew,
                    "Turn the radio off",
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            TextMMD("Turn off the radio", fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private fun isNotificationListenerEnabled(context: Context): Boolean {
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    return flat != null && flat.contains(context.packageName)
}

private fun performCommand(context: Context, command: RadioCommand, packageName: String) {
    if (ExternalMediaRepository.value.packageName == packageName &&
        command != RadioCommand.STOP && command != RadioCommand.FORCE_PLAY) {
        when (command) {
            RadioCommand.NEXT -> ExternalMediaRepository.skipToNext()
            RadioCommand.PREVIOUS -> ExternalMediaRepository.skipToPrevious()
            RadioCommand.TOGGLE_POWER -> ExternalMediaRepository.togglePlayPause()
            else -> {}
        }
        return
    }

    if (command == RadioCommand.STOP) {
        val offIntents = listOf(
            "fmradio.turnoff",
            "com.android.fmradio.turnoff",
            "fmradio.stop",
            "com.caf.fmradio.FMOFF"
        )
        offIntents.forEach { action ->
            val intent = Intent(action)
            intent.setPackage(packageName)
            context.sendBroadcast(intent)
        }
        return
    }

    val keyEventCode = when (command) {
        RadioCommand.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
        RadioCommand.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        RadioCommand.TOGGLE_POWER -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        RadioCommand.FORCE_PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
    }

    sendTargetedMediaKey(context, keyEventCode, packageName)
}

private fun sendTargetedMediaKey(context: Context, keyCode: Int, packageName: String) {
    val pm = context.packageManager
    val queryIntent = Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(packageName)
    val receivers = pm.queryBroadcastReceivers(queryIntent, 0)

    val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
    intent.setPackage(packageName)

    if (receivers.isNotEmpty()) {
        val receiver = receivers[0]
        intent.component = ComponentName(receiver.activityInfo.packageName, receiver.activityInfo.name)
    }

    intent.putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
    context.sendBroadcast(intent)

    intent.putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_UP, keyCode))
    context.sendBroadcast(intent)
}

private fun findRadioPackage(context: Context): String? {
    val candidates = listOf("com.android.fmradio", "com.mediatek.fmradio", "com.caf.fmradio")
    return candidates.firstOrNull {
        context.packageManager.getLaunchIntentForPackage(it) != null
    }
}

private fun launchSystemRadioApp(context: Context, packageName: String) {
    try {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) context.startActivity(intent)
    } catch (_: Exception) { }
}