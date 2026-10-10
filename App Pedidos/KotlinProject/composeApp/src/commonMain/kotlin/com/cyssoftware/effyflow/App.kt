package com.cyssoftware.effyflow

import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

import com.cyssoftware.effyflow.Theme.EffyFlowTheme
import com.cyssoftware.effyflow.config.AppConfigRepository
import com.cyssoftware.effyflow.config.ServerConfigScreen
import com.cyssoftware.effyflow.pedido.ui.PedidoScreen
import com.cyssoftware.effyflow.home.HomeScreen
import com.cyssoftware.effyflow.login.ui.LoginScreen
import com.cyssoftware.effyflow.pedido.nuevoPedido.PedidoRepository
import com.cyssoftware.effyflow.pedido.nuevoPedido.ui.NuevoPedidoScreen
import com.cyssoftware.effyflow.pedido.nuevoPedido.viewmodel.NuevoPedidoViewModel
import com.cyssoftware.effyflow.pedido.pedidosDia.PedidosDiaRepository
import com.cyssoftware.effyflow.pedido.pedidosDia.ui.PedidosDiaScreen
import com.cyssoftware.effyflow.pedido.pedidosDia.viewmodel.PedidosDiaViewModel
import com.cyssoftware.effyflow.pedido.sincronizacion.ui.SyncScreen
import kotlinx.coroutines.Dispatchers
import com.cyssoftware.effyflow.db.DatabaseProvider
import com.cyssoftware.effyflow.login.data.LoginRepository
import com.cyssoftware.effyflow.pedido.sincronizacion.viewModel.SyncViewModel
import com.cyssoftware.effyflow.ticket.TicketRepository
import com.cyssoftware.effyflow.ticket.TicketScreen
import com.cyssoftware.effyflow.ticket.TicketViewModel

