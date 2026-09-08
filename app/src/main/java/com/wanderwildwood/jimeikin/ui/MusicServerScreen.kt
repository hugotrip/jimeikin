package com.wanderwildwood.jimeikin.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.jimeikin.data.SubsonicConfig
import kotlinx.coroutines.delay

/**
 * Where a Navidrome — or any Subsonic — server is entered.
 *
 * Three fields and one button. Connecting reads the whole library in one go rather than
 * asking as you browse, so what the server holds is on the Songs, Albums and Artists screens
 * beside the music on the card, and stays listed when the phone is away from the network;
 * playing one then needs the network, which the dotted rule under its row says.
 */
@Composable
fun MusicServerScreen(
    config: SubsonicConfig?,
    isBusy: Boolean,
    statusMessage: String?,
    onConnectClick: (SubsonicConfig) -> Unit,
    onForgetClick: () -> Unit,
) {
    var address by rememberSaveable { mutableStateOf(config?.baseUrl ?: "") }
    var user by rememberSaveable { mutableStateOf(config?.user ?: "") }
    var password by rememberSaveable { mutableStateOf(config?.password ?: "") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = address,
            onValueChange = { address = it },
            label = { TextMMD(text = "Address") },
            placeholder = { TextMMD(text = "192.168.1.10:4533") },
            singleLine = true,
            enabled = !isBusy,
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = user,
            onValueChange = { user = it },
            label = { TextMMD(text = "Username") },
            singleLine = true,
            enabled = !isBusy,
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = password,
            onValueChange = { password = it },
            label = { TextMMD(text = "Password") },
            singleLine = true,
            enabled = !isBusy,
            visualTransformation = PasswordVisualTransformation(),
        )

        Spacer(modifier = Modifier.height(20.dp))

        val entered = SubsonicConfig(address, user, password)
        ButtonMMD(
            onClick = { onConnectClick(entered) },
            modifier = Modifier.fillMaxWidth(),
            enabled = entered.isComplete && !isBusy,
        ) {
            TextMMD(
                text = if (config == null) "Connect" else "Connect again",
                fontSize = 16.sp,
            )
        }

        if (statusMessage != null) {
            Spacer(modifier = Modifier.height(16.dp))
            TextMMD(text = statusMessage, fontSize = 14.sp)
        }

        Spacer(modifier = Modifier.height(24.dp))

        TextMMD(
            text = "The password is kept on this phone. It is not sent to the server: each " +
                "request carries a one-time signature made from it instead. Anyone who can " +
                "read your network can still replay a request, so a server reachable from " +
                "outside the house wants https.",
            fontSize = 13.sp,
        )

        if (config != null) {
            Spacer(modifier = Modifier.weight(1f))
            ForgetServerRow(onForget = onForgetClick)
        }
    }
}

/** The row asks. Nothing on the server is touched; its songs leave this phone's library. */
@Composable
private fun ForgetServerRow(onForget: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(4000)
            armed = false
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (armed) { armed = false; onForget() } else armed = true }
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        TextMMD(
            text = if (armed) {
                "Forget this server — its songs leave the library; tap again"
            } else {
                "Forget this server"
            },
            fontSize = 16.sp,
            fontWeight = if (armed) FontWeight.Bold else FontWeight.Normal,
        )
    }
}
