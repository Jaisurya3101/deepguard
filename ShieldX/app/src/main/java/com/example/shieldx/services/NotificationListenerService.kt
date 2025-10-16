package com.example.shieldx.services

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Observer
import com.example.shieldx.R
import com.example.shieldx.activities.DashboardActivity
import com.example.shieldx.models.Alert
import com.example.shieldx.models.ScanRequest
import com.example.shieldx.network.ApiClient
import com.example.shieldx.repository.ScanRepository
import com.example.shieldx.utils.SharedPref
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * Enhanced NotificationListenerService for DeepGuard v3.0
 * 
 * Features:
 * - Advanced harassment detection with AI integration
 * - Real-time notification monitoring
 * - Customizable user preferences
 * - Smart filtering and whitelisting
 * - Local and remote analysis
 * - Comprehensive threat assessment
 * - Performance optimization
 */
class NotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "DeepGuardNLS"
        private const val CHANNEL_ID = "deepguard_protection"
        private const val NOTIFICATION_ID = 1001
        private const val THREAT_NOTIFICATION_ID = 1002
        
        // Monitored apps - can be customized via settings
        private val DEFAULT_MONITORED_APPS = setOf(
            "com.whatsapp",
            "com.facebook.orca", // Messenger
            "com.instagram.android",
            "org.telegram.messenger",
            "com.android.mms", // SMS
            "com.snapchat.android",
            "org.thoughtcrime.securesms", // Signal
            "com.viber.voip",
            "com.skype.raider",
            "com.discord",
            "com.twitter.android",
            "com.linkedin.android",
            "com.tencent.mm", // WeChat
            "jp.naver.line.android", // LINE
            "com.kakao.talk" // KakaoTalk
        )
        
        // Harassment patterns for local detection
        private val HARASSMENT_PATTERNS = listOf(
            // Explicit threats
            Pattern.compile("\\b(kill|murder|hurt|harm|beat|attack|destroy)\\s+(you|u)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(die|death|dead|suicide)\\b.*\\b(you|u|your|ur)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(threat|threaten|warning|warn)\\b", Pattern.CASE_INSENSITIVE),
            
            // Sexual harassment
            Pattern.compile("\\b(sex|sexual|naked|nude|rape|assault)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(body|breast|private|intimate)\\s+(parts?|photos?|pics?)\\b", Pattern.CASE_INSENSITIVE),
            
            // Cyberbullying
            Pattern.compile("\\b(stupid|idiot|loser|worthless|pathetic|ugly|fat|disgusting)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(hate|despise|can't stand)\\s+(you|u)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(nobody likes|everyone hates|no one cares)\\b", Pattern.CASE_INSENSITIVE),
            
            // Stalking behavior
            Pattern.compile("\\b(follow|watching|stalking|tracking|know where)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(address|location|home|work|school)\\b", Pattern.CASE_INSENSITIVE),
            
            // Discriminatory language
            Pattern.compile("\\b(racist|sexist|homophobic|transphobic)\\s+(slurs?|words?)\\b", Pattern.CASE_INSENSITIVE),
            
            // Repetitive harassment indicators
            Pattern.compile("\\b(stop ignoring|answer me|respond|reply)\\b.*\\b(now|immediately)\\b", Pattern.CASE_INSENSITIVE)
        )
        
        // Severity levels for threat assessment
        enum class ThreatLevel(val value: Int, val description: String) {
            SAFE(0, "Safe"),
            LOW(1, "Low Risk"),
            MEDIUM(2, "Medium Risk"),
            HIGH(3, "High Risk"),
            CRITICAL(4, "Critical Threat")
        }
        
        /**
         * Check if notification listener permission is enabled
         */
        fun isNotificationServiceEnabled(context: Context): Boolean {
            val packageName = context.packageName
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            )
            return flat?.contains(packageName) ?: false
        }
        
        /**
         * Open notification access settings
         */
        fun openNotificationAccessSettings(context: Context) {
            val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        }
    }
    
    // Service components
    private lateinit var sharedPref: SharedPref
    private lateinit var scanRepository: ScanRepository
    private lateinit var notificationManager: NotificationManager
    
    // Monitoring state
    private var isMonitoring = false
    private var monitoringStartTime = 0L
    private val processedNotifications = ConcurrentHashMap<String, Long>()
    private val recentAlerts = mutableListOf<Alert>()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Statistics
    private var notificationsScanned = 0
    private var threatsDetected = 0
    private var threatsBlocked = 0
    private var warningsSent = 0
    
    // User preferences cache
    private var monitoredApps: Set<String> = DEFAULT_MONITORED_APPS
    private var harassmentDetectionEnabled = true
    private var deepfakeDetectionEnabled = true
    private var autoBlockEnabled = false
    private var realTimeAnalysis = true
    private var notificationThreshold = 50 // Confidence threshold for notifications
    
    @SuppressLint("ForegroundServiceType")
    override fun onCreate() {
        super.onCreate()
        
        // CRITICAL: Start foreground service immediately to prevent ANR
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createServiceNotification())
        
        initializeService()
        loadUserPreferences()
        
        Log.i(TAG, "DeepGuard NotificationListenerService created and running in foreground")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopMonitoring()
        serviceScope.cancel()
        Log.i(TAG, "DeepGuard NotificationListenerService destroyed")
    }
    
    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "NotificationListenerService connected")
        
        if (sharedPref.isMonitoringActive()) {
            startMonitoring()
        }
    }
    
    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "NotificationListenerService disconnected")
        stopMonitoring()
    }
    
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)
        
        if (!isMonitoring || !shouldProcessNotification(sbn)) {
            return
        }
        
        serviceScope.launch {
            try {
                processNotification(sbn)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing notification", e)
            }
        }
    }
    
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        super.onNotificationRemoved(sbn)
        // Clean up processed notifications cache
        processedNotifications.remove(sbn.key)
    }
    
    /**
     * Initialize service components
     */
    private fun initializeService() {
        sharedPref = SharedPref.getInstance(this)
        scanRepository = ScanRepository(this, ApiClient.getApiService())
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }
    
    /**
     * Create notification channel for service notifications
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DeepGuard Protection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background protection service notifications"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            notificationManager.createNotificationChannel(channel)
            
            // Create threat alert channel
            val threatChannel = NotificationChannel(
                "deepguard_threats",
                "DeepGuard Threat Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High priority alerts for detected threats"
                setShowBadge(true)
                enableLights(true)
                enableVibration(true)
                lightColor = getColor(R.color.danger_color)
            }
            notificationManager.createNotificationChannel(threatChannel)
        }
    }
    
    /**
     * Load user preferences for monitoring
     */
    private fun loadUserPreferences() {
        harassmentDetectionEnabled = sharedPref.isHarassmentDetectionEnabled()
        deepfakeDetectionEnabled = sharedPref.isDeepfakeDetectionEnabled()
        autoBlockEnabled = sharedPref.isAutoBlockEnabled()
        realTimeAnalysis = sharedPref.isRealTimeModeEnabled()
        
        // Load monitored apps (can be customized in settings)
        val customApps = sharedPref.getMonitoredApps()
        monitoredApps = if (customApps.isNotEmpty()) {
            customApps.split(",").toSet()
        } else {
            DEFAULT_MONITORED_APPS
        }
        
        notificationThreshold = sharedPref.getNotificationThreshold()
        
        Log.i(TAG, "User preferences loaded - Harassment: $harassmentDetectionEnabled, " +
                "Deepfake: $deepfakeDetectionEnabled, AutoBlock: $autoBlockEnabled")
    }
    
    /**
     * Start monitoring notifications
     */
    @SuppressLint("ForegroundServiceType")
    fun startMonitoring() {
        if (isMonitoring) return
        
        isMonitoring = true
        monitoringStartTime = System.currentTimeMillis()
        sharedPref.setMonitoringActive(true)
        sharedPref.setMonitoringStartTime(monitoringStartTime)
        
        // Show persistent notification
        val notification = createServiceNotification()
        startForeground(NOTIFICATION_ID, notification)
        
        // Reset statistics
        notificationsScanned = 0
        threatsDetected = 0
        threatsBlocked = 0
        warningsSent = 0
        
        Log.i(TAG, "Monitoring started")
    }
    
    /**
     * Stop monitoring notifications
     */
    fun stopMonitoring() {
        if (!isMonitoring) return
        
        isMonitoring = false
        sharedPref.setMonitoringActive(false)
        
        stopForeground(true)
        processedNotifications.clear()
        
        Log.i(TAG, "Monitoring stopped")
    }
    
    /**
     * Check if notification should be processed
     */
    private fun shouldProcessNotification(sbn: StatusBarNotification): Boolean {
        val packageName = sbn.packageName
        
        // Skip system notifications
        if (packageName == "android" || packageName == "com.android.systemui") {
            return false
        }
        
        // Check if app is in monitored list
        if (!monitoredApps.contains(packageName)) {
            return false
        }
        
        // Skip notifications without text content
        val notification = sbn.notification
        val text = extractNotificationText(notification)
        if (text.isBlank()) {
            return false
        }
        
        // Skip duplicate processing
        val notificationKey = "${sbn.packageName}_${text.hashCode()}_${sbn.postTime}"
        if (processedNotifications.containsKey(notificationKey)) {
            return false
        }
        
        processedNotifications[notificationKey] = System.currentTimeMillis()
        return true
    }
    
    /**
     * Process individual notification for threats
     */
    private suspend fun processNotification(sbn: StatusBarNotification) {
        notificationsScanned++
        
        val packageName = sbn.packageName
        val appName = getAppName(packageName)
        val notification = sbn.notification
        val text = extractNotificationText(notification)
        val sender = extractSender(notification)
        
        Log.d(TAG, "Processing notification from $appName: ${text.take(50)}...")
        
        // Perform threat analysis
        val analysisResult = analyzeNotificationThreat(text, packageName, sender)
        
        if (analysisResult.threatLevel.value >= ThreatLevel.MEDIUM.value) {
            threatsDetected++
            
            val alert = Alert(
                id = UUID.randomUUID().toString(),
                title = "🛡️ Threat Detected",
                message = "Potential ${analysisResult.threatType} detected in $appName",
                appName = appName,
                threatType = analysisResult.threatType,
                confidence = analysisResult.confidence,
                timestamp = System.currentTimeMillis(),
                content = text.take(200),
                isBlocked = autoBlockEnabled && analysisResult.threatLevel.value >= ThreatLevel.HIGH.value
            )
            
            // Add to recent alerts
            recentAlerts.add(0, alert)
            if (recentAlerts.size > 50) {
                recentAlerts.removeAt(recentAlerts.size - 1)
            }
            
            // Handle the threat
            handleThreatDetection(alert, analysisResult, sbn)
        }
        
        // Update monitoring statistics
        updateMonitoringStats()
    }
    
    /**
     * Analyze notification for harassment and threats
     */
    private suspend fun analyzeNotificationThreat(
        text: String,
        packageName: String,
        sender: String?
    ): ThreatAnalysisResult = withContext(Dispatchers.Default) {
        
        var maxConfidence = 0
        var threatType = "Unknown"
        var threatLevel = ThreatLevel.SAFE
        var detectionMethod = "Local"
        
        // Local pattern-based detection
        if (harassmentDetectionEnabled) {
            val localResult = performLocalHarassmentDetection(text)
            if (localResult.confidence > maxConfidence) {
                maxConfidence = localResult.confidence
                threatType = localResult.type
                threatLevel = localResult.level
            }
        }
        
        // Remote AI analysis (if available and enabled)
        if (realTimeAnalysis && maxConfidence < 80) {
            try {
                val remoteResult = performRemoteAnalysis(text, packageName)
                if (remoteResult != null && remoteResult.confidence > maxConfidence) {
                    maxConfidence = remoteResult.confidence
                    threatType = remoteResult.type
                    threatLevel = remoteResult.level
                    detectionMethod = "AI"
                }
            } catch (e: Exception) {
                Log.w(TAG, "Remote analysis failed, using local detection", e)
            }
        }
        
        // Context-based adjustments
        val contextAdjustedResult = applyContextualAdjustments(
            maxConfidence, threatType, threatLevel, packageName, sender, text
        )
        
        ThreatAnalysisResult(
            confidence = contextAdjustedResult.confidence,
            threatType = contextAdjustedResult.type,
            threatLevel = contextAdjustedResult.level,
            detectionMethod = detectionMethod,
            analysisTimestamp = System.currentTimeMillis()
        )
    }
    
    /**
     * Perform local harassment detection using patterns
     */
    private fun performLocalHarassmentDetection(text: String): LocalAnalysisResult {
        var maxConfidence = 0
        var detectedType = "Safe"
        
        // Check against harassment patterns
        for ((index, pattern) in HARASSMENT_PATTERNS.withIndex()) {
            if (pattern.matcher(text).find()) {
                val confidence = when (index) {
                    in 0..2 -> 85 // Explicit threats - high confidence
                    in 3..4 -> 80 // Sexual harassment - high confidence
                    in 5..7 -> 70 // Cyberbullying - medium-high confidence
                    in 8..9 -> 75 // Stalking - high confidence
                    10 -> 90 // Discriminatory language - very high confidence
                    11 -> 60 // Repetitive harassment - medium confidence
                    else -> 50
                }
                
                if (confidence > maxConfidence) {
                    maxConfidence = confidence
                    detectedType = when (index) {
                        in 0..2 -> "Threat"
                        in 3..4 -> "Sexual Harassment"
                        in 5..7 -> "Cyberbullying"
                        in 8..9 -> "Stalking"
                        10 -> "Hate Speech"
                        11 -> "Harassment"
                        else -> "Suspicious"
                    }
                }
            }
        }
        
        // Additional checks for spam and phishing
        if (maxConfidence < 30) {
            val spamConfidence = checkForSpamIndicators(text)
            if (spamConfidence > maxConfidence) {
                maxConfidence = spamConfidence
                detectedType = "Spam"
            }
        }
        
        val threatLevel = when (maxConfidence) {
            in 0..20 -> ThreatLevel.SAFE
            in 21..40 -> ThreatLevel.LOW
            in 41..60 -> ThreatLevel.MEDIUM
            in 61..80 -> ThreatLevel.HIGH
            else -> ThreatLevel.CRITICAL
        }
        
        return LocalAnalysisResult(maxConfidence, detectedType, threatLevel)
    }
    
    /**
     * Perform remote AI-based analysis
     */
    private suspend fun performRemoteAnalysis(
        text: String,
        packageName: String
    ): RemoteAnalysisResult? = withContext(Dispatchers.IO) {
        try {
            val scanRequest = ScanRequest(
                text = text,
                scanType = "harassment_detection"
            )
            
            val response = scanRepository.scanText(text)
            response.fold(
                onSuccess = { result ->
                    RemoteAnalysisResult(
                        confidence = (result.confidenceScore * 100).toInt(),
                        type = if (result.isHarassment) "Harassment" else "Safe",
                        level = when {
                            result.confidenceScore >= 0.8 -> ThreatLevel.CRITICAL
                            result.confidenceScore >= 0.6 -> ThreatLevel.HIGH
                            result.confidenceScore >= 0.4 -> ThreatLevel.MEDIUM
                            result.confidenceScore >= 0.2 -> ThreatLevel.LOW
                            else -> ThreatLevel.SAFE
                        }
                    )
                },
                onFailure = { null }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Remote analysis failed", e)
            null
        }
    }
    
    /**
     * Apply contextual adjustments to threat analysis
     */
    private fun applyContextualAdjustments(
        confidence: Int,
        type: String,
        level: ThreatLevel,
        packageName: String,
        sender: String?,
        text: String
    ): ContextualResult {
        var adjustedConfidence = confidence
        var adjustedType = type
        var adjustedLevel = level
        
        // App-specific adjustments
        when (packageName) {
            "com.whatsapp", "com.facebook.orca" -> {
                // Higher confidence for personal messaging apps
                adjustedConfidence = (adjustedConfidence * 1.1).toInt().coerceAtMost(100)
            }
            "com.twitter.android", "com.instagram.android" -> {
                // Slightly lower threshold for public platforms
                adjustedConfidence = (adjustedConfidence * 0.9).toInt()
            }
        }
        
        // Sender-based adjustments
        sender?.let {
            // Check if sender is in whitelist (trusted contacts)
            val trustedContacts = sharedPref.getStringValue("trusted_contacts", "").split(",")
            if (trustedContacts.contains(it)) {
                adjustedConfidence = (adjustedConfidence * 0.5).toInt()
                adjustedType = "Trusted Contact"
            }
        }
        
        // Time-based adjustments (late night messages might be more concerning)
        val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (currentHour in 22..23 || currentHour in 0..5) {
            if (adjustedConfidence > 50) {
                adjustedConfidence = (adjustedConfidence * 1.2).toInt().coerceAtMost(100)
            }
        }
        
        // Recalculate threat level based on adjusted confidence
        adjustedLevel = when (adjustedConfidence) {
            in 0..20 -> ThreatLevel.SAFE
            in 21..40 -> ThreatLevel.LOW
            in 41..60 -> ThreatLevel.MEDIUM
            in 61..80 -> ThreatLevel.HIGH
            else -> ThreatLevel.CRITICAL
        }
        
        return ContextualResult(adjustedConfidence, adjustedType, adjustedLevel)
    }
    
    /**
     * Handle detected threat
     */
    private suspend fun handleThreatDetection(
        alert: Alert,
        analysisResult: ThreatAnalysisResult,
        sbn: StatusBarNotification
    ) {
        // Log the threat
        Log.w(TAG, "THREAT DETECTED: ${alert.threatType} (${alert.confidence}%) in ${alert.appName}")
        
        // Auto-block if enabled and threat level is high
        if (autoBlockEnabled && analysisResult.threatLevel.value >= ThreatLevel.HIGH.value) {
            blockNotification(sbn)
            threatsBlocked++
        }
        
        // Send notification if confidence meets threshold
        if (alert.confidence >= notificationThreshold) {
            sendThreatNotification(alert, analysisResult)
            warningsSent++
        }
        
        // Store threat data for analytics
        storeThreatData(alert, analysisResult)
        
        // Update user if app is in foreground
        broadcastThreatAlert(alert)
    }
    
    /**
     * Block notification from being displayed
     */
    private fun blockNotification(sbn: StatusBarNotification) {
        try {
            cancelNotification(sbn.key)
            Log.i(TAG, "Blocked notification from ${sbn.packageName}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to block notification", e)
        }
    }
    
    /**
     * Send threat notification to user
     */
    private fun sendThreatNotification(alert: Alert, analysisResult: ThreatAnalysisResult) {
        val intent = Intent(this, DashboardActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(this, "deepguard_threats")
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(alert.title)
            .setContentText("${alert.message} with ${alert.confidence}% confidence")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("${alert.message}\\n\\nType: ${alert.threatType}\\nFrom: ${alert.appName}\\nConfidence: ${alert.confidence}%"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setColor(getColor(R.color.danger_color))
            .addAction(R.drawable.ic_block, "Block Sender", null) // TODO: Implement block action
            .addAction(R.drawable.ic_check, "Mark Safe", null) // TODO: Implement mark safe action
            .build()
        
        notificationManager.notify(THREAT_NOTIFICATION_ID, notification)
    }
    
    /**
     * Create service notification
     */
    private fun createServiceNotification(): Notification {
        val intent = Intent(this, DashboardActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("DeepGuard Protection Active")
            .setContentText("Monitoring notifications for threats • $notificationsScanned scanned")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Real-time protection is active\\n$notificationsScanned notifications scanned\\n$threatsDetected threats detected"))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }
    
    /**
     * Extract text content from notification
     */
    private fun extractNotificationText(notification: Notification): String {
        val text = StringBuilder()
        
        // Extract title
        notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.let {
            text.append(it).append(" ")
        }
        
        // Extract main text
        notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.let {
            text.append(it).append(" ")
        }
        
        // Extract big text
        notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.let {
            text.append(it).append(" ")
        }
        
        // Extract sub text
        notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.let {
            text.append(it).append(" ")
        }
        
        return text.toString().trim()
    }
    
    /**
     * Extract sender information from notification
     */
    private fun extractSender(notification: Notification): String? {
        // Try to get conversation title or sender name
        notification.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.let {
            return it.toString()
        }
        
        notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.let {
            return it.toString()
        }
        
        return null
    }
    
    /**
     * Get app name from package name
     */
    private fun getAppName(packageName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }
    
    /**
     * Check for spam indicators
     */
    private fun checkForSpamIndicators(text: String): Int {
        var spamScore = 0
        
        // Check for common spam patterns
        val spamPatterns = listOf(
            "click here",
            "limited time",
            "act now",
            "free money",
            "congratulations",
            "winner",
            "claim now",
            "urgent",
            "verify account",
            "suspended account",
            "bitcoin",
            "cryptocurrency",
            "investment opportunity"
        )
        
        for (pattern in spamPatterns) {
            if (text.contains(pattern, ignoreCase = true)) {
                spamScore += 15
            }
        }
        
        // Check for excessive use of numbers and special characters
        val numberCount = text.count { it.isDigit() }
        val specialCharCount = text.count { !it.isLetterOrDigit() && !it.isWhitespace() }
        
        if (numberCount > text.length * 0.3) spamScore += 10
        if (specialCharCount > text.length * 0.2) spamScore += 10
        
        return spamScore.coerceAtMost(80)
    }
    
    /**
     * Store threat data for analytics
     */
    private fun storeThreatData(alert: Alert, analysisResult: ThreatAnalysisResult) {
        // Store in local database for analytics
        // This would integrate with Room database for persistence
        serviceScope.launch {
            try {
                // TODO: Implement database storage
                Log.d(TAG, "Storing threat data: ${alert.id}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to store threat data", e)
            }
        }
    }
    
    /**
     * Broadcast threat alert to app components
     */
    private fun broadcastThreatAlert(alert: Alert) {
        val intent = Intent("com.deepguard.THREAT_DETECTED")
        intent.putExtra("alert_id", alert.id)
        intent.putExtra("threat_type", alert.threatType)
        intent.putExtra("confidence", alert.confidence)
        intent.putExtra("app_name", alert.appName)
        sendBroadcast(intent)
    }
    
    /**
     * Update monitoring statistics
     */
    private fun updateMonitoringStats() {
        // Update service notification
        val notification = createServiceNotification()
        notificationManager.notify(NOTIFICATION_ID, notification)
        
        // Save stats to preferences
        sharedPref.saveIntValue("notifications_scanned", notificationsScanned)
        sharedPref.saveIntValue("threats_detected", threatsDetected)
        sharedPref.saveIntValue("threats_blocked", threatsBlocked)
        sharedPref.saveIntValue("warnings_sent", warningsSent)
    }
    
    // Data classes for analysis results
    data class ThreatAnalysisResult(
        val confidence: Int,
        val threatType: String,
        val threatLevel: ThreatLevel,
        val detectionMethod: String,
        val analysisTimestamp: Long
    )
    
    data class LocalAnalysisResult(
        val confidence: Int,
        val type: String,
        val level: ThreatLevel
    )
    
    data class RemoteAnalysisResult(
        val confidence: Int,
        val type: String,
        val level: ThreatLevel
    )
    
    data class ContextualResult(
        val confidence: Int,
        val type: String,
        val level: ThreatLevel
    )
}
