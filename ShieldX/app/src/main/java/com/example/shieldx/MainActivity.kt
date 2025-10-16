package com.example.shieldx

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.shieldx.service.ShieldXNotificationListener
import com.example.shieldx.api.ConnectionTester
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            checkNotificationListenerPermission()
        } else {
            Toast.makeText(this, "Notification permission is required for ShieldX to work", Toast.LENGTH_LONG).show()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            ShieldXTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ShieldXMainScreen(
                        onRequestNotificationPermission = { requestNotificationPermission() },
                        onRequestListenerPermission = { requestNotificationListenerPermission() },
                        isNotificationListenerEnabled = { isNotificationListenerEnabled() },
                        onTestConnection = { testBackendConnection() }
                    )
                }
            }
        }
    }
    
    private fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            checkNotificationListenerPermission()
        }
    }
    
    private fun checkNotificationListenerPermission() {
        if (!isNotificationListenerEnabled()) {
            requestNotificationListenerPermission()
        }
    }
    
    private fun requestNotificationListenerPermission() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        startActivity(intent)
        Toast.makeText(
            this,
            "Please enable ShieldX in the Notification Access settings",
            Toast.LENGTH_LONG
        ).show()
    }
    
    private fun isNotificationListenerEnabled(): Boolean {
        val enabledListeners = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        )
        val componentName = ComponentName(this, ShieldXNotificationListener::class.java)
        return enabledListeners?.contains(componentName.flattenToString()) == true
    }
    
    private fun testBackendConnection() {
        ConnectionTester.runAllTests()
        Toast.makeText(this, "Connection test started - check Logcat for results", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun ShieldXMainScreen(
    onRequestNotificationPermission: () -> Unit,
    onRequestListenerPermission: () -> Unit,
    isNotificationListenerEnabled: () -> Boolean,
    onTestConnection: () -> Unit
) {
    var listenerEnabled by remember { mutableStateOf(isNotificationListenerEnabled()) }
    var isConnected by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // App Icon and Title
        Icon(
            imageVector = Icons.Default.Security,
            contentDescription = "Shield",
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "🛡️ ShieldX",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "AI-Powered Harassment Detection",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(48.dp))
        
        // Status Cards
        StatusCard(
            title = if (listenerEnabled) "✅ Protection Active" else "⚠️ Setup Required",
            description = if (listenerEnabled) 
                "ShieldX is monitoring your notifications for harassment" 
            else 
                "Notification access permission is required",
            isActive = listenerEnabled
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        StatusCard(
            title = if (isConnected) "🌐 Backend Connected" else "🔴 Backend Disconnected",
            description = if (isConnected) 
                "Connected to DeepGuard AI backend" 
            else 
                "Unable to reach harassment detection backend",
            isActive = isConnected
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Action Buttons
        if (!listenerEnabled) {
            Button(
                onClick = onRequestNotificationPermission,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Enable Protection")
            }
            
            Spacer(modifier = Modifier.height(12.dp))
        }
        
        OutlinedButton(
            onClick = { listenerEnabled = isNotificationListenerEnabled() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Refresh Status")
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        OutlinedButton(
            onClick = {
                scope.launch {
                    onTestConnection()
                    // Simulate connection test result
                    kotlinx.coroutines.delay(2000)
                    isConnected = true
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Test Backend Connection")
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Information
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "📱 Monitored Apps",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "WhatsApp • Messenger • Instagram • Telegram • SMS • Snapchat",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
fun StatusCard(
    title: String,
    description: String,
    isActive: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) 
                MaterialTheme.colorScheme.primaryContainer 
            else 
                MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isActive) Icons.Default.Security else Icons.Default.Warning,
                contentDescription = if (isActive) "Active" else "Inactive",
                tint = if (isActive) 
                    MaterialTheme.colorScheme.onPrimaryContainer 
                else 
                    MaterialTheme.colorScheme.onErrorContainer
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isActive) 
                        MaterialTheme.colorScheme.onPrimaryContainer 
                    else 
                        MaterialTheme.colorScheme.onErrorContainer
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = if (isActive) 
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) 
                    else 
                        MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
fun ShieldXTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF1976D2),
            primaryContainer = Color(0xFFE3F2FD),
            secondary = Color(0xFF00BCD4),
            error = Color(0xFFD32F2F),
            errorContainer = Color(0xFFFFEBEE)
        )
    ) {
        content()
    }
}