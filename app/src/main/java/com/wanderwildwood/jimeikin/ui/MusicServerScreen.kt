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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.wanderwildwood.jimeikin.data.SubsonicConfig
import kotlinx.coroutines.delay
import com.wanderwildwood.jimeikin.R

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

    // The fields are seeded from the stored login once and then belong to whoever is typing,
    // which is right everywhere except here: forgetting a server used to leave the address and
    // a boxful of password dots sitting under the word "Forgotten", which says the opposite of
    // what has just happened. They were only on screen - nothing was kept - but a screen that
    // looks like it kept your password is not much better than one that did.
    LaunchedEffect(config) {
        if (config == null) {
            address = ""
            user = ""
            password = ""
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        TextFieldMMD(
            modifier = Modifier.fillMaxWidth(),
            value = address,
            onValueChange = { address = it },
            label = { TextMMD(text = stringResource(R.string.library_server_address)) },
            placeholder = { TextMMD(text = "192.168.1.10:4533") },
            singleLine = true,
            enabled = !isBusy,
        )

        Spacer(modifier = Modifier.height(12.dp))

        TextFieldMMD(
            modifier = Modifier.fillMaxWidth(),
            value = user,
            onValueChange = { user = it },
            label = { TextMMD(text = stringResource(R.string.library_server_username)) },
            singleLine = true,
            enabled = !isBusy,
        )

        Spacer(modifier = Modifier.height(12.dp))

        TextFieldMMD(
            modifier = Modifier.fillMaxWidth(),
            value = password,
            onValueChange = { password = it },
            label = { TextMMD(text = stringResource(R.string.library_server_password)) },
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
                text = if (config == null) stringResource(R.string.library_server_connect) else stringResource(R.string.library_server_connect_again),
                style = MaterialTheme.typography.titleSmall,
            )
        }

        if (statusMessage != null) {
            Spacer(modifier = Modifier.height(16.dp))
            TextMMD(text = statusMessage, style = MaterialTheme.typography.labelSmall)
        }

        Spacer(modifier = Modifier.height(24.dp))

        TextMMD(
            text = stringResource(R.string.library_server_password_note),
            style = MaterialTheme.typography.labelSmall,
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
                stringResource(R.string.library_server_forget_armed)
            } else {
                stringResource(R.string.library_server_forget)
            },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (armed) FontWeight.Bold else FontWeight.Normal,
        )
    }
}
