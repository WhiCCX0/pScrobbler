package com.arn.scrobble.pref

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.InsetDrawable
import android.os.*
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.animation.AnimationUtils
import android.webkit.URLUtil
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat.getSystemService
import androidx.lifecycle.lifecycleScope
import androidx.preference.*
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.RecyclerView
import com.arn.scrobble.*
import com.arn.scrobble.R
import com.arn.scrobble.Stuff.dp
import com.arn.scrobble.billing.BillingFragment
import com.arn.scrobble.databinding.DialogImportBinding
import com.arn.scrobble.databinding.TextInputEditBinding
import com.arn.scrobble.db.PanoDb
import com.arn.scrobble.edits.BlockedMetadataFragment
import com.arn.scrobble.edits.RegexEditsFragment
import com.arn.scrobble.edits.SimpleEditsFragment
import com.arn.scrobble.themes.ThemesFragment
import com.arn.scrobble.ui.MyClickableSpan
import com.arn.scrobble.widget.ChartsWidgetActivity
import com.arn.scrobble.widget.ChartsWidgetProvider
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.divider.MaterialDividerItemDecoration
import com.google.android.material.transition.MaterialSharedAxis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*


/**
 * Created by arn on 09/07/2017.
 */

class PrefFragment : PreferenceFragmentCompat() {

    private val restoreHandler by lazy { Handler(Looper.getMainLooper()) }
    private lateinit var exportRequest: ActivityResultLauncher<Intent>
    private lateinit var exportPrivateDataRequest: ActivityResultLauncher<Intent>
    private lateinit var importRequest: ActivityResultLauncher<Intent>
    private val prefs by lazy { MainPrefs(context!!) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        exitTransition = MaterialSharedAxis(MaterialSharedAxis.Y, true)
        reenterTransition = MaterialSharedAxis(MaterialSharedAxis.Y, false)

        exportRequest =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK && result.data != null)
                    export(result.data!!)
            }

        exportPrivateDataRequest =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK && result.data != null)
                    export(result.data!!, privateData = true)
            }

        importRequest =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK && result.data != null)
                    import(result.data!!)
            }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
