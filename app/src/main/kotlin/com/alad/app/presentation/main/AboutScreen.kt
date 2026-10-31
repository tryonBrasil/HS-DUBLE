package com.alad.app.presentation.main

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alad.app.R
import com.alad.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit, onNavigateToSettings: () -> Unit = {}) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val version = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "—"
    }.getOrDefault("—")
    AmbientBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Voltar", onBack)
                    Spacer(Modifier.width(14.dp))
                    Text("Sobre", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            },
            bottomBar = {
                NavigationBar(containerColor = DeepSpaceElevated.copy(alpha = 0.98f)) {
                    NavigationBarItem(
                        selected = false, onClick = onBack,
                        icon = { Icon(Icons.Default.Home, contentDescription = null) },
                        label = { Text("Início", fontSize = 10.sp) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = NeonCyan, selectedTextColor = NeonCyan, indicatorColor = NeonCyan.copy(alpha = 0.12f), unselectedIconColor = TextSecondary, unselectedTextColor = TextSecondary)
                    )
                    NavigationBarItem(
                        selected = false, onClick = onNavigateToSettings,
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        label = { Text("Configurações", fontSize = 10.sp) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = NeonCyan, selectedTextColor = NeonCyan, indicatorColor = NeonCyan.copy(alpha = 0.12f), unselectedIconColor = TextSecondary, unselectedTextColor = TextSecondary)
                    )
                    NavigationBarItem(
                        selected = true, onClick = { },
                        icon = { Icon(Icons.Default.Info, contentDescription = null) },
                        label = { Text("Sobre", fontSize = 10.sp) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = NeonCyan, selectedTextColor = NeonCyan, indicatorColor = NeonCyan.copy(alpha = 0.12f), unselectedIconColor = TextSecondary, unselectedTextColor = TextSecondary)
                    )
                }
            }
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Spacer(Modifier.height(8.dp))
                GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), borderColor = NeonCyan, borderAlpha = 0.35f) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(painterResource(R.drawable.ic_hs_logo), "HS Voice Translator", Modifier.size(96.dp).clip(RoundedCornerShape(22.dp)))
                        Spacer(Modifier.height(12.dp))
                        Text("HS Voice Translator", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                        Text("Versão $version", color = TextSecondary, fontSize = 12.sp)
                        Spacer(Modifier.height(12.dp))
                        Text("Tradução de voz em tempo real para seus jogos favoritos.", color = TextSecondary, fontSize = 13.sp)
                    }
                }
                GlassCard(Modifier.fillMaxWidth(), borderColor = NeonCyan, borderAlpha = 0.25f) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        AboutInfoRow(Icons.Default.Code, "Desenvolvido por", "HS Tech")
                        AboutInfoRow(Icons.Default.SystemUpdate, "Atualizações automáticas", "Verificação ao abrir o aplicativo")
                        AboutInfoRow(Icons.Default.Policy, "Licença", "Uso pessoal")
                        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                        TextButton(onClick = { uriHandler.openUri("https://tryonbrasil.github.io/PhilipsRemoteSemAds/") }) {
                            Icon(Icons.Default.Policy, null, tint = NeonCyan)
                            Spacer(Modifier.width(8.dp))
                            Text("Política de privacidade", color = Color.White)
                        }
                        TextButton(onClick = { uriHandler.openUri("https://github.com/tryonBrasil/HS-DUBLE") }) {
                            Icon(Icons.Default.Description, null, tint = NeonCyan)
                            Spacer(Modifier.width(8.dp))
                            Text("Projeto e código-fonte", color = Color.White)
                        }
                    }
                }
                GlassCard(Modifier.fillMaxWidth(), borderColor = NeonCyan, borderAlpha = 0.3f) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("HS", color = NeonCyan, fontSize = 22.sp, fontWeight = FontWeight.Black)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("HS TECH", color = Color.White, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                            Text("Tecnologia a favor dos gamers", color = TextSecondary, fontSize = 11.sp)
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun AboutInfoRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = NeonCyan, modifier = Modifier.size(21.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, color = TextSecondary, fontSize = 11.sp)
            Text(value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
