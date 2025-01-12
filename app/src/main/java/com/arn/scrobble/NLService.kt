package com.arn.scrobble

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.Intent.ACTION_PACKAGE_ADDED
import android.content.Intent.ACTION_TIME_CHANGED
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.session.MediaSessionManager
import android.net.ConnectivityManager
import android.net.ConnectivityManager.CONNECTIVITY_ACTION
import android.os.*
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.Html
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.media.app.MediaStyleMod
import com.arn.scrobble.db.PanoDb
import com.arn.scrobble.themes.ColorPatchUtils
import de.umass.lastfm.Period
import de.umass.lastfm.scrobble.ScrobbleData
import java.text.NumberFormat
import android.media.AudioManager
import androidx.core.content.ContextCompat
import com.arn.scrobble.Stuff.copy
import com.arn.scrobble.Stuff.isUrlOrDomain
import com.arn.scrobble.Stuff.toBundle
import com.arn.scrobble.db.BlockedMetadata
import com.arn.scrobble.pref.MainPrefs
import com.google.firebase.crashlytics.FirebaseCrashlytics
import de.umass.lastfm.Track
import kotlinx.coroutines.*
import timber.log.Timber


class NLService : NotificationListenerService() {
    private val prefs by lazy { MainPrefs(applicationContext) }
    private val nm by lazy { getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }
    private var sessListener: SessListener? = null
    private lateinit var handler: ScrobbleHandler
    private var lastNpTask: LFMRequester? = null
    private lateinit var coroutineScope: CoroutineScope
    private var currentBundle = Bundle()
    private var notiColor = Color.MAGENTA
    private var job: Job? = null
    private var browserPackages: Set<String> = mutableSetOf()
//    private var connectivityCb: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        if (BuildConfig.DEBUG)
            Stuff.toast(applicationContext,getString(R.string.scrobbler_on))
        super.onCreate()
//        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.M)
        init()
    }

    //from https://gist.github.com/xinghui/b2ddd8cffe55c4b62f5d8846d5545bf9
    override fun onStartCommand(intent: Intent, flags: Int, startId: Int) =
        Service.START_STICKY


