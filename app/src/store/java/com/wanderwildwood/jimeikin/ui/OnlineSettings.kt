package com.wanderwildwood.jimeikin.ui

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable

// The store edition has no online catalogue, and so no settings for one; see [SettingsScreen].

@Suppress("UnusedReceiverParameter")
internal fun LazyListScope.onlineSwitches() = Unit

@Suppress("UnusedReceiverParameter")
internal fun LazyListScope.onlineDownloadRows() = Unit

@Composable
internal fun onlineAccountConnected(): Boolean = false

@Suppress("UnusedReceiverParameter", "UNUSED_PARAMETER")
internal fun LazyListScope.onlineAccountRows(connected: Boolean, onConnectClick: () -> Unit) = Unit

@Suppress("UNUSED_PARAMETER")
@Composable
internal fun OnlineSetUpButton(onClick: () -> Unit) = Unit