//        preferenceManager.preferenceDataStore = MainPrefsDataStore(context!!)

        val field = preferenceManager::class.java.getDeclaredField("mSharedPreferences")
        field.isAccessible = true
        field.set(preferenceManager, prefs.sharedPreferences)

        addPreferencesFromResource(R.xml.preferences)

        val hideOnTV = mutableListOf<Preference>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !MainActivity.isTV) {
            val master = findPreference<SwitchPreference>(MainPrefs.PREF_MASTER)!!
            master.summary = getString(R.string.pref_master_qs_hint)
        }

        val notiCategories = findPreference<Preference>("noti_categories")!!

        hideOnTV += notiCategories

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !MainActivity.isTV) {
            notiCategories.summary = getString(R.string.pref_noti_q)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            arrayOf(
                MainPrefs.CHANNEL_NOTI_SCROBBLING,
                MainPrefs.CHANNEL_NOTI_DIGEST_WEEKLY,
                MainPrefs.CHANNEL_NOTI_DIGEST_MONTHLY
            ).forEach {
                findPreference<Preference>(it)?.isVisible = false
            }
            notiCategories.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                val intent = Intent().apply {
                    action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                    putExtra(Settings.EXTRA_APP_PACKAGE, activity!!.packageName)
                }
                startActivity(intent)
                true
            }
        } else {
            notiCategories.isVisible = false
        }

        val changeLocalePref = findPreference<Preference>("locale")!!
        val _entryValues = LocaleUtils.localesSet.toTypedArray()
        var prevLang = ""
        val _entries = _entryValues.map {
            val locale = Locale.forLanguageTag(it)

            val displayStr = when {
                locale.language in LocaleUtils.showScriptSet ->
                    locale.displayLanguage + " (${locale.displayScript})"
                prevLang == locale.language ->
                    locale.displayLanguage + " (${locale.displayCountry})"
                else ->
                    locale.displayLanguage
            }
            prevLang = locale.language
            displayStr
        }.toTypedArray()

        val entries = arrayOf(getString(R.string.auto)) + _entries
        val entryValues = arrayOf("auto") + _entryValues

        changeLocalePref.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                MaterialAlertDialogBuilder(context!!)
                    .setTitle(R.string.pref_change_locale)
                    .setItems(entries) { _, idx ->
                        val oldval = prefs.locale ?: "auto"
                        val newVal = entryValues[idx]
                        if (oldval != newVal) {
                            prefs.locale = newVal
                            activity!!.recreate()
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }

        val appList = findPreference<Preference>(MainPrefs.PREF_ALLOWED_PACKAGES)!!
        appList.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            parentFragmentManager.beginTransaction()
                .remove(this)
                .add(R.id.frame, AppListFragment())
                .addToBackStack(null)
                .commit()
            true
        }

        val pixelNp = findPreference<SwitchPreference>(MainPrefs.PREF_PIXEL_NP)!!
        hideOnTV.add(pixelNp)
        if (Build.MANUFACTURER.lowercase() != Stuff.MANUFACTURER_GOOGLE) {
            pixelNp.summary = getString(R.string.pref_pixel_np_nope)
            pixelNp.isEnabled = false
            pixelNp.isPersistent = false
            pixelNp.isChecked = false
        }
        val autoDetect = findPreference<SwitchPreference>(MainPrefs.PREF_AUTO_DETECT)!!
        hideOnTV.add(autoDetect)

        findPreference<Preference>(MainPrefs.CHANNEL_NOTI_DIGEST_WEEKLY)!!
            .title = getString(R.string.s_top_scrobbles, getString(R.string.weekly))

        findPreference<Preference>(MainPrefs.CHANNEL_NOTI_DIGEST_MONTHLY)!!
            .title = getString(R.string.s_top_scrobbles, getString(R.string.monthly))

        findPreference<Preference>("charts_widget")!!
            .onPreferenceClickListener = Preference.OnPreferenceClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val appWidgetManager =
                    getSystemService(context!!, AppWidgetManager::class.java) as AppWidgetManager
                if (appWidgetManager.isRequestPinAppWidgetSupported) {
                    val pi = PendingIntent.getActivity(
                        context,
                        30,
                        Intent(context, ChartsWidgetActivity::class.java)
                            .apply { putExtra(Stuff.EXTRA_PINNED, true) },
                        Stuff.updateCurrentOrMutable
                    )

                    val myProvider = ComponentName(context!!, ChartsWidgetProvider::class.java)
                    appWidgetManager.requestPinAppWidget(myProvider, null, pi)
                }
            }
            true
        }

        findPreference<Preference>("themes")!!
            .setOnPreferenceClickListener {
                parentFragmentManager.beginTransaction()
                    .remove(this)
                    .add(R.id.frame, ThemesFragment())
                    .addToBackStack(null)
                    .commit()
                true
            }

        val showScrobbleSources = findPreference<SwitchPreference>("show_scrobble_sources")!!
        val fakeShowScrobbleSources = findPreference<SwitchPreference>("fake_show_scrobble_sources")!!
        val searchInSource = findPreference<SwitchPreference>("search_in_source")!!

        fakeShowScrobbleSources.isVisible = !prefs.proStatus
        showScrobbleSources.isVisible = prefs.proStatus
        searchInSource.isVisible = prefs.proStatus && prefs.showScrobbleSources
        showScrobbleSources.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
            if (newValue is Boolean) {
                searchInSource.isVisible = newValue
            }
            true
        }

        findPreference<Preference>(MainPrefs.PREF_EXPORT)
            ?.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            val cal = Calendar.getInstance()

            if (prefs.proStatus && prefs.showScrobbleSources) {
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                    putExtra(
                        Intent.EXTRA_TITLE, getString(
                            R.string.export_file_name,
                            "scrobble_sources_" + cal[Calendar.YEAR] + "_" + cal[Calendar.MONTH] + "_" + cal[Calendar.DATE]
                        )
                    )
                }

                exportPrivateDataRequest.launch(intent)
            }

            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(
                    Intent.EXTRA_TITLE, getString(
                        R.string.export_file_name,
                        "" + cal[Calendar.YEAR] + "_" + cal[Calendar.MONTH] + "_" + cal[Calendar.DATE]
                    )
                )
            }

            exportRequest.launch(intent)
            true
        }

        findPreference<Preference>(MainPrefs.PREF_IMPORT)
            ?.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            // On Android 11 TV:
            // Permission Denial: opening provider com.android.externalstorage.ExternalStorageProvider
            // from ProcessRecord{a608cee 5039:com.google.android.documentsui/u0a21}
            // (pid=5039, uid=10021) requires that you obtain access using ACTION_OPEN_DOCUMENT or related APIs
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/*"
            }
            importRequest.launch(intent)
            true
        }
        hideOnTV.add(findPreference("imexport")!!)


        initAuthConfirmation("lastfm", {
            val wf = WebViewFragment()
            wf.arguments = Bundle().apply {
                putString(Stuff.ARG_URL, Stuff.LASTFM_AUTH_CB_URL)
                putBoolean(Stuff.ARG_SAVE_COOKIES, true)
            }
            parentFragmentManager.beginTransaction()
                .remove(this)
                .add(R.id.frame, wf)
                .addToBackStack(null)
                .commit()
        },
            MainPrefs.PREF_LASTFM_USERNAME, MainPrefs.PREF_LASTFM_SESS_KEY,
            logout = { LastfmUnscrobbler(context!!).clearCookies() }
        )

        initAuthConfirmation(
            "librefm", {
                val wf = WebViewFragment()
                wf.arguments = Bundle().apply {
                    putString(Stuff.ARG_URL, Stuff.LIBREFM_AUTH_CB_URL)
                }
                parentFragmentManager.beginTransaction()
                    .remove(this)
                    .add(R.id.frame, wf)
                    .addToBackStack(null)
                    .commit()
            },
            MainPrefs.PREF_LIBREFM_USERNAME, MainPrefs.PREF_LIBREFM_SESS_KEY
        )

        initAuthConfirmation(
            "gnufm", {
                val nixtapeUrl =
                    preferenceManager.sharedPreferences?.getString(
                        MainPrefs.PREF_GNUFM_ROOT,
                        "https://"
                    )!!
                val binding = TextInputEditBinding.inflate(layoutInflater)
                binding.root.isHintEnabled = false
                binding.edittext.setText(nixtapeUrl)
                val padding = 16.dp

                val dialog = MaterialAlertDialogBuilder(context!!)
                    .setTitle(R.string.pref_gnufm_title)
                    .setPositiveButton(android.R.string.ok) { dialog, id ->
                        var newUrl = binding.edittext.text.toString()
                        if (URLUtil.isValidUrl(newUrl)) {
                            if (!newUrl.endsWith('/'))
                                newUrl += '/'
                            preferenceManager.sharedPreferences!!.edit()
                                .putString(MainPrefs.PREF_GNUFM_ROOT, newUrl).apply()
                            val wf = WebViewFragment()
                            wf.arguments = Bundle().apply {
                                putString(
                                    Stuff.ARG_URL,
                                    newUrl + "api/auth?api_key=" + Stuff.LIBREFM_KEY + "&cb=pscrobble://auth/gnufm"
                                )
                            }
                            parentFragmentManager.beginTransaction()
                                .remove(this)
                                .add(R.id.frame, wf)
                                .addToBackStack(null)
                                .commit()
                        } else
                            Stuff.toast(activity!!, getString(R.string.failed_encode_url))
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .create()
                dialog.setView(binding.root, padding, padding / 3, padding, 0)
                dialog.show()
            },
            MainPrefs.PREF_GNUFM_USERNAME, MainPrefs.PREF_GNUFM_SESS_KEY, MainPrefs.PREF_GNUFM_ROOT
        )


        initAuthConfirmation(
            "listenbrainz", {
                val b = Bundle().apply {
                    putString(LoginFragment.HEADING, getString(R.string.listenbrainz))
                    putString(LoginFragment.TEXTFL, getString(R.string.pref_token_label))
                }

                val loginFragment = LoginFragment()
                loginFragment.arguments = b
                activity!!.supportFragmentManager.beginTransaction()
                    .replace(R.id.frame, loginFragment)
                    .addToBackStack(null)
                    .commit()
            },
            MainPrefs.PREF_LISTENBRAINZ_USERNAME, MainPrefs.PREF_LISTENBRAINZ_TOKEN
        )

        initAuthConfirmation(
            "lb",
            {
                val b = Bundle().apply {
                    putString(LoginFragment.HEADING, getString(R.string.custom_listenbrainz))
                    putString(LoginFragment.TEXTF1, getString(R.string.api_url))
                    putString(LoginFragment.TEXTFL, getString(R.string.pref_token_label))
                }

                val loginFragment = LoginFragment()
                loginFragment.arguments = b
                parentFragmentManager.beginTransaction()
                    .replace(R.id.frame, loginFragment)
                    .addToBackStack(null)
                    .commit()
            },
            MainPrefs.PREF_LB_CUSTOM_USERNAME,
            MainPrefs.PREF_LB_CUSTOM_TOKEN,
            MainPrefs.PREF_LB_CUSTOM_ROOT
        )

        findPreference<Preference>(MainPrefs.PREF_INTENTS)!!
            .onPreferenceClickListener = Preference.OnPreferenceClickListener {
            val ss = SpannableString(
                getString(
                    R.string.pref_intents_dialog_desc,
                    NLService.iLOVE,
                    NLService.iUNLOVE,
                    NLService.iCANCEL,

                    NLService.iSCROBBLER_ON,
                    NLService.iSCROBBLER_OFF,
                )
            )
            val newlinePosList = mutableListOf(-1)
            ss.forEachIndexed { index, c ->
                if (c == '\n')
                    newlinePosList += index
            }
            newlinePosList.forEachIndexed { index, i ->
                if (index == 0)
                    return@forEachIndexed

                val start = newlinePosList[index - 1] + 1
                val end = newlinePosList[index]
                if (ss.indexOf("com.", startIndex = start) == start)
                    ss.setSpan(
                        MyClickableSpan(start, end) { text ->
                            val clipboard =
                                context!!.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("intent", text)
                            clipboard.setPrimaryClip(clip)
                            Stuff.toast(context, getString(R.string.copied))
                        },
                        start,
                        end,
                        Spanned.SPAN_INCLUSIVE_EXCLUSIVE
                    )
            }

            MaterialAlertDialogBuilder(context!!)
                .setTitle(R.string.pref_intents_dialog_title)
                .setMessage(ss)
                .setPositiveButton(android.R.string.ok) { _, _ -> }
                .show()
                .findViewById<TextView>(android.R.id.message)?.movementMethod =
                LinkMovementMethod.getInstance()

            true
        }

        findPreference<Preference>("translate")!!
            .onPreferenceClickListener = Preference.OnPreferenceClickListener {
            Stuff.openInBrowser(context!!, getString(R.string.crowdin_link))
            true
        }

        findPreference<Preference>("privacy")!!
            .onPreferenceClickListener = Preference.OnPreferenceClickListener {
            Stuff.openInBrowser(context!!, getString(R.string.privacy_policy_link))
            true
        }

        val about = findPreference<Preference>("about")!!
        try {
            about.title = "v " + BuildConfig.VERSION_NAME
            about.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                Stuff.openInBrowser(context!!, about.summary.toString())
                true
            }
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
        }

        if (MainActivity.isTV)
            hideOnTV.forEach {
                it.isVisible = false
            }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val rv = view.findViewById<RecyclerView>(androidx.preference.R.id.recycler_view)
        rv.layoutAnimation =
            AnimationUtils.loadLayoutAnimation(context!!, R.anim.layout_animation_slide_up)
        rv.scheduleLayoutAnimation()

        val decoration = MaterialDividerItemDecoration(context!!, DividerItemDecoration.VERTICAL)
        decoration.setDividerInsetStartResource(context!!, R.dimen.divider_inset)
        decoration.setDividerInsetEndResource(context!!, R.dimen.divider_inset)
        val colorDrawable = ColorDrawable(decoration.dividerColor)
        val insetDrawable = InsetDrawable(
            colorDrawable,
            decoration.dividerInsetStart,
            0,
            decoration.dividerInsetEnd,
            0
        )
        setDivider(insetDrawable)
    }

    private fun setAuthLabel(elem: Preference) {
        val username =
            preferenceManager.sharedPreferences?.getString(elem.key + "_username", null)
        elem.extras.putInt("state", 1)
        if (username != null) {
            elem.summary = getString(R.string.pref_logout) + ": [$username]"
            elem.extras.putInt("state", STATE_LOGOUT)
        } else {
            elem.summary = getString(R.string.pref_login)
            elem.extras.putInt("state", STATE_LOGIN)
        }
    }

    private fun setAuthLabel(elemKey: String) = setAuthLabel(findPreference(elemKey)!!)

    private fun initAuthConfirmation(
        key: String,
        login: () -> Unit,
        vararg keysToClear: String,
        logout: (() -> Unit)? = null
    ) {
        val elem = findPreference<Preference>(key)!!
        setAuthLabel(elem)
        if (elem.key == "listenbrainz" || elem.key == "lb")
            elem.title = elem.title.toString() + " " + getString(R.string.pref_scrobble_only)
        elem.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                val state = it.extras.getInt("state", STATE_LOGIN)
                when (state) {
                    STATE_LOGOUT -> {
                        restoreHandler.postDelayed({
                            setAuthLabel(it)
                        }, CONFIRM_TIME)
                        val span = SpannableString(getString(R.string.pref_confirm_logout))
                        span.setSpan(
                            ForegroundColorSpan(
                                MaterialColors.getColor(
                                    context!!,
                                    R.attr.colorPrimary,
                                    null
                                )
                            ),
                            0, span.length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE
                        )
                        span.setSpan(
                            StyleSpan(Typeface.BOLD_ITALIC),
                            0,
                            span.length,
                            Spanned.SPAN_INCLUSIVE_EXCLUSIVE
                        )

                        it.summary = span
                        it.extras.putInt("state", STATE_CONFIRM)
                    }
                    STATE_CONFIRM -> {
                        keysToClear.forEach {
                            preferenceManager.sharedPreferences!!.edit().putString(it, null).apply()
                        }
                        logout?.invoke()
                        setAuthLabel(it)
                    }
                    STATE_LOGIN -> login()
                }
                true
            }
    }

    private fun export(data: Intent, privateData: Boolean = false) {
        val currentUri = data.data ?: return
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val exported = ImExporter().use {
                it.setOutputUri(context!!, currentUri)
                if (privateData)
                    it.exportPrivateData()
                else
                    it.export()
            }
            if (!exported)
                withContext(Dispatchers.Main) {
                    Stuff.toast(
                        context!!,
                        getString(R.string.export_failed),
                        Toast.LENGTH_LONG
                    )
                }
            else
                Stuff.log("Exported")
        }
    }

    private fun import(data: Intent) {
        val currentUri = data.data ?: return
        val binding = DialogImportBinding.inflate(layoutInflater)
        MaterialAlertDialogBuilder(context!!)
            .setView(binding.root)
            .setTitle(R.string.import_options)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val editsModeMap = mapOf(
                    R.id.import_edits_nope to Stuff.EDITS_NOPE,
                    R.id.import_edits_replace_all to Stuff.EDITS_REPLACE_ALL,
                    R.id.import_edits_replace_existing to Stuff.EDITS_REPLACE_EXISTING,
                    R.id.import_edits_keep to Stuff.EDITS_KEEP_EXISTING
                )
                val editsMode = editsModeMap[binding.importRadioGroup.checkedRadioButtonId]!!
                val settingsMode = binding.importSettings.isChecked
                if (editsMode == Stuff.EDITS_NOPE && !settingsMode)
                    return@setPositiveButton
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    val imported = ImExporter().use {
                        it.setInputUri(context!!, currentUri)
                        it.import(editsMode, settingsMode)
                    }
                    withContext(Dispatchers.Main) {
                        if (!imported)
                            Stuff.toast(
                                context!!,
                                getString(R.string.import_hey_wtf),
                                Toast.LENGTH_LONG
                            )
                        else {
                            Stuff.toast(
                                context!!,
                                getString(R.string.imported)
                            )
                            parentFragmentManager.popBackStack()
                        }
                    }
                }
            }
            .show()
    }

    override fun onStart() {
        super.onStart()
        Stuff.setTitle(activity!!, R.string.settings)

        listView.isNestedScrollingEnabled = false

        setAuthLabel("listenbrainz")
        setAuthLabel("lb")

        val iF = IntentFilter().apply {
            addAction(NLService.iSESS_CHANGED_S)
        }
        activity!!.registerReceiver(sessChangeReceiver, iF, NLService.BROADCAST_PERMISSION, null)

        val simpleEdits = findPreference<Preference>("simple_edits")!!
        simpleEdits.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            parentFragmentManager.beginTransaction()
                .remove(this)
                .add(R.id.frame, SimpleEditsFragment())
                .addToBackStack(null)
                .commit()
            true
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val numEdits = withContext(Dispatchers.IO) {
                PanoDb.getDb(context!!).getSimpleEditsDao().count
            }
            withContext(Dispatchers.Main) {
                simpleEdits.title =
                    resources.getQuantityString(R.plurals.num_simple_edits, numEdits, numEdits)
            }
        }

        val regexEdits = findPreference<Preference>("regex_edits")!!
        regexEdits.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            parentFragmentManager.beginTransaction()
                .remove(this)
                .add(R.id.frame, RegexEditsFragment())
                .addToBackStack(null)
                .commit()
            true
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val numEdits = withContext(Dispatchers.IO) {
                PanoDb.getDb(context!!).getRegexEditsDao().count
            }
            withContext(Dispatchers.Main) {
                regexEdits.title =
                    resources.getQuantityString(R.plurals.num_regex_edits, numEdits, numEdits)
            }
        }

        val blockedMetadata = findPreference<Preference>("blocked_metadata")!!
        blockedMetadata.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            parentFragmentManager.beginTransaction()
                .remove(this)
                .add(R.id.frame, BlockedMetadataFragment())
                .addToBackStack(null)
                .commit()
            true
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val numEdits = withContext(Dispatchers.IO) {
                PanoDb.getDb(context!!).getBlockedMetadataDao().count
            }
            withContext(Dispatchers.Main) {
                blockedMetadata.title =
                    resources.getQuantityString(R.plurals.num_blocked_metadata, numEdits, numEdits)
            }
        }

        findPreference<SwitchPreference>("show_scrobble_sources")!!.isVisible = prefs.proStatus
        findPreference<SwitchPreference>("fake_show_scrobble_sources")!!.isVisible = !prefs.proStatus

        if (!prefs.proStatus) {
            val fakeShowScrobbleSources =
                findPreference<SwitchPreference>("fake_show_scrobble_sources")!!
            fakeShowScrobbleSources.isChecked = false
            fakeShowScrobbleSources.setOnPreferenceClickListener {
                parentFragmentManager.beginTransaction()
                    .remove(this)
                    .add(R.id.frame, BillingFragment())
                    .addToBackStack(null)
                    .commit()
                true
            }
        }
    }

    override fun onStop() {
        activity!!.unregisterReceiver(sessChangeReceiver)
        restoreHandler.removeCallbacksAndMessages(null)
        super.onStop()
    }

    private val sessChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == NLService.iSESS_CHANGED_S) {
                setAuthLabel("lastfm")
                setAuthLabel("librefm")
                setAuthLabel("gnufm")
            }
        }
    }

}

private const val STATE_LOGIN = 0
private const val STATE_LOGOUT = 2
private const val STATE_CONFIRM = 1
private const val CONFIRM_TIME = 3000L