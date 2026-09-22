package dev.bbkb.ime.core

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.inputmethodservice.AbstractInputMethodService
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import android.util.PrintWriterPrinter
import android.util.SparseArray
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.view.inputmethod.InputMethodSubtype
import android.widget.TextView
import com.blackberry.nuanceshim.languagepack.LanguageVariantStore
import dev.bbkb.ime.BuildConfig
import dev.bbkb.ime.R
import dev.bbkb.ime.core.device.interceptor.KeyInterceptorManager
import dev.bbkb.ime.core.contacts.ContactsLearningManager
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.device.state.PhysicalKeyboardStateTracker
import dev.bbkb.ime.core.engine.DictionaryLoader
import dev.bbkb.ime.personaldictionary.DictionaryManager
import dev.bbkb.ime.personaldictionary.OneTapAddWord
import dev.bbkb.ime.core.gesture.MultiPointerGestureDetector
import dev.bbkb.ime.core.gesture.ShakeGestureHandler
import dev.bbkb.ime.core.keyevent.CompositeKeyCharacterInterpreter
import dev.bbkb.ime.core.keyevent.InputEvent
import dev.bbkb.ime.core.keyevent.InputEventContext
import dev.bbkb.ime.core.keyevent.KeyEventConverter
import dev.bbkb.ime.core.keyevent.KeyHoldHandler
import dev.bbkb.ime.core.engine.learning.DynamicLearningManager
import dev.bbkb.ime.core.settings.ComposeSettingsActivity
import dev.bbkb.ime.core.settings.util.SettingsManager
import dev.bbkb.ime.core.settings.util.SettingsValues
import dev.bbkb.ime.core.subtypeswitcher.SubtypeSwitcherReceiver
import dev.bbkb.ime.core.suggestion.SuggestedWords
import dev.bbkb.ime.core.suggestion.SuggestionEngine
import dev.bbkb.ime.core.suggestion.SuggestionStripPresenter
import dev.bbkb.ime.core.suggestion.SuggestionUpdater
import dev.bbkb.ime.core.textinput.InputLogic
import dev.bbkb.ime.core.textinput.InputMethodHelper
import dev.bbkb.ime.core.textinput.InputSessionCoordinator
import dev.bbkb.ime.core.textinput.connection.EditorCapabilities
import dev.bbkb.ime.core.shared.CoordinateUtils
import dev.bbkb.ime.core.shared.WeakOwnerHandler
import dev.bbkb.ime.core.keyevent.CurrencyKeyHandler
import dev.bbkb.ime.core.keyevent.InputSource
import dev.bbkb.ime.core.settings.IntentUtils
import dev.bbkb.ime.core.locale.LocaleUtils
import dev.bbkb.ime.core.shared.InAppEventBus
import dev.bbkb.ime.core.shared.Logger
import dev.bbkb.ime.core.shared.StartupTiming
import dev.bbkb.ime.core.shared.ViewLayoutUtils
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.receivers.ConnectivityAndScreenReceiver
import dev.bbkb.ime.core.receivers.LearningModelSaveReceiver
import dev.bbkb.ime.core.receivers.LocaleChangeReceiver
import dev.bbkb.ime.core.shared.CursorAnchorInfoUtils
import dev.bbkb.ime.core.device.ResourceConfigManager
import dev.bbkb.ime.core.locale.ResourceLocaleUtils
import dev.bbkb.ime.core.shared.StringHelper.capitalizeFirstCodePoint
import dev.bbkb.ime.core.ime.CursorMovementListener
import dev.bbkb.ime.core.textinput.CursorTracker
import dev.bbkb.ime.keyboard.auxbar.suggestions.FlickSuggestionView
import dev.bbkb.ime.keyboard.KeyboardActionListenerInterface
import dev.bbkb.ime.keyboard.KeyboardColorManager
import dev.bbkb.ime.keyboard.internal.KeyboardId
import dev.bbkb.ime.keyboard.KeyboardSwitcher
import dev.bbkb.ime.keyboard.MainKeyboardView
import dev.bbkb.ime.keyboard.auxbar.ArrowBarController
import dev.bbkb.ime.keyboard.auxbar.autofill.InlineAutofillManager
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojibaseDataProvider
import dev.bbkb.ime.keyboard.auxbar.suggestions.CJKSuggestionGridView
import dev.bbkb.ime.keyboard.auxbar.suggestions.SuggestionStripListener
import dev.bbkb.ime.keyboard.inputboard.UnifiedInputBoardHandler
import dev.bbkb.ime.keyboard.inputboard.clipboard.ClipboardController
import dev.bbkb.ime.keyboard.inputboard.fcc.FccController
import dev.bbkb.ime.keyboard.inputboard.numberpad.NumberPadController
import dev.bbkb.ime.keyboard.inputboard.voice.VoiceInputController
import dev.bbkb.ime.keyboard.internal.GestureEventProcessor
import dev.bbkb.ime.keyboard.slideboard.NumericSubpanelController
import dev.bbkb.ime.keyboard.slideboard.QuickPhrasesController
import com.blackberry.nuanceshim.NuanceSDK
import com.blackberry.nuanceshim.languagepack.LanguagePackLocaleMonitor
import com.blackberry.nuanceshim.languagepack.LanguagePackManager
import java.io.FileDescriptor
import java.io.PrintWriter
import java.util.Locale
import dev.bbkb.ime.core.shared.InputPathDebug
import dev.bbkb.ime.core.gesture.replay.GestureReplayReceiver
import dev.bbkb.ime.core.gesture.replay.SensorVizOverlay
import dev.bbkb.ime.core.locale.SubtypeManager
import dev.bbkb.ime.core.locale.SubtypeState
import dev.bbkb.ime.core.locale.RichInputMethodManager
import dev.bbkb.ime.core.engine.NuanceSDKManager
import dev.bbkb.ime.core.ime.UIUpdateHandler
import dev.bbkb.ime.core.ime.ControlModeController
import dev.bbkb.ime.core.ime.HardwareKeyBridge
import dev.bbkb.ime.core.ime.CkbGestureBridge
import dev.bbkb.ime.core.ime.ImeInsets
import dev.bbkb.ime.core.ime.ThemePrefsListener
import dev.bbkb.ime.core.ime.InputViewCoordinator
import dev.bbkb.ime.core.ime.SwipeToDeleteAnimatorView
import dev.bbkb.ime.core.ime.VkbGestureListener
import dev.bbkb.ime.core.keyevent.KeyEventProcessor
import dev.bbkb.ime.core.keyevent.MultitapEventHandler
import dev.bbkb.ime.core.keyevent.SoftwareMultitapHandler
import dev.bbkb.ime.core.keyevent.ModifierStatusBarUpdater