@Composable
fun App() {
    // 1. Inicialización de DB y Repositorios Globales
    val loginRepository = remember { LoginRepository() }
    val configRepository = remember { AppConfigRepository() }
    val ticketRepository = remember { TicketRepository() }
    val pedidoRepository = remember { PedidoRepository() }
    val pedidosDiaRepository = remember { PedidosDiaRepository() }
    var globalDataRefreshKey by remember { mutableStateOf(0) }
    val triggerGlobalRefresh = { globalDataRefreshKey++ }
    // 2. Estados de la Sesión
    // 'isLoading' bloquea la UI hasta que sepamos si hay usuario o no
    var isLoading by remember { mutableStateOf(false) }
    // Aquí guardamos el ID real recuperado de la BD
    var usuarioIdLogueado by remember { mutableStateOf<Long?>(null) }
    // Definimos la ruta inicial dinámicamente
    var startRoute by remember { mutableStateOf("login") }

    val navController = rememberNavController()

    // 3. Lógica de Arranque (Check Session)
    LaunchedEffect(Unit) {
        // Each launch asks for the local password; persisted tokens/verifiers remain independent.
        loginRepository.logout()
        startRoute = if (!loginRepository.hasCatalog() && configRepository.getBaseUrl().isNullOrBlank()) "server_config_required" else "login"

    }

    // Local session revocation (disable/version change) must also leave protected screens.
    LaunchedEffect(usuarioIdLogueado) {
        while (usuarioIdLogueado != null) {
            kotlinx.coroutines.delay(1000)
            val activeSession = kotlinx.coroutines.withContext(Dispatchers.IO) { loginRepository.getOfflineSession() }
            if (activeSession == null) {
                usuarioIdLogueado = null
                navController.navigate("login") { popUpTo(navController.graph.id) { inclusive=true } }
            }
        }
    }

    // 4. ViewModels Dependientes del Usuario
    // Estos se recrean automáticamente si usuarioIdLogueado cambia
    val nuevoPedidoViewModel = remember(usuarioIdLogueado, globalDataRefreshKey) {
        usuarioIdLogueado?.let { id ->
            NuevoPedidoViewModel(
                pedidoRepository = pedidoRepository,
                usuarioIdLogueado = id,
                dispatcher = Dispatchers.Main
            )
        }
    }

    val pedidosDiaViewModel = remember(usuarioIdLogueado) {
        usuarioIdLogueado?.let { id ->
            PedidosDiaViewModel(
                pedidosDiaRepository = pedidosDiaRepository,
                agenteRepository = pedidoRepository,
                usuarioId = id,
                dispatcher = Dispatchers.Main
            )
        }
    }

    DisposableEffect(pedidosDiaViewModel) {
        onDispose { pedidosDiaViewModel?.close() }
    }

    val sessionSyncViewModel = remember(usuarioIdLogueado) {
        usuarioIdLogueado?.let { userId ->
            SyncViewModel(
                getToken = { loginRepository.getSyncTokensForUser(userId) },
                saveTokens = { access, refresh -> loginRepository.updateTokensForUser(userId, access, refresh) },
                onSyncComplete = { triggerGlobalRefresh() }
            )
        }
    }
    DisposableEffect(sessionSyncViewModel) {
        onDispose { sessionSyncViewModel?.close() }
    }

    // 5. Pantalla de Carga (Splash)
    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return // ⛔ Detenemos la ejecución aquí hasta que isLoading sea false
    }

    // 6. Navegación Principal
    NavHost(navController, startDestination = startRoute) {

        composable("login") {
            EffyFlowTheme {
                // ⚠️ Nota: Tu LoginScreen debe recibir el repo o inyectarlo
                LoginScreen(
                    // Si modificaste LoginScreen para aceptar repo, pásalo aquí:
                    // repository = loginRepository,
                    repository = loginRepository,
                    onLoginSuccess = {
                        // 🔄 AL LOGIN EXITOSO: Actualizamos el estado global
                        // Recuperamos la sesión recién guardada para obtener el ID
                        val session = loginRepository.getOfflineSession()
                        usuarioIdLogueado = session?.usuarioId?.toLong()

                        navController.navigate("home") {
                            popUpTo("login") { inclusive = true }
                        }
                    },
                    onConfig = { navController.navigate("server_config") }
                )
            }
        }

        composable("server_config_required") {
            EffyFlowTheme {
                ServerConfigScreen(
                    repository = configRepository,
                    required = true,
                    onSaved = {
                        navController.navigate("login") {
                            popUpTo("server_config_required") { inclusive = true }
                        }
                    }
                )
            }
        }

        composable("server_config") {
            EffyFlowTheme {
                ServerConfigScreen(
                    repository = configRepository,
                    required = false,
                    onSaved = { navController.popBackStack() },
                    onCancel = { navController.popBackStack() }
                )
            }
        }

        composable("home") {
            EffyFlowTheme {
                HomeScreen(
                    repository = loginRepository,
                    onModuloPedido = { navController.navigate("pedidos") },
                    onConfig = { navController.navigate("server_config") },
                    onLogout = {
                        loginRepository.logout()
                        usuarioIdLogueado = null
                        navController.navigate("login") { popUpTo(navController.graph.id) { inclusive=true } }
                    },
                )
            }
        }

        composable("pedidos") {
            EffyFlowTheme {
                PedidoScreen(
                    onBack = {
                        // Logout opcional: si vuelven atrás desde pedidos, ¿van al home o salen?
                        // Asumimos Home
                        navController.navigate("home") {
                            popUpTo("home") { inclusive = true }
                        }
                    },
                    onNuevoPedido = { navController.navigate("nuevo_pedido") },
                    onPedidosDia = { navController.navigate("pedidos_dia") },
                    onSincronizacion = { navController.navigate("sync") },
                    onReimpresion = { navController.navigate("reimpresion") }
                )
            }
        }

        composable("sync") {
            val syncViewModel = sessionSyncViewModel ?: return@composable
            EffyFlowTheme {
                SyncScreen(
                    viewModel = syncViewModel,
                    onBack = {
                    navController.navigate("pedidos") {
                        popUpTo("pedidos") { inclusive = true }
                    }

                })
            }
        }

        composable("nuevo_pedido") {
            EffyFlowTheme {
                //triggerGlobalRefresh()
                if (nuevoPedidoViewModel != null) {
                    NuevoPedidoScreen(


                        viewModel = nuevoPedidoViewModel,
                        onSave = {
                            navController.navigate("home") {
                                popUpTo("pedidos_dia") { inclusive = true }
                            }
                        },
                        onPrintTicket = { pedidoId ->
                            navController.navigate("ticket_screen/$pedidoId")
                        },
                        onBack = {
                            navController.navigate("pedidos") {
                                popUpTo("pedidos") { inclusive = true }
                            }
                        }
                    )
                } else {
                    // Fallback por seguridad
                    SimpleScreen("Cargando datos del usuario...")
                }
            }
        }

        composable(
            route = "ticket_screen/{pedidoId}",
            arguments = listOf(navArgument("pedidoId") { type = NavType.LongType })
        ) { backStackEntry ->
            val pedidoId: Long = backStackEntry.savedStateHandle.run {
                // 1) si viene como Long
                get<Long>("pedidoId")
                // 2) si viene como String
                    ?: get<String>("pedidoId")?.toLongOrNull()
                    // 3) si no existe
                    ?: 0L
            }

            val ticketViewModel = remember(pedidoId) {
                TicketViewModel(pedidoId, ticketRepository)
            }

            TicketScreen(
                viewModel = ticketViewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable("pedidos_dia") {
            EffyFlowTheme {
                if (pedidosDiaViewModel != null) {
                    PedidosDiaScreen(
                        viewModel = pedidosDiaViewModel,
                        onRefreshStates = { sessionSyncViewModel?.syncEstadosPedidos() },
                        refreshingStates = sessionSyncViewModel?.isLoading == true,
                        stateSyncMessage = sessionSyncViewModel?.message.orEmpty(),
                        onPrintTicket = { pedidoId ->
                            navController.navigate("ticket_screen/$pedidoId")
                        },
                        onBack = {
                            navController.navigate("pedidos") {
                                popUpTo("pedidos") { inclusive = true }
                            }
                        })
                } else {
                    SimpleScreen("Error: Sesión no iniciada.")
                }
            }
        }

        composable("reimpresion") { SimpleScreen("🖨️ Reimpresión") }
    }
}

@Composable
fun SimpleScreen(title: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
    }
}

