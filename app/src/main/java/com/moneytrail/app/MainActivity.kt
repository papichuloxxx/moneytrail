package com.moneytrail.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { App(vm) } }
    }
}

@Composable
fun App(vm: MainViewModel) {
    val ctx = LocalContext.current
    val perms = arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)
    fun hasPerms() = perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

    var granted by remember { mutableStateOf(hasPerms()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasPerms()
    }
    LaunchedEffect(granted) { if (granted) vm.importInbox() }

    if (granted) {
        TransactionSummaryScreen(vm)
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp)
                .testTag("sms_permission_screen"),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Money Trail", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            Text(
                "To track your EcoCash, NMB Bank and Omari transactions, the app needs permission to read " +
                    "those SMS messages. Everything stays on this phone.",
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { launcher.launch(perms) },
                modifier = Modifier.testTag("allow_sms_permission_button"),
            ) {
                Text("Allow SMS access")
            }
        }
    }
}

@Composable
fun TrackerScreen(vm: TransactionViewModel) {
    TransactionSummaryScreen(vm)
}
