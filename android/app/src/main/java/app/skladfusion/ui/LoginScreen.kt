package app.skladfusion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import app.skladfusion.BuildConfig
import app.skladfusion.Server
import app.skladfusion.repo

@Composable
fun LoginScreen() {
    var server by remember { mutableStateOf(Server.url) }
    var editServer by remember { mutableStateOf(false) }
    if (server.isEmpty() || editServer) {
        ServerScreen(server, onCancel = if (server.isEmpty()) null else ({ editServer = false })) { server = it; editServer = false }
        return
    }
    UsersLogin(onChangeServer = if (Server.editable) ({ editServer = true }) else null)
}

/** Адрес сервера: первый запуск сборки без адреса или смена сервера на экране входа. */
@Composable
private fun ServerScreen(current: String, onCancel: (() -> Unit)?, onDone: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(current.removePrefix("https://")) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(BuildConfig.APP_NAME, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Navy, modifier = Modifier.padding(top = 24.dp, bottom = 24.dp))
        Text("Адрес сервера склада", fontSize = 18.sp, modifier = Modifier.padding(bottom = 12.dp))
        OutlinedTextField(
            value = text, onValueChange = { text = it; error = null }, singleLine = true,
            placeholder = { Text("sklad.example.com") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            enabled = !busy && text.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(56.dp),
            onClick = {
                val url = Server.normalize(text)
                if (url == null) { error = "Нужен адрес вида sklad.example.com (только HTTPS)"; return@Button }
                busy = true
                scope.launch {
                    error = Server.probe(url)
                    busy = false
                    if (error == null) {
                        if (current.isNotEmpty() && url != current) confirm = url
                        else { Server.change(ctx, url); onDone(url) }
                    }
                }
            },
        ) { Text("Подключиться", fontSize = 18.sp) }
        if (onCancel != null) TextButton(onClick = onCancel) { Text("Отмена") }
        if (busy) CircularProgressIndicator(Modifier.padding(12.dp))
        error?.let { Text(it, color = Bad, modifier = Modifier.padding(top = 12.dp)) }
    }
    confirm?.let { url ->
        val pending = ctx.repo.state.value.pending
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Сменить сервер?") },
            text = {
                Text("Данные прежнего склада на телефоне будут стёрты" +
                    (if (pending > 0) ", в том числе $pending неотправленных действий" else "") + ". Приложение перезапустится.")
            },
            confirmButton = { TextButton(onClick = { Server.change(ctx, url) }) { Text("Сменить") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun UsersLogin(onChangeServer: (() -> Unit)?) {
    val ctx = LocalContext.current
    val repo = ctx.repo
    val scope = rememberCoroutineScope()
    var users by remember { mutableStateOf(repo.cachedUsers()) }
    var name by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching { users = repo.loadUsers() }.onFailure { if (users.isEmpty()) error = "Нет связи с сервером склада" }
    }

    LaunchedEffect(Unit) { app.skladfusion.Updater.check() }
    Column(Modifier.fillMaxSize().statusBarsPadding()) { UpdateBanner()
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(BuildConfig.APP_NAME, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Navy, modifier = Modifier.padding(top = 24.dp, bottom = 24.dp))
        val n = name
        if (n == null) {
            Text("Кто работает?", fontSize = 18.sp, modifier = Modifier.padding(bottom = 12.dp))
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(users) { u ->
                    Card(Modifier.fillMaxWidth().clickable { name = u; pin = ""; error = null }) {
                        Text(u, fontSize = 20.sp, modifier = Modifier.padding(18.dp))
                    }
                }
            }
            if (users.isEmpty() && error == null) CircularProgressIndicator()
            if (onChangeServer != null) {
                TextButton(onClick = onChangeServer, modifier = Modifier.padding(top = 12.dp)) {
                    Text("Сервер: " + Server.url.substringAfter("://") + " · сменить", fontSize = 13.sp)
                }
            }
        } else {
            Text(n, fontSize = 22.sp, fontWeight = FontWeight.Medium)
            Text("●".repeat(pin.length).ifEmpty { "Введите PIN" }, fontSize = 28.sp, modifier = Modifier.padding(vertical = 16.dp))
            val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "OK"))
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    row.forEach { k ->
                        val action = {
                            when (k) {
                                "⌫" -> pin = pin.dropLast(1)
                                "OK" -> if (pin.length >= 4 && !busy) {
                                    busy = true
                                    scope.launch {
                                        runCatching { repo.login(n, pin) }.onFailure { error = it.message; pin = "" }
                                        busy = false
                                    }
                                }
                                else -> if (pin.length < 8) pin += k
                            }
                        }
                        if (k == "OK") Button(onClick = action, modifier = Modifier.height(64.dp).padding(0.dp)) { Text(k, fontSize = 22.sp) }
                        else OutlinedButton(onClick = action, modifier = Modifier.height(64.dp)) { Text(k, fontSize = 24.sp) }
                    }
                }
            }
            if (busy) CircularProgressIndicator(Modifier.padding(12.dp))
            TextButton(onClick = { name = null }) { Text("Другой сотрудник") }
        }
        error?.let { Text(it, color = Bad, modifier = Modifier.padding(top = 12.dp)) }
    }
}
}