/*
@Composable
fun App() {
    LaunchedEffect(Unit) {
        DatabaseProvider.database
        println("✅ DB inicializada antes de crear repositorios")
    }
    val navController = rememberNavController()
    val ticketRepository = remember { TicketRepository() }
    val pedidoRepository = remember { PedidoRepository() }


    var usuarioIdLogueado by remember { mutableStateOf<Long?>(null) }


    val nuevoPedidoViewModel = remember(usuarioIdLogueado) {
        if (usuarioIdLogueado != null) {
            NuevoPedidoViewModel(
                pedidoRepository = pedidoRepository,
                usuarioIdLogueado = usuarioIdLogueado!!,
                dispatcher = Dispatchers.Main // Correcto para Desktop
            )
        } else {
            null // No hay ViewModel si no hay login
        }
    }

    // 2. Repositorio para PEDIDOS DEL DÍA
    val pedidosDiaRepository = remember { PedidosDiaRepository() }
    // 3. ViewModel para PEDIDOS DEL DÍA (Debe estar vivo durante toda la sesión)
    val pedidosDiaViewModel = remember(usuarioIdLogueado) {
        if (usuarioIdLogueado != null) {
            PedidosDiaViewModel(
                pedidosDiaRepository = pedidosDiaRepository,
                agenteRepository = pedidoRepository, // Reusamos el PedidoRepository para obtener datos de Agente
                usuarioId = usuarioIdLogueado!!,
                dispatcher = Dispatchers.Main
            )
        } else {
            null
        }
    }

    var startRoute by remember { mutableStateOf("login") }



    NavHost(navController, startDestination = "home")/*login*/ {
        composable("login") {
            EffyFlowTheme {
                LoginScreen(onLoginSuccess = {
                    navController.navigate("home") {
                        // Borra la pila hasta el destino "login"
                        popUpTo("login") {
                            // Incluye el destino "login" para que también sea eliminado
                            inclusive = true
                        }
                    }
                })
            }
        }

        composable("home") {
            EffyFlowTheme {
                HomeScreen(
                    onModuloPedido = { navController.navigate("pedidos") },
                )
            }
        }

        composable("pedidos") {
            EffyFlowTheme {
                PedidoScreen(
                    onBack = {
                        navController.navigate("home") {
                            popUpTo("home") { inclusive = true }
                        }
                    },
                    onNuevoPedido = { navController.navigate("nuevo_pedido") },
                    onPedidosDia = { navController.navigate("pedidos_dia") },
                    onSincronizacion = { navController.navigate("sync") },
                    onReimpresion = { navController.navigate("reimpresion") }
                )
            }
        }



        composable("sync") {
            EffyFlowTheme {
                SyncScreen(onBack = {
                    navController.navigate("pedidos") {
                        popUpTo("pedidos") { inclusive = true }
                    }
                })
            }
        }

        composable("nuevo_pedido") {
            EffyFlowTheme {
                // Le pasamos el ViewModel que ya "vive" en la App.
                if (nuevoPedidoViewModel != null) {
                    NuevoPedidoScreen(
                        viewModel = nuevoPedidoViewModel,
                        onSave = {
                            navController.navigate("home") {
                                popUpTo("pedidos_dia") { inclusive = true }
                            }
                        },
                        onPrintTicket = { pedidoId ->
                            val pedido = pedidoId
                            println("dato enviado" + pedido.toString())
                            // Navegas a la pantalla del Ticket pasando el ID
                            navController.navigate("ticket_screen/$pedido")

                            // Opcional: Si quieres borrar la pantalla de 'Nuevo Pedido' del stack
                            // para que al volver del ticket vaya al Home y no al wizard:
                            /*
                            navController.navigate("ticket_screen/$pedidoId") {
                                popUpTo("nuevo_pedido") { inclusive = true }
                            }
                            */
                        },
                        onBack = {
                            navController.navigate("pedidos") {
                                popUpTo("pedidos") { inclusive = true }
                            }
                        }
                    )
                } else {
                    // Esto no debería pasar si la navegación es correcta
                    SimpleScreen("Error: Reinicia sesión.")
                }
            }
        }

        composable(
            route = "ticket_screen/{pedidoId}",
            arguments = listOf(navArgument("pedidoId") { type = NavType.LongType })
        ) { backStackEntry ->

            val pedidoId: Long = backStackEntry.savedStateHandle.run {
                // 1) si viene como Long
                get<Long>("pedidoId")
                // 2) si viene como String
                    ?: get<String>("pedidoId")?.toLongOrNull()
                    // 3) si no existe
                    ?: 0L
            }

            println("valor → $pedidoId")

            val ticketViewModel = remember(pedidoId) {
                TicketViewModel(
                    pedidoId = pedidoId,
                    ticketRepository = ticketRepository
                )
            }

            TicketScreen(
                viewModel = ticketViewModel,
                onBack = { navController.popBackStack() }
            )
        }



        composable("pedidos_dia") {
            EffyFlowTheme {
                if (pedidosDiaViewModel != null) {
                    // Si el ViewModel existe, lo inyectamos en la pantalla
                    PedidosDiaScreen(
                        viewModel = pedidosDiaViewModel,
                        onPrintTicket = { pedidoId ->
                            navController.navigate("ticket_screen/$pedidoId")
                        },
                        onBack = {
                            navController.navigate("pedidos") {
                                popUpTo("pedidos") { inclusive = true }
                            }
                        })
                } else {
                    SimpleScreen("Error: Sesión no iniciada o datos faltantes.")
                }
            }
        }
        composable("reimpresion") { SimpleScreen("🖨️ Reimpresión") }
    }
}


@Composable
fun SimpleScreen(title: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
    }
}*/
