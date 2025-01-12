package com.arn.scrobble

import android.animation.ValueAnimator
import android.app.*
import android.app.Notification.INTENT_CATEGORY_NOTIFICATION_PREFERENCES
import android.content.*
import android.content.pm.LabeledIntent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Color
import android.media.session.MediaSessionManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.MenuItem
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.media.app.MediaStyleMod
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.viewpager.widget.ViewPager
import coil.*
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.size.Precision
import com.arn.scrobble.LocaleUtils.getLocaleContextWrapper
import com.arn.scrobble.Stuff.memoryCacheKey
import com.arn.scrobble.billing.BillingFragment
import com.arn.scrobble.billing.BillingViewModel
import com.arn.scrobble.databinding.ActivityMainBinding
import com.arn.scrobble.databinding.HeaderNavBinding
import com.arn.scrobble.db.PanoDb
import com.arn.scrobble.info.InfoFragment
import com.arn.scrobble.pending.PendingScrService
import com.arn.scrobble.pref.*
import com.arn.scrobble.search.SearchFragment
import com.arn.scrobble.themes.ColorPatchUtils
import com.arn.scrobble.ui.*
import com.google.android.material.color.MaterialColors
import com.google.android.material.internal.NavigationMenuItemView
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.text.NumberFormat
import java.util.*


class MainActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener,
        FragmentManager.OnBackStackChangedListener{

    private lateinit var toggle: ActionBarDrawerToggle
    private lateinit var prefs: MainPrefs
    private var lastDrawerOpenTime = 0L
    private var backArrowShown = false
    var coordinatorPadding = 0
    private var drawerInited = false
    var pendingSubmitAttempted = false
    lateinit var binding: ActivityMainBinding
    private lateinit var navHeaderbinding: HeaderNavBinding
    private lateinit var connectivityCb: ConnectivityManager.NetworkCallback
    val billingViewModel by lazy { VMFactory.getVM(this, BillingViewModel::class.java) }
    val mainNotifierViewModel by lazy { VMFactory.getVM(this, MainNotifierViewModel::class.java) }
    private val npReceiver by lazy { NPReceiver(mainNotifierViewModel) }

    override fun onCreate(savedInstanceState: Bundle?) {
        Stuff.timeIt("onCreate start")
        super.onCreate(savedInstanceState)

        ColorPatchUtils.setTheme(this, billingViewModel.proStatus.value == true)

        binding = ActivityMainBinding.inflate(layoutInflater)
        navHeaderbinding = HeaderNavBinding.inflate(layoutInflater, binding.navView, false)
        binding.navView.addHeaderView(navHeaderbinding.root)
        binding.drawerLayout.drawerElevation = 0f
        setContentView(binding.root)
        Stuff.timeIt("onCreate setContentView")
        setSupportActionBar(binding.coordinatorMain.toolbar)
        supportActionBar!!.setDisplayHomeAsUpEnabled(true)

        prefs = MainPrefs(this)
        coordinatorPadding = binding.coordinatorMain.coordinator.paddingStart
        isTV = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

        val imageLoader = ImageLoader.Builder(applicationContext)
            .components {
                add(AppIconKeyer())
                add(AppIconFetcher.Factory())
                add(MusicEntryImageInterceptor())
                add(StarInterceptor())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .crossfade(Stuff.CROSSFADE_DURATION)
            .precision(Precision.INEXACT)
            .build()
        Coil.setImageLoader(imageLoader)

        binding.coordinatorMain.appBar.onStateChangeListener = { state ->

            when (state) {
                StatefulAppBar.EXPANDED -> {
                    binding.coordinatorMain.toolbar.title = null
                    binding.coordinatorMain.tabBar.visibility = View.GONE
                }
                StatefulAppBar.IDLE -> {
                    binding.coordinatorMain.tabBar.visibility = View.GONE
                }
                StatefulAppBar.COLLAPSED -> {
                    if (supportFragmentManager.findFragmentByTag(Stuff.TAG_HOME_PAGER)?.isVisible == true ||
                    supportFragmentManager.findFragmentByTag(Stuff.TAG_CHART_PAGER)?.isVisible == true) {
                        binding.coordinatorMain.tabBar.visibility = View.VISIBLE
                    } else {
                        binding.coordinatorMain.tabBar.visibility = View.GONE
                    }
                }
            }
        }

        toggle = object: ActionBarDrawerToggle(
                this, binding.drawerLayout, R.string.navigation_drawer_open, R.string.navigation_drawer_close){
            override fun onDrawerOpened(drawerView: View) {
                mainNotifierViewModel.drawerData.value?.let {
                    this@MainActivity.onDrawerOpened()
                }
            }
        }
        toggle.drawerArrowDrawable = ShadowDrawerArrowDrawable(drawerToggleDelegate?.actionBarThemedContext)

        if (isTV) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
                for (i in 0..binding.coordinatorMain.toolbar.childCount) {
                    val child = binding.coordinatorMain.toolbar.getChildAt(i)
                    if (child is ImageButton) {
                        child.setFocusable(false)
                        break
                    }
                }
        }
        binding.drawerLayout.addDrawerListener(toggle)
        binding.navView.setNavigationItemSelectedListener(this)

        val hidePassBox =
            if (intent.data?.isHierarchical == true && intent.data?.path == "/testFirstThings"){
                prefs.lastfmSessKey = null
                true
            } else
                false

        if (savedInstanceState == null) {
            if (FirstThingsFragment.checkAuthTokenExists(prefs) &&
                FirstThingsFragment.checkNLAccess(this)) {

                var directOpenExtra = intent?.getIntExtra(Stuff.DIRECT_OPEN_KEY, 0) ?: 0
                if (intent?.categories?.contains(INTENT_CATEGORY_NOTIFICATION_PREFERENCES) == true)
                    directOpenExtra = Stuff.DL_SETTINGS

                when (directOpenExtra) {
                    Stuff.DL_SETTINGS -> supportFragmentManager.beginTransaction()
                        .replace(R.id.frame, PrefFragment())
                        .addToBackStack(null)
                        .commit()
                    Stuff.DL_APP_LIST -> supportFragmentManager.beginTransaction()
                        .replace(R.id.frame, AppListFragment())
                        .addToBackStack(null)
                        .commit()
                    Stuff.DL_MIC -> supportFragmentManager.beginTransaction()
                        .replace(R.id.frame, RecFragment())
                        .addToBackStack(null)
                        .commit()
                    Stuff.DL_SEARCH -> supportFragmentManager.beginTransaction()
                        .replace(R.id.frame, SearchFragment())
                        .addToBackStack(null)
                        .commit()
                    Stuff.DL_PRO -> supportFragmentManager.beginTransaction()
                        .replace(R.id.frame, BillingFragment())
                        .addToBackStack(null)
                        .commit()
                    else -> {
                        if (coordinatorPadding > 0)
                            binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED) //for some devices
                        showHomePager()

                        if (intent.getStringExtra(NLService.B_ARTIST) != null)
                            showInfoFragment(intent)
                        else {
                            if (!Stuff.isScrobblerRunning(this))
                                showNotRunning()
                            else if (!isTV && billingViewModel.proStatus.value != true)
                                AppRater(this, prefs).appLaunched()
                        }
                    }
                }
            } else {
                showFirstThings(hidePassBox)
            }
        } else {
            binding.coordinatorMain.tabBar.visibility = savedInstanceState.getInt("tab_bar_visible", View.GONE)
            if (supportFragmentManager.findFragmentByTag(Stuff.TAG_HOME_PAGER)?.isAdded == true &&
                    supportFragmentManager.backStackEntryCount == 0)
                openLockDrawer()
        }
        supportFragmentManager.addOnBackStackChangedListener(this)
        billingViewModel.proStatus.observe(this) {
            if (it == true) {
                binding.navView.menu.removeItem(R.id.nav_pro)
            }
        }
        billingViewModel.queryPurchases()
        mainNotifierViewModel.drawerData.observe(this) {
            it?.let { drawerData ->

                val nf = NumberFormat.getInstance()
                navHeaderbinding.navNumScrobbles.text = getString(R.string.num_scrobbles_nav,
                    nf.format(drawerData.scrobblesTotal), nf.format(drawerData.scrobblesToday))

                if (navHeaderbinding.navProfilePic.tag != drawerData.profilePicUrl) // prevent flash
                    navHeaderbinding.navProfilePic.load(drawerData.profilePicUrl) {
                        placeholderMemoryCacheKey(navHeaderbinding.navProfilePic.memoryCacheKey)
                        error(R.drawable.vd_wave)
                        listener(
                            onSuccess = { _, _ ->
                                navHeaderbinding.navProfilePic.tag = drawerData.profilePicUrl
                            },
                            onError = { _, _ ->
                                navHeaderbinding.navProfilePic.tag = drawerData.profilePicUrl
                            }
                        )
                    }
            }
        }

        if (prefs.proStatus && prefs.showScrobbleSources) {
            val filter = IntentFilter().apply {
                addAction(NLService.iNOW_PLAYING_INFO_S)
            }
            registerReceiver(npReceiver, filter, NLService.BROADCAST_PERMISSION, null)
            sendBroadcast(
                Intent(NLService.iNOW_PLAYING_INFO_REQUEST_S),
                NLService.BROADCAST_PERMISSION
            )
        }
//        showNotRunning()
//        testNoti()
    }

    fun showHomePager(){
        openLockDrawer()
        supportFragmentManager.beginTransaction()
                .replace(R.id.frame, HomePagerFragment(), Stuff.TAG_HOME_PAGER)
                .commit()
    }

    private fun showFirstThings(hidePassBox: Boolean) {
        val f = FirstThingsFragment()
        f.arguments = Bundle().apply {
            putBoolean(Stuff.ARG_NOPASS, hidePassBox)
        }
        supportFragmentManager.beginTransaction()
                .replace(R.id.frame, f, Stuff.TAG_FIRST_THINGS)
                .commit()
        binding.coordinatorMain.appBar.setExpanded(expanded = false, animate = true)
        closeLockDrawer()
    }

    private fun showInfoFragment(intent: Intent){
        val artist = intent.getStringExtra(NLService.B_ARTIST)
        val album = intent.getStringExtra(NLService.B_ALBUM)
        val track = intent.getStringExtra(NLService.B_TRACK)
        val info = InfoFragment()
        info.arguments = Bundle().apply {
            putString(NLService.B_ARTIST, artist)
            putString(NLService.B_ALBUM, album)
            putString(NLService.B_TRACK, track)
        }
        supportFragmentManager.findFragmentByTag(Stuff.TAG_INFO_FROM_WIDGET)?.let {
            (it as InfoFragment).dismiss()
        }
        info.show(supportFragmentManager, Stuff.TAG_INFO_FROM_WIDGET)
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        toggle.syncState()
        val lockMode = binding.drawerLayout.getDrawerLockMode(GravityCompat.START)
        backArrowShown = lockMode == DrawerLayout.LOCK_MODE_LOCKED_CLOSED
        toggle.onDrawerSlide(binding.drawerLayout, if (backArrowShown) 1f else 0f)

        Stuff.timeIt("onPostCreate")
    }

    private fun onDrawerOpened(){
        if (!binding.drawerLayout.isDrawerVisible(GravityCompat.START) || (
                        System.currentTimeMillis() - lastDrawerOpenTime < Stuff.RECENTS_REFRESH_INTERVAL))
            return

        LFMRequester(applicationContext, lifecycleScope, mainNotifierViewModel.drawerData).getDrawerInfo()

        val username = prefs.lastfmUsername ?: "nobody"
        val displayUsername = if (BuildConfig.DEBUG) "nobody" else username
        if (navHeaderbinding.navName.tag == null)
            navHeaderbinding.navName.text = displayUsername

        navHeaderbinding.navProfileLink.setOnClickListener {
            val servicesToUrls = mutableMapOf</*@StringRes */Int, String>()

            prefs.lastfmUsername?.let {
                servicesToUrls[R.string.lastfm] = "https://www.last.fm/user/$it"
            }
            prefs.librefmUsername?.let {
                servicesToUrls[R.string.librefm] = "https://www.libre.fm/user/$it"
            }
            prefs.gnufmUsername?.let {
                servicesToUrls[R.string.gnufm] = prefs.gnufmRoot + "user/$it"
            }
            prefs.listenbrainzUsername?.let {
                servicesToUrls[R.string.listenbrainz] = "https://listenbrainz.org/user/$it"
            }
            prefs.customListenbrainzUsername?.let {
                servicesToUrls[R.string.custom_listenbrainz] = prefs.customListenbrainzRoot + "user/$it"
            }

            if (servicesToUrls.size == 1)
                Stuff.openInBrowser(this, servicesToUrls.values.first())
            else {
                val popup = PopupMenu(this, it)
                servicesToUrls.forEach { (strRes, url) ->
                    popup.menu.add(0, strRes, 0, strRes)
                }

                popup.setOnMenuItemClickListener { menuItem: MenuItem ->
                    Stuff.openInBrowser(this, servicesToUrls[menuItem.itemId]!!)
                    true
                }
                popup.show()
            }
        }

        lastDrawerOpenTime = System.currentTimeMillis()

        if (navHeaderbinding.navName.tag == null) {
            val cal = Calendar.getInstance()
            val c = (cal[Calendar.MONTH] == 11 && cal[Calendar.DAY_OF_MONTH] >= 25) ||
                    (cal[Calendar.MONTH] == 0 && cal[Calendar.DAY_OF_MONTH] <= 5)
            if (!c)
                return
            navHeaderbinding.navName.tag = "☃️"
            lifecycleScope.launch {
                while (true) {
                    if (navHeaderbinding.navName.tag == "☃️")
                        navHeaderbinding.navName.tag = "⛄️"
                    else
                        navHeaderbinding.navName.tag = "☃️"
                    navHeaderbinding.navName.text = (navHeaderbinding.navName.tag as String) + displayUsername + "\uD83C\uDF84"

                    delay(500)
                }
            }
        }
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        if (coordinatorPadding == 0)
            binding.drawerLayout.closeDrawer(GravityCompat.START)

        when (item.itemId) {
            R.id.nav_last_week -> {
                val username = prefs.lastfmUsername ?: "nobody"
                Stuff.openInBrowser(this, "https://www.last.fm/user/$username/listening-report/week")
            }
            R.id.nav_recents -> {
                binding.coordinatorMain.tabBar.getTabAt(0)?.select()
            }
            R.id.nav_loved -> {
                binding.coordinatorMain.tabBar.getTabAt(1)?.select()
            }
            R.id.nav_friends -> {
                binding.coordinatorMain.tabBar.getTabAt(2)?.select()
            }
            R.id.nav_charts -> {
                binding.coordinatorMain.tabBar.getTabAt(3)?.select()
            }
            R.id.nav_random -> {
                enableGestures()
                supportFragmentManager.beginTransaction()
                        .replace(R.id.frame, RandomFragment())
                        .addToBackStack(null)
                        .commit()
            }
            R.id.nav_rec -> {
                enableGestures()
                supportFragmentManager.beginTransaction()
                        .replace(R.id.frame, RecFragment())
                        .addToBackStack(null)
                        .commit()
            }
            R.id.nav_search -> {
                enableGestures()
                supportFragmentManager.beginTransaction()
                        .replace(R.id.frame, SearchFragment())
                        .addToBackStack(null)
                        .commit()
            }
            R.id.nav_settings -> {
                enableGestures()
                supportFragmentManager.beginTransaction()
                        .replace(R.id.frame, PrefFragment())
                        .addToBackStack(null)
                        .commit()
            }
            R.id.nav_report -> {
                mailLogs()
            }
            R.id.nav_pro -> {
                enableGestures()
                supportFragmentManager.beginTransaction()
                    .replace(R.id.frame, BillingFragment())
                    .addToBackStack(null)
                    .commit()
            }
        }
        return true
    }

    fun enableGestures() {
        val hp = supportFragmentManager.findFragmentByTag(Stuff.TAG_HOME_PAGER) as? HomePagerFragment
        hp?.setGestureExclusions(false)
    }

    override fun onBackStackChanged() {
            val animate = true
            if (supportFragmentManager.backStackEntryCount == 0) {
                val firstThingsVisible = supportFragmentManager.findFragmentByTag(Stuff.TAG_FIRST_THINGS)?.isVisible

                if (firstThingsVisible != true)
                    showBackArrow(false)

                if (supportFragmentManager.fragments.isEmpty()) //came back from direct open
                    showHomePager()
            } else {
                showBackArrow(true)
            }

            val pager = supportFragmentManager.findFragmentByTag(Stuff.TAG_HOME_PAGER)?.view?.findViewById<ViewPager>(R.id.pager)

            val expand = pager != null && pager.currentItem != 2 && pager.currentItem != 3 &&
                    supportFragmentManager.findFragmentByTag(Stuff.TAG_FIRST_THINGS)?.isVisible != true

            binding.coordinatorMain.appBar.setExpanded(expand, animate)
    }

    override fun onBackPressed() {
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START) && coordinatorPadding == 0)
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        else if (mainNotifierViewModel.backButtonEnabled)
            super.onBackPressed()
    }

    private fun showNotRunning(){
        Snackbar
                .make(binding.coordinatorMain.frame, R.string.not_running, Snackbar.LENGTH_INDEFINITE)
                .setAction(R.string.not_running_fix_action) {
                    FixItFragment().show(supportFragmentManager, null)
                }
                .addCallback(object : Snackbar.Callback() {
                    override fun onShown(sb: Snackbar?) {
                        super.onShown(sb)
                        if (sb != null && isTV)
                            sb.view.postDelayed({
                                sb.view.findViewById<View>(com.google.android.material.R.id.snackbar_action)
                                        .requestFocus()
                        }, 200)
                    }
            })
            .show()
        Timber.tag(Stuff.TAG).w(Exception("bgScrobbler not running"))
    }

    private fun mailLogs(){
        val activeSessions = try {
            val sessManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            sessManager.getActiveSessions(ComponentName(this, NLService::class.java))
                .joinToString { it.packageName }
        } catch (e: SecurityException) {
            "SecurityException"
        }
        var bgRam = -1
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (proc in manager.runningAppProcesses){
            if (proc?.processName?.contains("bgScrobbler") == true){
                // https://stackoverflow.com/questions/2298208/how-do-i-discover-memory-usage-of-my-application-in-android
                val memInfo = manager.getProcessMemoryInfo(intArrayOf(proc.pid)).first()
                bgRam = memInfo.totalPss / 1024
                break
            }
        }


        var text = ""
        text += getString(R.string.app_name) + " v" + BuildConfig.VERSION_NAME+ "\n"
        text += "Android " + Build.VERSION.RELEASE+ "\n"
        text += "ROM: " + Build.DISPLAY+ "\n"
        text += "Device: " + Build.BRAND + " " + Build.MODEL + " / " + Build.DEVICE + "\n" //Build.PRODUCT is obsolete

        val mi = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(mi)
        val megs = mi.totalMem / 1048576L
        text += "RAM: " + megs + "M \n"
        text += "Background RAM usage: " + bgRam + "M \n"

        val dm = resources.displayMetrics

        text += "Screen: " + dm.widthPixels + " x " + dm.heightPixels + ",  " + dm.densityDpi + " DPI\n"

        if (!Stuff.isScrobblerRunning(this))
            text += "Background service isn't running\n"
        text += "Active Sessions: $activeSessions\n"

        text += if (billingViewModel.proStatus.value == true)
            "~~~~~~~~~~~~~~~~~~~~~~~~"
        else
            "------------------------"
        text += "\n\n[describe the issue]\n"
        //keep the email in english

        val log = Stuff.exec("logcat -d")
        val logFile = File(filesDir, "log.txt")
        logFile.writeText(log)
        val logUri = FileProvider.getUriForFile(this, "com.arn.scrobble.fileprovider", logFile)

//        PendingScrobblesDb.destroyInstance()
//        val dbFile = File(filesDir, PendingScrobblesDb.tableName + ".sqlite")
//        getDatabasePath(PendingScrobblesDb.tableName).copyTo(dbFile, true)
//        val dbUri = FileProvider.getUriForFile(this, "com.arn.scrobble.fileprovider", dbFile)

        val emailIntent = Intent(Intent.ACTION_SENDTO, Uri.fromParts(
                "mailto", "huh@huh.com", null))
        emailIntent.putExtra(Intent.EXTRA_SUBJECT, "huh?")
        val resolveInfos = packageManager.queryIntentActivities(emailIntent, 0)
        val intents = arrayListOf<LabeledIntent>()
        for (info in resolveInfos) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                component = ComponentName(info.activityInfo.packageName, info.activityInfo.name)
                putExtra(Intent.EXTRA_EMAIL, arrayOf(getString(R.string.email)))
                putExtra(
                    Intent.EXTRA_SUBJECT,
                    getString(R.string.app_name) + " - Bug report"
                )
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_STREAM, logUri)
            }
            intents.add(LabeledIntent(intent, info.activityInfo.packageName, info.loadLabel(packageManager), info.icon))
        }
        if (intents.size > 0) {
            val chooser = Intent.createChooser(intents.removeAt(intents.size - 1), getString(R.string.bug_report))
            chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, intents.toTypedArray())
            startActivity(chooser)
        }else
            Stuff.toast(this, getString(R.string.no_mail_apps))
    }

    override fun onSupportNavigateUp(): Boolean {
        if (backArrowShown)
            supportFragmentManager.popBackStack()
        else
            binding.drawerLayout.openDrawer(GravityCompat.START)
        return true
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.data?.isHierarchical == true) {
            val uri = intent.data!!
            val path = uri.path
            val token = uri.getQueryParameter("token")
            if (token != null){
                Stuff.log("onNewIntent got token for $path")
                when(path) {
                    "/lastfm" ->
                        LFMRequester(applicationContext, lifecycleScope).doAuth(R.string.lastfm, token)
                    "/librefm" ->
                        LFMRequester(applicationContext, lifecycleScope).doAuth(R.string.librefm, token)
                    "/gnufm" ->
                        LFMRequester(applicationContext, lifecycleScope).doAuth(R.string.gnufm, token)
                    "/testFirstThings" -> {
                        prefs.lastfmSessKey = null
                        for (i in 0..supportFragmentManager.backStackEntryCount)
                            supportFragmentManager.popBackStackImmediate()
                        showFirstThings(true)
                    }
                }
            }
        } else if (intent?.getStringExtra(NLService.B_ARTIST) != null)
            showInfoFragment(intent)
    }

    private fun showBackArrow(show: Boolean){
        if (backArrowShown != show) {
            val start = if (show) 0f else 1f
            val anim = ValueAnimator.ofFloat(start, 1 - start)
            anim.addUpdateListener { valueAnimator ->
                val slideOffset = valueAnimator.animatedValue as Float
                toggle.onDrawerSlide(binding.drawerLayout, slideOffset)
            }
            anim.interpolator = DecelerateInterpolator()
            anim.startDelay = 200
            anim.duration = 1000
            anim.start()

            when {
                show -> closeLockDrawer()
                coordinatorPadding > 0 -> openLockDrawer()
                else -> binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
            }

            backArrowShown = show
        }
    }

    override fun attachBaseContext(newBase: Context?) {
        if (newBase != null)
            super.attachBaseContext(newBase.getLocaleContextWrapper())
    }

    public override fun onStart() {
        super.onStart()

        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val builder = NetworkRequest.Builder()
        connectivityCb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                isOnline = true
            }

            override fun onLost(network: Network) {
                isOnline = cm.activeNetworkInfo?.isConnected == true
            }

            override fun onUnavailable() {
                isOnline = cm.activeNetworkInfo?.isConnected == true
            }
        }

        cm.registerNetworkCallback(builder.build(), connectivityCb)

        val ni = cm.activeNetworkInfo
        isOnline = ni?.isConnected == true
    }

    private fun closeLockDrawer(){
        binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        if (coordinatorPadding > 0)
            binding.coordinatorMain.coordinator.setPadding(0,0,0,0)
    }


    private fun openLockDrawer(){
        if(coordinatorPadding > 0) {
            binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_OPEN)
            if (!drawerInited) {
                binding.navView.addOnLayoutChangeListener { view, left, top, right, bottom,
                                                     leftWas, topWas, rightWas, bottomWas ->
                    if (left != leftWas || right != rightWas)
                        onDrawerOpened()
                }
                drawerInited = true
            }
            if (binding.coordinatorMain.coordinator.paddingStart != coordinatorPadding)
                binding.coordinatorMain.coordinator.setPaddingRelative(coordinatorPadding,0,0,0)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
//        Stuff.log("focus: $currentFocus")
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            val f = currentFocus
            if (f is NavigationMenuItemView) {
                if (resources.getBoolean(R.bool.is_rtl))
                    f.nextFocusLeftId = R.id.pager
                else
                    f.nextFocusRightId = R.id.pager
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    public override fun onStop() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.unregisterNetworkCallback(connectivityCb)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (binding.drawerLayout.getDrawerLockMode(GravityCompat.START) == DrawerLayout.LOCK_MODE_LOCKED_OPEN)
            binding.drawerLayout.isSaveEnabled = false
        outState.putInt("tab_bar_visible", binding.coordinatorMain.tabBar.visibility)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (!PendingScrService.mightBeRunning)
            PanoDb.destroyInstance()
        try {
            unregisterReceiver(npReceiver)
        } catch (e: Exception) {
        }
        imageLoader.shutdown()
        super.onDestroy()
    }


    fun testNoti (){
        AppRater(this, prefs).showRateSnackbar()
        val res = Resources.getSystem()
        val attrs = arrayOf(android.R.attr.textColor).toIntArray()

        var sysStyle = res.getIdentifier("TextAppearance.Material.Notification.Title", "style", "android")
        val titleTextColor = obtainStyledAttributes(sysStyle, attrs).getColor(0, Color.BLACK)

        sysStyle = res.getIdentifier("TextAppearance.Material.Notification", "style", "android")
        val secondaryTextColor = obtainStyledAttributes(sysStyle, attrs).getColor(0, Color.BLACK)

        Stuff.log("clr: $titleTextColor $secondaryTextColor")

        val longDescription = SpannableStringBuilder()
        longDescription.append("def ")

        var start = longDescription.length
        longDescription.append("c1 ")
        longDescription.setSpan(ForegroundColorSpan(ContextCompat.getColor(applicationContext, android.R.color.secondary_text_light)), start, longDescription.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        start = longDescription.length
        longDescription.append("c2 ")
        longDescription.setSpan(ForegroundColorSpan(titleTextColor), start, longDescription.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        start = longDescription.length
        longDescription.append("c3 ")
        longDescription.setSpan(ForegroundColorSpan(secondaryTextColor), start, longDescription.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
//        longDescription.setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, longDescription.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        longDescription.append(" rest")

        val launchIntent = PendingIntent.getActivity(applicationContext, 0, Intent(applicationContext, MainActivity::class.java)
            .putExtra(Stuff.DIRECT_OPEN_KEY, Stuff.DL_APP_LIST),
            Stuff.updateCurrentOrImmutable)

        val style = MediaStyleMod()//android.support.v4.media.app.NotificationCompat.MediaStyle()
        style.setShowActionsInCompactView(0, 1)
        val icon = ContextCompat.getDrawable(this, R.drawable.ic_launcher)
//        icon.setColorFilter(ContextCompat.getColor(applicationContext, R.color.colorPrimary), PorterDuff.Mode.SRC_ATOP)

        val nb = NotificationCompat.Builder(applicationContext, MainPrefs.CHANNEL_NOTI_SCROBBLING)
            .setSmallIcon(R.drawable.vd_noti)
//                .setLargeIcon(Stuff.drawableToBitmap(icon))
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setAutoCancel(true)
            .setShowWhen(false)
            .setUsesChronometer(true)
            .setPriority(Notification.PRIORITY_HIGH)
            .addAction(R.drawable.vd_undo, getString(R.string.unscrobble), launchIntent)
            .addAction(R.drawable.vd_check, getString(R.string.unscrobble), launchIntent)
            .setContentTitle("setContentTitle")
            .setContentText("longDescription")
            .setSubText("setSubText")
            .setColor(MaterialColors.getColor(this, R.attr.colorPrimary, null))
            .setStyle(style)
//                .setCustomBigContentView(null)
//                .setCustomContentView(null)

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val n = nb.build()
        n.bigContentView = null
        val rv = n.contentView
/*
        var resId = res.getIdentifier("title", "id", "android")
        rv.setTextColor(resId, Color.BLACK)
        resId = res.getIdentifier("text", "id", "android")
        rv.setTextColor(resId, Color.BLACK)
        resId = res.getIdentifier("text2", "id", "android")
        rv.setTextColor(resId, Color.BLACK)
        resId = res.getIdentifier("status_bar_latest_event_content", "id", "android")
        Stuff.log("resId $resId")
        rv.setInt(resId, "setBackgroundColor", R.drawable.notification_bg)

        resId = res.getIdentifier("action0", "id", "android")
        val c = Class.forName("android.widget.RemoteViews")
        val m = c.getMethod("setDrawableParameters", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, PorterDuff.Mode::class.java, Int::class.javaPrimitiveType)
        m.invoke(rv, resId, false, -1, ContextCompat.getColor(applicationContext, R.color.colorPrimary), android.graphics.PorterDuff.Mode.SRC_ATOP, -1)
        rv.setImageViewResource(resId, R.drawable.vd_ban)
*/
        nm.notify(9, n)

    }

    class NPReceiver(private val mainNotifierViewModel: MainNotifierViewModel):
        BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == NLService.iNOW_PLAYING_INFO_S)
                mainNotifierViewModel.trackBundleLd.value = intent.extras
        }
    }

    companion object {
        var isOnline = true
        var isTV = false
    }
}