class BlackBerryIME : InputMethodService(),
    DictionaryLoader.DictionaryInitCallback,
    SettingsManager.OnSettingsChangeListener,
    CJKSuggestionGridView.Listener,
    FlickSuggestionView.Listener,
    SuggestionStripListener,
    CursorMovementListener,
    KeyboardActionListenerInterface,
    dev.bbkb.ime.keyboard.auxbar.AuxBarManager.AuxBarEventListener {

    companion object {
        @JvmField
        val ASSERTIONS_DISABLED: Boolean = !BlackBerryIME::class.java.desiredAssertionStatus()

        @JvmField
        val LOG_TAG: String = BlackBerryIME::class.java.simpleName

        /**
         * The two [PhysicalKeyboardStateTracker.getSymbolPageOrder] values that mean "a symbol
         * page is showing" (audit CT-22: these were bare 1/3 literals here and in
         * KeyEventProcessor).
         */
        const val SYMBOL_PAGE_ORDER_FIRST = 1
        const val SYMBOL_PAGE_ORDER_SECOND = 3
    }

    // ==================== Fields: framework / window ====================

    private var mThemedContext: Context? = null

    private var mInflater: LayoutInflater? = null

    /* Track current night mode for real-time theme switching */
    private var mCurrentNightMode: Int = -1

    /* Preference change listener for theme changes */
    private var themeChangeListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /**
     * ISSUE 2 (modern back) — Android 13+ predictive back callback.
     *
     * On `targetSdk = 33+` IMEs that want correct predictive-back gesture animations
     * must register an `OnBackInvokedCallback` rather than rely on the legacy
     * `KEYCODE_BACK` dispatch. We hold the callback as a field so we can unregister
     * it in `onWindowHidden()`.
     *
     * The field is declared as `Any?` so the source file remains compilable on
     * Android Studio configurations that strip API-33 stubs from the boot classpath.
     * The actual registration / unregistration helpers below cast it to the
     * `android.window.OnBackInvokedCallback` shape behind a `Build.VERSION.SDK_INT`
     * gate.
     */
    private var backInvokedCallback: Any? = null

    var inputMethodImpl: AbstractInputMethodService.AbstractInputMethodImpl? = null

    var extractEditText: TextView? = null

    private val extractTextPreDrawListener: ViewTreeObserver.OnPreDrawListener = ViewTreeObserver.OnPreDrawListener {
        this@BlackBerryIME.updateExtractedText()
        true
    }

    var isShowingWindow: Boolean = false

    var currentAlertDialog: AlertDialog? = null

    // ==================== Fields: input-session state ====================

    var isInputActive: Boolean = false

    var currentPackageName: String? = null

    private var isBlackBerryApp: Boolean = false

    var shouldShowOnFirstKeypress: Boolean = false

    var needsKeyboardReload: Boolean = false

    var wasBackPressed: Boolean = false

    var shouldInsertAsString: Boolean = false

    var isAltEnterPressed: Boolean = false

    var lastKeyEventTime: Long = 0L

    var lastCursorAnchorInfo: CursorAnchorInfo? = null

    var isCursorModeEnabled: Boolean = false

    /** True while the multifunction key is held down remapped as Ctrl, so its key-up is
     *  remapped consistently even if the Alt state changed mid-hold. */
    private var multifunctionCtrlDown = false

    private var lastBatchUpdateRequestTime = 0L

    // ==================== Fields: views (assigned by InputViewCoordinator) ====================

    var rootInputView: View? = null


    var flickSuggestionView: FlickSuggestionView? = null

    var cjkSuggestionGridView: CJKSuggestionGridView? = null

    var swipeToDeleteAnimatorView: SwipeToDeleteAnimatorView? = null

    var arrowBarController: ArrowBarController? = null

    var auxBarManager: dev.bbkb.ime.keyboard.auxbar.AuxBarManager? = null

    var unifiedInputBoardHandler: UnifiedInputBoardHandler? = null

    // ==================== Fields: input-board / panel controllers ====================

    var fccController: FccController? = null

    var clipboardController: ClipboardController? = null

    var numberPadController: NumberPadController? = null

    var voiceInputController: VoiceInputController? = null

    var quickPhrasesController: QuickPhrasesController? = null

    var numericSubpanelController: NumericSubpanelController? = null

    // ==================== Fields: singletons and managers ====================

    private val settingsManager: SettingsManager = SettingsManager.getInstance()

    private val subtypeManager: SubtypeManager = SubtypeManager.getInstance()

    private val keyboardSwitcher: KeyboardSwitcher = KeyboardSwitcher.getInstance()

    var richInputMethodManager: RichInputMethodManager? = null

    private val dictionaryLoader: DictionaryLoader = DictionaryLoader()

    var languagePackLocaleMonitor: LanguagePackLocaleMonitor? = null

    var contactsLearningManager: ContactsLearningManager? = null

    var dynamicLearningManager: DynamicLearningManager? = null

    var subtypeSwitcherReceiver: SubtypeSwitcherReceiver? = null

    var shakeGestureHandler: ShakeGestureHandler? = null

    /* Input handler for device-specific logic (PKB vs VKB) */

    private val currencyKeyHandler: CurrencyKeyHandler = CurrencyKeyHandler()

    private val subtypeState: SubtypeState = SubtypeState()

    @JvmField
    val keyEventConverters: SparseArray<KeyEventConverter> = SparseArray(1)

    private val cursorTracker: CursorTracker = CursorTracker(this)

    var vkbGestureListener: VkbGestureListener? = null

    var gestureDetector: MultiPointerGestureDetector? = null

    // ==================== Fields: collaborators built on this service (initialiser order matters: later ones may read earlier fields) ====================

    private val physicalKeyboardStateTracker: PhysicalKeyboardStateTracker = PhysicalKeyboardStateTracker(ModifierStatusBarUpdater(this))

    private val inputLogic: InputLogic = InputLogic(this, this, dictionaryLoader)

    @JvmField
    val uiUpdateHandler: UIUpdateHandler = UIUpdateHandler(this)

    /** W6a façade: the single funnel for all suggestion-strip update requests. */
    @JvmField
    val suggestionUpdater: SuggestionUpdater = SuggestionUpdater(this)

    @JvmField
    val multitapEventHandler: MultitapEventHandler =
        MultitapEventHandler(this, keyboardSwitcher.getKeyboardLayoutCallback())

    @JvmField
    val softwareMultitapHandler: SoftwareMultitapHandler = SoftwareMultitapHandler(this, keyboardSwitcher)

    private val uiCoordinator: InputViewCoordinator = InputViewCoordinator(this)

    /** Ctrl-shortcut / control-mode state machine; the host adapter is the only path to UI/editor. */
    private val controlMode = ControlModeController(object : ControlModeController.Host {
        override val isVkbControlModeEnabled: Boolean get() = settingsManager.getSettingsValues().isVkbControlModeEnabled
        override val controlModeSetting: Int get() = settingsManager.getSettingsValues().controlMode
        override fun sendKeyDownWithMeta(keyCode: Int, metaState: Int) = inputLogic.sendKeyDownWithMeta(keyCode, metaState)
        override fun sendKeyUpWithMeta(keyCode: Int, metaState: Int) = inputLogic.sendKeyUpWithMeta(keyCode, metaState)
        override fun showControlModeUi() {
            // The notice bar itself is gone (its view forced itself GONE), but these four
            // actions ran on every sticky-control-mode entry and are the visible behaviour.
            hideUnifiedInputBoard()
            enableCursorMode(false)
            uiCoordinator.hideSuggestionViews()
            hideInputBoard()
        }
        override fun hideControlModeUi() {
            // Was hideControlModeNotice(), whose body was unreachable: it returned early on
            // isControlModeShowing(), which the GONE-forcing view made permanently false.
        }
    })

    /** Accessibility-interceptor special keys, SYM / Alt+Sym, alt-character resolver, voice key. */
    private val hardwareKeys = HardwareKeyBridge(this)

    /** CKB gesture arbiter (classify → policy → action) and the cursor-mode drag. */
    private val ckbGestures = CkbGestureBridge(this)

    /**
     * Audit CT-27: the bounded worker pool for cold-start warmups. These used to be four raw
     * `Thread(...).start()` calls that nothing could join, cancel or bound — three of them racing
     * the first `onStartInputView`, one of them (`NuanceSDKPrewarm`) calling `sdk.clear()` on the
     * shared native engine. One pool, shut down in `releaseResources`, gives the same overlap
     * with a cancellation point.
     */
    private val startupExecutor: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newFixedThreadPool(2) { r ->
            Thread(r, "ImeStartup").apply { isDaemon = true }
        }

    /** Token for [uiUpdateHandler]-posted deferred startup work, so onDestroy can cancel it. */
    private val deferredInitToken = Any()

    /** True once [registerNonCriticalReceivers] has actually registered the three receivers. */
    private var nonCriticalReceiversRegistered = false

    private val imeInsets = ImeInsets()

    private val disableCursorModeRunnable: Runnable = Runnable {
        this@BlackBerryIME.enableCursorMode(false)
    }

    // Constructed lazily: they capture collaborators that onCreate initialises after this
    // object's property initialisers run, and registration is deferred past onCreate anyway.
    private val connectivityAndScreenReceiver: BroadcastReceiver by lazy {
        ConnectivityAndScreenReceiver(this, subtypeManager, physicalKeyboardStateTracker)
    }

    private val learningModelSaveReceiver: BroadcastReceiver by lazy {
        LearningModelSaveReceiver(dynamicLearningManager!!)
    }

    private val localeChangeReceiver: BroadcastReceiver by lazy {
        LocaleChangeReceiver(this, subtypeManager, richInputMethodManager!!)
    }

    // Debug-only rigs (never constructed in release builds).
    private var gestureReplay: GestureReplayReceiver? = null

    private val sensorVizOverlay: SensorVizOverlay by lazy { SensorVizOverlay(this) }

    private val inputSessionCoordinator = InputSessionCoordinator(this)

    private val keyEventProcessor = KeyEventProcessor(this)

    // ==================== Service lifecycle ====================

    @Throws(Resources.NotFoundException::class)
    override fun onCreate() {
        StartupTiming.anchor("ime.onCreate")
        val onCreateToken = StartupTiming.begin()
        initializeStrictMode()
        InAppEventBus.getInstance().subscribe(LanguageVariantStore.ACTION_LANGUAGE_PACK_CHANGED, languagePackChangedListener)

        // First-load: getDefaultSharedPreferences() only *starts* an async file load;
        // the first read blocks until it finishes. Force both prefs files to finish
        // loading on a worker so the parse overlaps the service/singleton init below
        // instead of stalling the first main-thread pref read (~190 ms measured).
        PrefsManager.init(applicationContext)
        startupExecutor.execute {
            try {
                PrefsManager.getPrefs().contains(PrefsManager.Keys.KEYBOARD_THEME_MODE)
                getSharedPreferences("savestate", 0).contains("isManaged")
            } catch (_: Throwable) {}
        }

        // Step 1: Initialize components that MUST run before the service is created.
        var t = StartupTiming.begin()
        SettingsManager.initialize(applicationContext)
        StartupTiming.end("ime.onCreate.settingsManagerInitialize", t)
        settingsManager.addOnSettingsChangeListener(this)
        t = StartupTiming.begin()
        KeyboardSwitcher.initialize(this, uiCoordinator)
        StartupTiming.end("ime.onCreate.keyboardSwitcherInitialize", t)

        // Step 2: Call super.onCreate() ONCE to create the base service.
        t = StartupTiming.begin()
        super.onCreate()
        StartupTiming.end("ime.onCreate.superOnCreate", t)

        // W1 (debug): load + self-check the OWNED ET9KDB module (libxt9kdb.so). Inert w.r.t. the
        // live keyboard — parses a KDB asset through the owned Load_XmlKDB and logs the model.
        // Runs off-thread: the ~100 ms asset parse doesn't need to block cold start.
        if (BuildConfig.DEBUG) {
            startupExecutor.execute {
                try { com.blackberry.nuanceshim.Xt9Kdb.selfCheck(this, "athena") } catch (_: Throwable) {}
            }
        }

        // W2 replay doorway (debug builds only; master plan W2, owner-approved 2026-08-15):
        // replays a recorded or synthetic CKB gesture trace into onGenericMotionEvent — the
        // exact entry the capacitive pad's hardware events use — with original coordinates and
        // inter-sample timing. Events carry the profile's touchKeypadDeviceId so the
        // isFromTouchKeypad gate passes identically to hardware (the emulator poses as a KEY2
        // via the device-config screen's athena config, whose CKB device-type forces
        // hasTouchKeypad). Release builds register nothing.
        if (BuildConfig.DEBUG) gestureReplay = GestureReplayReceiver(this).also { it.register() }

        // ISSUE 2 FIX: Explicitly declare back-handling disposition. Without this
        // the platform falls back to OEM-specific heuristics for whether the
        // navigation bar's Back button should reach the IME or the foreground
        // activity. BACK_DISPOSITION_DEFAULT lets the framework hide the IME on
        // back press whenever the input window is visible, which is exactly what
        // we want.
        setBackDisposition(InputMethodService.BACK_DISPOSITION_DEFAULT)

        // Step 3: Now that the service exists, set up our themed context and the rest.
        t = StartupTiming.begin()
        initializeThemedContext()
        StartupTiming.end("ime.onCreate.themedContext", t)
        t = StartupTiming.begin()
        initializeSingletons()
        StartupTiming.end("ime.onCreate.singletons", t)
        t = StartupTiming.begin()
        initializeColorManager()
        StartupTiming.end("ime.onCreate.colorManager", t)
        t = StartupTiming.begin()
        initializeLearningManagers()
        StartupTiming.end("ime.onCreate.learningManagers", t)

        // ISSUE 1 FIX: Defer non-critical onCreate work off the cold-start path.
        //
        // - registerNonCriticalReceivers(): broadcast filters for connectivity, ringer,
        //   screen-off, learning-model-save, locale-changed. None of these are needed
        //   for the first onCreateInputView / onStartInputView cycle.
        // - initializeKeyInterceptor(): only relevant for PKB devices, and the IME
        //   processes its first physical-key event tens of ms after onCreate at the
        //   earliest. We can register the AccessibilityService callback after the
        //   first frame without risking a missed key.
        //
        // initializeControllers() and initializeBackgroundPreloading() stay on the
        // critical path: controllers (FCC, clipboard, voice) are needed by
        // setInputView()'s wiring, and the preload-thread spawn itself is cheap.
        // Audit CT-24/CT-27: post through the IME's own handler with a token instead of a
        // throwaway Handler nothing could cancel. Without the token, a service destroyed inside
        // the 150 ms window ran registerNonCriticalReceivers() AFTER releaseResources() had
        // already unregistered, leaving three process-scoped receivers holding the dead service.
        uiUpdateHandler.postTokenized(deferredInitToken, 150L) { registerNonCriticalReceivers() }
        uiUpdateHandler.postTokenized(deferredInitToken, 0L) { hardwareKeys.registerInterceptorCallbacks() }

        t = StartupTiming.begin()
        initializeControllers()
        StartupTiming.end("ime.onCreate.controllers", t)
        t = StartupTiming.begin()
        initializeBackgroundPreloading()
        StartupTiming.end("ime.onCreate.backgroundPreloading", t)
        StartupTiming.end("ime.onCreate.TOTAL", onCreateToken)
    }

    private fun initializeStrictMode() {
        val isDebug = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (isDebug) {
            android.os.StrictMode.setThreadPolicy(android.os.StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build())
            android.os.StrictMode.setVmPolicy(android.os.StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectActivityLeaks()
                .penaltyLog()
                .build())
        }
    }

    private fun initializeThemedContext() {
        // CRITICAL: Use Material 3 IME theme to properly propagate theme-aware colors
        mThemedContext = ContextThemeWrapper(this, R.style.Theme_BlackberryKeyboard_IME)
        mInflater = mThemedContext!!.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        if (BuildConfig.DEBUG) Log.i(LOG_TAG, "onCreate()")

        // Initialize night mode tracking for real-time theme switching
        val config = resources.configuration
        mCurrentNightMode = config.uiMode and Configuration.UI_MODE_NIGHT_MASK
    }

    private fun initializeSingletons() {
        var t = StartupTiming.begin()
        RichInputMethodManager.init(this)
        richInputMethodManager = RichInputMethodManager.getInstance()
        StartupTiming.end("ime.singletons.richInputMethodManager", t)
        t = StartupTiming.begin()
        SubtypeManager.initialize(applicationContext)
        StartupTiming.end("ime.singletons.subtypeManager", t)
        t = StartupTiming.begin()
        AudioAndHapticFeedbackManager.init(this)
        InputMethodHelper.init(this as Context)
        DictionaryManager.shutdownInstance()
        uiUpdateHandler.initializeDelays()
        StartupTiming.end("ime.singletons.audioHelperDictHandler", t)

        // Initialize unified DeviceProfile with device-specific overrides.
        // The heavy part (config XML parse + hardware scan) runs on startupExecutor; this call
        // only publishes the ticket. See DeviceProfile.startInitialize / awaitInitialized.
        t = StartupTiming.begin()
        DeviceProfile.startInitialize(applicationContext, resources.configuration)
        StartupTiming.end("ime.singletons.deviceProfileStart", t)

        hardwareKeys.install()
    }

    private fun initializeColorManager() {
        // Initialize PrefsManager singleton for centralized SharedPreferences access
        PrefsManager.init(this)
        val prefs = PrefsManager.getPrefs()
        PrefsManager.migrateThemePrefs(prefs)
        KeyboardColorManager.init(
            this,
            KeyboardColorManager.Style.fromPref(
                prefs.getString(PrefsManager.Keys.KEYBOARD_THEME_STYLE, "modern")),
            KeyboardColorManager.Scheme.fromPref(
                prefs.getString(PrefsManager.Keys.KEYBOARD_THEME_MODE, "auto")),
            prefs.getBoolean(PrefsManager.Keys.KEYBOARD_USE_SYSTEM_COLORS, false),
        )

        // Register preference listener for theme changes
        themeChangeListener = ThemePrefsListener(this)
        prefs.registerOnSharedPreferenceChangeListener(themeChangeListener)
    }

    private fun initializeLearningManagers() {
        dynamicLearningManager = DynamicLearningManager(applicationContext)
        languagePackLocaleMonitor = LanguagePackLocaleMonitor(applicationContext, uiUpdateHandler)
        contactsLearningManager = ContactsLearningManager(this)
        // loadSettings() must stay on the critical path — settingsManager.getSettingsValues()
        // is dereferenced (with !!) from many subsequent code paths.
        loadSettings()
        // The profile must be readable before anything reads a keyboard shape off it, and
        // SettingsManager.loadSettings -> DeviceProfile.fromSettings has just done so. Log what
        // the background load cost the main thread here, where the first join actually happened.
        Logger.info(LOG_TAG, "DeviceProfile initialized: ${DeviceProfile.current()}")
        // ISSUE 1 FIX: reloadDictionaryForSubtype() kicks off dictionary initialization, which is
        // already internally async (loadDictionaryAsync posts to a worker thread),
        // but still does synchronous setup on the main thread (locale-monitor
        // bookkeeping, language notification, personalized dictionary attach).
        // None of that needs to happen before onCreateInputView is allowed to run,
        // so we post it to the front of the main-thread queue. It will fire after
        // onCreate returns and the framework has had a chance to call
        // onCreateInputView for the first editor.
        // Audit CT-27: the IME already owns a main-thread handler; use it (and its cancel token)
        // instead of allocating a throwaway Handler for one post.
        uiUpdateHandler.postTokenized(deferredInitToken, 0L) { reloadDictionaryForSubtype() }
    }

    private fun initializeControllers() {
        subtypeSwitcherReceiver = SubtypeSwitcherReceiver(this, uiUpdateHandler)
        shakeGestureHandler = ShakeGestureHandler(this, this)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            voiceInputController = VoiceInputController(this, uiCoordinator)
        }
        fccController = FccController(this, uiCoordinator)
        clipboardController = ClipboardController(this, uiCoordinator)
        numberPadController = NumberPadController(this, uiCoordinator)
        quickPhrasesController = QuickPhrasesController(this, settingsManager)
        numericSubpanelController = NumericSubpanelController(this, subtypeManager, settingsManager)
        uiCoordinator.initialize()
    }

    private fun initializeBackgroundPreloading() {
        // Audit DK-18: KeyEventConverter is constructed lazily on the key path and its
        // initializeAltMappingsIfNeeded() does a synchronous XmlResourceParser walk (plus, on a
        // miss, a second attempt through DeviceInputResolver) on the calling thread — so the whole
        // load used to land on the FIRST KEYSTROKE after IME start, on the main thread. Warm the
        // cache here instead; AltMappingsParser.getCachedMappings is synchronized, so the per-event call
        // then only ever hits the cache.
        startupExecutor.execute {
            try {
                val profile = DeviceProfile.current()
                if (profile.hasCustomAltMappings()) profile.getAltMappingsTable(applicationContext)
            } catch (e: Throwable) {
                Logger.warn(LOG_TAG, "Alt-mappings prewarm failed: ${e.message}")
            }
        }

        // The language-pack catalogue (ldb/manifest.json, 105 entries, plus the side-loaded
        // pack registry file) is parsed lazily now; nothing reads it before the first dictionary
        // load, which is itself posted off onCreate. Warm it here so that load finds it done.
        startupExecutor.execute {
            try {
                com.blackberry.nuanceshim.languagepack.LanguagePackManager
                    .getInstance(applicationContext).prewarmRegistry()
            } catch (e: Throwable) {
                Logger.warn(LOG_TAG, "Language-pack registry prewarm failed: ${e.message}")
            }
        }

        // Phase 1 Optimization: Pre-build unified input bar keyboard in background
        startupExecutor.execute {
            try {
                val themedContext = android.view.ContextThemeWrapper(
                    applicationContext, R.style.KeyboardTheme_LXX)

                val builder = dev.bbkb.ime.keyboard.KeyboardBuilder.Builder(themedContext, null)

                builder.setSubtype(dev.bbkb.ime.core.subtypeswitcher.SubtypeFactory.createSubtype(
                    Locale.ENGLISH.toString(), "unified_input_menu"))

                val resources = themedContext.resources
                builder.setKeyboardGeometry(
                    ResourceConfigManager.getScreenWidthPixels(resources),
                    ResourceConfigManager.getSuggestionsStripHeight(resources)
                )

                val keyboardBuilder = builder.build()

                // Pre-build layout 138 (unified input menu bar keyboard)
                keyboardBuilder.getKeyboardForShift(138, true)

                Logger.info(LOG_TAG, "Successfully pre-built unified input bar keyboard in background")
            } catch (e: Exception) {
                Logger.warn(LOG_TAG, "Failed to pre-build unified input bar keyboard: ${e.message}")
                // Non-fatal: keyboard will be built on-demand if pre-building fails
            }
        }

        // PKB Optimization: Pre-warm NuanceSDK in background for faster first suggestion
        if (DeviceProfile.current().isPkbDevice()) {
            startupExecutor.execute {
                try {
                    val startTime = System.currentTimeMillis()
                    val sdk = NuanceSDKManager.getInstance()
                    if (sdk != null) {
                        sdk.clear()
                        val elapsed = System.currentTimeMillis() - startTime
                        Logger.info(LOG_TAG, "PKB: NuanceSDK pre-warmed in ${elapsed}ms")
                    }
                } catch (e: Exception) {
                    Logger.warn(LOG_TAG, "PKB: Failed to pre-warm NuanceSDK: ${e.message}")
                }
            }
        }
    }

    /**
     * Register non-critical broadcast receivers.
     * Called after CommitType delay from onCreate() to move work off the critical startup path.
     */
    private fun registerNonCriticalReceivers() {
        try {
            registerNotExported(connectivityAndScreenReceiver,
                "android.net.conn.CONNECTIVITY_CHANGE", "android.media.RINGER_MODE_CHANGED", "android.intent.action.SCREEN_OFF")
            registerNotExported(learningModelSaveReceiver,
                "android.intent.action.SCREEN_OFF", "android.intent.action.ACTION_POWER_CONNECTED")
            registerNotExported(localeChangeReceiver, "android.intent.action.LOCALE_CHANGED")
            // Audit CT-24: record that registration actually happened, so releaseResources
            // does not construct the `by lazy` receivers (and swallow IllegalArgumentException)
            // for a registration that never ran.
            nonCriticalReceiversRegistered = true
            Logger.debug(LOG_TAG, "Non-critical receivers registered")
        } catch (e: Exception) {
            Logger.warn(LOG_TAG, "Failed to register non-critical receivers: ${e.message}")
        }
    }

    private fun registerNotExported(receiver: BroadcastReceiver, vararg actions: String) {
        val filter = IntentFilter().apply { actions.forEach(::addAction) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    /**
     * IB-26: the emojibase dataset (a 735 KB asset parsed into ~3700 EmojiData objects plus its
     * category and skin-tone indexes) lives in a process-lifetime static with no eviction hook.
     * Drop it once the IME UI is hidden or the system is under pressure; it is re-parsed lazily
     * the next time the emoji board or the custom symbol page asks for it.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            EmojibaseDataProvider.releaseShared()
        }
    }

    override fun onDestroy() {
        InAppEventBus.getInstance().unsubscribe(LanguageVariantStore.ACTION_LANGUAGE_PACK_CHANGED, languagePackChangedListener)
        // Unregister theme change listener
        if (themeChangeListener != null) {
            val prefs = PrefsManager.getPrefs()
            prefs.unregisterOnSharedPreferenceChangeListener(themeChangeListener)
        }

        gestureReplay?.unregister()
        gestureReplay = null
        releaseResources()
        DictionaryManager.getInstance().shutdown()
        enableCursorMode(false)
        dictionaryLoader.closeAndReset()
        super.onDestroy()
    }

    private fun releaseResources() {
        settingsManager.removeOnSettingsChangeListener(this)
        settingsManager.unregisteredListener()
        // Audit CT-24: drop any still-pending deferred startup work FIRST, so a service torn
        // down inside the 150 ms window cannot register receivers after this point. The
        // registered flag then keeps the unregister calls (and the `by lazy` receiver
        // construction they force) from running for a registration that never happened.
        uiUpdateHandler.removeCallbacksAndMessages(deferredInitToken)
        // Audit CT-27: stop accepting new warmup work; in-flight tasks are allowed to finish.
        startupExecutor.shutdown()
        ckbGestures.release()
        if (nonCriticalReceiversRegistered) {
            nonCriticalReceiversRegistered = false
            try { unregisterReceiver(connectivityAndScreenReceiver) } catch (ignored: IllegalArgumentException) {}
            try { unregisterReceiver(learningModelSaveReceiver) } catch (ignored: IllegalArgumentException) {}
            try { unregisterReceiver(localeChangeReceiver) } catch (ignored: IllegalArgumentException) {}
        }
        // Both interceptor callbacks, not just the special-key one: the all-keys callback is what
        // lets KeyInterceptorService's Alt bleed-through block consume hardware Alt, so leaving it
        // installed past this point would keep every hardware Alt consumed system-wide with a dead
        // IME behind it (and retain that IME via a static field).
        hardwareKeys.unregisterInterceptorCallbacks()
        physicalKeyboardStateTracker.onModifierListenerReset()
        languagePackLocaleMonitor!!.cleanup()
        flickSuggestionView?.init(null as FlickSuggestionView.Listener?, null as MainKeyboardView?)
        flickSuggestionView = null
        cjkSuggestionGridView?.setListener(null)
        cjkSuggestionGridView = null
        fccController?.destroy()
        fccController = null
        clipboardController?.destroy()
        clipboardController = null
        numberPadController?.destroy()
        numberPadController = null
        voiceInputController?.destroy()
        voiceInputController = null
        contactsLearningManager!!.unregister()
        dynamicLearningManager!!.destroy()
        keyboardSwitcher.destroy()
        InlineAutofillManager.destroyInstance()
        EmojibaseDataProvider.releaseShared()
        shakeGestureHandler!!.stop()
        quickPhrasesController?.hide()
        quickPhrasesController = null
        numericSubpanelController?.refresh()
        numericSubpanelController = null
        NuanceSDKManager.disposePrimary()
    }

    fun recycle() {
        releaseResources()
        inputLogic.shutdown()
        super.onDestroy()
    }

    override fun onConfigurationChanged(configuration: Configuration) {
        // UIM-07 fix: Deterministically close all boards before any config change handling.
        uiCoordinator.hideAllInputUi()

        controlMode.resetAll()
        controlMode.clearControlState()
        uiUpdateHandler.resetOrientationState()
        val settingsValues = settingsManager.getSettingsValues()
        val zM4990a = SettingsManager.hasHardwareKeyboard(configuration)
        vkbGestureListener?.clearLastKeyEventTime()

        // Real-time theme switching: Detect system theme change
        val newNightMode = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (mCurrentNightMode != -1 && mCurrentNightMode != newNightMode) {
            val prefs = PrefsManager.getPrefs()
            val themeMode = prefs.getString("pref_keyboard_theme_mode", "auto")

            if ("auto" == themeMode) {
                Logger.debug(LOG_TAG, "System theme changed (night mode: $newNightMode), reloading keyboard theme")
                loadSettings()

                keyboardSwitcher.getUnifiedInputBoardManager()?.updateTheme(this)
                // The keyboard view repaints through KeyboardColorManager's observer
                // chain (onConfigurationChanged below reloads the palette); there is
                // no themed context to swap and no input view to recreate.
            }
        }
        mCurrentNightMode = newNightMode

        if (settingsValues.displayOrientation != configuration.orientation) {
            Logger.debug(LOG_TAG, "orientation changed: newOrientation=${configuration.orientation}")
            quickPhrasesController?.hide()
            keyboardSwitcher.getSlideboardManager()?.hide()
            uiUpdateHandler.prepareForOrientationChange()
            inputLogic.commitComposingOrReset(settingsManager.getSettingsValues())
            physicalKeyboardStateTracker.resetAllMetaState()
            updatePhysicalKeyboardFilter()
        }

        if (settingsValues.hasHardwareKeyboard != zM4990a) {
            onKeyboardConfigurationChanged(zM4990a, configuration.orientation)
        }

        // Update color manager for theme changes (dark mode toggle, wallpaper changes)
        KeyboardColorManager.onConfigurationChanged()

        // Re-apply nav-bar insets after a config/orientation change so the keyboard stays
        // positioned above the nav bar in the new configuration (§1).
        rootInputView?.let { androidx.core.view.ViewCompat.requestApplyInsets(it) }

        super.onConfigurationChanged(configuration)
    }

    fun onKeyboardConfigurationChanged(z: Boolean, i: Int) {
        Logger.info(LOG_TAG, "Updating keyboard configuration settings")
        if (!z && i == 2 && !isInputViewShown() && hasActiveInputConnection() && !wasBackPressed) {
            richInputMethodManager!!.getInputMethodManager().showSoftInputFromInputMethod(window.window!!.attributes.token, 0)
        }
        loadSettings()
        if (settingsManager.getSettingsValues().hasHardwareKeyboard) {
            cleanupKeyboard()
        }
        enableCursorMode(false)
        fccController?.hideFcc()
        Logger.info(LOG_TAG, "Done updating keyboard configuration settings")
    }

    override fun getSystemService(name: String): Any? {
        // Serve the themed inflater once it exists; before that, or for any other service, defer.
        if (Context.LAYOUT_INFLATER_SERVICE == name) mInflater?.let { return it }
        return super.getSystemService(name)
    }

    // ==================== View lifecycle and window ====================

    override fun onCreateInputView(): View {
        Logger.debug(LOG_TAG, "onCreateInputView() - Creating keyboard view")
        // The custom keyboard views never opted into a hardware layer (the historical reflective
        // lookup of the hidden enableHardwareAcceleration() always returned null); the IME window
        // itself is hardware-accelerated by the framework regardless.
        return keyboardSwitcher.createInputView(false)
    }

    override fun setInputView(view: View) {
        super.setInputView(view)
        uiCoordinator.setupInputView(view)
        ImeInsets.installNavBarInsetListener(view)
    }

    override fun setExtractView(view: View?) {
        var textView: TextView? = null
        val textView2 = extractEditText
        super.setExtractView(view)
        if (view != null) {
            val viewFindViewById = view.findViewById<View>(android.R.id.inputExtractEditText)
            textView = if (viewFindViewById is TextView) viewFindViewById else null
        }
        if (textView2 === textView) {
            return
        }
        textView2?.viewTreeObserver?.removeOnPreDrawListener(extractTextPreDrawListener)
        extractEditText = textView
        extractEditText?.viewTreeObserver?.addOnPreDrawListener(extractTextPreDrawListener)
    }

    fun updateExtractedText() {
        if (!isFullscreenMode()) return
        val textView = extractEditText ?: return
        inputLogic.updateCursorAnchorInfo(CursorAnchorInfoUtils.getCursorAnchorInfo(textView))
    }

    /**
     * Returns whether the soft input view should be shown.
     *
     * IMPORTANT — PKB vs VKB distinction:
     *
     * On a PKB (physical-keyboard) device the IME's "input view" is the suggestion
     * strip / Unified Input Menu (UIM), NOT a full on-screen keyboard. The hardware
     * keyboard handles typing, but the IME must still display its suggestion strip.
     * The framework's `super.onEvaluateInputViewShown()` returns `false` whenever a
     * hardware keyboard is connected (it is trying to suppress the soft VKB), so we
     * must NOT defer to super on PKB devices — doing so hides the suggestion strip /
     * UIM entirely (regression observed after the lifecycle audit). PKB devices
     * therefore preserve the original semantics: show the input view, except when the
     * device has been rotated out of its natural orientation into a non-landscape
     * configuration (the legacy `isLandscapeOrientation()` heuristic).
     *
     * On a VKB (touchscreen-only) device we defer to `super.onEvaluateInputViewShown()`
     * so the soft keyboard hides correctly when there is no editor (e.g., on the home
     * screen) or when an external hardware keyboard is attached. This is the modern
     * lifecycle fix for Issue 3 (IME staying visible after returning home).
     *
     * The PKB home-screen hiding behavior is handled separately by the binding gate in
     * `onShowInputRequested()` and the state clearing in `onWindowHidden()`, so PKB
     * devices do not need to suppress the input view here.
     */
    override fun onEvaluateInputViewShown(): Boolean {
        if (isShowingWindow) return true
        if (DeviceProfile.isVkbForcedForPackage(currentPackageName) || DeviceProfile.isForceVkbMode()) {
            return true
        }
        if (DeviceProfile.isPkb()) {
            // PKB: the suggestion strip / UIM is the input view and must show even
            // when a hardware keyboard is present (where super would return false).
            val rotation = getDisplayRotation()
            if (rotation != 0) return isLandscapeOrientation()
            return true
        }
        // VKB: defer to the framework so the IME hides when there is no editor.
        return super.onEvaluateInputViewShown()
    }

    /**
     * Recomputes whether the on-screen keyboard is showing and publishes it to [DeviceProfile]
     * (the side effect is the point; callers that ignore the return value want the publish).
     */
    fun refreshOnScreenKeyboardShowing(): Boolean {
        val z = super.onEvaluateInputViewShown() || DeviceProfile.isVkbForcedForPackage(currentPackageName) || DeviceProfile.isForceVkbMode() || isUimVisible() || isLandscapeOrientation()
        DeviceProfile.setOnScreenKeyboardShowing(z)
        return z
    }

    @SuppressWarnings("deprecation")
    private fun getDisplayRotation(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: 0
        } else {
            @Suppress("DEPRECATION")
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        }
    }

    fun isLandscapeOrientation(): Boolean {
        return !NuanceSDK.DEVICE_VENICE.equals(Build.DEVICE) && (resources.configuration.orientation == 2)
    }

    /**
     * Returns whether the IME wants to be shown in response to a `showSoftInput`
     * request from the application.
     *
     * Prior to the audit, this override unconditionally returned `true` for any
     * PKB device, which caused the IME to re-appear on the home screen whenever
     * the system issued a transient show request during a window transition.
     *
     * The fix:
     *   - Defer to `super.onShowInputRequested(...)` first.
     *   - Only force `true` for PKB devices when there is genuinely an editor
     *     waiting for input (a non-null `getCurrentInputBinding()` *and*
     *     `onEvaluateInputViewShown()` agrees the view should be shown).
     *   - Preserve the legacy non-PKB-device fallback for the `flags & 1 == 0`
     *     (explicit-show) case.
     */
    override fun onShowInputRequested(i: Int, z: Boolean): Boolean {
        var zOnShowInputRequested = super.onShowInputRequested(i, z)
        if (!zOnShowInputRequested && DeviceProfile.current().isPkbDevice()) {
            // PKB devices may want to show even when super says no — but only
            // when there is actually an editor bound and the view-shown predicate agrees.
            if (getCurrentInputBinding() != null && onEvaluateInputViewShown()) {
                zOnShowInputRequested = true
            }
        } else if (!zOnShowInputRequested && onEvaluateInputViewShown() && (i and 1) == 0) {
            zOnShowInputRequested = DeviceProfile.isPkb()
        }
        Logger.debug(LOG_TAG, "onShowInputRequested(): flags=$i configChange=$z show=$zOnShowInputRequested")
        return zOnShowInputRequested
    }

    override fun onEvaluateFullscreenMode(): Boolean {
        // Was InputHandler.shouldUseFullscreenMode(). The PKB arm returned false outright; the
        // VKB arm was "fullscreen enabled in settings AND not IME_FLAG_NO_FULLSCREEN", which the
        // next two lines already test (super.onEvaluateFullscreenMode() is where AOSP honours
        // that flag). Only the device-type half carried information.
        if (DeviceProfile.current().isPkbDevice()) return false
        val zM5010d = SettingsManager.isFullscreenModeEnabled(resources)
        if (!super.onEvaluateFullscreenMode() || !zM5010d) {
            return false
        }
        val currentInputEditorInfo = getCurrentInputEditorInfo()
        return currentInputEditorInfo == null || (currentInputEditorInfo.imeOptions and EditorInfo.IME_FLAG_NO_EXTRACT_UI) == 0
    }

    override fun updateFullscreenMode() {
        val window = window.window
        ViewLayoutUtils.updateLayoutHeightOf(window, -1)
        if (rootInputView != null) {
            val i = if (isFullscreenMode()) -2 else -1
            val viewFindViewById = window!!.findViewById<View>(android.R.id.inputArea)
            ViewLayoutUtils.updateLayoutHeightOf(viewFindViewById, i)
            ViewLayoutUtils.updateLayoutGravityOf(viewFindViewById, 80)
            ViewLayoutUtils.updateLayoutHeightOf(rootInputView, i)
        }
        super.updateFullscreenMode()
        inputLogic.setMoreKeysEnabled(isFullscreenMode())
    }

    override fun onComputeInsets(insets: InputMethodService.Insets) {
        val previousVisibleTop = insets.visibleTopInsets
        super.onComputeInsets(insets)
        val keyboardView = keyboardSwitcher.getActiveKeyboardView()
        val auxBarView = auxBarManager?.getAuxBarView()
        if (keyboardView == null || auxBarView == null) {
            if (BuildConfig.DEBUG) Log.w("BBKBdiag", "onComputeInsets: PREEMPT - no ${if (keyboardView == null) "keyboard view" else "aux bar"} (super insets left as-is)")
            return
        }
        val contentTop = imeInsets.compute(
            insets, rootInputView!!, keyboardView, auxBarView,
            isPkbDevice = DeviceProfile.current().isPkbDevice(),
            mainKeyboardShowing = keyboardSwitcher.isMainKeyboardShowing(),
        ) {
            // The CJK grid's visible-height bookkeeping has a side effect consumed elsewhere.
            cjkSuggestionGridView!!.setVisibleHeight(keyboardView.height)
        }
        if (contentTop != null && previousVisibleTop != contentTop) updateFlickMetrics()
    }

    override fun showWindow(z: Boolean) {
        if (BuildConfig.DEBUG) android.util.Log.d("KBD_LIFECYCLE", "showWindow(): showInput=$z")
        Logger.debug(LOG_TAG, "showWindow(): showInput=$z")
        super.showWindow(z)
        if (z) {
            // Surface-classification follow-up (2026-08-29): syncKeyboardLayout decides the
            // engine frame from "MainKeyboardView actually displayed", but the sync that runs
            // during startInput happens BEFORE the view is laid out (isShown/height still
            // false/0), so an appearing on-screen keyboard was left on the PKB frame until some
            // later sync. Re-sync after this window is up, posted so the view has real layout.
            keyboardSwitcher.getMainKeyboardView()?.let { v ->
                v.post { keyboardSwitcher.syncKeyboardLayout() }
                // Second pass after the window settles: the immediate post can still read a
                // transient view state (e.g. the keyboard view measured before the pad-mode
                // collapse) and a wrong frame would otherwise stick until the next layout event.
                v.postDelayed({ keyboardSwitcher.syncKeyboardLayout() }, 600)
            }
        }
    }

    override fun hideWindow() {
        if (BuildConfig.DEBUG) android.util.Log.d("KBD_LIFECYCLE", "hideWindow() called")
        Logger.debug(LOG_TAG, "hideWindow()")
        // Restored to match original smali bytecode.
        keyboardSwitcher.cancelKeyTimers()
        DeviceProfile.setForceVkbMode(false)
        if (isAlertDialogShowing()) {
            currentAlertDialog?.dismiss()
            currentAlertDialog = null
        }
        super.hideWindow()
    }

    fun showImeWindow() {
        isShowingWindow = true
        showWindow(true)
        isShowingWindow = false
    }

    override fun onWindowShown() {
        super.onWindowShown()
        // Re-request inset application on every show so the keyboard is positioned above the
        // nav bar deterministically, including after an IME switch-away-and-back (§0/§1).
        rootInputView?.let { androidx.core.view.ViewCompat.requestApplyInsets(it) }
        // ISSUE 2 FIX: clear the back-press latch on each fresh show so we cannot
        // inherit a stale `wasBackPressed = true` from a previous session, which
        // would suppress the auto-show-on-key-press path until a full editor reset.
        wasBackPressed = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerBackInvokedCallback()
        }
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        resetUiState()
        fccController?.dismiss()
        clipboardController?.dismiss()
        // ISSUE 3 FIX: clear auto-show latches so a stale key press cannot re-summon
        // the IME after the user returns to the home screen.
        shouldShowOnFirstKeypress = false
        wasBackPressed = false
        isInputActive = false
        lastCursorAnchorInfo = null
        // ISSUE 2 (modern back): pair with the registerBackInvokedCallback() call in
        // onWindowShown() so we don't leak a callback after the window is gone.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            unregisterBackInvokedCallback()
        }
    }

    /**
     * ISSUE 2 (modern back) — register an OnBackInvokedCallback on API 33+.
     *
     * The IME's window (not the Service itself) owns the dispatcher, so we have to
     * look it up via `window.window.onBackInvokedDispatcher`. The callback dismisses
     * the IME the same way `dismissKeyboard()` does — resetting UI state and then
     * calling `requestHideSelf(0)`.
     *
     * Manifest opt-in: `android:enableOnBackInvokedCallback="true"` on `<application>`.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun registerBackInvokedCallback() {
        if (backInvokedCallback != null) return
        val dispatcher = window?.window?.onBackInvokedDispatcher ?: return
        val cb = android.window.OnBackInvokedCallback { dismissKeyboard() }
        dispatcher.registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            cb
        )
        backInvokedCallback = cb
        Logger.debug(LOG_TAG, "registered OnBackInvokedCallback")
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun unregisterBackInvokedCallback() {
        val cb = backInvokedCallback as? android.window.OnBackInvokedCallback ?: return
        window?.window?.onBackInvokedDispatcher?.unregisterOnBackInvokedCallback(cb)
        backInvokedCallback = null
        Logger.debug(LOG_TAG, "unregistered OnBackInvokedCallback")
    }

    override fun onExtractedTextClicked() {
        if (settingsManager.getSettingsValues().shouldShowLxxButton) {
            return
        }
        super.onExtractedTextClicked()
    }

    override fun onExtractedCursorMovement(i: Int, i2: Int) {
        if (settingsManager.getSettingsValues().shouldShowLxxButton) {
            return
        }
        super.onExtractedCursorMovement(i, i2)
    }

    // ==================== Input session ====================

    override fun onCreateInputMethodInterface(): AbstractInputMethodService.AbstractInputMethodImpl {
        inputMethodImpl = super.onCreateInputMethodInterface()
        return inputMethodImpl!!
    }

    override fun onStartInput(editorInfo: EditorInfo?, z: Boolean) {
        Logger.info(LOG_TAG, "onStartInput(): restarting=$z, editorInfo=${if (editorInfo == null) "NULL" else "valid"}")
        // Investigation hook (debug only, opt-in via `setprop debug.et9.probe 1`): dump the ET9
        // engine's live ranking/adaptation configuration. onStartInput (not ...View) because PKB
        // sessions never create an input view. Opt-in because it can SIGSEGV on a cold start —
        // see Et9Probe.isProbeEnabled and docs/et9-ranking-documentation.md.
        if (BuildConfig.DEBUG) {
            com.blackberry.nuanceshim.Et9Probe.probe(NuanceSDKManager.getInstance())
        }
        if (BuildConfig.DEBUG) sensorVizOverlay.syncActiveFromPrefs()
        if (BuildConfig.DEBUG) android.util.Log.d("KBD_LIFECYCLE", "onStartInput(): restarting=$z isInputViewShown=${isInputViewShown()} pkg=${editorInfo?.packageName ?: "null"}")

        // ISSUE 2 FIX: clear the back-press latch on every onStartInput. The prior
        // logic only reset it in onStartInputViewInternal's `shouldInit` branch, which
        // left it stuck when the framework restarted the same editor.
        wasBackPressed = false

        if (editorInfo == null) {
            Logger.debug(LOG_TAG, "onStartInput() - EditorInfo is NULL, hiding UI elements")
            uiCoordinator.hideAllInputUi()
        }

        refreshSubtypeSwitcher()
        uiUpdateHandler.handleStartInput(editorInfo, z)
        isInputActive = hasActiveInputConnection()
        val str = editorInfo?.packageName
        var z2 = false
        if (!isInputActive || str == null || str != currentPackageName) {
            enableCursorMode(false)
        }
        currentPackageName = str
        physicalKeyboardStateTracker.resetAllMetaState()

        hideUnifiedInputBoard()
        if (str != null && (str == "com.blackberry.help" || str == "com.blackberry.retaildemo")) {
            z2 = true
        }
        isBlackBerryApp = z2
        if (LocaleUtils.isCurrentSubtypeKorean() && str != null && str.lowercase(Locale.ROOT).startsWith("com.skmc.okcashbag")) {
            // OK Cashbag workaround (original app): implicitly show the input view on a PKB-only device
            // when the field asks for NAVIGATE_NEXT or has either of action bits 0/2 set.
            val opts = editorInfo!!.imeOptions
            // Audit CT-22: 5 == IME_ACTION_NONE (1) | IME_ACTION_SEARCH (4).
            val wantsNextOrAction = (opts and EditorInfo.IME_FLAG_NAVIGATE_NEXT) != 0 ||
                (opts and (EditorInfo.IME_ACTION_NONE or EditorInfo.IME_ACTION_SEARCH)) != 0
            if (wantsNextOrAction && DeviceProfile.current().isPkbWithoutAlphabeticKeyboard()) {
                inputMethodImpl!!.showSoftInput(android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT, null)
            }
        }

        if (InlineAutofillManager.isSupported()) {
            InlineAutofillManager.getInstance(this).onInputStarted(editorInfo, z)
        }
    }

    @Throws(Resources.NotFoundException::class)
    override fun onStartInputView(editorInfo: EditorInfo?, z: Boolean) {
        Logger.info(LOG_TAG, "onStartInputView()")
        // Window re-show for a still-bound editor delivers onStartInputView WITHOUT a
        // preceding onStartInput, leaving isInputActive stale-false after
        // onWindowHidden cleared it (Aliucord/Fossify dead-autocomplete/SYM bug).
        refreshInputActive()
        // Re-read the log.tag.XT9Input sysprop so input-path diagnostics can be
        // toggled via setprop without killing the IME process.
        if (BuildConfig.DEBUG) InputPathDebug.refresh()
        uiUpdateHandler.handleStartInputView(editorInfo, z)
    }

    override fun onFinishInputView(z: Boolean) {
        Logger.info(LOG_TAG, "onFinishInputView(), finishingInput=$z")
        uiUpdateHandler.handleFinishInputView(z)
        controlMode.clearControlState()
    }

    override fun onFinishInput() {
        Logger.info(LOG_TAG, "onFinishInput()")
        uiUpdateHandler.handleFinishInput()
        physicalKeyboardStateTracker.resetAllMetaState()
        isInputActive = false

        if (InlineAutofillManager.isSupported()) {
            InlineAutofillManager.getInstance(this).onInputFinished()
        }
    }

    fun startInputInternal(editorInfo: EditorInfo?, z: Boolean) {
        inputSessionCoordinator.onStartInputInternal(editorInfo, z)
    }

    @Throws(Resources.NotFoundException::class)
    fun startInputViewInternal(editorInfo: EditorInfo?, z: Boolean) {
        inputSessionCoordinator.onStartInputViewInternal(editorInfo, z)
    }

    fun finishInputInternal() {
        inputSessionCoordinator.onFinishInputInternal()
    }

    fun finishInputViewInternal(z: Boolean) {
        inputSessionCoordinator.onFinishInputViewInternal(z)
    }

    /**
     * Re-derive [isInputActive] from live connection state (the same expression
     * onStartInput uses). The flag is cleared in onWindowHidden and onFinishInput, but
     * the framework does NOT call onStartInput when re-showing the window for a
     * still-bound editor (only onStartInputView) — so any path that can run before a
     * view (re)start must heal the flag here or every hardware key fails the
     * KeyEventProcessor master gate (dead autocomplete + dead SYM key; regression
     * from d95a44af). Safe against the deliberate onFinishInput clear:
     * getCurrentInputStarted() is false in that window, so this cannot resurrect it.
     */
    fun refreshInputActive() {
        isInputActive = hasActiveInputConnection()
    }

    private fun hasActiveInputConnection(): Boolean {
        if (!getCurrentInputStarted()) return false
        val currentInputConnection = getCurrentInputConnection() ?: return false
        val currentInputBinding = getCurrentInputBinding()
        return currentInputBinding == null || currentInputBinding.connection != currentInputConnection
    }

    fun cleanupKeyboard() {
        keyboardSwitcher.cleanup()
        uiUpdateHandler.cancelPendingSuggestionUpdates()
        inputLogic.cancelInput()
        // Audit DW-4: multitap state and MSG_MULTITAP_TIMEOUT had no lifecycle reset at all —
        // removeMultitapTimeout's only callers were the two commitMultitap methods. This is the
        // seam every finishInputView route passes through, including the deferred-cleanup flush.
        multitapEventHandler.resetMultitapState()
        softwareMultitapHandler.resetMultitapState()
    }

    /**
     * Consolidated UI state reset. Idempotent — safe to call from multiple lifecycle paths.
     */
    fun resetUiState() {
        keyboardSwitcher.getMainKeyboardView()?.closing()
        uiCoordinator.hideAllInputUi()
        enableCursorMode(false)
        physicalKeyboardStateTracker.resetAllMetaState()
        shakeGestureHandler!!.stop()
    }

    /**
     * Canonical keyboard dismiss method. Performs complete cleanup and requests
     * the framework to hide the IME window.
     */
    fun dismissKeyboard() {
        Logger.info(LOG_TAG, "dismissKeyboard()")
        resetUiState()
        fccController?.dismiss()
        clipboardController?.dismiss()
        requestHideSelf(0)
    }

    override fun onUpdateSelection(i: Int, i2: Int, i3: Int, i4: Int, i5: Int, i6: Int) {
        super.onUpdateSelection(i, i2, i3, i4, i5, i6)

        val inputConnection = getCurrentInputConnection()
        if (inputConnection == null || !getCurrentInputStarted()) {
            Logger.debug(LOG_TAG, "onUpdateSelection() - No valid input connection, hiding UI elements")
            uiCoordinator.hideAllInputUi()
        }

        val settingsValues = settingsManager.getSettingsValues()
        val zM4467a = inputLogic.onUpdateSelection(i, i2, i3, i4, settingsValues)
        if (zM4467a) {
            resetKeyboardState()
        }
        if (physicalKeyboardStateTracker.isShiftKeyDown()) {
            physicalKeyboardStateTracker.consumeModifiersAfterKey(0, true)
        }
        if (keyboardSwitcher.isShiftKeyPressed() || keyboardSwitcher.isShiftKeyMomentary()) {
            keyboardSwitcher.onKeyReleaseExternal()
            keyboardSwitcher.onCodeInput(-21, true, getCurrentInputType(), getCurrentImeOptions())
        }
        if (zM4467a) {
            multitapEventHandler.commitMultitapIfActive()
            softwareMultitapHandler.commitMultitap()
        }
        if (i3 != i4 && !isMetaKeyActive()) {
            enableCursorMode(false)
        }
        fccController?.onSelectionUpdate(i3, i4)
    }

    override fun onUpdateCursorAnchorInfo(cursorAnchorInfo: CursorAnchorInfo?) {
        if (cursorAnchorInfo != null && isCursorModeEnabled) {
            val selectionStart = cursorAnchorInfo.selectionStart
            val selectionEnd = cursorAnchorInfo.selectionEnd
            if (selectionStart != selectionEnd) {
                if (!isMetaKeyActive()) {
                    enableCursorMode(false)
                } else {
                    cursorTracker.hide()
                }
            } else {
                if (!isMetaKeyActive()) {
                    if (cursorTracker.isShowing()) {
                        cursorTracker.updatePosition(cursorAnchorInfo)
                    } else {
                        cursorTracker.show(cursorAnchorInfo, rootInputView as ViewGroup?)
                    }
                }
            }
        }
        lastCursorAnchorInfo = cursorAnchorInfo
        if (!isFullscreenMode()) {
            inputLogic.updateCursorAnchorInfo(cursorAnchorInfo)
        }
    }

    override fun onViewClicked(z: Boolean) {
        Logger.debug(LOG_TAG, "onViewClicked, focusChanged=$z")
        super.onViewClicked(z)
        enableCursorMode(false)
        refreshUnifiedInputBoardState()
    }

    private fun refreshUnifiedInputBoardState() {
        val c1011iM6838p = KeyboardSwitcher.getInstance().getUnifiedInputBoardManager()
        if (c1011iM6838p != null) {
            c1011iM6838p.hideOtherComponents(-11)
            c1011iM6838p.refresh()
        }
    }

    /**
     * Called by the system to create an inline suggestions request (Android 11+).
     */
    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest? {
        if (!InlineAutofillManager.isSupported()) {
            return null
        }

        Logger.debug(LOG_TAG, "onCreateInlineSuggestionsRequest()")

        val maxWidth = maxWidth
        var maxHeight = ResourceConfigManager.getSuggestionsStripHeight(resources)

        if (maxHeight <= 0) {
            // InlinePresentationSpec sizes are pixels, so convert the 44dp fallback
            maxHeight = (44 * resources.displayMetrics.density).toInt()
        }

        // Pass the live EditorInfo: this callback has no ordering guarantee against
        // onStartInput, so the manager must not rely on what onInputStarted last recorded.
        return InlineAutofillManager.getInstance(this)
            .createInlineSuggestionsRequest(maxWidth, maxHeight, currentInputEditorInfo)
    }

    /**
     * Called by the system when inline suggestions are available from an autofill service (Android 11+).
     */
    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        // Audit CT-23: `response == null` was a constant-false test against a non-null type.
        if (!InlineAutofillManager.isSupported()) {
            return false
        }

        Logger.debug(LOG_TAG, "onInlineSuggestionsResponse(): ${response.inlineSuggestions.size} suggestions")

        return InlineAutofillManager.getInstance(this).handleResponse(response)
    }

    @Throws(Resources.NotFoundException::class)
    override fun onCurrentInputMethodSubtypeChanged(inputMethodSubtype: InputMethodSubtype) {
        subtypeManager.onSubtypeChanged(inputMethodSubtype)
        updateLanguagePacksForSubtype(inputMethodSubtype)
        if (!ResourceLocaleUtils.isNonCjkLanguage(richInputMethodManager!!.getLastInputMethodSubtype()) && keyboardSwitcher.getUnifiedInputBoardManager() != null && !keyboardSwitcher.getUnifiedInputBoardManager()!!.isShowing()) {
            keyboardSwitcher.onFinishShiftLongPress()
        }
        inputLogic.fullReset(ResourceLocaleUtils.getConverterDescriptor(inputMethodSubtype), settingsManager.getSettingsValues())
        loadKeyboard()
        richInputMethodManager!!.recordSubtypeSwitch(inputMethodSubtype)
        cjkSuggestionGridView?.setVisible(false)
        val mainKeyboardViewM6790T = keyboardSwitcher.getMainKeyboardView()
        val settingsValues = settingsManager.getSettingsValues()
        if (mainKeyboardViewM6790T != null) {
            mainKeyboardViewM6790T.setVkbGestureHandlingEnabledByUser(settingsValues.isVkbGestureInputEnabledForLocale())
            mainKeyboardViewM6790T.setCkbGestureHandlingEnabledByUser(settingsValues.isCkbGestureInputEnabledForLocale())
        }
        numericSubpanelController?.onSubtypeChanged(inputMethodSubtype)
        quickPhrasesController?.show()
    }

    fun refreshSubtypeSwitcher() {
        val c0664aaM4239a = SubtypeManager.getInstance()
        if (!c0664aaM4239a.isInitialized()) return
        val listM4860a = RichInputMethodManager.getInstance().getEnabledSubtypesOfThisIme()
        if (listM4860a == null || listM4860a.size <= 0) return
        val localeM4259h = c0664aaM4239a.getCurrentSubtypeLocale()
        val setM4260i = c0664aaM4239a.getCurrentSubtypeAdditionalLocales()
        var z = false
        for (inputMethodSubtype in listM4860a) {
            val localeM5625c = ResourceLocaleUtils.getSubtypeLocale(inputMethodSubtype)
            val setM5634h = ResourceLocaleUtils.getAdditionalLocales(inputMethodSubtype)
            if (localeM4259h == localeM5625c && ((setM4260i != null && setM4260i == setM5634h) || (setM4260i == null && setM5634h == null))) {
                z = true
                break
            }
        }
        if (z) return
        onCurrentInputMethodSubtypeChanged(listM4860a[0])
    }

    // ==================== Settings, dictionary, subtype ====================

    fun getSettingsValues(): SettingsValues {
        return settingsManager.getSettingsValues()
    }

    override fun onSettingsValuesChanged(c0804d: SettingsValues) {
    }

    fun loadSettings() {
        val localeM4259h = subtypeManager.getCurrentSubtypeLocale()
        val currentInputEditorInfo = getCurrentInputEditorInfo()
        settingsManager.loadSettings(applicationContext, localeM4259h,
            EditorCapabilities(
                currentInputEditorInfo,
                isFullscreenMode(),
                packageName,
                localeM4259h,
                // Audit CT-17: route through PrefsManager, the project's single prefs accessor.
                SettingsManager.isForceSuggestionsEnabled(PrefsManager.getPrefs())
            )
        )
        val settingsValues = settingsManager.getSettingsValues()
        AudioAndHapticFeedbackManager.getInstance().onSettingsChanged(settingsValues, this)
        if (!uiUpdateHandler.hasPendingAdditionalLocalesLoad()) {
            initDictionaryForLocale(localeM4259h)
        }
        vkbGestureListener?.updateSettings(settingsValues)
        if (currentInputEditorInfo != null) {
            DictionaryManager.getInstance().ensureEnglishCapitalISubstitution(localeM4259h)
        }
        contactsLearningManager?.setContactsDictEnabled(settingsValues.useContactsDicts)
        // Re-apply the KeyInterceptor toggle. Registration used to happen only once, from the
        // deferred-init token in onCreate(), so turning "Enable special key support" on in
        // Settings had no effect until the IME process restarted. The call is idempotent, and
        // clears the callbacks when the toggle is off.
        hardwareKeys.registerInterceptorCallbacks()
        fccController?.hideIfDisabled()
        // Timed at the call site, not inside NuanceSDKManager: this is where the engine's
        // native init actually lands. getInstance() creates the SDK lazily, the first caller on
        // the IME path is this line, and the first loadSettings() runs on the onCreate main
        // thread -- so the engine init is *inside* the phase the tracker records as
        // "SettingsManager.initialize ~191 ms". The PKB prewarm in initializeBackgroundPreloading()
        // is submitted after this point, so it never gets there first.
        val sdkToken = StartupTiming.begin()
        val sdkM4875a2 = NuanceSDKManager.getInstance()
        StartupTiming.endIfOver("ime.loadSettings.nuanceSdkGet", sdkToken, 5L)
        if (sdkM4875a2 != null) {
            sdkM4875a2.setEmojiPredictionEnabled(settingsValues.isEmojiPredictionsEnabled)
        } else {
            Logger.warn(LOG_TAG, "NuanceSDK unavailable; skipping setEmojiPredictionEnabled")
        }
    }

    @Throws(Resources.NotFoundException::class)
    fun loadKeyboard() {
        uiUpdateHandler.postLoadAdditionalLocales()
        loadSettings()
        hideUnifiedInputBoard()
        if (keyboardSwitcher.getMainKeyboardView() != null) {
            physicalKeyboardStateTracker.resetAllMetaState()
            keyboardSwitcher.startInput(getCurrentInputEditorInfo(), getCurrentInputType(), getCurrentImeOptions())
        }
    }

    fun applyAutoCorrectionSettings(c0804d: SettingsValues) {
        val isPkb = DeviceProfile.current().hasShiftedSymbolKeyboard()
        val acMode = if (!isPkb) 1 else 0
        if (BuildConfig.DEBUG) {
        android.util.Log.d("PKB_SPACE_AUTOCORRECT_DEBUG", "applyAutoCorrectionSettings (updateAutoCorrection): isPkb=$isPkb" +
            " acEnabledPerUser=${c0804d.isAutoCorrectionEnabledPerUserSettings}" +
            " acModeValue=${c0804d.autoCorrectionMode}" +
            " willSetThreshold=${c0804d.autoCorrectionMode} willSetMode=$acMode")
        }
        if (c0804d.isAutoCorrectionEnabledPerUserSettings) {
            inputLogic.mSuggestionEngine.setAutoCorrectThreshold(c0804d.autoCorrectionMode)
            inputLogic.mSuggestionEngine.setAutoCorrectMode(acMode)
        }
    }

    fun updateLanguagePacksForCurrentSubtype() {
        val current = subtypeManager.getCurrentSubtype()
        if (current != null) {
            updateLanguagePacksForSubtype(current)
        } else {
            Logger.warn(LOG_TAG, "Current subtype is null; skipping updateLanguagePacksForCurrentSubtype()")
        }
        KeyboardSwitcher.getInstance().syncKeyboardLayout()
    }

    fun updateLanguagePacksForSubtype(inputMethodSubtype: InputMethodSubtype) {
        val arrayList = ArrayList<Locale>()
        arrayList.add(ResourceLocaleUtils.getSubtypeLocale(inputMethodSubtype))
        if (inputMethodSubtype.containsExtraValueKey("AdditionalLocales")) {
            arrayList.add(Locale(inputMethodSubtype.getExtraValueOf("AdditionalLocales")))
        }
        val sdk = NuanceSDKManager.getInstance()
        if (sdk != null) {
            LanguagePackManager.getInstance(applicationContext).setLanguages(sdk, arrayList.toTypedArray())
        } else {
            Logger.warn(LOG_TAG, "NuanceSDK unavailable; skipping language update in updateLanguagePacksForSubtype")
        }
    }

    /**
     * Re-read the dictionary after the Language packs screen swapped the file behind the active
     * locale.
     *
     * <p>The engine finds a dictionary by reading whatever `*.ldb` sits in
     * `no_backup/nuance/<locale>/`, and caches it once loaded, so putting a different file there
     * changes nothing until something asks for the language again. Settings runs in this same
     * process but is a different component, so the bus is how it reaches a LIVE service; when the
     * IME is not running there is no subscriber and the new file is simply picked up on the next
     * start, which is equally correct.
     */
    private val languagePackChangedListener = InAppEventBus.EventListener { _, _ ->
        if (BuildConfig.DEBUG) Log.i(LOG_TAG, "Language pack changed; reloading the dictionary")
        reloadDictionaryForSubtype()
    }

    fun reloadDictionaryForSubtype() {
        var setM4260i: Set<Locale>?
        var localeM4259h = subtypeManager.getCurrentSubtypeLocale()
        if (TextUtils.isEmpty(localeM4259h.toString())) {
            if (BuildConfig.DEBUG) Log.e(LOG_TAG, "System is reporting no current subtype.")
            // Audit CT-15: Configuration.locale is deprecated (API 24) and ignores the user's
            // ordered locale list; LocaleUtils.getConfigurationLocale is the project's shim.
            localeM4259h = LocaleUtils.getConfigurationLocale(resources)
            setM4260i = null
        } else {
            setM4260i = subtypeManager.getCurrentSubtypeAdditionalLocales()
        }
        languagePackLocaleMonitor!!.onLocaleChanged(localeM4259h, setM4260i)
        initDictionaryForLocale(localeM4259h)
        multitapEventHandler.refreshAltMultitapSupport()
        DictionaryManager.getInstance().initialiseOrSwitchLanguages(applicationContext, java.util.Collections.singletonList(localeM4259h))
    }

    fun reloadAdditionalLocales() {
        dictionaryLoader.updateAdditionalLocales(subtypeManager.getCurrentSubtypeAdditionalLocales(), true, this)
    }

    private fun initDictionaryForLocale(locale: Locale) {
        val settingsValues = settingsManager.getSettingsValues()
        dictionaryLoader.initDictionary(this, locale, settingsValues.useContactsDicts, false, this)
        applyAutoCorrectionSettings(settingsValues)
    }

    fun reinitDictionary() {
        val settingsValues = settingsManager.getSettingsValues()
        val localeM4644a = dictionaryLoader.getLocale()
        if (localeM4644a != null) {
            languagePackLocaleMonitor!!.onLocaleChanged(localeM4644a, subtypeManager.getCurrentSubtypeAdditionalLocales())
            dictionaryLoader.initDictionary(this, localeM4644a, settingsValues.useContactsDicts, true, this)
            applyAutoCorrectionSettings(settingsValues)
            multitapEventHandler.refreshAltMultitapSupport()
            DictionaryManager.getInstance().initialiseOrSwitchLanguages(applicationContext, java.util.Collections.singletonList(localeM4644a))
        }
    }

    override fun onDictionaryInitialized(z: Boolean) {
        keyboardSwitcher.getMainKeyboardView()?.setMainDictionaryAvailability(z)
        if (z) uiUpdateHandler.postDictionaryLoaded()
        val hadTimeout = uiUpdateHandler.hasDictionaryLoadTimeout()
        if (hadTimeout) uiUpdateHandler.removeDictionaryLoadTimeout()
        if (z || hadTimeout) uiUpdateHandler.postUpdateShiftState(true, false)
    }

    override fun onLocalesUpdated() {
        uiUpdateHandler.postUpdateShiftState(true, false)
    }

    fun toggleCangjieMode() {
        if (dictionaryLoader != null) {
            val i = if (SettingsManager.getInstance().getSettingsValues().cangjieMode == 0) 1 else 0
            SettingsManager.setCangjieMode(PrefsManager.getPrefs(), i)
            val sdkM4875a3 = NuanceSDKManager.getInstance()
            if (sdkM4875a3 != null) {
                sdkM4875a3.setInputMethod(if (i == 0) NuanceSDK.CANGJIE_VARIANT else NuanceSDK.QUICK_CANGJIE_VARIANT)
            } else {
                Logger.warn(LOG_TAG, "NuanceSDK unavailable; skipping setInputMethod")
            }
        }
    }

    fun switchToNextSubtype(enumC0690f: InputSource): Boolean {
        if (!richInputMethodManager!!.hasMultipleEnabledSubtypesInThisIme(false) || !settingsManager.getSettingsValues().isSpacebarLanguageSwitchingEnabled) {
            return false
        }
        return subtypeSwitcherReceiver!!.show(this, this, enumC0690f)
    }

    fun updateSuggestionsFromSubtype(enumC0690f: InputSource): Boolean {
        if (!RichInputMethodManager.isInitialized()) {
            RichInputMethodManager.init(applicationContext)
        }
        if (!ASSERTIONS_DISABLED && window.window == null) {
            throw AssertionError()
        }
        // CT-16: no window token needed — RichInputMethodManager takes this InputMethodService and
        // uses the token-free switchInputMethod/switchToNextInputMethod on API 28+.
        subtypeState.switchSubtype(this, richInputMethodManager)
        return true
    }

    fun getCurrentLocale(): Locale {
        return subtypeManager.getCurrentSubtypeLocale()
    }

    /**
     * Audit CT-8: this used to render the locale's own display name (an ICU lookup that allocates
     * a String) and inspect the bidi class of its first character — on every VKB fling, via
     * [VkbGestureListener.isBackwardSwipe], which decides delete-word vs. language-switch. The
     * heuristic was also wrong for locales whose endonym starts with a Latin letter or digit.
     */
    fun isCurrentLanguageRtl(): Boolean =
        TextUtils.getLayoutDirectionFromLocale(subtypeManager.getCurrentSubtypeLocale()) ==
            View.LAYOUT_DIRECTION_RTL

    fun shouldShowLanguageSwitchKey(): Boolean {
        val zM5179d = settingsManager.getSettingsValues().hasMultipleEnabledInputMethodsOrSubtypes()
        // Audit CT-16: this service IS an InputMethodService, which offers the token-free form of
        // the query (API 28+). The IMM IBinder-token variant is deprecated and has been
        // progressively restricted; it also silently no-ops when the window token is unavailable,
        // which is why the fallback below still exists for pre-P.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return shouldOfferSwitchingToNextInputMethod()
        }
        val iBinder = window.window?.attributes?.token ?: return zM5179d
        @Suppress("DEPRECATION")
        return richInputMethodManager!!.shouldOfferSwitchingToNextInputMethod(iBinder)
    }

    // ==================== Hardware key entry (KeyEventProcessor does the work) ====================

    override fun onKeyDown(i: Int, keyEvent: KeyEvent): Boolean {
        if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "BlackBerryIME.onKeyDown: keyCode=$i deviceId=${keyEvent.deviceId} source=0x${Integer.toHexString(keyEvent.source)} scanCode=${keyEvent.scanCode} flags=0x${Integer.toHexString(keyEvent.flags)}")
        ckbGestures.onHardwareKey(keyEvent.eventTime)
        return keyEventProcessor.onKeyDownInternal(i, keyEvent)
    }

    override fun onKeyUp(i: Int, keyEvent: KeyEvent): Boolean {
        if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "BlackBerryIME.onKeyUp: keyCode=$i deviceId=${keyEvent.deviceId} source=0x${Integer.toHexString(keyEvent.source)} scanCode=${keyEvent.scanCode} flags=0x${Integer.toHexString(keyEvent.flags)}")
        ckbGestures.onHardwareKey(keyEvent.eventTime)
        return keyEventProcessor.onKeyUpInternal(i, keyEvent)
    }

    /**
     * Process CommitType key event for state updates only (shift, alt, symbol mode).
     * Called by the accessibility service when pre-processing keys without an active text field.
     */
    fun processKeyEventForState(event: KeyEvent) {
        keyEventProcessor.processKeyEventForState(event)
    }

    /**
     * Summon the IME window for a physical key press that arrived while it was hidden; returns
     * whether the input view is showing afterwards. Used by [HardwareKeyBridge] for the SYM key,
     * which has to open a board in that window. The caller must already know the press came from
     * a physical keyboard.
     */
    fun requestShowOnKeyPress(): Boolean = keyEventProcessor.requestShowOnKeyPress()

    fun processHardwareKeyPress(i: Int, i2: Int, z: Boolean) {
        applyPostEventUpdates(inputLogic.processInputEvent(settingsManager.getSettingsValues(), InputEvent.createHardwareKeyPress(i, i2, null, z, -1L), physicalKeyboardStateTracker, InputSource.UNKNOWN, keyboardSwitcher.getKeyboardElementId(), uiUpdateHandler), true)
    }

    fun remapKeyEvent(i: Int, ev: KeyEvent): KeyEvent {
        val mfRemapped = remapMultifunctionCtrlKey(ev)
        return controlMode.remapModifierKeyEvent(mfRemapped.keyCode, mfRemapped)
    }

    /**
     * When the device's MULTIFUNCTION key is configured to act as Ctrl, rewrite its events
     * to KEYCODE_CTRL_LEFT so the physical-Ctrl machinery (chording, Ctrl+C/V/X shortcuts)
     * treats it as a real Ctrl key — the same trick the control_mode setting uses to remap
     * Shift to Ctrl. Skipped while Alt is active at press time so Alt+key can still type
     * the mapping's alt character (e.g. '0' on the Key2 mic key).
     */
    private fun remapMultifunctionCtrlKey(ev: KeyEvent): KeyEvent {
        val mapping = dev.bbkb.ime.core.device.config.resolver.ScancodeMappingResolver
            .getInstance().resolve(ev.scanCode, ev.keyCode) ?: return ev
        if (mapping.role != dev.bbkb.ime.core.device.config.model.KeyRole.MULTIFUNCTION) return ev
        if (dev.bbkb.ime.core.keyevent.MultifunctionKeyHandler.getConfiguredAction(mapping)
            != dev.bbkb.ime.core.keyevent.MultifunctionKeyHandler.ACTION_CTRL) return ev
        if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) {
            val altActive = ev.isAltPressed ||
                (physicalKeyboardStateTracker.getInternalMetaState() and KeyEvent.META_ALT_MASK) != 0
            multifunctionCtrlDown = !altActive
        }
        if (!multifunctionCtrlDown) return ev
        if (ev.action == KeyEvent.ACTION_UP) multifunctionCtrlDown = false
        return ControlModeController.withKeyAndMeta(ev, KeyEvent.KEYCODE_CTRL_LEFT, ev.metaState or ControlModeController.META_CTRL_LEFT)
    }

    fun getOrCreateKeyEventConverter(i: Int): KeyEventConverter {
        val interfaceC0916c = keyEventConverters.get(i)
        if (interfaceC0916c != null) return interfaceC0916c
        val resolver = hardwareKeys.auxCharacterResolver()
        val c0917d = KeyEventConverter(i, CompositeKeyCharacterInterpreter(currencyKeyHandler.asKeyCharacterInterpreter(), keyboardSwitcher.getKeyCharacterInterpreter(), multitapEventHandler.asKeyCharacterInterpreter()), this, resolver)
        keyEventConverters.put(i, c0917d)
        return c0917d
    }

    fun updateMainKeyboardViewForKeyEvent(event: KeyEvent, isKeyDown: Boolean) {
        if (isInputViewShown() && isGestureInputReady()) {
            val mkv = keyboardSwitcher.getMainKeyboardView()!!
            if (isKeyDown) {
                mkv.onPhysicalKeyDown(event)
            } else {
                mkv.onPhysicalKeyUp(event)
            }
            if (PhysicalKeyboardStateTracker.isShiftKey(event.keyCode)) {
                mkv.updateGestureHandlingState(isMetaKeyActive())
            }
        }
    }

    fun updatePhysicalKeyboardFilter() {
        physicalKeyboardStateTracker.updateFilterAndAltGr(getCurrentInputType(), keyboardSwitcher.getKeyCharacterMap())
    }

    /**
     * Text-key board dismissal (was misnamed onSelectionChangedForFcc): a text key
     * went down, so any open board should close. Runs the close through the board
     * coordinator so its active-board state stays truthful — previously this hid
     * views behind the coordinator's back and the next board-key press was a dead
     * press. Board keys never reach here (KeyEventProcessor exempts them), so this
     * can no longer fight a board key's own key-up toggle.
     */
    fun dismissBoardsForTextKey() {
        val uim = KeyboardSwitcher.getInstance().getUnifiedInputBoardManager()
        val sv = SettingsManager.getInstance().getSettingsValues()
        val coordinator = uim?.getBoardCoordinator()

        val isDynamicSearchActive = sv != null
            && sv.isEmojiDynamicSearchEnabled
            && keyboardSwitcher.isEmojiKeyboardShowing()

        fccController?.let { fcc ->
            // FCC keeps its legacy dismissal with the mid-toggle exemption; reconcile
            // the coordinator only when FCC actually hid.
            fcc.hideUnlessToggling()
            if (coordinator != null && coordinator.activeBoard() == -42 && !fcc.isViewActive()) {
                coordinator.notifyBoardClosed()
            }
        }

        if (uim != null && sv != null && sv.isUimEnabled) {
            if (!isDynamicSearchActive) {
                // Primary: close the active board via the coordinator (per-board close
                // semantics + state cleared). FCC was handled above with its exemption.
                if (coordinator != null && coordinator.activeBoard() != -42) {
                    coordinator.onTextKeyPressed()
                }
                // Defense-in-depth sweep for components showing outside coordinator
                // tracking (e.g. auto-shown autofill). Spares FCC like before.
                uim.hideOtherComponents(-42)
            }
            uim.refresh()
        }
        if (sv == null || !keyboardSwitcher.isEmojiKeyboardShowing() || sv.isUimEnabled) return
        if (!isDynamicSearchActive) keyboardSwitcher.getUnifiedInputBoardManager()!!.closeActiveComponent()
    }

    fun isShiftChording(): Boolean = keyboardSwitcher.isShiftKeyReleasing() && physicalKeyboardStateTracker.isShiftReleased()

    fun isMetaKeyActive(): Boolean {
        val c0919fM4047Z = physicalKeyboardStateTracker
        // Audit CT-22: 256 == MetaKeyKeyListener.META_CAP_LOCKED.
        return (!c0919fM4047Z.isShiftReleased() &&
            c0919fM4047Z.hasMetaFlag(android.text.method.MetaKeyKeyListener.META_CAP_LOCKED)) &&
            !c0919fM4047Z.isAltUsedWithKey()
    }

    fun getSymbolPageProvider(): SymbolPageProvider {
        return if (DeviceProfile.current().hasShiftedSymbolKeyboard()) physicalKeyboardStateTracker else keyboardSwitcher
    }

    fun updateSymbolShift(z: Boolean) {
        if (z) return
        if (isInputViewShown()) {
            keyboardSwitcher.onSymbolShiftToggle(getCurrentInputType(), getCurrentImeOptions(), false, true)
        } else {
            uiUpdateHandler.post {
                keyboardSwitcher.onSymbolShiftToggle(getCurrentInputType(), getCurrentImeOptions(), false, true)
            }
        }
        cjkSuggestionGridView?.setVisible(false)
    }

    // ==================== Soft key entry (KeyboardActionListenerInterface) ====================

    override fun onCodeInput(i: Int, i2: Int, i3: Int, j: Long, z: Boolean) {
        vkbGestureListener?.setLastKeyEventTime(j)
        when (i) {
            -37 -> return
            -29 -> {
                InputMethodHelper.getInstance().switchToRapidInputIme(this)
                return
            }
            -27, -7 -> {
                // Bug #4 fix: do NOT gate voice input on isUimEnabled(). The voice input view is
                // an independent ViewStub in input_view, not part of the unified input bar, so
                // requiring UIM caused the on-screen mic key (code -27) to fall through to the
                // IME switcher whenever the user had UIM disabled.
                val voice = voiceInputController
                if (voice != null) voice.show() else InputMethodHelper.getInstance().switchToVoiceIme(this)
                return
            }
        }
        val mainKeyboardView = keyboardSwitcher.getMainKeyboardView()!!
        val keyX = mainKeyboardView.getKeyX(i2)
        val keyY = mainKeyboardView.getKeyY(i3)
        // Shift on a non-alphabet keyboard means "symbol shift" (-13).
        val code = if (i == -1 && keyboardSwitcher.getCurrentKeyboard()?.mId?.isAlphabetKeyboard() != true) -13 else i
        val shouldProcessKey = (i < 0) || controlMode.isVkbShiftChordActive || (i > 0 && isInputViewShown())
        if (shouldProcessKey) {
            val interpretation = softwareMultitapHandler.interpretCodePoint(code)
            val event = if (interpretation != null && interpretation.isModifier()) {
                createKeyPressEvent(interpretation.codePoint, keyX, keyY, j, interpretation.isModifier(), interpretation.isShiftLocked())
            } else {
                createKeyPressEvent(code, keyX, keyY, j, z)
            }
            softwareMultitapHandler.onInputEvent(event)
            applyPostEventUpdates(inputLogic.processInputEvent(settingsManager.getSettingsValues(), event, keyboardSwitcher, InputSource.SOFTWARE, keyboardSwitcher.getKeyboardElementId(), uiUpdateHandler), true)
            keyboardSwitcher.onInputCodeChanged(code, getCurrentInputType(), getCurrentImeOptions())
            // For an on-screen PKB SYM keyboard tap, return to the alphabet AFTER the symbol has
            // been committed above. The state machine defers this from key-press so the tap isn't
            // lost (see KeyboardState.onCodeInput). No-op unless a PKB SYM entry is pending.
            keyboardSwitcher.onSoftwareSymbolCommitted(getCurrentInputType(), getCurrentImeOptions())
        }
        if (!Constants.isLetterCode(code)) handleSymbolKeyLongPress()
    }

    /** A key code <= 0 is a function key (no code point); > 0 is a code point with no key code. */
    private fun splitCode(i: Int): Pair<Int, Int> = if (i <= 0) Pair(-1, i) else Pair(i, 0)

    fun createKeyPressEvent(i: Int, i2: Int, i3: Int, j: Long, z: Boolean): InputEvent {
        val (codePoint, keyCode) = splitCode(i)
        return InputEvent.createKeyPress(codePoint, keyCode, i2, i3, j, z)
    }

    fun createKeyPressEvent(i: Int, i2: Int, i3: Int, j: Long, z: Boolean, z2: Boolean): InputEvent {
        val (codePoint, keyCode) = splitCode(i)
        return InputEvent.createModifierKeyPress(codePoint, keyCode, i2, i3, j, z, z2)
    }

    override fun onTextInput(str: String, j: Long) {
        var z = false
        val strM4443a0 = inputLogic.stripLeadingDot(str)
        // Audit CT-22: 16384 == TYPE_TEXT_FLAG_CAP_SENTENCES (== TextUtils.CAP_MODE_SENTENCES).
        val strM4443a = if (getCurrentInputType() == android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) capitalizeFirstCodePoint(strM4443a0, getCurrentLocale()) else strM4443a0
        val length = strM4443a.length
        val iArr = IntArray(length)
        var iCharCount = 0
        var i = 0
        while (iCharCount < length) {
            val iCodePointAt = strM4443a.codePointAt(iCharCount)
            iArr[i] = iCodePointAt
            iCharCount += Character.charCount(iCodePointAt)
            i++
        }
        val settingsValues = settingsManager.getSettingsValues()
        for (i2 in 0 until i) {
            // Audit CT-22: 28 == Character.OTHER_SYMBOL.
            if (settingsValues.isWordSeparator(iArr[i2]) || Character.getType(iArr[i2]) == Character.OTHER_SYMBOL.toInt()) {
                z = true
                break
            }
        }
        // CRITICAL FIX: Detect emoji sequences with skin tone modifiers
        if (!z && i > 1) {
            for (i2 in 0 until i) {
                val codepoint = iArr[i2]
                if ((codepoint in 0x1F3FB..0x1F3FF) ||
                    (codepoint in 0x1F9B0..0x1F9B3) ||
                    codepoint == 0x200D ||
                    codepoint == 0xFE0F) {
                    z = true
                    break
                }
            }
        }
        val c0920gM4442a: InputEventContext
        if (z) {
            c0920gM4442a = inputLogic.commitVoiceInput(settingsValues, InputEvent.createTextInputEvent(strM4443a, 0), keyboardSwitcher.getSymbolPageOrder(), shouldInsertAsString, uiUpdateHandler)
            shouldInsertAsString = false
        } else {
            var c0914aM5909a: InputEvent? = null
            for (i3 in i - 1 downTo 0) {
                c0914aM5909a = InputEvent.createHardwareKeyEvent(iArr[i3], 0, j, c0914aM5909a)
            }
            if (c0914aM5909a == null) return
            c0920gM4442a = inputLogic.processInputEvent(settingsValues, c0914aM5909a, getSymbolPageProvider(), InputSource.INTERNAL, keyboardSwitcher.getSymbolPageOrder(), uiUpdateHandler)
        }
        vkbGestureListener?.setLastKeyEventTime(j)
        applyPostEventUpdates(c0920gM4442a, true)
        keyboardSwitcher.onInputCodeChanged(-4, getCurrentInputType(), getCurrentImeOptions())
    }

    override fun onPressKey(i: Int, i2: Int, z: Boolean) {
        if (controlMode.handleSoftKeyDown(i)) return
        if (swipeToDeleteAnimatorView?.isAnimating() == true) {
            swipeToDeleteAnimatorView?.endAnimation()
        }
        keyboardSwitcher.onCodeInput(i, z, getCurrentInputType(), getCurrentImeOptions())
        multitapEventHandler.commitMultitapIfActive()
        inputLogic.onSoftKeyDown(i)
        playKeyFeedback(i, i2)
        enableCursorMode(false)
        if (LocaleUtils.isChineseCangjie(SubtypeManager.getInstance().getCurrentSubtypeLocale()) && isInputViewShown() && (i == -40 || i == -39)) {
            toggleCangjieMode()
        }
        if (i == -1) {
            inputLogic.sendKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT)
        }
        // The disable-then-restore cycle guards the UIM bar against accidental taps
        // WHILE TYPING. Shift/caps-lock toggles aren't typing — running the cycle for
        // them flashed the emoji/voice keys into their disabled icons on every toggle.
        if (i != -1 && i != -2) {
            unifiedInputBoardHandler?.scheduleKeyStateRestore()
        }
    }

    override fun onReleaseKey(i: Int, z: Boolean) {
        if (controlMode.handleSoftKeyUp(i)) return
        keyboardSwitcher.onCodeRelease(i, z, getCurrentInputType(), getCurrentImeOptions())
        inputLogic.onSoftKeyUp(i)
        if (i == -1) {
            inputLogic.sendKeyUp(59)
        }
    }

    override fun onStartBatchInput() {
        inputLogic.commitTypedWord(settingsManager.getSettingsValues(), "", InputSource.INTERNAL)
        // Reset ET9's gesture/active-input buffer at the start of each swipe so accumulated
        // stray edge-touches and the previous word don't bleed into this gesture. Without
        // this the trace record ring (traceObj+0x49000) and composing-start index (+0x39c)
        // never advance per word, causing "my" -> "how are you" / "good" -> "void".
        NuanceSDKManager.getInstance()?.clear()
        inputLogic.onSuggestionStripUpdate(settingsManager.getSettingsValues(), getSymbolPageProvider().getSymbolPageOrder(), uiUpdateHandler)
        val c0919fM4047Z = physicalKeyboardStateTracker
        keyboardSwitcher.getMainKeyboardView()!!.setGestureInputStartsBeforeRunningShift(true)
        c0919fM4047Z.cancelModifierTimers()
    }

    override fun onUpdateBatchInput() {
        // Throttle: this callback can arrive per touch event during a gesture (dozens
        // of times per second). Every request rewrites the engine's context buffer and
        // rebuilds the selection list — besides the CPU burn, the repeated context
        // writes multiply the engine's adaptive-language learning, which is how the
        // DLM got poisoned into ranking recent commits above trace shape. Stock
        // throttles gesture-preview updates to ~100ms; match that.
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastBatchUpdateRequestTime < 120) return
        lastBatchUpdateRequestTime = now
        suggestionUpdater.requestSuggestionsNow()
    }

    override fun onEndBatchInput(enumC0690f: InputSource) {
        suggestionUpdater.requestPredictionsNow(enumC0690f)
    }

    override fun onCancelBatchInput() {
        inputLogic.disableSuggestions(uiUpdateHandler)
    }

    override fun onFinishSlidingInput() {
        keyboardSwitcher.onMomentaryStateFinish(getCurrentInputType(), getCurrentImeOptions())
    }

    override fun onCustomRequest(i: Int): Boolean {
        if (!isAlertDialogShowing() && i == 1) {
            return switchToNextSubtype(InputSource.SOFTWARE)
        }
        return false
    }

    override fun onMoreKeysKeyTyped() {
        sendPrivateCommand("dev.bbkb.ime.TYPED_ALT_CHAR_VKB", null)
    }

    override fun onCancelInput() {
    }

    fun applyPostEventUpdates(c0920g: InputEventContext, z: Boolean) {
        when (c0920g.getUiUpdateMode()) {
            1 -> resetKeyboardState()
            2 -> uiUpdateHandler.scheduleShiftStateUpdate()
        }
        if (c0920g.shouldUpdateSuggestions()) {
            val reason = when {
                c0920g.event.isSuggestionPicked() -> SuggestionUpdater.Reason.AFTER_MANUAL_PICK
                c0920g.event.isGestureEvent() -> SuggestionUpdater.Reason.AFTER_GESTURE
                else -> SuggestionUpdater.Reason.AFTER_KEYSTROKE
            }
            if (z) {
                suggestionUpdater.requestDelayedLocaleAware(reason)
            } else if (LocaleUtils.isCurrentSubtypeJapanese()) {
                requestSuggestionUpdateAsync(reason.legacyCode)
            } else {
                requestSuggestionUpdateSync(reason.legacyCode)
            }
        }
        if (c0920g.isKeyHandled()) {
            subtypeState.setCurrentSubtypeHasBeenUsed()
        }
    }

    fun playKeyFeedback(i: Int, i2: Int) {
        val mainKeyboardViewM6790T = keyboardSwitcher.getMainKeyboardView()
        if (mainKeyboardViewM6790T == null || !mainKeyboardViewM6790T.isInDraggingFinger()) {
            if (i2 <= 0 || ((i != -5 || inputLogic.mRichInputConnection.hasCursorPosition()) && i2 % 2 != 0)) {
                val c0662aM4226a = AudioAndHapticFeedbackManager.getInstance()
                if (i2 == 0 && i != -27 && i != -29 && i != -23) {
                    c0662aM4226a.performHapticFeedback(mainKeyboardViewM6790T)
                }
                c0662aM4226a.performAudioFeedback(i)
            }
        }
    }

    private fun handleSymbolKeyLongPress() {
        keyboardSwitcher.onSymbolKeyLongPress(getCurrentInputType(), getCurrentImeOptions())
    }

    fun resetKeyboardState() {
        updatePhysicalKeyboardFilter()
        keyboardSwitcher.setAlphabetKeyboard(getCurrentInputType(), getCurrentImeOptions())
    }

    fun getCurrentInputType(): Int {
        return inputLogic.getCapsMode(settingsManager.getSettingsValues())
    }

    fun getCurrentImeOptions(): Int {
        return inputLogic.getConfigParserResult()
    }

    fun getKeyCoordinates(iArr: IntArray): IntArray {
        val c0965eM6834l = keyboardSwitcher.getCurrentKeyboard()
            ?: return CoordinateUtils.newCoordinateArray(iArr.size, -1, -1)
        return c0965eM6834l.getCoordinatesForCodes(iArr)
    }

    // ==================== Touch and gesture entry ====================

    override fun onGenericMotionEvent(motionEvent: MotionEvent): Boolean {
        if (motionEvent.actionMasked == MotionEvent.ACTION_DOWN) {
            val profile = DeviceProfile.current()
            if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "onGenericMotionEvent ACTION_DOWN: deviceId=${motionEvent.deviceId}" +
                " source=0x${Integer.toHexString(motionEvent.source)}" +
                " hasTouchKeypad=${profile.hasTouchKeypad()}" +
                " touchKeypadDeviceId=${profile.touchKeypadDeviceId}" +
                " isFromTouchKeypad=${profile.isFromTouchKeypad(motionEvent)}")
        }
        if (DeviceProfile.current().isFromTouchKeypad(motionEvent)) {
            if (motionEvent.actionMasked != MotionEvent.ACTION_MOVE) {
                if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "onGenericMotionEvent: CKB event received action=${motionEvent.actionMasked}, deviceId=${motionEvent.deviceId}, source=0x${Integer.toHexString(motionEvent.source)}")
            }
            if (!isInputViewShown()) {
                if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "onGenericMotionEvent: inputView NOT shown, returning false")
                return false
            }
            // CALIBRATION: the RAW CONTINUOUS sensor point, straight from the touchpad stream — the same
            // source the visualizer reads, UNSNAPPED (the touchStart path is fed grid-snapped coords on a
            // firm key press). Use LIGHT touches so events route here. View: adb logcat -s XT9RAW:I
            if (InputPathDebug.on()) {
                val a = motionEvent.actionMasked
                if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_POINTER_DOWN) {
                    val pi = motionEvent.actionIndex
                    android.util.Log.i("XT9RAW", "down raw=(%.1f, %.1f)".format(motionEvent.getX(pi), motionEvent.getY(pi)))
                } else if (a == MotionEvent.ACTION_MOVE) {
                    android.util.Log.i("XT9RAW", "move raw=(%.1f, %.1f)".format(motionEvent.x, motionEvent.y))
                }
            }
            // Debug sensor visualizer: when active it swallows every raw keypad sample upstream of
            // the engine (so it works even when the KDB fails to load).
            if (BuildConfig.DEBUG && sensorVizOverlay.forwardIfActive(motionEvent)) return true
            if (motionEvent.actionMasked == MotionEvent.ACTION_DOWN ||
                motionEvent.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                ckbGestures.onContactStart(motionEvent.eventTime)
            }
            val pointerId = motionEvent.getPointerId(motionEvent.actionIndex)
            val gestureProcessor = GestureEventProcessor.getGestureEventProcessor(pointerId)
            if (isCursorModeEnabled) {
                // Cursor (FCC) mode active: drag moves the cursor continuously; a tap exits.
                // Always consume (no typing/flow while in cursor mode).
                NuanceSDKManager.withSdk("fccCursor.touchCancel") { it.touchCancel(pointerId.toLong()) }
                gestureProcessor.cancelTracking()
                ckbGestures.handleCursorModeDrag(motionEvent)
                return true
            }
            // The gesture arbiter drives all CKB gestures. On a consumed contact, cancel any
            // in-progress flow and swallow the event; otherwise (Tap / FlowTrace / pre-UP) fall
            // through to the shared key/flow path.
            if (ckbGestures.feed(motionEvent)) {
                NuanceSDKManager.withSdk("newEngineConsumed.touchCancel") { it.touchCancel(pointerId.toLong()) }
                gestureProcessor.cancelTracking()
                return true
            }
            val ag = isGestureInputReady()
            val asCursorMode = isCursorModeEnabled
            val isMove = motionEvent.actionMasked == MotionEvent.ACTION_MOVE
            if (!isMove) {
                val typeBySwipingForLocale = settingsManager.getSettingsValues().isCkbGestureInputEnabledForLocale
                if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "onGenericMotionEvent gate: action=${motionEvent.actionMasked} isGestureInputReady=$ag isCursorModeEnabled(cursor mode)=$asCursorMode | settings: type_by_swiping_ckb(locale-checked)=$typeBySwipingForLocale")
            }
            if (!ag || asCursorMode) {
                if (!isMove && InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "onGenericMotionEvent: BLOCKED (gate failed) — KeyDetector will NOT receive this event")
                return true
            }
            if (isMetaKeyActive() && !isGestureHandlingActive()) {
                if (!isMove && InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "onGenericMotionEvent: BLOCKED (isMetaKeyActive && !isGestureHandlingActive)")
                return true
            }
            if (!isMove && InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "onGenericMotionEvent: PASS — dispatching to KeyDetector.processMotionEvent action=" + motionEvent.action + " x=" + motionEvent.x + " y=" + motionEvent.y)
            gestureProcessor.processMotionEvent(motionEvent)
            // FIX 2: Update last key time so noise-filtering in KeyDetector.onDownEvent
            // does not reject this motion event based on a stale hardware key timestamp.
            GestureEventProcessor.updateLastKeyTime(motionEvent.eventTime)
            return true
        } else {
            if (motionEvent.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "onGenericMotionEvent: event NOT from touch keypad, falling through to super. deviceId=${motionEvent.deviceId} source=0x${Integer.toHexString(motionEvent.source)} action=${motionEvent.actionMasked}")
            }
        }
        return super.onGenericMotionEvent(motionEvent)
    }

    /** Lazy getter for the (forked, multi-pointer) GestureDetector shared by the VKB views. */
    fun getOrCreateGestureDetector(): MultiPointerGestureDetector? {
        // Audit CT-23: `settingsManager != null` was constant-true (it is a non-null val) and
        // read as if the detector were only built once settings existed.
        if (gestureDetector == null) {
            vkbGestureListener = VkbGestureListener(this, settingsManager.getSettingsValues())
            gestureDetector = MultiPointerGestureDetector(this, vkbGestureListener, null, this)
            gestureDetector!!.setIsLongpressEnabled(false)
        }
        return gestureDetector
    }

    fun handleSwipeGesture(i: Int, motionEvent: MotionEvent, motionEvent2: MotionEvent) {
        val actionIndex = motionEvent.actionIndex
        var str = ""
        var zM5299a = false
        if (i == -18) {
            controlMode.clearControlState()
            val rawX = motionEvent.rawX
            val y = motionEvent.getY(actionIndex)
            val rawX2 = motionEvent2.rawX
            if (inputLogic.hasSelectionOrCursor()) {
                updateSwipeToDelete(rawX, y, rawX2)
                if (onSwipeDelete()) {
                    str = "dev.bbkb.ime.SWIPE_DELETE_VKB"
                    zM5299a = true
                } else {
                    Logger.warn("IMEGesture", "Delete gesture at valid position did nothing!")
                }
            }
        } else if (i == -19) {
            val swipeSettings = settingsManager.getSettingsValues()
            run {
                if (swipeSettings.isVkbSwipeDownEnabled) {
                    Logger.info(LOG_TAG, "Swipe-down dismiss: isCkb=false")
                    controlMode.clearControlState()
                    dismissKeyboard()
                    str = "dev.bbkb.ime.SWIPE_DISMISS_VKB"
                    zM5299a = true
                } else {
                    controlMode.clearControlState()
                    cjkSuggestionGridView?.setVisible(false)
                    str = "dev.bbkb.ime.SWIPE_SYMBOLS_VKB"
                    if (zM5299a) physicalKeyboardStateTracker.resetAltStateAndNotify()
                    zM5299a = keyboardSwitcher.onSymbolShiftToggle(getCurrentInputType(), getCurrentImeOptions(), true, zM5299a)
                }
            }
        } else if (i == -17) {
            controlMode.clearControlState()
            val flick = flickSuggestionView
            if (flick != null && flick.isShown) {
                val x2 = motionEvent.getX(actionIndex)
                val y3 = motionEvent.getY(actionIndex)
                Logger.debug(LOG_TAG, "Swipe for prediction selection: starting at x $x2, y $y3")
                zM5299a = flick.handleFlick(x2, y3, settingsManager.getSettingsValues().inLetterMaxSwipeToWordDistance)
            }
            str = "dev.bbkb.ime.SWIPE_PREDICT_VKB"
        }
        if (zM5299a) AudioAndHapticFeedbackManager.getInstance().performAudioFeedback(i)
        if (str.isNotEmpty()) sendPrivateCommand(str, null)
    }

    fun onSwipeDelete(): Boolean {
        if (!inputLogic.mComposingTracker.isComposing() && inputLogic.mEventDispatcher.isRevertEligible()) {
            // Synthesized backspace whose x/y carry COORD_SWIPE_DELETE_REVERT so the
            // backspace revert path treats this swipe-delete as always revert-eligible.
            onCodeInput(-5, InputEvent.COORD_SWIPE_DELETE_REVERT, InputEvent.COORD_SWIPE_DELETE_REVERT, -1L, false)
            return false
        }
        val zM4481b = inputLogic.handleSwipeDelete(settingsManager.getSettingsValues())
        if (zM4481b) {
            resetKeyboardState()
            suggestionUpdater.requestDelayed(SuggestionUpdater.Reason.AFTER_CURSOR_MOVE)
        }
        return zM4481b
    }

    fun updateSwipeToDelete(f: Float, f2: Float, f3: Float) {
        val mainKeyboardViewM6790T = keyboardSwitcher.getMainKeyboardView()!!
        swipeToDeleteAnimatorView?.setCurrentKeyboardHeight(mainKeyboardViewM6790T.height)
        val auxBarShown = auxBarManager != null && auxBarManager!!.getAuxBarView() != null && (auxBarManager!!.getAuxBarView()?.isShowing() ?: false)
        swipeToDeleteAnimatorView?.setSuggestionStripShown(auxBarShown)
        swipeToDeleteAnimatorView?.setCurrentKeyboardWidth(mainKeyboardViewM6790T.width)
        // Audit CT-33: the icon comes from android:src in main_keyboard_frame.xml, resolved once
        // at inflate; it was being re-resolved on every swipe-delete.
        swipeToDeleteAnimatorView?.startSwipeAnimation(f, f2, f3)
    }

    fun shouldHandleGestureEvent(motionEvent: MotionEvent): Boolean {
        val isFromTouchKeypad = DeviceProfile.current().isFromTouchKeypad(motionEvent)
        return (isFromTouchKeypad && SettingsManager.getInstance().getSettingsValues().isCkbGestureInputEnabledForLocale() && isGestureInputReady()) ||
            (!isFromTouchKeypad && SettingsManager.getInstance().getSettingsValues().isVkbGestureInputEnabledForLocale() && isVkbGestureAvailable())
    }

    fun isGestureInputReady(): Boolean {
        val mainKeyboardViewM6790T = keyboardSwitcher.getMainKeyboardView()
        val mkvNonNull = mainKeyboardViewM6790T != null
        val mkvC = mainKeyboardViewM6790T?.isGestureKeyboard() ?: false
        val notLocaleA = !LocaleUtils.isCurrentSubtypeChinese()
        val notLocaleD = !LocaleUtils.isCurrentSubtypeJapanese()
        val result = mkvNonNull && mkvC && notLocaleA && notLocaleD
        if (!result) {
            if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "isGestureInputReady returned FALSE | mkv!=null=$mkvNonNull mkv.isGestureKeyboard=$mkvC !LocaleUtils.a=$notLocaleA !LocaleUtils.d=$notLocaleD")
        }
        return result
    }

    fun isVkbGestureAvailable(): Boolean {
        val mainKeyboardViewM6790T = keyboardSwitcher.getMainKeyboardView()
        return mainKeyboardViewM6790T != null && mainKeyboardViewM6790T.isGestureInputEnabled() && !LocaleUtils.isCurrentSubtypeChinese() && !LocaleUtils.isCurrentSubtypeJapanese()
    }

    fun isGestureHandlingActive(): Boolean {
        val mainKeyboardViewM6790T = keyboardSwitcher.getMainKeyboardView()
        return mainKeyboardViewM6790T != null && mainKeyboardViewM6790T.isGestureHandlingActive()
    }

    fun sendPrivateCommand(str: String, bundle: Bundle?) {
        if (!isBlackBerryApp) return
        val currentInputConnection = getCurrentInputConnection() ?: return
        // Audit CT-23: `str == null` was constant-false; `str` is a non-null String.
        currentInputConnection.performPrivateCommand(str, bundle)
    }

    // ==================== Cursor mode (CursorMovementListener + arrow bar) ====================

    override fun moveRight(i: Int) {
        clearStuckMetaState()
        inputLogic.moveCursorRight(settingsManager.getSettingsValues(), i, isMetaKeyActive())
        sendPrivateCommand("dev.bbkb.ime.MOVE_RIGHT_CKB", null)
    }

    override fun moveLeft(i: Int) {
        clearStuckMetaState()
        inputLogic.moveCursorLeft(settingsManager.getSettingsValues(), i, isMetaKeyActive())
        sendPrivateCommand("dev.bbkb.ime.MOVE_LEFT_CKB", null)
    }

    override fun moveUp(i: Int) {
        clearStuckMetaState()
        inputLogic.moveCursorUp(i, isMetaKeyActive())
        sendPrivateCommand("dev.bbkb.ime.MOVE_UP_CKB", null)
    }

    override fun moveDown(i: Int) {
        clearStuckMetaState()
        inputLogic.moveCursorDown(i, isMetaKeyActive())
        sendPrivateCommand("dev.bbkb.ime.MOVE_DOWN_CKB", null)
    }

    private fun clearStuckMetaState() {
        if (!isMetaKeyActive()) {
            if (isCursorModeEnabled) physicalKeyboardStateTracker.cancelModifierTimers()
            physicalKeyboardStateTracker.consumeModifiersAfterKey(0, true)
        }
        if (isUnifiedInputBoardShowing()) return
        scheduleCursorModeDisable()
    }

    fun enableCursorMode(z: Boolean) {
        applyCursorModeState(z, true, false)
    }

    fun toggleCursorMode() {
        val z = !isCursorModeEnabled
        Logger.debug("ARROW_BAR_DIAG", "toggleCursorMode: toggling FCC to enable=$z (isCursorModeEnabled was ${isCursorModeEnabled})")
        applyCursorModeState(z, true, false)
    }

    fun applyCursorModeState(z: Boolean, z2: Boolean, z3: Boolean) {
        Logger.debug("ARROW_BAR_DIAG", "applyCursorModeState: enable=$z z3=$z3 isUnifiedInputBoardShowing()=${isUnifiedInputBoardShowing()} → will proceed=${z3 || !isUnifiedInputBoardShowing()}")
        if (z3 || !isUnifiedInputBoardShowing()) {
            if (z) {
                if (isCursorModeEnabled) return
                Logger.debug("IMEGesture", "Cursor mode enabled")
                fccController?.onFccEnabled(z3)
                isCursorModeEnabled = true
                if (!z3) showArrowBar()
                val currentInputConnection = getCurrentInputConnection()
                if (currentInputConnection != null && lastCursorAnchorInfo != null && !isMetaKeyActive() && (!z3 || !inputLogic.mRichInputConnection.hasSelection())) {
                    if (lastCursorAnchorInfo!!.selectionEnd != lastCursorAnchorInfo!!.selectionStart || inputLogic.mRichInputConnection.hasSelection()) {
                        currentInputConnection.performPrivateCommand("dev.bbkb.ime.FCC_ON", null)
                    } else {
                        cursorTracker.show(lastCursorAnchorInfo!!, rootInputView as ViewGroup?)
                    }
                    currentInputConnection.performPrivateCommand("dev.bbkb.ime.FCC_OFF", null)
                }
                if (!z3) scheduleCursorModeDisable()
                if (isGestureInputReady()) {
                    keyboardSwitcher.getMainKeyboardView()!!.cancelAllGestureTracking()
                }
                return
            }
            if (isCursorModeEnabled) {
                Logger.debug("IMEGesture", "Cursor mode disabled")
                isCursorModeEnabled = false
                hideArrowBar(z2)
                cursorTracker.hide()
                uiUpdateHandler.removeCallbacks(disableCursorModeRunnable)
                requestSuggestionsForCursorPosition()
            }
        }
    }

    /**
     * Re-evaluate the suggestion strip for wherever the cursor ended up, on **every** cursor-mode
     * exit — the second double tap, the 4 s auto-disable, a key press, [onViewClicked].
     *
     * Entering cursor mode cancels the composing word and hides the aux bar
     * ([showArrowBar]), and `RecorrectionController.performRecorrection` returns immediately
     * while [isCursorModeEnabled] is set, so none of the `onUpdateSelection` callbacks the arrow
     * keys generate ever reach it. The only re-evaluation on the way out was
     * [restoreSuggestionStrip]'s `postUpdateShiftState(false, false)`, and that `false` is
     * `performRecorrection`'s "do not offer the word under the cursor" flag: with it the word
     * list it builds is empty unless the word happens to carry suggestion spans, so the result
     * is `SuggestedWords.EMPTY` -> `setNeutralSuggestionStrip()`, which on Latin draws nothing.
     * The strip came back on screen blank (KEY2 owner report).
     *
     * This is the identical request `InputLogic.onUpdateSelection` makes at the end of the
     * editor-tap path — `postUpdateShiftState(true, true)` -> `MSG_UPDATE_SHIFT_MODE` ->
     * `performRecorrection(values, true, elementId)` — so the cursor's word gets its
     * recorrection suggestions and a cursor after a space gets next-word predictions, exactly as
     * if the user had tapped there. It re-composes only the word the cursor is actually on;
     * nothing resurrects the word cancelled on entry.
     *
     * [postUpdateShiftState] itself is gated on `isSuggestionStripActive()`; the
     * [isInputActive] check keeps the teardown exits ([onDestroy], [resetUiState]) from queuing
     * work for an editor that is going away.
     */
    private fun requestSuggestionsForCursorPosition() {
        if (!isInputActive) return
        uiUpdateHandler.postUpdateShiftState(true, true)
    }

    private fun scheduleCursorModeDisable() {
        uiUpdateHandler.removeCallbacks(disableCursorModeRunnable)
        if (isMetaKeyActive()) return
        uiUpdateHandler.postDelayed(disableCursorModeRunnable, 4000L)
    }

    private fun showArrowBar() {
        val arrowBar = arrowBarController
        Logger.debug("ARROW_BAR_DIAG", "showArrowBar: arrowBarController=${if (arrowBar == null) "NULL" else "non-null"} isShowing=${arrowBar?.isShowing()}")
        if (arrowBar == null || arrowBar.isShowing()) return
        hideUnifiedInputBoard()
        inputLogic.cancelComposingAndTouchEvent()
        uiCoordinator.hideSuggestionViews()
        hideInputBoard()
        Logger.debug("ARROW_BAR_DIAG", "showArrowBar: calling arrowBarController.show()")
        arrowBar.show()
    }

    private fun hideArrowBar(z: Boolean) {
        val arrowBar = arrowBarController
        if (arrowBar == null || !arrowBar.isShowing()) return
        arrowBar.hide()
        if (hasAuxBarView() || hasCjkSuggestionGrid()) {
            restoreSuggestionStrip(false, z)
        }
    }

    fun restoreSuggestionStrip(z: Boolean, z2: Boolean) {
        uiCoordinator.showSuggestionStripOrUim()
        if (!z2 || inputLogic.mRichInputConnection.hasSelection()) return
        uiUpdateHandler.postUpdateShiftState(false, false)
    }

    // ==================== Suggestions (SuggestionStripListener, CJK / flick listeners) ====================

    fun requestSuggestionUpdateSync(i: Int) {
        uiUpdateHandler.cancelPendingSuggestionUpdates()
        inputLogic.updateSuggestionsSync(settingsManager.getSettingsValues(), i)
    }

    fun requestSuggestionUpdateAsync(i: Int) {
        uiUpdateHandler.cancelPendingSuggestionUpdates()
        inputLogic.updateSuggestionsAsync(settingsManager.getSettingsValues(), i)
    }

    fun runSuggestionRequest(i: Int, aVar: SuggestionEngine.SuggestionCallback) {
        val c0965eM6834l = keyboardSwitcher.getCurrentKeyboard()
        if (BuildConfig.DEBUG) Log.d("SUGG", "runSuggestionRequest: keyboard=${c0965eM6834l != null} thread=${Thread.currentThread().name}")
        if (c0965eM6834l == null) {
            aVar.onSuggestionsReady(SuggestedWords.EMPTY)
        } else {
            inputLogic.requestSuggestionsWithContext(this, settingsManager.getSettingsValues(), c0965eM6834l.getProximityGrid(), getSymbolPageProvider().getSymbolPageOrder(), i, aVar)
        }
    }

    /** Display side of the suggestion pipeline (the request side is [suggestionUpdater]). */
    private val suggestionStrip = SuggestionStripPresenter(this)

    override fun showSuggestionStrip(c0666ac: SuggestedWords) = suggestionStrip.showOrNeutral(c0666ac)

    fun displaySuggestions(c0666ac: SuggestedWords, z: Boolean) = suggestionStrip.display(c0666ac, z)

    fun updateFlickMetrics() = suggestionStrip.updateFlickMetrics()

    fun updateFlickAndFilter() {
        updateFlickMetrics()
        updatePhysicalKeyboardFilter()
    }

    fun hideFlickSuggestions() {
        flickSuggestionView?.hide()
    }

    fun clearSuggestions() = suggestionStrip.clear()

    override fun setNeutralSuggestionStrip() = suggestionStrip.showNeutral()

    override fun showAddToDictionaryHint(str: String) {
        flickSuggestionView?.clearSuggestions(str)
    }

    // SuggestionStripListener interface methods
    override fun isShowingMoreSuggestions(): Boolean {
        return false
    }

    override fun dismissMoreSuggestions() {
        // AuxBarView handles this now - dismiss more suggestions panel
    }

    override fun onSuggestionPicked(suggestedWordInfoVar: SuggestedWords.SuggestedWordInfo, enumC0690f: InputSource) {
        applyPostEventUpdates(inputLogic.handleManualPick(settingsManager.getSettingsValues(), suggestedWordInfoVar, getSymbolPageProvider(), keyboardSwitcher.getKeyboardElementId(), uiUpdateHandler, enumC0690f), true)
    }

    override fun onCjkSuggestionSelected(suggestedWordInfoVar: SuggestedWords.SuggestedWordInfo) {
        onSuggestionPicked(suggestedWordInfoVar, InputSource.UNKNOWN)
    }

    fun isSuggestionStripActive(): Boolean {
        return settingsManager.getSettingsValues().isPredictionsEnabled && isInputActive && isInputViewShown()
    }

    fun refreshSuggestionStripVisibility() {
        uiCoordinator.updateSuggestionStripVisibility()
    }

    fun commitTouchEventText() {
        inputLogic.commitTouchEventText()
    }

    fun isAltMultitapSupported(): Boolean {
        return multitapEventHandler.isAltMultitapSupported()
    }

    fun setInsertAsString(z: Boolean) {
        shouldInsertAsString = z
    }

    /**
     * The plus on the add-to-dictionary highlight: one tap adds the word to the personal
     * dictionary, which also teaches the engine's DLM and syncs the system user dictionary
     * ([DictionaryManager.addWordSubstitution] -> `PersonalDictionaryUtil.add`), then asks the
     * editor to re-run its spell check so the red underline the word may carry goes away. The
     * system's add-word dialog, which this used to launch, is now only the fallback for a
     * dictionary that is not loaded yet.
     */
    fun addWordToUserDictionary(str: String) {
        val added = OneTapAddWord.add(DictionaryManager.getInstance(), str, dictionaryLoader.getLocale())
        if (added) {
            inputLogic.mRichInputConnection.requestSpellCheck()
        } else {
            val intentM5787a = IntentUtils.getAddWordToDictionaryIntent(str, dictionaryLoader.getLocale())
            if (intentM5787a != null) {
                try {
                    startActivity(intentM5787a)
                } catch (e: ActivityNotFoundException) {
                    Logger.errorWithException(LOG_TAG, e, "addWordToUserDictionary() failed to start dialog activity: ")
                }
            }
        }
        inputLogic.dismissMoreKeys()
    }

    // ==================== UIM, boards, dialogs and UI helpers ====================

    fun isOnScreenKeyboardVisible(): Boolean {
        return DeviceProfile.isOnScreenKeyboardVisible()
    }

    fun isUnifiedInputBoardShowing(): Boolean {
        val c0979i = keyboardSwitcher
        return c0979i.isUnifiedInputBoardShowing()
    }

    fun isUimEnabled(): Boolean = uiCoordinator.isUimEnabled()

    override fun shouldShowUim(): Boolean = uiCoordinator.shouldShowUim()

    private fun isUimVisible(): Boolean {
        // A view question, so it asks the view: isPalettesVisible() is EmojiPalettesView's own
        // renamed isShowing(). "Is the emoji board open" is a different question and belongs to
        // EmojiBoardController / the KeyboardState machine (§5.6 item 4).
        return !(!isUnifiedInputBoardShowing() || keyboardSwitcher.peekEmojiPalettesView()?.isPalettesVisible() == true)
    }

    private fun hideInputBoard() {
        uiCoordinator.hideUnifiedInputBoard()
    }

    fun hideUnifiedInputBoard() {
        val c1011iM6838p = keyboardSwitcher.getUnifiedInputBoardManager()
        if (c1011iM6838p == null || isUimEnabled()) return
        c1011iM6838p.hideKeyboardOnKeyboardStateChange()
    }

    fun refreshUnifiedInputBoard() {
        if (isOnScreenKeyboardVisible()) return
        val c1011iM6838p = KeyboardSwitcher.getInstance().getUnifiedInputBoardManager() ?: return
        if (!c1011iM6838p.isShowing()) return
        c1011iM6838p.refresh()
    }

    fun attachInputBoardHandler(handlerC1013k: UnifiedInputBoardHandler) {
        unifiedInputBoardHandler = handlerC1013k
    }

    fun hasAuxBarView(): Boolean {
        return auxBarManager != null && auxBarManager!!.getAuxBarView() != null
    }

    fun hasFlickSuggestionView(): Boolean = flickSuggestionView != null

    fun hasCjkSuggestionGrid(): Boolean = cjkSuggestionGridView != null

    fun hasFccController(): Boolean = fccController != null

    fun hasVoiceInputController(): Boolean = voiceInputController != null

    fun hasClipboardController(): Boolean = clipboardController != null

    fun getAuxBarView(): dev.bbkb.ime.keyboard.auxbar.AuxBarView? {
        return auxBarManager?.getAuxBarView()
    }

    fun cancelQuickSwitch() {
        if (isInputViewShown()) {
            richInputMethodManager!!.getInputMethodManager().hideSoftInputFromInputMethod(window.window!!.attributes.token, 2)
        }
    }

    fun showInputOptions() {
        if (isAlertDialogShowing()) return
        showInputOptionsMenu()
    }

    private fun showInputOptionsMenu() {
        val string = getString(R.string.english_ime_input_options)
        val string2 = getString(R.string.language_selection_title)
        val settingsString = try {
            val activityInfo = packageManager.getActivityInfo(android.content.ComponentName(this, ComposeSettingsActivity::class.java), 0)
            if (activityInfo != null) getString(activityInfo.labelRes) else "Settings"
        } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
            "Settings"
        }
        val charSequenceArr: Array<CharSequence> = arrayOf(string2, settingsString)
        val onClickListener = DialogInterface.OnClickListener { dialogInterface, i ->
            dialogInterface.dismiss()
            when (i) {
                0 -> {
                    val intentM5786a = IntentUtils.getInputLanguageSelectionIntent(richInputMethodManager!!.getInputMethodIdOfThisIme(), 337641472)
                    intentM5786a.putExtra("android.intent.extra.TITLE", string2)
                    startActivity(intentM5786a)
                }
                1 -> openSettings()
            }
        }
        val builder = AlertDialog.Builder(android.view.ContextThemeWrapper(this, R.style.platformDialogTheme))
        builder.setItems(charSequenceArr, onClickListener).setTitle(string)
        val alertDialogCreate = builder.create()
        alertDialogCreate.setCancelable(true)
        alertDialogCreate.setCanceledOnTouchOutside(true)
        showAlertDialog(alertDialogCreate)
    }

    private fun showAlertDialog(alertDialog: AlertDialog) {
        val windowToken = keyboardSwitcher.getMainKeyboardView()?.windowToken ?: return
        val window = alertDialog.window
        val attributes = window!!.attributes
        attributes.token = windowToken
        attributes.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
        window.attributes = attributes
        // Audit CT-22: 131072 == WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM.
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        currentAlertDialog = alertDialog
        alertDialog.show()
    }

    private fun isAlertDialogShowing(): Boolean {
        val alertDialog = currentAlertDialog
        return alertDialog != null && alertDialog.isShowing
    }

    private fun createSettingsIntent(): Intent {
        inputLogic.commitTypedWord(settingsManager.getSettingsValues(), "", InputSource.INTERNAL)
        requestHideSelf(0)
        keyboardSwitcher.getMainKeyboardView()?.closing()
        val intent = Intent()
        intent.setClass(this, dev.bbkb.ime.core.settings.ComposeSettingsActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or Intent.FLAG_ACTIVITY_NO_ANIMATION
        intent.putExtra("show_home_as_up", false)
        return intent
    }

    fun openSettings() {
        startActivity(createSettingsIntent())
    }

    fun openSlideboardSettings() {
        val intent = Intent(this, dev.bbkb.ime.core.settings.ComposeSettingsActivity::class.java)
        intent.putExtra("screen", "slideboard_settings")
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
    }

    // ==================== AuxBarManager.AuxBarEventListener ====================

    // Audit CT-23: both parameters are declared non-null, so the guards were constant-true and
    // sent a reader debugging a dropped suggestion chasing a null that cannot occur.
    override fun onSuggestionSelected(wordInfo: SuggestedWords.SuggestedWordInfo, inputSource: InputSource) {
        onSuggestionPicked(wordInfo, inputSource)
    }

    override fun onSuggestionLongPressed(wordInfo: SuggestedWords.SuggestedWordInfo) {
        onCjkSuggestionSelected(wordInfo)
    }

    override fun onAutofillSelected(position: Int) {
        if (BuildConfig.DEBUG) android.util.Log.d("INLINE_AUTOFILL_DEBUG", "[IME] onAutofillSelected() pos=$position - hiding bar")
        if (InlineAutofillManager.isSupported()) {
            val autofillManager = InlineAutofillManager.getInstance(this)
            autofillManager.onSelectionMade()
        }
    }

    override fun onHamburgerMenuClicked() {
        // Audit CT-23: uiCoordinator is a non-null val; the safe call was a no-op.
        uiCoordinator.showUnifiedInputMenuFromSuggestionStrip()
    }

    override fun onExpandSuggestionsClicked() {
        handleSymbolKeyLongPress()
    }

    override fun onAuxBarStateChanged(
        oldState: dev.bbkb.ime.keyboard.auxbar.AuxBarState,
        newState: dev.bbkb.ime.keyboard.auxbar.AuxBarState
    ) {
        Logger.debug(LOG_TAG, "AuxBar state changed: $oldState -> $newState")
    }

    override fun getMainKeyboard(): dev.bbkb.ime.keyboard.Keyboard? {
        return keyboardSwitcher.getCurrentKeyboard()
    }

    override fun onAccentSelected(accentChar: String) {
        // Audit CT-23: accentChar is non-null; only the emptiness half of the test is real.
        if (accentChar.isNotEmpty()) {
            inputLogic.replaceLastCharWithAccent(accentChar)
            Logger.debug(LOG_TAG, "Accent committed: $accentChar")
        }
    }

    override fun isInSymbolMode(): Boolean {
        val symbolPageOrder = physicalKeyboardStateTracker.getSymbolPageOrder()
        return symbolPageOrder == SYMBOL_PAGE_ORDER_FIRST || symbolPageOrder == SYMBOL_PAGE_ORDER_SECOND
    }

    override fun getMoreKeysForScanCode(scanCode: Int): String? {
        return keyboardSwitcher.getMoreKeysForScanCode(scanCode)
    }

    override fun getAuxCharacterResolver(): dev.bbkb.ime.core.keyevent.AuxCharacterResolver {
        return hardwareKeys.auxCharacterResolver()
    }

    // ==================== Accessors and super bridges for the Java collaborators ====================

    fun getSettingsManager(): SettingsManager = settingsManager

    fun getInputLogic(): InputLogic = inputLogic

    fun getUiCoordinator(): InputViewCoordinator = uiCoordinator

    fun getSubtypeManager(): SubtypeManager = subtypeManager

    fun getUiUpdateHandler(): UIUpdateHandler = uiUpdateHandler

    fun getDictionaryLoader(): DictionaryLoader? = dictionaryLoader

    fun getCursorTracker(): CursorTracker = cursorTracker

    fun getPhysicalKeyboardStateTracker(): PhysicalKeyboardStateTracker = physicalKeyboardStateTracker

    fun getKeyboardSwitcher(): KeyboardSwitcher = keyboardSwitcher

    fun getMultitapEventHandler(): MultitapEventHandler = multitapEventHandler

    fun getDisableCursorModeRunnable(): Runnable = disableCursorModeRunnable

    fun getSymbolPageOrder(): Int = physicalKeyboardStateTracker.getSymbolPageOrder()

    fun getControlMode(): ControlModeController = controlMode

    fun getHardwareKeys(): HardwareKeyBridge = hardwareKeys

    fun clearLastCursorAnchorInfo() { lastCursorAnchorInfo = null }

    fun superOnStartInput(ei: EditorInfo, r: Boolean) { super.onStartInput(ei, r) }

    fun superOnStartInputView(ei: EditorInfo, r: Boolean) { super.onStartInputView(ei, r) }

    fun superOnFinishInputView(f: Boolean) { super.onFinishInputView(f) }

    fun superOnFinishInput() { super.onFinishInput() }

    fun superOnKeyDown(i: Int, ev: KeyEvent): Boolean = super.onKeyDown(i, ev)

    fun superOnKeyUp(i: Int, ev: KeyEvent): Boolean = super.onKeyUp(i, ev)

    // ==================== Debug dump ====================

    override fun dump(fileDescriptor: FileDescriptor, printWriter: PrintWriter, strArr: Array<String>) {
        super.dump(fileDescriptor, printWriter, strArr)
        val printWriterPrinter = PrintWriterPrinter(printWriter)
        printWriterPrinter.println("BlackBerryIME state :")
        try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            // Audit CT-35: versionCode truncates to the low 32 bits of longVersionCode (API 28+).
            printWriterPrinter.println("  VersionCode = ${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(packageInfo)}")
            printWriterPrinter.println("  VersionName = ${packageInfo.versionName}")
        } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
            printWriterPrinter.println("  VersionCode = 0")
            printWriterPrinter.println("  VersionName = ")
        }
        val c0965eM6834l = keyboardSwitcher.getCurrentKeyboard()
        printWriterPrinter.println("  Keyboard mode = ${if (c0965eM6834l != null) c0965eM6834l.mId.mMode else -1}")
        val settingsValues = settingsManager.getSettingsValues()
        printWriterPrinter.println(settingsValues.dumpSettings())
    }

}