// this prevents the service from starting on N+
//    override fun onBind(intent: Intent): IBinder? {
//        return null
//    }
/*
    override fun onListenerConnected() {
//    This sometimes gets called twice without calling onListenerDisconnected or onDestroy
//    onCreate seems to get called only once in those cases.
//    also unreliable on lp and mm
        super.onListenerConnected()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (Looper.myLooper() == null) {
                Handler(mainLooper!!).post { init() }
            } else
                init()
        }
    }
*/

    private fun init() {
        initChannels()

        job = SupervisorJob()
        coroutineScope = CoroutineScope(Dispatchers.IO + job!!)

        val filter = IntentFilter().apply {
            addAction(iCANCEL)
            addAction(iLOVE)
            addAction(iUNLOVE)
            addAction(iALLOWLIST)
            addAction(iBLOCKLIST)
            addAction(iDIGEST_WEEKLY)
            addAction(iDIGEST_MONTHLY)
            addAction(iSCROBBLER_ON)
            addAction(iSCROBBLER_OFF)

            addAction(ACTION_TIME_CHANGED)
            addAction(CONNECTIVITY_ACTION)
        }
        applicationContext.registerReceiver(nlserviceReciver, filter)

        val pkgFilter = IntentFilter().apply {
            addDataScheme("package")
            addAction(ACTION_PACKAGE_ADDED)
        }
        applicationContext.registerReceiver(pkgInstallReceiver, pkgFilter)

        val sFilter = IntentFilter().apply {
            addAction(iTHEME_CHANGED_S)
            addAction(iNOW_PLAYING_INFO_REQUEST_S)
            addAction(iBAD_META_S)
            addAction(iOTHER_ERR_S)
            addAction(iMETA_UPDATE_S)
        }
        applicationContext.registerReceiver(nlserviceReciverWithPermission, sFilter, BROADCAST_PERMISSION, null)

        notiColor = ColorPatchUtils.getNotiColor(applicationContext)

        handler = ScrobbleHandler(mainLooper)
        val sessManager = applicationContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        sessListener = SessListener(prefs, handler, audioManager)
        browserPackages = Stuff.getBrowsersAsStrings(packageManager)
        try {
            sessManager.addOnActiveSessionsChangedListener(sessListener!!, ComponentName(this, this::class.java))
            //scrobble after the app is updated
            sessListener?.onActiveSessionsChanged(sessManager.getActiveSessions(ComponentName(this, this::class.java)))
        } catch (exception: SecurityException) {
            Stuff.log("Failed to start media controller: " + exception.message)
            // Try to unregister it, just in case.
            try {
                sessManager.removeOnActiveSessionsChangedListener(sessListener!!)
            } catch (e: Exception) {
                Timber.tag(Stuff.TAG).w(e)
            }
            // Media controller needs notification listener service
            // permissions to be granted.
        }
//        KeepNLSAliveJob.checkAndSchedule(applicationContext)

//        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
//        isOnline = cm.activeNetworkInfo?.isConnected == true
        Stuff.scheduleDigests(applicationContext, prefs)
        sendBroadcast(Intent(iNLS_STARTED_S), BROADCAST_PERMISSION)
//      Dont instantiate BillingRepository in this service, it causes unexplained ANRs
        if (!BuildConfig.DEBUG)
            coroutineScope.launch {
                while (isActive) {
                    delay(Stuff.CRASH_REPORT_INTERVAL)
                    FirebaseCrashlytics.getInstance().sendUnsentReports()
                }
            }

        if (prefs.notiPersistent)
            ContextCompat.startForegroundService(this, Intent(this, PersistentNotificationService::class.java))
        Stuff.log("init")
    }

    private fun destroy() {
        Stuff.log("destroy")
        try {
            applicationContext.unregisterReceiver(nlserviceReciver)
//            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
//            cm.unregisterNetworkCallback(connectivityCb)
        } catch(e:IllegalArgumentException) {
            Stuff.log("nlservicereciver wasn't registered")
        }
        try {
            applicationContext.unregisterReceiver(pkgInstallReceiver)
        } catch(e:IllegalArgumentException) {
        }
        try {
            applicationContext.unregisterReceiver(nlserviceReciverWithPermission)
        } catch(e:IllegalArgumentException) {
        }
        if (sessListener != null) {
            sessListener?.removeSessions(setOf())
            (applicationContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager)
                    .removeOnActiveSessionsChangedListener(sessListener!!)
            sessListener!!.unregisterPrefsChangeListener()
            sessListener = null
            handler.removeCallbacksAndMessages(null)
        }
        job?.cancel()
        PanoDb.destroyInstance()
    }

    /*
    override fun onListenerDisconnected() { //api 24+ only
        destroy()
        super.onListenerDisconnected()
    }
    */

    override fun onDestroy() {
        if (sessListener != null)
            destroy()
        super.onDestroy()
    }

    private fun initChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
            return

        val channels = nm.notificationChannels

        // delete old channels, if they exist
        if (channels?.any { it.id == "fg" } == true) {
            channels.forEach { nm.deleteNotificationChannel(it.id) }
        }

        nm.createNotificationChannel(NotificationChannel(MainPrefs.CHANNEL_NOTI_SCROBBLING,
                getString(R.string.state_scrobbling), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(MainPrefs.CHANNEL_NOTI_SCR_ERR,
                getString(R.string.channel_err), NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(MainPrefs.CHANNEL_NOTI_NEW_APP,
                getString(R.string.new_player, getString(R.string.new_app)), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(MainPrefs.CHANNEL_NOTI_PENDING,
                getString(R.string.pending_scrobbles), NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(
            MainPrefs.CHANNEL_NOTI_DIGEST_WEEKLY,
                getString(R.string.s_top_scrobbles, getString(R.string.weekly)), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(
            MainPrefs.CHANNEL_NOTI_DIGEST_MONTHLY,
                getString(R.string.s_top_scrobbles, getString(R.string.monthly)), NotificationManager.IMPORTANCE_LOW))
    }

    private fun isAppEnabled(pkgName: String) =
         pkgName in prefs.allowedPackages || (prefs.autoDetectApps && pkgName !in prefs.blockedPackages)

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (prefs.pixelNowPlaying)
            scrobbleFromNoti(
                sbn,
                removed = false,
                packageNames = listOf(Stuff.PACKAGE_PIXEL_NP, Stuff.PACKAGE_PIXEL_NP_R),
                channelName = Stuff.CHANNEL_PIXEL_NP,
                notiField = Notification.EXTRA_TITLE,
                format = R.string.song_format_string
            )
        if (isAppEnabled(Stuff.PACKAGE_SHAZAM))
            scrobbleFromNoti(
                sbn,
                removed = false,
                packageNames = listOf(Stuff.PACKAGE_SHAZAM),
                channelName = Stuff.CHANNEL_SHAZAM,
                notiField = Notification.EXTRA_TEXT,
                format = R.string.auto_shazam_now_playing
            )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?, reason: Int) { //only for >26
        if (reason == REASON_APP_CANCEL || reason == REASON_APP_CANCEL_ALL ||
                reason == REASON_TIMEOUT || reason == REASON_ERROR) {
            if (prefs.pixelNowPlaying)
                scrobbleFromNoti(
                    sbn,
                    removed = true,
                    packageNames = listOf(Stuff.PACKAGE_PIXEL_NP, Stuff.PACKAGE_PIXEL_NP_R),
                    channelName = Stuff.CHANNEL_PIXEL_NP,
                )
            if (isAppEnabled(Stuff.PACKAGE_SHAZAM))
                scrobbleFromNoti(
                    sbn,
                    removed = true,
                    packageNames = listOf(Stuff.PACKAGE_SHAZAM),
                    channelName = Stuff.CHANNEL_SHAZAM,
                )
        }
    }

    private fun scrobbleFromNoti(sbn: StatusBarNotification?, removed:Boolean,
                                 packageNames: List<String>, channelName: String,
                                 notiField: String = Notification.EXTRA_TITLE,
                                 @StringRes format: Int = 0
    ) {
        if (prefs.scrobblerEnabled &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && sbn != null && sbn.packageName in packageNames) {
            val n = sbn.notification
            if (n.channelId == channelName) {
                val notiText = n.extras.getString(notiField) ?: return
                Stuff.log("${this::scrobbleFromNoti.name} $notiText removed=$removed")
                if (removed) {
                    handler.remove(currentBundle.getInt(B_HASH))
                    return
                }
                val meta = MetadataUtils.scrobbleFromNotiExtractMeta(notiText, getString(format))
                if (meta != null){
                    val (artist, title) = meta
                    val hash = Stuff.genHashCode(artist, "", title, sbn.packageName)
                    if (handler.hasMessages(currentBundle.getInt(B_HASH)))
                        handler.remove(currentBundle.getInt(B_HASH))

                    val delay = System.currentTimeMillis() - currentBundle.getLong(B_TIME) - currentBundle.getLong(B_DELAY)
                    if (currentBundle.getInt(B_HASH) == hash && delay < 0){ //"resume" scrobbling
                        val m = handler.obtainMessage()
                        m.data = currentBundle
                        m.what = hash
                        handler.sendMessageDelayed(m, -delay)
                        handler.notifyScrobble(artist, title, hash, true, currentBundle.getBoolean(B_USER_LOVED))
                    } else if (currentBundle.getInt(B_HASH) == hash &&
                            System.currentTimeMillis() - currentBundle.getLong(B_TIME) < Stuff.NOTI_SCROBBLE_INTERVAL)
                        Stuff.log("${this::scrobbleFromNoti.name} ignoring possible duplicate")
                    else
                        handler.nowPlaying(
                            artist,
                            "",
                            title,
                            "",
                            0,
                            0,
                            hash,
                            sbn.packageName,
                            true
                        )
                } else
                    Stuff.log("\"${this::scrobbleFromNoti.name} parse failed")
            }
        }
    }

    private fun markAsScrobbled(hash: Int) {
        sessListener?.packageMap?.values?.any {
            if (it.lastScrobbleHash == hash) {
                it.lastScrobbledHash = hash
                true
            } else
                false
        }
    }

    private fun broadcastNowPlayingInfo() {
        val i = Intent(iNOW_PLAYING_INFO_S).apply {
            putExtras(currentBundle)
        }
        sendBroadcast(i, BROADCAST_PERMISSION)
    }

    private val nlserviceReciver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action){
                 iCANCEL -> {
                     val hash: Int
                     if (!intent.hasExtra(B_HASH)) {
                         hash = currentBundle.getInt(B_HASH)
                         if (currentBundle[B_HASH] == null || !handler.hasMessages(hash))
                             return
                         nm.cancel(MainPrefs.CHANNEL_NOTI_SCROBBLING, 0)
                     } else {
                         hash = intent.getIntExtra(B_HASH, 0)
                         val errMag = intent.getStringExtra(B_ERR_MSG)
                         if (errMag != null)
                             handler.notifyUnscrobbled()
                         else
                             nm.cancel(MainPrefs.CHANNEL_NOTI_SCROBBLING, 0)
                     }
                     handler.removeMessages(hash)
                     markAsScrobbled(hash)
                 }
                iLOVE, iUNLOVE -> {
                    val loved = intent.action == iLOVE
                    var artist = intent.getStringExtra(B_ARTIST)
                    var title = intent.getStringExtra(B_TRACK)
                    val hash = currentBundle.getInt(B_HASH, 0)
                    if (artist == null && title == null) {
                        if (currentBundle.getBoolean(B_IS_SCROBBLING) && currentBundle.getString(B_ARTIST) != null &&
                                currentBundle.getString(B_TRACK) != null) {
                            artist = currentBundle.getString(B_ARTIST)!!
                            title = currentBundle.getString(B_TRACK)!!
                            Stuff.toast(context, (
                                    if (loved)
                                        "♥"
                                    else
                                        "\uD83D\uDC94"
                                    ) + getString(R.string.artist_title, artist, title)
                            )
                            if (loved == currentBundle.getBoolean(B_USER_LOVED, false))
                                return
                        } else
                            return
                    }
                    LFMRequester(applicationContext, coroutineScope)
                            .loveOrUnlove(Track(title!!, null, artist!!), loved)
                    val np = handler.hasMessages(hash)
                    currentBundle.putBoolean(B_USER_LOVED, loved)
                    handler.notifyScrobble(artist,
                            title, hash, np, loved, currentBundle.getInt(B_USER_PLAY_COUNT))
                }
                iALLOWLIST, iBLOCKLIST -> {
                    //handle pixel_np blacklist in its own settings
                    val pkgName = intent.getStringExtra("packageName")!!
                    if (pkgName == Stuff.PACKAGE_PIXEL_NP || pkgName == Stuff.PACKAGE_PIXEL_NP_R){
                        if (intent.action == iBLOCKLIST) {
                            prefs.pixelNowPlaying = false
                            handler.remove(currentBundle.getInt(B_HASH))
                            nm.cancel(MainPrefs.CHANNEL_NOTI_NEW_APP, 0)
                            return
                        }
                    }
                    //create copies
                    val aSet = prefs.allowedPackages.toMutableSet()
                    val bSet = prefs.blockedPackages.toMutableSet()

                    if (intent.action == iALLOWLIST)
                        aSet += pkgName
                    else
                        bSet += pkgName
                    bSet.removeAll(aSet) // allowlist takes over blocklist for conflicts
                    prefs.allowedPackages = aSet
                    prefs.blockedPackages = bSet

                    nm.cancel(MainPrefs.CHANNEL_NOTI_NEW_APP, 0)
                }
                iDIGEST_WEEKLY -> {
                    Stuff.scheduleDigests(applicationContext, prefs)
                    if (Stuff.isNotiEnabled(nm, prefs.sharedPreferences, MainPrefs.CHANNEL_NOTI_DIGEST_WEEKLY))
                        handler.notifyDigest(Period.WEEK)
                }
                iDIGEST_MONTHLY -> {
                    Stuff.scheduleDigests(applicationContext, prefs)
                    if (Stuff.isNotiEnabled(nm, prefs.sharedPreferences, MainPrefs.CHANNEL_NOTI_DIGEST_MONTHLY))
                        handler.notifyDigest(Period.ONE_MONTH)
                }
                iSCROBBLER_ON -> {
                    prefs.scrobblerEnabled = true
                    Stuff.toast(context, getString(R.string.scrobbler_on))
                }
                iSCROBBLER_OFF -> {
                    prefs.scrobblerEnabled = false
                    Stuff.toast(context, getString(R.string.scrobbler_off))
                }
                CONNECTIVITY_ACTION -> {
                    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    MainActivity.isOnline =  cm.activeNetworkInfo?.isConnected == true
                }
                ACTION_TIME_CHANGED -> {
                    Stuff.scheduleDigests(applicationContext, prefs)
                }
            }
        }
    }

    private val nlserviceReciverWithPermission = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                iOTHER_ERR_S -> {
                    if (intent.getBooleanExtra(B_PENDING, false))
                        handler.notifyOtherError(getString(R.string.saved_as_pending), intent.getStringExtra(B_ERR_MSG)!!)
                    else
                        handler.notifyOtherError(" ", intent.getStringExtra(B_ERR_MSG)!!)
                }
                iBAD_META_S -> {
                    val scrobbleData = ScrobbleData().apply {
                        track = intent.getStringExtra(B_TRACK)!!
                        album = intent.getStringExtra(B_ALBUM)!!
                        artist = intent.getStringExtra(B_ARTIST)!!
                        albumArtist = intent.getStringExtra(B_ALBUM_ARTIST)!!
                        timestamp = (intent.getLongExtra(B_TIME, System.currentTimeMillis())/1000).toInt()
                    }
                    handler.notifyBadMeta(
                        scrobbleData,
                        intent.getStringExtra(B_PACKAGE_NAME)!!,
                        intent.getIntExtra(B_HASH, 0),
                        intent.getBooleanExtra(B_FORCEABLE, true),
                        intent.getStringExtra(B_ERR_MSG),
                    )
                }
                iMETA_UPDATE_S -> {
                    if (handler.hasMessages(intent.getIntExtra(B_HASH, 0))) {
                        currentBundle.putAll(intent.extras!!)
                        handler.notifyScrobble(currentBundle.getString(B_ARTIST)!!, currentBundle.getString(B_TRACK)!!,
                            intent.getIntExtra(B_HASH, 0), true,
                            currentBundle.getBoolean(B_USER_LOVED), currentBundle.getInt(B_USER_PLAY_COUNT))
                    }
                }
                iTHEME_CHANGED_S -> {
                    notiColor = ColorPatchUtils.getNotiColor(applicationContext)
                }
                iNOW_PLAYING_INFO_REQUEST_S -> {
                    broadcastNowPlayingInfo()
                }
            }
        }
    }

    private val pkgInstallReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_PACKAGE_ADDED) {
                if (!intent.getBooleanExtra(Intent.EXTRA_REPLACING, false))
                    browserPackages = Stuff.getBrowsersAsStrings(packageManager)
            }
        }
    }

    inner class ScrobbleHandler : Handler {
        constructor() : super()
        constructor(looper: Looper) : super(looper)

        override fun handleMessage(m: Message) {
            val hash = m.data.getInt(B_HASH)
            val packageN = m.data.getString(B_PACKAGE_NAME)!!

            val scrobbleData = ScrobbleData().apply {
                track = m.data.getString(B_TRACK)!!
                artist = m.data.getString(B_ARTIST)!!
                album = m.data.getString(B_ALBUM)!!
                albumArtist = m.data.getString(B_ALBUM_ARTIST)!!
                timestamp = (m.data.getLong(B_TIME)/1000).toInt()
                duration = (m.data.getLong(B_DURATION)/1000).toInt() // in secs
            }

            submitScrobble(scrobbleData, packageN, m.what)
            markAsScrobbled(hash)
        }

        fun nowPlaying(artist:String, album:String, title: String, albumArtist:String, position: Long, duration:Long,
                       hash:Int, packageName: String, lessDelay: Boolean = false) {
            if (title != "" && !hasMessages(hash)){
                val now = System.currentTimeMillis()

                val scrobbleData = ScrobbleData()
                scrobbleData.artist = MetadataUtils.sanitizeArtist(artist)
                scrobbleData.album = MetadataUtils.sanitizeAlbum(album)
                scrobbleData.track = title
                scrobbleData.albumArtist = MetadataUtils.sanitizeAlbumArtist(albumArtist)
                scrobbleData.timestamp = (now/1000).toInt() // in secs
                scrobbleData.duration = (duration/1000).toInt() // in secs

                var unparsedScrobbleData: ScrobbleData? = null

                val ignoreArtistMeta = packageName in Stuff.IGNORE_ARTIST_META ||
                        packageName in browserPackages && scrobbleData.artist.isUrlOrDomain()

                if (ignoreArtistMeta) {
                    val (parsedArtist, parsedTitle) = MetadataUtils.parseArtistTitle(title)

                    unparsedScrobbleData = scrobbleData.copy()
                    scrobbleData.artist = parsedArtist
                    scrobbleData.track = parsedTitle
                    scrobbleData.albumArtist = ""
                    scrobbleData.album = ""
                } else if (packageName in browserPackages) {
                    unparsedScrobbleData = scrobbleData.copy()
                }

                lastNpTask?.cancel()
                lastNpTask = LFMRequester(applicationContext, coroutineScope).apply {
                    scrobble(true, scrobbleData, hash, packageName, unparsedScrobbleData)
                }

                val b = scrobbleData.toBundle().apply {
                    putBoolean(B_FORCEABLE, unparsedScrobbleData == null)
                    putInt(B_HASH, hash)
                    putBoolean(B_IS_SCROBBLING, true)
                    putString(B_PACKAGE_NAME, packageName)
                    putString(B_IGNORED_ARTIST, unparsedScrobbleData?.artist)
                }

                val delayMillis = prefs.delaySecs.toLong() * 1000
                val delayPer = prefs.delayPercent.toLong()
                var delay = if (duration > 10000 && duration*delayPer/100 < delayMillis) //dont scrobble <10 sec songs?
                    duration*delayPer/100
                else {
                    if (lessDelay)
                        delayMillis*2/3 //esp for pixel now playing
                    else
                        delayMillis
                }
                if (delay - position > 1000)
                    delay -= position
                b.putLong(B_DELAY, delay)
                currentBundle = b

                val m = obtainMessage()
                m.data = b
                m.what = hash
                sendMessageDelayed(m, delay)

                notifyScrobble(scrobbleData.artist, scrobbleData.track, hash, nowPlaying = true, loved = false)
                if (packageName !in prefs.allowedPackages) {
                    notifyApp(packageName)
                }

                broadcastNowPlayingInfo()

                //for rating
                AppRater.incrementScrobbleCount(prefs)
            }
        }

        private fun submitScrobble(scrobbleData: ScrobbleData, packageName: String, hash:Int) {
            LFMRequester(applicationContext, coroutineScope)
                    .scrobble(false, scrobbleData, hash, packageName)

            var userPlayCount = currentBundle.getInt(B_USER_PLAY_COUNT)
            if (userPlayCount > 0)
                currentBundle.putInt(B_USER_PLAY_COUNT, ++userPlayCount)
            notifyScrobble(scrobbleData.artist, scrobbleData.track, hash, false, currentBundle.getBoolean(B_USER_LOVED), userPlayCount)
        }

        private fun buildNotification(): NotificationCompat.Builder {
            val visibility = if (prefs.notiLockscreen)
                    NotificationCompat.VISIBILITY_PUBLIC
                else
                    NotificationCompat.VISIBILITY_SECRET
            return NotificationCompat.Builder(applicationContext)
                    .setShowWhen(false)
                    .setColor(notiColor)
                    .setAutoCancel(true)
                    .setCustomBigContentView(null)
                    .setVisibility(visibility)
        }

        fun notifyScrobble(artist: String, title: String, hash:Int, nowPlaying: Boolean, loved: Boolean = false, userPlayCount: Int = 0) {
            if (!Stuff.isNotiEnabled(nm, prefs.sharedPreferences, MainPrefs.CHANNEL_NOTI_SCROBBLING))
                return
            var i = Intent()
                    .putExtra(B_ARTIST, artist)
                    .putExtra(B_TRACK, title)
            val loveAction = if (loved) {
                i.action = iUNLOVE
                val loveIntent = PendingIntent.getBroadcast(applicationContext, 4, i,
                        Stuff.updateCurrentOrImmutable)
                getAction(R.drawable.vd_heart_break, "\uD83D\uDC94", getString(R.string.unlove), loveIntent)
            } else {
                i.action = iLOVE
                val loveIntent = PendingIntent.getBroadcast(applicationContext, 3, i,
                        Stuff.updateCurrentOrImmutable)
                getAction(R.drawable.vd_heart, "❤", getString(R.string.love), loveIntent)
            }

            i = Intent(applicationContext, MainActivity::class.java)
                    .putExtra(Stuff.DIRECT_OPEN_KEY, Stuff.DL_RECENTS)
            val launchIntent = PendingIntent.getActivity(applicationContext, 8, i,
                    Stuff.updateCurrentOrImmutable)

            i = Intent(iCANCEL)
                    .putExtra(B_HASH, hash)
                    .putExtra(B_ERR_MSG, getString(R.string.state_unscrobbled))

            val cancelToastIntent = PendingIntent.getBroadcast(applicationContext, 5, i,
                    Stuff.updateCurrentOrImmutable)

            val style= MediaStyleMod()
            val nb = buildNotification()
                    .setAutoCancel(false)
                    .setChannelId(MainPrefs.CHANNEL_NOTI_SCROBBLING)
                    .setSmallIcon(R.drawable.vd_noti)
                    .setContentIntent(launchIntent)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setStyle(style)
                    .addAction(loveAction)
            if (userPlayCount > 0)
                nb.setContentTitle(getString(R.string.artist_title, artist, title))
                    .setContentText(resources.getQuantityString(R.plurals.num_scrobbles_noti,
                            userPlayCount,
                            NumberFormat.getInstance().format(userPlayCount)))
            else
                nb.setContentTitle(title)
                    .setContentText(artist)

            if (nowPlaying) {
                nb.setSubText(getString(R.string.state_scrobbling))
                nb.addAction(getAction(R.drawable.vd_undo, "❌", getString(R.string.unscrobble), cancelToastIntent))
                if (resources.getBoolean(R.bool.is_rtl))
                    style.setShowActionsInCompactView(1, 0)
                else
                    style.setShowActionsInCompactView(0, 1)
            } else {
                nb.setSubText(getString(R.string.state_scrobbled))
                style.setShowActionsInCompactView(0)
            }

            try {
                nm.notify(MainPrefs.CHANNEL_NOTI_SCROBBLING, 0, nb.buildMediaStyleMod())
            } catch (e: RuntimeException){
                val nExpandable =  nb.setLargeIcon(null)
                        .setStyle(null)
                        .build()
                nm.notify(MainPrefs.CHANNEL_NOTI_SCROBBLING, 0, nExpandable)
            }
        }

        fun notifyBadMeta(scrobbleData: ScrobbleData, packageName: String, hash: Int, forcable: Boolean, stateText: String?) {
            val b = scrobbleData.toBundle().apply {
                putBoolean(B_STANDALONE, true)
                putBoolean(B_FORCEABLE, forcable)
                putString(B_PACKAGE_NAME, packageName)
            }
            val i = Intent(applicationContext, MainDialogActivity::class.java).apply {
                putExtras(b)
            }

            val editIntent = PendingIntent.getActivity(applicationContext, 9, i,
                    Stuff.updateCurrentOrImmutable)

            val nb = buildNotification()
                    .setAutoCancel(false)
                    .setChannelId(MainPrefs.CHANNEL_NOTI_SCR_ERR)
                    .setSmallIcon(R.drawable.vd_noti_err)
                    .setContentIntent(editIntent)
                    .setContentText(
                        if (stateText == null)
                            scrobbleData.artist
                        else
                            scrobbleData.track
                    )
                    .setContentTitle(
                            (stateText ?: getString(R.string.state_unrecognised_artist)) + " " +
                                    (if (forcable)
                                        getString(R.string.tap_to_edit_force)
                                    else
                                        getString(R.string.tap_to_edit))
                        )
                    .setPriority(
                        if (stateText == null)
                            NotificationCompat.PRIORITY_LOW
                        else
                            NotificationCompat.PRIORITY_MIN
                    )
            remove(hash, false)
            currentBundle = Bundle()

            nm.notify(MainPrefs.CHANNEL_NOTI_SCROBBLING, 0, nb.build())
            markAsScrobbled(hash)
        }

        fun notifyOtherError(title:String, errMsg: String) {
            val intent = Intent(applicationContext, MainActivity::class.java)
            val launchIntent = PendingIntent.getActivity(applicationContext, 8, intent,
                    Stuff.updateCurrentOrImmutable)
            val spanned = Html.fromHtml(errMsg)

            val nb = buildNotification()
                    .setChannelId(MainPrefs.CHANNEL_NOTI_SCROBBLING)
                    .setSmallIcon(R.drawable.vd_noti_err)
                    .setContentIntent(launchIntent)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setContentText(spanned) //required on recent oneplus devices

            val isMinimised = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    nm.getNotificationChannel(MainPrefs.CHANNEL_NOTI_SCROBBLING).importance < NotificationManager.IMPORTANCE_LOW
            if (isMinimised)
                nb.setContentTitle(errMsg.replace("</?br?>".toRegex(), ""))
            else
                nb.setContentTitle(title)
//            nb.setSubtextCompat(getString(R.string.state_didnt_scrobble))

            nb.setStyle(NotificationCompat.BigTextStyle()
                    .setBigContentTitle(title)
                    .bigText(spanned))

            nm.notify(MainPrefs.CHANNEL_NOTI_SCROBBLING, 0, nb.build())
        }

        fun notifyUnscrobbled() {
            val delay = 2000L

            val blockedMetadata = BlockedMetadata(
                track = currentBundle.getString(B_TRACK) ?: "",
                album = currentBundle.getString(B_ALBUM) ?: "",
                artist = currentBundle.getString(B_ARTIST) ?: "",
                albumArtist = currentBundle.getString(B_ALBUM_ARTIST) ?: "",
            )
            val intent = Intent(this@NLService, MainDialogActivity::class.java).apply {
                putExtra(Stuff.ARG_DATA, blockedMetadata)
                putExtra(B_IGNORED_ARTIST, currentBundle.getString(B_IGNORED_ARTIST))
            }

            val pendingIntent = PendingIntent.getActivity(this@NLService, 20, intent, Stuff.updateCurrentOrImmutable)

            val nb = buildNotification()
                    .setChannelId(MainPrefs.CHANNEL_NOTI_SCROBBLING)
                    .setSmallIcon(R.drawable.vd_noti_err)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setContentTitle(getString(R.string.state_unscrobbled) + " • " + getString(R.string.blocked_metadata_noti))
                    .setContentIntent(pendingIntent)
                    .setTimeoutAfter(delay)
            nm.notify(MainPrefs.CHANNEL_NOTI_SCROBBLING, 0, nb.build())
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
                handler.postDelayed({ nm.cancel(MainPrefs.CHANNEL_NOTI_SCROBBLING, 0) }, delay)
        }

        private fun notifyApp(packageName:String) {
            val appName = try {
                    val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
                    packageManager.getApplicationLabel(applicationInfo).toString()
                } catch (e: Exception) {
                    //eat up all NPEs and stuff
                    packageName
                }

            var intent = Intent(iBLOCKLIST)
                    .putExtra("packageName", packageName)
            val ignoreIntent = PendingIntent.getBroadcast(applicationContext, 1, intent,
                    Stuff.updateCurrentOrImmutable)
            intent = Intent(iALLOWLIST)
                    .putExtra("packageName", packageName)
            val okayIntent = PendingIntent.getBroadcast(applicationContext, 2, intent,
                    Stuff.updateCurrentOrImmutable)
            intent = Intent(applicationContext, MainActivity::class.java)
                    .putExtra(Stuff.DIRECT_OPEN_KEY, Stuff.DL_APP_LIST)
            val launchIntent = PendingIntent.getActivity(applicationContext, 7, intent,
                    Stuff.updateCurrentOrImmutable)

            val n = buildNotification()
                    .setContentTitle(getString(R.string.new_player, appName))
                    .setContentText(getString(R.string.new_player_prompt))
                    .setChannelId(MainPrefs.CHANNEL_NOTI_NEW_APP)
                    .setSmallIcon(R.drawable.vd_appquestion_noti)
                    .setContentIntent(launchIntent)
                    .addAction(getAction(R.drawable.vd_ban, "\uD83D\uDEAB", getString(R.string.no), ignoreIntent))
                    .addAction(getAction(R.drawable.vd_check, "✔", getString(R.string.yes), okayIntent))
                    .setStyle(
                            if (resources.getBoolean(R.bool.is_rtl))
                        MediaStyleMod().setShowActionsInCompactView(1, 0)
                    else
                        MediaStyleMod().setShowActionsInCompactView(0, 1)
                    )
                    .buildMediaStyleMod()
            nm.notify(MainPrefs.CHANNEL_NOTI_NEW_APP, 0, n)
        }

        fun notifyDigest(period: Period) {
            if (prefs.lastfmUsername == null)
                return

            val wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
                        newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, BuildConfig.APPLICATION_ID + "::digest").apply {
                            acquire(30000)
                        }
                    }
            LFMRequester(applicationContext, coroutineScope)
                .getDigest(period) { digestArr ->

                    if (digestArr.isNullOrEmpty()) {
                        wakeLock.release()
                        return@getDigest
                    }

                    val title = if (period == Period.WEEK)
                        getString(R.string.digest_weekly)
                    else
                        getString(R.string.digest_monthly)
                    val id = if (period == Period.WEEK)
                        MainPrefs.CHANNEL_NOTI_DIGEST_WEEKLY
                    else
                        MainPrefs.CHANNEL_NOTI_DIGEST_MONTHLY

                    var intent = Intent(Intent.ACTION_SEND)
                            .putExtra("packageName", packageName)
                    var shareText = title + "\n\n" +
                            digestArr.mapIndexed { i, s ->
                                if (i % 2 == 1 && i != digestArr.size - 1)
                                    s + "\n"
                                else
                                    s
                            }
                                .joinToString(separator = "\n")
                    if (!prefs.proStatus)
                        shareText += "\n\n" + getString(R.string.share_sig)
                    intent.type = "text/plain"
                    intent.putExtra(Intent.EXTRA_TEXT, shareText)
                    val shareIntent = PendingIntent.getActivity(applicationContext, 10 + period.ordinal,
                            Intent.createChooser(intent, title),
                            Stuff.updateCurrentOrImmutable)
                    intent = Intent(applicationContext, MainActivity::class.java)
                            .putExtra(Stuff.DIRECT_OPEN_KEY, Stuff.DL_CHARTS)
                    val launchIntent = PendingIntent.getActivity(applicationContext, 10 + Period.values().size, intent,
                            Stuff.updateCurrentOrImmutable)

                    var digestHtml = ""
                    digestArr.forEachIndexed { index, s ->
                        digestHtml += if (index % 2 == 0)
                            "<b>$s</b>"
                        else if (index == digestArr.size - 1)
                            " $s"
                        else
                            " $s<br>"
                    }
                    val spanned = Html.fromHtml(digestHtml)
                    val nb = buildNotification()
                            .setChannelId(id)
                            .setSmallIcon(R.drawable.vd_charts)
                            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                            .setContentTitle(title)
                            .setContentIntent(launchIntent)
                            .addAction(getAction(R.drawable.vd_share, "↗", getString(R.string.share), shareIntent))
                            .setContentText(spanned)
                            .setShowWhen(true)
                            .setStyle(NotificationCompat.BigTextStyle()
                                    .setBigContentTitle(title)
                                    .bigText(spanned))
                    nm.notify(id, period.ordinal, nb.build())
                    wakeLock.release()
                }
        }

        private fun getAction(icon:Int, emoji:String, text:String, pIntent:PendingIntent): NotificationCompat.Action {
//            return if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.M)
                return NotificationCompat.Action(icon, text, pIntent)
//            else
//                NotificationCompat.Action(R.drawable.ic_transparent, emoji + " "+ text, pIntent)
        }

        private var notiIconBitmap: Bitmap? = null

        private fun NotificationCompat.Builder.setSubtextCompat(state:String): NotificationCompat.Builder {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N){
                setSubText(state)
            } else {
                setContentInfo(state)
            }
            return this
        }

        @SuppressLint("RestrictedApi")
        private fun NotificationCompat.Builder.buildMediaStyleMod(): Notification {
            val modNeeded = Build.VERSION.SDK_INT <= Build.VERSION_CODES.M && mActions != null && mActions.isNotEmpty()
            if (modNeeded) {
                if (notiIconBitmap == null || notiIconBitmap?.isRecycled == true){
                    notiIconBitmap = BitmapFactory.decodeResource(resources, R.drawable.ic_launcher)
                }
//                icon.setColorFilter(ContextCompat.getColor(applicationContext, R.color.colorPrimary), PorterDuff.Mode.SRC_ATOP)
                this.setLargeIcon(notiIconBitmap)
            }
            val n = build()
            if (modNeeded)
                n.bigContentView = null

                /*
                val res = Resources.getSystem()
                val attrs = arrayOf(android.R.attr.textColor).toIntArray()

                var sysStyle = res.getIdentifier("TextAppearance.Material.Notification.Title", "style", "android")
                val titleColorHack = obtainStyledAttributes(sysStyle, attrs).getColor(0, Color.BLACK)

                sysStyle = res.getIdentifier("TextAppearance.Material.Notification", "style", "android")
                val descColorHack = obtainStyledAttributes(sysStyle, attrs).getColor(0, Color.BLACK)


                val rv = n.contentView
                var resId = res.getIdentifier("title", "id", "android")
                rv.setTextColor(resId, titleColorHack)
                resId = res.getIdentifier("text", "id", "android")
                rv.setTextColor(resId, descColorHack)
                resId = res.getIdentifier("text2", "id", "android")
                rv.setTextColor(resId, descColorHack)

                resId = res.getIdentifier("action0", "id", "android")
                val context = Class.forName("android.widget.RemoteViews")
                val m = context.getMethod("setDrawableParameters", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, PorterDuff.Mode::class.java, Int::class.javaPrimitiveType)
                m.invoke(rv, resId, false, -1, ContextCompat.getColor(applicationContext, R.color.colorPrimary), android.graphics.PorterDuff.Mode.SRC_ATOP, -1)
                */

            return n
        }

        fun remove(hash: Int, removeNoti: Boolean = true) {
            Stuff.log("$hash cancelled")
            removeMessages(hash)
            if (hash == currentBundle.getInt(B_HASH)) {
                currentBundle.putBoolean(B_IS_SCROBBLING, false)
                if (removeNoti)
                    nm.cancel(MainPrefs.CHANNEL_NOTI_SCROBBLING, 0)
                broadcastNowPlayingInfo()
            }
        }
    }

    companion object {
        const val iCANCEL = "com.arn.scrobble.CANCEL"
        const val iLOVE = "com.arn.scrobble.LOVE"
        const val iUNLOVE = "com.arn.scrobble.UNLOVE"
        const val iBLOCKLIST = "com.arn.scrobble.BLOCKLIST"
        const val iALLOWLIST = "com.arn.scrobble.ALLOWLIST"
        const val iDIGEST_WEEKLY = "com.arn.scrobble.DIGEST_WEEKLY"
        const val iDIGEST_MONTHLY = "com.arn.scrobble.DIGEST_MONTHLY"
        const val iUPDATE_WIDGET = "com.arn.scrobble.UPDATE_WIDGET"
        const val iSCROBBLER_ON = "com.arn.scrobble.SCROBBLER_ON"
        const val iSCROBBLER_OFF = "com.arn.scrobble.SCROBBLER_OFF"

        const val iNLS_STARTED_S = "com.arn.scrobble.NLS_STARTED"
        const val iSESS_CHANGED_S = "com.arn.scrobble.SESS_CHANGED"
        const val iMETA_UPDATE_S = "com.arn.scrobble.iMETA_UPDATE"
        const val iOTHER_ERR_S = "com.arn.scrobble.OTHER_ERR"
        const val iBAD_META_S = "com.arn.scrobble.BAD_META"
        const val iTHEME_CHANGED_S = "com.arn.scrobble.THEME_CHANGED"
        const val iNOW_PLAYING_INFO_S = "com.arn.scrobble.NOW_PLAYING_INFO"
        const val iNOW_PLAYING_INFO_REQUEST_S = "com.arn.scrobble.NOW_PLAYING_INFO_REQUEST"

        const val BROADCAST_PERMISSION = "com.arn.scrobble.MY_AWESOME_PERMISSION"

        const val B_TRACK = "track"
        const val B_ALBUM_ARTIST = "albumartist"
        const val B_TIME = "time"
        const val B_ARTIST = "artist"
        const val B_ALBUM = "album"
        const val B_DURATION = "duration"
        const val B_USER_PLAY_COUNT = "playcount"
        const val B_USER_LOVED = "loved"
        const val B_HASH = "hash"
        const val B_ERR_MSG = "err"
        const val B_PENDING = "pending_saved"
        const val B_STANDALONE = "alone"
        const val B_FORCEABLE = "forceable"
        const val B_DELAY = "delay"
        const val B_IS_SCROBBLING = "scrobbling"
        const val B_PACKAGE_NAME = "package_name"
        const val B_IGNORED_ARTIST = "ignored_artist"
    }
}