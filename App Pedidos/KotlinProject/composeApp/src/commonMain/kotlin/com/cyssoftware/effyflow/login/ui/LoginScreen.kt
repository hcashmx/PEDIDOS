package com.cyssoftware.effyflow.login.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cyssoftware.effyflow.login.viewmodel.LoginViewModel
import com.cyssoftware.effyflow.login.data.LoginRepository

@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit = {},
    onConfig: () -> Unit = {},
    repository: LoginRepository = remember { LoginRepository() },
) {
    val viewModel = remember(repository) { LoginViewModel(repository) }
    val uiState by viewModel.uiState.collectAsState()
    val savedEmail by viewModel.savedEmail.collectAsState()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    LaunchedEffect(savedEmail) {
        if (savedEmail.isNotEmpty()) {
            email = savedEmail
        }
    }
    // 🎨 Fondo con gradiente
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surface
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        // 🪟 Tarjeta del formulario
        Card(
            modifier = Modifier
                .widthIn(min = 320.dp, max = 400.dp)
                .padding(horizontal = 16.dp, vertical = 24.dp)
                .clip(RoundedCornerShape(24.dp)),
            elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 🧭 Título
                Text(
                    text = "Bienvenido a EffyFlow",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    ),
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "Inicia sesión para continuar",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )

                AnimatedVisibility(uiState.message != null) {
                    Text(
                        text = uiState.message ?: "",
                        color = if (uiState.messageIsError) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .fillMaxWidth()
                    )
                }

                // 📨 Campo de email
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Usuario") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(Modifier.height(12.dp))

                // 🔐 Campo de contraseña
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Contraseña") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(Modifier.height(12.dp))
                // 🔘 Botón de ingreso
                Button(
                    onClick = {
                        if (email.isNotBlank() && password.isNotBlank()) {
                            viewModel.login(email, password)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    enabled = !uiState.isLoading && uiState.catalogReady,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (uiState.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Ingresar", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    }
                }

                OutlinedButton(
                    onClick = { viewModel.synchronizeUsers(email, password) },
                    enabled = !uiState.isLoading && email.isNotBlank() && password.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Sincronizar usuarios") }
                if (!uiState.catalogReady) Text("La provisión inicial requiere usuario y contraseña online.", style=MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))

                // 🔄 Mensajes de estado
                TextButton(
                    onClick = onConfig,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Configurar servidor")
                }

                if (uiState.authorizedUsers.isNotEmpty()) {
                    Text(uiState.authorizedUsers.joinToString("\n"), style=MaterialTheme.typography.bodySmall,
                        modifier=Modifier.heightIn(max=64.dp).verticalScroll(rememberScrollState()))
                    Spacer(Modifier.height(12.dp))
                }
                // ✅ Login exitoso
                if (uiState.success) {
                    LaunchedEffect(Unit) {
                        password = ""
                        onLoginSuccess()
                    }
                }
            }
        }
    }
}
