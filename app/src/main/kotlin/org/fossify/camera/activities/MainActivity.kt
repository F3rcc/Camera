package org.fossify.camera.activities

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.ProgressDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.InputType
import android.text.TextUtils
import android.view.*
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.appcompat.widget.AppCompatEditText
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import androidx.core.view.*
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.transition.*
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestOptions
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.tabs.TabLayout
import org.fossify.camera.BuildConfig
import org.fossify.camera.R
import org.fossify.camera.databinding.ActivityMainBinding
import org.fossify.camera.extensions.config
import org.fossify.camera.extensions.fadeIn
import org.fossify.camera.extensions.fadeOut
import org.fossify.camera.extensions.getLastFolderName
import org.fossify.camera.extensions.getLastTwoFolderNames
import org.fossify.camera.extensions.setShadowIcon
import org.fossify.camera.extensions.toFlashModeId
import org.fossify.camera.helpers.*
import org.fossify.camera.implementations.CameraXInitializer
import org.fossify.camera.implementations.CameraXPreviewListener
import org.fossify.camera.interfaces.MyPreview
import org.fossify.camera.models.ResolutionOption
import org.fossify.camera.models.TimerMode
import org.fossify.camera.views.FocusCircleView
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.*
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : SimpleActivity(), PhotoProcessor.MediaSavedListener, CameraXPreviewListener {
    private companion object {
        private const val ANIMATION_DURATION = 500L
        private const val PHOTO_MODE_INDEX = 1
        private const val VIDEO_MODE_INDEX = 0
        private const val MIN_SWIPE_DISTANCE_X = 100
        private const val TIMER_2_SECONDS = 2001
        private const val SWITCH_CAMERA_ROTATION_ANGLE = 180f
        private const val MAX_ZOOM_PRESETS = 4
        private const val ZOOM_BAR_BACKGROUND = 0x33FFFFFF
        private val RENAME_DIALOG_BACKGROUND_COLOR = 0xFF2A292F.toInt()
    }

    private val binding by viewBinding(ActivityMainBinding::inflate)

    private lateinit var defaultScene: Scene
    private lateinit var flashModeScene: Scene
    private lateinit var timerScene: Scene
    private lateinit var mFocusCircleView: FocusCircleView
    private lateinit var mediaSoundHelper: MediaSoundHelper
    private var mPreview: MyPreview? = null
    private var mediaSizeToggleGroup: MaterialButtonToggleGroup? = null
    private var mPreviewUri: Uri? = null
    private var mIsHardwareShutterHandled = false
    private var currentRotation = Surface.ROTATION_0
    private var countDownTimer: CountDownTimer? = null
    private var mOriginalBrightness: Float? = null
    private val photoRenamer by lazy { PhotoRenamer(this) }

    /**
     * One photo is being captured or written at a time. The rename dialog is shown right after the
     * shutter press, long before the file exists, so the user decision and the disk write have to
     * be matched up here instead of blocking the ui.
     */
    private class PendingPhoto(
        val stagingUri: Uri,
        val suggestedName: String,
    ) {
        var dialog: AlertDialog? = null
        var confirmedName: String? = null
        var targetLocations: List<Int>? = null
        var cancelled = false
        var savedUri: Uri? = null
        var finalized = false
    }

    // a shot can still be written while the next one is already being taken, so keep them apart
    private val pendingPhotos = mutableListOf<PendingPhoto>()
    private var pendingThumbnailUri: Uri? = null
    private val thumbnailHandler = Handler(Looper.getMainLooper())
    private val thumbnailRunnable = Runnable {
        pendingThumbnailUri?.let { loadLastTakenMedia(it) }
    }

    private val orientationListener = object : OrientationManager.Listener {
        override fun onDeviceRotationChanged(rotation: Int) {
            if (isDestroyed || rotation == currentRotation) {
                return
            }

            currentRotation = rotation
            animateViews(rotationToUiDegrees(rotation))
        }
    }

    private var zoomChips = mutableListOf<TextView>()
    private var zoomChipRatios = listOf<Float>()
    private var zoomBarMinRatio = -1f
    private var zoomBarMaxRatio = -1f
    private var zoomRestored = false
    private var isFrontCameraSelected = false
    private var lastZoomRatio = 1f
    private val zoomSaveHandler = Handler(Looper.getMainLooper())
    private val saveZoomRunnable = Runnable { persistZoomRatio() }

    private val tabSelectedListener = object : TabSelectedListener {
        override fun onTabSelected(tab: TabLayout.Tab) {
            handlePermission(PERMISSION_RECORD_AUDIO) {
                if (it) {
                    when (tab.position) {
                        VIDEO_MODE_INDEX -> mPreview?.initVideoMode()
                        PHOTO_MODE_INDEX -> mPreview?.initPhotoMode()
                        else -> throw IllegalStateException("Unsupported tab position ${tab.position}")
                    }
                } else {
                    toast(org.fossify.commons.R.string.no_audio_permissions)
                    selectPhotoTab()
                    if (isVideoCaptureIntent()) {
                        finish()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        useDynamicTheme = false
        super.onCreate(savedInstanceState)
        appLaunched(BuildConfig.APPLICATION_ID)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        initVariables()
        tryInitCamera()
        supportActionBar?.hide()

        val windowInsetsController = ViewCompat.getWindowInsetsController(window.decorView)
        windowInsetsController?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController?.hide(WindowInsetsCompat.Type.statusBars())

        if (isOreoMr1Plus()) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_FULLSCREEN
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasStorageAndCameraPermissions()) {
            val isInPhotoMode = isInPhotoMode()
            setupPreviewImage(isInPhotoMode)
            mFocusCircleView.setStrokeColor(getProperPrimaryColor())
            toggleActionButtons(enabled = true)
            OrientationManager.getInstance(this).addListener(orientationListener)
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        mOriginalBrightness = window.updateBrightness(config.maxBrightness, mOriginalBrightness)
        ensureTransparentNavigationBar()
        if (ViewCompat.getWindowInsetsController(window.decorView) == null) {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    override fun onPause() {
        super.onPause()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        OrientationManager.getInstance(this).removeListener(orientationListener)
        zoomSaveHandler.removeCallbacks(saveZoomRunnable)
        persistZoomRatio()
        thumbnailHandler.removeCallbacks(thumbnailRunnable)
        if (!isAskingPermissions) {
            cancelTimer()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mPreview = null
        mediaSoundHelper.release()
    }

    override fun onBackPressedCompat(): Boolean {
        return if (!closeOptions()) {
            false
        } else {
            true
        }
    }

    private fun selectPhotoTab(triggerListener: Boolean = false) {
        if (!triggerListener) {
            removeTabListener()
        }

        binding.cameraModeTab.getTabAt(PHOTO_MODE_INDEX)?.select()
        setTabListener()
    }

    private fun selectVideoTab(triggerListener: Boolean = false) {
        if (!triggerListener) {
            removeTabListener()
        }
        binding.cameraModeTab.getTabAt(VIDEO_MODE_INDEX)?.select()
        setTabListener()
    }

    private fun setTabListener() {
        binding.cameraModeTab.addOnTabSelectedListener(tabSelectedListener)
    }

    private fun removeTabListener() {
        binding.cameraModeTab.removeOnTabSelectedListener(tabSelectedListener)
    }

    private fun ensureTransparentNavigationBar() {
        window.navigationBarColor = ContextCompat.getColor(this, android.R.color.transparent)
    }

    private fun initVariables() {
        mIsHardwareShutterHandled = false
        mediaSoundHelper = MediaSoundHelper(this)
        mediaSoundHelper.loadSounds()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return if (keyCode == KeyEvent.KEYCODE_CAMERA && !mIsHardwareShutterHandled) {
            mIsHardwareShutterHandled = true
            shutterPressed()
            true
        } else if (!mIsHardwareShutterHandled && config.volumeButtonsAsShutter && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            mIsHardwareShutterHandled = true
            shutterPressed()
            true
        } else {
            super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_CAMERA || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            mIsHardwareShutterHandled = false
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun hideIntentButtons() = binding.apply {
        cameraModeHolder.beGone()
        layoutTop.settings.beGone()
        lastPhotoVideoPreview.beInvisible()
    }

    private fun tryInitCamera() {
        handlePermission(PERMISSION_CAMERA) { grantedCameraPermission ->
            if (grantedCameraPermission) {
                handleStoragePermission {
                    val isInPhotoMode = isInPhotoMode()
                    if (isInPhotoMode) {
                        initializeCamera(true)
                    } else {
                        handlePermission(PERMISSION_RECORD_AUDIO) { grantedRecordAudioPermission ->
                            if (grantedRecordAudioPermission) {
                                initializeCamera(false)
                            } else {
                                toast(org.fossify.commons.R.string.no_audio_permissions)
                                if (isThirdPartyIntent()) {
                                    finish()
                                } else {
                                    // re-initialize in photo mode
                                    config.initPhotoMode = true
                                    tryInitCamera()
                                }
                            }
                        }
                    }
                }
            } else {
                toast(org.fossify.commons.R.string.no_camera_permissions)
                finish()
            }
        }
    }

    private fun isInPhotoMode(): Boolean {
        return mPreview?.isInPhotoMode() ?: if (isVideoCaptureIntent()) {
            false
        } else if (isImageCaptureIntent()) {
            true
        } else {
            config.initPhotoMode
        }
    }

    private fun handleStoragePermission(callback: (granted: Boolean) -> Unit) {
        if (isTiramisuPlus()) {
            val mediaPermissionIds =
                mutableListOf(PERMISSION_READ_MEDIA_IMAGES, PERMISSION_READ_MEDIA_VIDEO)
            if (isUpsideDownCakePlus()) {
                mediaPermissionIds.add(PERMISSION_READ_MEDIA_VISUAL_USER_SELECTED)
            }

            handlePartialMediaPermissions(permissionIds = mediaPermissionIds, callback = callback)
        } else {
            handlePermission(PERMISSION_WRITE_STORAGE, callback)
        }
    }

    private fun isThirdPartyIntent() = isVideoCaptureIntent() || isImageCaptureIntent()

    private fun isImageCaptureIntent(): Boolean =
        intent?.action == MediaStore.ACTION_IMAGE_CAPTURE || intent?.action == MediaStore.ACTION_IMAGE_CAPTURE_SECURE

    private fun isVideoCaptureIntent(): Boolean = intent?.action == MediaStore.ACTION_VIDEO_CAPTURE

    private fun createToggleGroup(): MaterialButtonToggleGroup {
        return MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun initializeCamera(isInPhotoMode: Boolean) {
        setContentView(binding.root)
        initButtons()
        initModeSwitcher()
        binding.apply {
            defaultScene = Scene(topOptions, layoutTop.defaultIcons)
            flashModeScene = Scene(topOptions, layoutFlash.flashToggleGroup)
            timerScene = Scene(topOptions, layoutTimer.timerToggleGroup)
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.viewHolder) { _, windowInsets ->
            val safeInsetBottom = windowInsets.displayCutout?.safeInsetBottom ?: 0
            val safeInsetTop = windowInsets.displayCutout?.safeInsetTop ?: 0

            binding.topOptions.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = safeInsetTop
            }

            val systemBarsInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val marginBottom = systemBarsInsets.bottom +
                    resources.getDimensionPixelSize(org.fossify.commons.R.dimen.bigger_margin)

            binding.shutter.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = marginBottom
            }

            WindowInsetsCompat.CONSUMED
        }

        if (isInPhotoMode) {
            selectPhotoTab()
        } else {
            selectVideoTab()
        }

        val outputUri = intent.extras?.get(MediaStore.EXTRA_OUTPUT) as? Uri
        val isThirdPartyIntent = isThirdPartyIntent()
        mPreview = CameraXInitializer(this).createCameraXPreview(
            binding.previewView,
            listener = this,
            mediaSoundHelper = mediaSoundHelper,
            outputUri = outputUri,
            isThirdPartyIntent = isThirdPartyIntent,
            initInPhotoMode = isInPhotoMode,
        )

        mFocusCircleView = FocusCircleView(this).apply {
            id = View.generateViewId()
        }
        binding.viewHolder.addView(mFocusCircleView)

        setupPreviewImage(true)
        initFlashModeTransitionNames()
        initTimerModeTransitionNames()

        if (isThirdPartyIntent) {
            hideIntentButtons()
        }
    }

    private fun initFlashModeTransitionNames() = binding.layoutFlash.apply {
        val baseName = getString(R.string.toggle_flash)
        flashAuto.transitionName = "$baseName$FLASH_AUTO"
        flashOff.transitionName = "$baseName$FLASH_OFF"
        flashOn.transitionName = "$baseName$FLASH_ON"
        flashAlwaysOn.transitionName = "$baseName$FLASH_ALWAYS_ON"
    }

    private fun initTimerModeTransitionNames() = binding.layoutTimer.apply {
        val baseName = getString(R.string.toggle_timer)
        timerOff.transitionName = "$baseName${TimerMode.OFF.name}"
        timer3s.transitionName = "$baseName${TimerMode.TIMER_3.name}"
        timer5s.transitionName = "$baseName${TimerMode.TIMER_5.name}"
        timer10S.transitionName = "$baseName${TimerMode.TIMER_10.name}"
    }

    private fun initButtons() = binding.apply {
        timerText.setFactory { layoutInflater.inflate(R.layout.timer_text, null) }
        toggleCamera.setOnClickListener {
            animateCameraToggle()
            mPreview!!.toggleFrontBackCamera()
        }

        lastPhotoVideoPreview.setOnClickListener { showLastMediaPreview() }

        layoutTop.apply {
            toggleFlash.setOnClickListener { mPreview!!.handleFlashlightClick() }
            toggleTimer.setOnClickListener {
                val transitionSet = createTransition()
                TransitionManager.go(timerScene, transitionSet)
                layoutTimer.timerToggleGroup.beVisible()
                layoutTimer.timerToggleGroup.check(config.timerMode.getTimerModeResId())
                layoutTimer.timerToggleGroup.children.forEach { setButtonColors(it as MaterialButton) }
            }

            settings.setShadowIcon(R.drawable.ic_settings_vector)
            settings.setOnClickListener { launchSettings() }
            changeResolution.setOnClickListener { mPreview?.showChangeResolution() }
        }

        shutter.setOnClickListener { shutterPressed() }

        layoutFlash.apply {
            flashOn.setShadowIcon(R.drawable.ic_flash_on_vector)
            flashOn.setOnClickListener { selectFlashMode(FLASH_ON) }

            flashOff.setShadowIcon(R.drawable.ic_flash_off_vector)
            flashOff.setOnClickListener { selectFlashMode(FLASH_OFF) }

            flashAuto.setShadowIcon(R.drawable.ic_flash_auto_vector)
            flashAuto.setOnClickListener { selectFlashMode(FLASH_AUTO) }

            flashAlwaysOn.setShadowIcon(R.drawable.ic_flashlight_vector)
            flashAlwaysOn.setOnClickListener { selectFlashMode(FLASH_ALWAYS_ON) }
        }

        layoutTimer.apply {
            timerOff.setShadowIcon(R.drawable.ic_timer_off_vector)
            timerOff.setOnClickListener { selectTimerMode(TimerMode.OFF) }

            timer3s.setShadowIcon(R.drawable.ic_timer_3_vector)
            timer3s.setOnClickListener { selectTimerMode(TimerMode.TIMER_3) }

            timer5s.setShadowIcon(R.drawable.ic_timer_5_vector)
            timer5s.setOnClickListener { selectTimerMode(TimerMode.TIMER_5) }

            timer10S.setShadowIcon(R.drawable.ic_timer_10_vector)
            timer10S.setOnClickListener { selectTimerMode(TimerMode.TIMER_10) }
        }

        setTimerModeIcon(config.timerMode)
    }

    private fun animateCameraToggle() = binding.toggleCamera.animate()
        .rotationBy(SWITCH_CAMERA_ROTATION_ANGLE)
        .setDuration(ANIMATION_DURATION)
        .setInterpolator(FastOutSlowInInterpolator())
        .withEndAction {
            // settle back onto the angle matching the current device rotation
            binding.toggleCamera.rotation = rotationToUiDegrees(currentRotation).toFloat()
        }
        .start()

    private fun selectTimerMode(timerMode: TimerMode) {
        config.timerMode = timerMode
        setTimerModeIcon(timerMode)
        closeOptions()
    }

    private fun setTimerModeIcon(timerMode: TimerMode) = binding.layoutTop.toggleTimer.apply {
        setShadowIcon(timerMode.getTimerModeDrawableRes())
        transitionName = "${getString(R.string.toggle_timer)}${timerMode.name}"
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initModeSwitcher() {
        val gestureDetector = GestureDetectorCompat(this, object : GestureDetectorListener() {
            override fun onDown(e: MotionEvent): Boolean {
                // we have to return true here so ACTION_UP (and onFling) can be dispatched
                return true
            }

            override fun onFling(
                event1: MotionEvent?,
                event2: MotionEvent?,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (event1 == null || event2 == null) {
                    return true
                }

                val deltaX = event1.x - event2.x
                val deltaXAbs = abs(deltaX)

                if (deltaXAbs >= MIN_SWIPE_DISTANCE_X) {
                    if (deltaX > 0) {
                        onSwipeLeft()
                    } else {
                        onSwipeRight()
                    }
                }

                return true
            }
        })

        binding.cameraModeTab.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
        }
    }

    private fun onSwipeLeft() {
        if (!isThirdPartyIntent() && binding.cameraModeHolder.isVisible()) {
            selectPhotoTab(triggerListener = true)
        }
    }

    private fun onSwipeRight() {
        if (!isThirdPartyIntent() && binding.cameraModeHolder.isVisible()) {
            selectVideoTab(triggerListener = true)
        }
    }

    private fun selectFlashMode(flashMode: Int) {
        closeOptions()
        mPreview?.setFlashlightState(flashMode)
    }


    private fun showLastMediaPreview() {
        if (mPreviewUri != null) {
            val path =
                applicationContext.getRealPathFromURI(mPreviewUri!!) ?: mPreviewUri!!.toString()
            openPathIntent(path, false, BuildConfig.APPLICATION_ID)
        }
    }

    private fun shutterPressed() {
        if (countDownTimer != null) {
            cancelTimer()
        } else if (isInPhotoMode()) {
            val timerMode = config.timerMode
            if (timerMode == TimerMode.OFF) {
                mPreview?.tryTakePicture()
            } else {
                scheduleTimer(timerMode)
            }
        } else {
            mPreview?.toggleRecording()
        }
    }

    private fun cancelTimer() {
        mediaSoundHelper.stopTimerCountdown2SecondsSound()
        countDownTimer?.cancel()
        countDownTimer = null
        resetViewsOnTimerFinish()
    }

    private fun launchSettings() {
        val intent = Intent(applicationContext, SettingsActivity::class.java)
        startActivity(intent)
    }

    override fun onInitPhotoMode() {
        zoomRestored = false
        binding.apply {
            shutter.setImageResource(R.drawable.ic_shutter_animated)
            layoutTop.toggleTimer.beVisible()
            layoutTop.toggleTimer.fadeIn()
        }
        closeOptions()
        setupPreviewImage(true)
        selectPhotoTab()
    }

    override fun onInitVideoMode() {
        zoomRestored = false
        binding.apply {
            shutter.setImageResource(R.drawable.ic_video_rec_animated)
            layoutTop.toggleTimer.fadeOut()
            layoutTop.toggleTimer.beGone()
        }
        closeOptions()
        setupPreviewImage(false)
        selectVideoTab()
    }

    private fun setupPreviewImage(isPhoto: Boolean) {
        val uri =
            if (isPhoto) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val lastMediaId = getLatestMediaId(uri)
        if (lastMediaId == 0L) {
            return
        }

        mPreviewUri = Uri.withAppendedPath(uri, lastMediaId.toString())

        loadLastTakenMedia(mPreviewUri)
    }

    private fun loadLastTakenMedia(uri: Uri?) {
        mPreviewUri = uri
        runOnUiThread {
            if (!isDestroyed) {
                val options = RequestOptions()
                    .circleCrop()
                    .diskCacheStrategy(DiskCacheStrategy.NONE)

                Glide.with(this)
                    .load(uri)
                    .apply(options)
                    .transition(DrawableTransitionOptions.withCrossFade())
                    .into(binding.lastPhotoVideoPreview)
            }
        }
    }

    private fun hasStorageAndCameraPermissions(): Boolean {
        return if (isInPhotoMode()) hasPhotoModePermissions() else hasVideoModePermissions()
    }

    private fun hasPhotoModePermissions(): Boolean {
        return if (isTiramisuPlus()) {
            var hasMediaPermission = hasPermission(PERMISSION_READ_MEDIA_IMAGES) || hasPermission(
                PERMISSION_READ_MEDIA_VIDEO
            )
            if (isUpsideDownCakePlus()) {
                hasMediaPermission =
                    hasMediaPermission || hasPermission(PERMISSION_READ_MEDIA_VISUAL_USER_SELECTED)
            }
            hasMediaPermission && hasPermission(PERMISSION_CAMERA)
        } else {
            hasPermission(PERMISSION_WRITE_STORAGE) && hasPermission(PERMISSION_CAMERA)
        }
    }

    private fun hasVideoModePermissions(): Boolean {
        return if (isTiramisuPlus()) {
            var hasMediaPermission = hasPermission(PERMISSION_READ_MEDIA_VIDEO)
            if (isUpsideDownCakePlus()) {
                hasMediaPermission =
                    hasMediaPermission || hasPermission(PERMISSION_READ_MEDIA_VISUAL_USER_SELECTED)
            }
            hasMediaPermission && hasPermission(PERMISSION_CAMERA) && hasPermission(
                PERMISSION_RECORD_AUDIO
            )
        } else {
            hasPermission(PERMISSION_WRITE_STORAGE) && hasPermission(PERMISSION_CAMERA) && hasPermission(
                PERMISSION_RECORD_AUDIO
            )
        }
    }

    private fun animateViews(degrees: Int) = binding.apply {
        val views = arrayOf(
            toggleCamera,
            layoutTop.toggleTimer,
            layoutTop.toggleFlash,
            layoutTop.changeResolution,
            shutter,
            layoutTop.settings,
            lastPhotoVideoPreview,
            layoutTimer.timerOff,
            layoutTimer.timer3s,
            layoutTimer.timer5s,
            layoutTimer.timer10S,
            layoutFlash.flashOff,
            layoutFlash.flashAuto,
            layoutFlash.flashOn,
            layoutFlash.flashAlwaysOn
        )
        for (view in views) {
            rotate(view, degrees)
        }
        mediaSizeToggleGroup?.children?.forEach { child ->
            rotate(child, degrees)
        }
        // the chips are rotated one by one instead of the whole row, the row keeps its size and
        // only the numbers follow the device orientation
        zoomChips.forEach { chip ->
            rotate(chip, degrees)
        }
    }

    private fun rotate(view: View, degrees: Int) =
        view.animate().rotation(degrees.toFloat()).start()

    override fun onZoomStateChanged(minRatio: Float, maxRatio: Float, currentRatio: Float) {
        lastZoomRatio = currentRatio
        if (minRatio != zoomBarMinRatio || maxRatio != zoomBarMaxRatio) {
            buildZoomBar(minRatio, maxRatio)
        }

        if (!zoomRestored) {
            zoomRestored = true
            val target = getZoomRatioToRestore(minRatio, maxRatio)
            if (abs(target - currentRatio) > 0.02f) {
                mPreview?.setZoomRatio(target)
            }
        }

        updateZoomSelection(currentRatio)
        scheduleZoomPersist()
    }

    private fun getZoomRatioToRestore(minRatio: Float, maxRatio: Float): Float {
        val stored = if (config.rememberZoom) {
            if (isFrontCameraSelected) config.zoomRatioFront else config.zoomRatioBack
        } else {
            1f
        }
        return stored.coerceIn(minRatio, maxRatio)
    }

    private fun buildZoomPresets(minRatio: Float, maxRatio: Float): List<Float> {
        val presets = mutableListOf<Float>()
        if (minRatio < 0.95f) {
            presets.add(minRatio)
        }
        presets.add(1f)
        listOf(2f, 5f, 10f).forEach { ratio ->
            if (ratio <= maxRatio * 0.99f) {
                presets.add(ratio)
            }
        }
        return presets.distinct().sorted().take(MAX_ZOOM_PRESETS)
    }

    private fun buildZoomBar(minRatio: Float, maxRatio: Float) {
        zoomBarMinRatio = minRatio
        zoomBarMaxRatio = maxRatio
        zoomChips.clear()
        zoomChipRatios = emptyList()
        binding.zoomBar.removeAllViews()

        val presets = buildZoomPresets(minRatio, maxRatio)
        if (presets.size < 2 || isImageCaptureIntent() || isVideoCaptureIntent()) {
            binding.zoomBar.beGone()
            return
        }

        val density = resources.displayMetrics.density
        val chipSize = (44 * density).toInt()
        val chipSpacing = (2 * density).toInt()

        // one translucent pill behind all the ratios, like the Xiaomi toggle view. The chips are
        // fixed size boxes so rotating each of them keeps the spacing even in every orientation
        binding.zoomBar.background = GradientDrawable().apply {
            cornerRadius = 26 * density
            setColor(ZOOM_BAR_BACKGROUND)
        }
        binding.zoomBar.setPadding(chipSpacing, chipSpacing, chipSpacing, chipSpacing)

        presets.forEachIndexed { index, ratio ->
            val chip = TextView(this).apply {
                text = formatZoomRatio(ratio)
                textSize = 13f
                gravity = Gravity.CENTER
                rotation = rotationToUiDegrees(currentRotation).toFloat()
                setTextColor(Color.WHITE)
                isClickable = true
                setOnClickListener { animateZoomTo(ratio) }
            }
            binding.zoomBar.addView(
                chip,
                LinearLayout.LayoutParams(chipSize, chipSize).apply {
                    if (index < presets.lastIndex) {
                        marginEnd = chipSpacing
                    }
                }
            )
            zoomChips.add(chip)
        }

        zoomChipRatios = presets
        binding.zoomBar.beVisible()
        updateZoomSelection(lastZoomRatio)
    }

    private fun updateZoomSelection(currentRatio: Float) {
        if (zoomChips.isEmpty()) {
            return
        }

        val primaryColor = getProperPrimaryColor()
        zoomChips.forEachIndexed { index, chip ->
            val ratio = zoomChipRatios.getOrNull(index) ?: return@forEachIndexed
            val isSelected = abs(ratio - currentRatio) <= max(0.02f, ratio * 0.02f)
            chip.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (isSelected) primaryColor else Color.TRANSPARENT)
            }
            chip.setTextColor(if (isSelected) Color.WHITE else Color.argb(230, 255, 255, 255))
        }
    }

    private fun animateZoomTo(ratio: Float) {
        if (abs(ratio - lastZoomRatio) < 0.01f) {
            return
        }

        ValueAnimator.ofFloat(lastZoomRatio, ratio).apply {
            duration = 200
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener { mPreview?.setZoomRatio(it.animatedValue as Float) }
            start()
        }
    }

    private fun formatZoomRatio(ratio: Float): String {
        return if (abs(ratio - ratio.roundToInt()) < 0.05f) {
            "${ratio.roundToInt()}x"
        } else {
            String.format(Locale.US, "%.1fx", ratio)
        }
    }

    private fun scheduleZoomPersist() {
        if (!config.rememberZoom) {
            return
        }

        zoomSaveHandler.removeCallbacks(saveZoomRunnable)
        zoomSaveHandler.postDelayed(saveZoomRunnable, 400)
    }

    private fun persistZoomRatio() {
        if (!config.rememberZoom) {
            return
        }

        if (isFrontCameraSelected) {
            config.zoomRatioFront = lastZoomRatio
        } else {
            config.zoomRatioBack = lastZoomRatio
        }
    }

    override fun onChangeCamera(frontCamera: Boolean) {
        isFrontCameraSelected = frontCamera
        zoomRestored = false
    }

    override fun setHasFrontAndBackCamera(hasFrontAndBack: Boolean) {
        binding.toggleCamera.beVisibleIf(hasFrontAndBack)
    }

    override fun setFlashAvailable(available: Boolean) {
        if (available) {
            binding.layoutTop.toggleFlash.beVisible()
        } else {
            binding.layoutTop.toggleFlash.beGone()
            mPreview?.setFlashlightState(FLASH_OFF)
        }
    }

    override fun onPhotoCaptureStart() {
        toggleActionButtons(enabled = false)
    }

    override fun onPhotoCaptureEnd() {
        toggleActionButtons(enabled = true)
    }

    private fun toggleActionButtons(enabled: Boolean) = binding.apply {
        runOnUiThread {
            shutter.isClickable = enabled
            previewView.isEnabled = enabled
            layoutTop.changeResolution.isEnabled = enabled
            toggleCamera.isClickable = enabled
            layoutTop.toggleFlash.isClickable = enabled
        }
    }

    override fun shutterAnimation() {
        binding.shutterAnimation.alpha = 1.0f
        binding.shutterAnimation.animate().alpha(0f).setDuration(ANIMATION_DURATION).start()
    }

    override fun onMediaSaved(uri: Uri, isPhoto: Boolean) {
        binding.layoutTop.changeResolution.isEnabled = true
        when {
            isImageCaptureIntent() -> {
                loadLastTakenMedia(uri)
                Intent().apply {
                    data = uri
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    setResult(RESULT_OK, this)
                }
                finish()
            }

            isVideoCaptureIntent() -> {
                loadLastTakenMedia(uri)
                Intent().apply {
                    data = uri
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    setResult(RESULT_OK, this)
                }
                finish()
            }

            isPhoto -> onPhotoSaved(uri)
            else -> {
                // videos still show the dialog after the file is complete, they are far too big
                // to keep the user waiting on a dialog before the recording is written
                scheduleThumbnailUpdate(uri)
                if (config.autoRenamePhoto) {
                    showRenamePhotoDialog(uri, isPhoto = false)
                }
            }
        }
    }

    override fun onPhotoCaptureQueued(uri: Uri, suggestedName: String) {
        if (!config.autoRenamePhoto || isImageCaptureIntent() || isVideoCaptureIntent()) {
            return
        }

        val pending = PendingPhoto(uri, suggestedName)
        pendingPhotos.add(pending)
        // let the capture start first, the dialog is allowed to appear a frame later
        window.decorView.post {
            if (pendingPhotos.contains(pending) && !isDestroyed) {
                showRenamePhotoDialog(uri, isPhoto = true, pending = pending)
            }
        }
    }

    override fun onPhotoCaptureFailed() {
        // only one capture is in flight at a time, so the newest entry is the one that failed
        val pending = pendingPhotos.lastOrNull() ?: return
        pending.dialog?.takeIf { it.isShowing }?.dismiss()
        pendingPhotos.remove(pending)
    }

    private fun onPhotoSaved(uri: Uri) {
        scheduleThumbnailUpdate(uri)

        val pending = pendingPhotos.firstOrNull { it.stagingUri == uri }
        if (pending == null) {
            // no dialog was opened for this shot, keep the old behaviour
            if (config.autoRenamePhoto) {
                showRenamePhotoDialog(uri, isPhoto = true)
            }
            return
        }

        pending.savedUri = uri
        when {
            pending.cancelled -> pendingPhotos.remove(pending)
            pending.confirmedName != null -> enqueuePhotoFinalize(pending)
            // otherwise the dialog is still open, the confirm click will enqueue it
        }
    }

    private fun enqueuePhotoFinalize(pending: PendingPhoto) {
        if (pending.finalized) {
            return
        }

        val savedUri = pending.savedUri ?: return
        val newName = pending.confirmedName ?: return
        val targets = pending.targetLocations ?: return
        pending.finalized = true
        pendingPhotos.remove(pending)

        // nothing to do when the name did not change and only the staging folder was chosen
        if (newName == pending.suggestedName && targets == listOf(1)) {
            return
        }

        mPreview?.distributeMedia(savedUri, newName, targets, isPhoto = true) { result ->
            result.failedIndexes.forEach { index ->
                toast(getString(R.string.location_save_failed, index))
            }
            result.savedUris.firstOrNull()?.let { scheduleThumbnailUpdate(it, immediate = true) }
        }
    }

    private fun scheduleThumbnailUpdate(uri: Uri, immediate: Boolean = false) {
        pendingThumbnailUri = uri
        thumbnailHandler.removeCallbacks(thumbnailRunnable)
        if (immediate) {
            thumbnailRunnable.run()
        } else {
            thumbnailHandler.postDelayed(thumbnailRunnable, 300)
        }
    }

    private fun showRenamePhotoDialog(uri: Uri, isPhoto: Boolean, pending: PendingPhoto? = null) {
        if (isDestroyed || isFinishing) {
            if (pending != null) {
                pendingPhotos.remove(pending)
            }
            return
        }

        // for a shot that is still being written the name is already known, otherwise read it
        val baseName = pending?.suggestedName ?: run {
            val currentName = photoRenamer.getDisplayName(uri) ?: return
            currentName.substringBeforeLast('.', currentName)
        }

        val available = (1..5).filter {
            config.getLocationEnabled(it) && config.getLocationPath(it).isNotEmpty()
        }
        if (available.isEmpty()) {
            if (pending != null) {
                pendingPhotos.remove(pending)
            }
            return
        }

        val remembered = if (isPhoto) config.lastSelectedPhotoLocations else config.lastSelectedVideoLocations
        var preChecked = remembered.intersect(available.toSet())
        if (preChecked.isEmpty()) {
            preChecked = if (1 in available) setOf(1) else available.take(1).toSet()
        }
        if (!isPhoto && preChecked.size > 2) {
            preChecked = preChecked.take(2).toSet()
        }
        val selected = preChecked.toMutableSet()
        val twoLevel = config.dialogShowTwoLevelPath
        val primaryColor = getProperPrimaryColor()

        val input = AppCompatEditText(this).apply {
            setText(baseName)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = EditorInfo.IME_ACTION_DONE
            setTextColor(Color.WHITE)
            setSelectAllOnFocus(true)
            selectAll()
        }

        val density = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), 0, (16 * density).toInt(), 0)
        }

        container.addView(TextView(this).apply {
            text = getString(R.string.save_location_dialog_title)
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(0, 0, 0, (8 * density).toInt())
        })

        available.forEach { index ->
            container.addView(AppCompatCheckBox(this).apply {
                val path = config.getLocationPath(index)
                val folderDisplay = if (twoLevel) path.getLastTwoFolderNames() else path.getLastFolderName()
                text = "${getString(R.string.location_index, index)} ($folderDisplay)"
                isChecked = index in selected
                setSingleLine()
                ellipsize = TextUtils.TruncateAt.START
                setTextColor(Color.WHITE)
                buttonTintList = ColorStateList.valueOf(primaryColor)
                setOnLongClickListener {
                    toast(path)
                    true
                }
                setOnClickListener {
                    if (isChecked) {
                        if (!isPhoto && selected.size >= 2) {
                            isChecked = false
                        } else {
                            selected.add(index)
                        }
                    } else {
                        if (selected.size <= 1) {
                            isChecked = true
                        } else {
                            selected.remove(index)
                        }
                    }
                }
            })
        }

        container.addView(input)

        val titleId = if (isPhoto) R.string.rename_photo_title else R.string.rename_video_title

        getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                setupDialogStuff(container, this, titleId = titleId) { dialog ->
                    pending?.dialog = dialog
                    // ask for the keyboard right away so it shows up together with the dialog
                    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
                    dialog.window?.setBackgroundDrawable(
                        resources.getColoredDrawableWithColor(
                            org.fossify.commons.R.drawable.dialog_bg,
                            RENAME_DIALOG_BACKGROUND_COLOR
                        )
                    )
                    dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.setTextColor(Color.WHITE)
                    dialog.findViewById<TextView>(org.fossify.commons.R.id.dialog_title_textview)?.setTextColor(Color.WHITE)

                    var handled = false

                    fun confirm() {
                        if (handled) {
                            return
                        }

                        val newBase = sanitizeFileName(input.text.toString())
                        if (newBase.isEmpty()) {
                            return
                        }
                        handled = true
                        val targets = selected.toList()
                        if (isPhoto) {
                            config.lastSelectedPhotoLocations = selected
                        } else {
                            config.lastSelectedVideoLocations = selected
                        }

                        if (pending != null) {
                            // the photo is still being captured or written, remember the decision
                            // and let the save callback run the rename and the distribution
                            pending.confirmedName = newBase
                            pending.targetLocations = targets
                            pending.dialog = null
                            dialog.dismiss()
                            if (pending.savedUri != null) {
                                enqueuePhotoFinalize(pending)
                            }
                            return
                        }

                        dialog.dismiss()

                        // 名字没变且只保存到位置1：无需分发
                        if (newBase == baseName && targets == listOf(1)) {
                            return
                        }

                        val progress = ProgressDialog(this@MainActivity).apply {
                            setMessage(getString(R.string.saving_progress))
                            setCancelable(false)
                            show()
                        }

                        mPreview?.distributeMedia(uri, newBase, targets, isPhoto) { result ->
                            progress.dismiss()
                            result.failedIndexes.forEach { index ->
                                toast(getString(R.string.location_save_failed, index))
                            }
                            result.savedUris.firstOrNull()?.let {
                                mPreviewUri = it
                                loadLastTakenMedia(it)
                            }
                        }
                    }

                    dialog.setOnDismissListener {
                        if (!handled && pending != null) {
                            // cancel button, back press or a tap outside: keep the photo in the
                            // staging folder under its suggested name
                            pending.cancelled = true
                            pending.dialog = null
                            if (pending.savedUri != null) {
                                pendingPhotos.remove(pending)
                            }
                        }
                    }

                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { confirm() }
                    input.setOnEditorActionListener { _, actionId, _ ->
                        if (actionId == EditorInfo.IME_ACTION_DONE) {
                            confirm()
                            true
                        } else {
                            false
                        }
                    }

                    input.post {
                        input.requestFocus()
                        input.selectAll()
                    }
                }
            }
    }

    private fun sanitizeFileName(name: String): String {
        val forbidden = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
        return name.trim().map { char ->
            if (char in forbidden || char.isISOControl()) '_' else char
        }.joinToString("")
    }

    override fun onImageCaptured(bitmap: Bitmap) {
        if (isImageCaptureIntent()) {
            Intent().apply {
                putExtra("data", bitmap)
                setResult(RESULT_OK, this)
            }
            finish()
        }
    }

    override fun onChangeFlashMode(flashMode: Int) {
        binding.layoutTop.apply {
            val flashDrawable = when (flashMode) {
                FLASH_OFF -> R.drawable.ic_flash_off_vector
                FLASH_ON -> R.drawable.ic_flash_on_vector
                FLASH_AUTO -> R.drawable.ic_flash_auto_vector
                else -> R.drawable.ic_flashlight_vector
            }
            toggleFlash.setShadowIcon(flashDrawable)
            toggleFlash.transitionName = "${getString(R.string.toggle_flash)}$flashMode"
        }
    }

    override fun onVideoRecordingStarted() {
        binding.apply {
            cameraModeHolder.beInvisible()
            videoRecCurrTimer.beVisible()

            toggleCamera.fadeOut()
            lastPhotoVideoPreview.fadeOut()

            layoutTop.changeResolution.isEnabled = false
            layoutTop.settings.isEnabled = false
            shutter.post {
                if (!isDestroyed) {
                    shutter.isSelected = true
                }
            }
        }
    }

    override fun onVideoRecordingStopped() {
        binding.apply {
            cameraModeHolder.beVisible()

            toggleCamera.fadeIn()
            lastPhotoVideoPreview.fadeIn()

            videoRecCurrTimer.text = 0.getFormattedDuration()
            videoRecCurrTimer.beGone()

            shutter.isSelected = false
            layoutTop.changeResolution.isEnabled = true
            layoutTop.settings.isEnabled = true
        }
    }

    override fun onVideoDurationChanged(durationNanos: Long) {
        val seconds = TimeUnit.NANOSECONDS.toSeconds(durationNanos).toInt()
        binding.videoRecCurrTimer.text = seconds.getFormattedDuration()
    }

    override fun onFocusCamera(xPos: Float, yPos: Float) {
        mFocusCircleView.drawFocusCircle(xPos, yPos)
    }

    override fun onTouchPreview() {
        closeOptions()
    }

    private fun closeOptions(): Boolean {
        binding.apply {
            if (mediaSizeToggleGroup?.isVisible() == true ||
                layoutFlash.flashToggleGroup.isVisible() || layoutTimer.timerToggleGroup.isVisible()
            ) {
                val transitionSet = createTransition()
                TransitionManager.go(defaultScene, transitionSet)
                mediaSizeToggleGroup?.beGone()
                layoutFlash.flashToggleGroup.beGone()
                layoutTimer.timerToggleGroup.beGone()
                layoutTop.defaultIcons.beVisible()
                return true
            }

            return false
        }
    }

    override fun displaySelectedResolution(resolutionOption: ResolutionOption) {
        val imageRes = resolutionOption.imageDrawableResId
        binding.layoutTop.changeResolution.setShadowIcon(imageRes)
        binding.layoutTop.changeResolution.transitionName = "${resolutionOption.buttonViewId}"
    }

    override fun showImageSizes(
        selectedResolution: ResolutionOption,
        resolutions: List<ResolutionOption>,
        isPhotoCapture: Boolean,
        isFrontCamera: Boolean,
        onSelect: (index: Int, changed: Boolean) -> Unit
    ) {
        binding.topOptions.removeView(mediaSizeToggleGroup)
        val mediaSizeToggleGroup = createToggleGroup().apply {
            mediaSizeToggleGroup = this
        }

        binding.topOptions.addView(mediaSizeToggleGroup)

        val onItemClick = { clickedViewId: Int ->
            closeOptions()
            val index = resolutions.indexOfFirst { it.buttonViewId == clickedViewId }
            onSelect.invoke(index, selectedResolution.buttonViewId != clickedViewId)
        }

        val currentDegrees = rotationToUiDegrees(currentRotation)

        resolutions.forEach {
            val button = createButton(it, onItemClick)
            button.rotation = currentDegrees.toFloat()
            mediaSizeToggleGroup.addView(button)
        }

        mediaSizeToggleGroup.check(selectedResolution.buttonViewId)

        val transitionSet = createTransition()
        val mediaSizeScene = Scene(binding.topOptions, mediaSizeToggleGroup)
        TransitionManager.go(mediaSizeScene, transitionSet)
        binding.layoutTop.defaultIcons.beGone()
        mediaSizeToggleGroup.beVisible()
        mediaSizeToggleGroup.children.map { it as MaterialButton }.forEach(::setButtonColors)
    }

    private fun createButton(
        resolutionOption: ResolutionOption,
        onClick: (clickedViewId: Int) -> Unit
    ): MaterialButton {
        val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            weight = 1f
        }

        return (layoutInflater.inflate(R.layout.layout_button, null) as MaterialButton).apply {
            layoutParams = params
            setShadowIcon(resolutionOption.imageDrawableResId)
            id = resolutionOption.buttonViewId
            transitionName = "${resolutionOption.buttonViewId}"
            setOnClickListener {
                onClick.invoke(id)
            }
        }
    }

    private fun createTransition(): Transition {
        val fadeTransition = Fade()
        return TransitionSet().apply {
            addTransition(fadeTransition)
            this.duration = resources.getInteger(R.integer.icon_anim_duration).toLong()
        }
    }

    override fun showFlashOptions(photoCapture: Boolean) {
        binding.layoutFlash.apply {
            val transitionSet = createTransition()
            TransitionManager.go(flashModeScene, transitionSet)
            flashAuto.beVisibleIf(photoCapture)
            flashAlwaysOn.beVisibleIf(photoCapture)
            flashToggleGroup.check(config.flashlightState.toFlashModeId())

            flashToggleGroup.beVisible()
            flashToggleGroup.children.forEach { setButtonColors(it as MaterialButton) }
        }
    }

    private fun setButtonColors(button: MaterialButton) {
        val primaryColor = getProperPrimaryColor()
        val states = arrayOf(
            intArrayOf(-android.R.attr.state_checked),
            intArrayOf(android.R.attr.state_checked)
        )
        val iconColors = intArrayOf(
            ContextCompat.getColor(this, org.fossify.commons.R.color.md_grey_white),
            primaryColor
        )
        button.iconTint = ColorStateList(states, iconColors)
    }

    override fun adjustPreviewView(requiresCentering: Boolean) {
        binding.apply {
            val constraintSet = ConstraintSet()
            constraintSet.clone(viewHolder)
            if (requiresCentering) {
                constraintSet.connect(
                    previewView.id,
                    ConstraintSet.TOP,
                    topOptions.id,
                    ConstraintSet.BOTTOM
                )
                constraintSet.connect(
                    previewView.id,
                    ConstraintSet.BOTTOM,
                    cameraModeHolder.id,
                    ConstraintSet.TOP
                )
            } else {
                constraintSet.connect(
                    previewView.id,
                    ConstraintSet.TOP,
                    ConstraintSet.PARENT_ID,
                    ConstraintSet.TOP
                )
                constraintSet.connect(
                    previewView.id,
                    ConstraintSet.BOTTOM,
                    ConstraintSet.PARENT_ID,
                    ConstraintSet.BOTTOM
                )
            }
            constraintSet.applyTo(viewHolder)
        }
    }

    override fun mediaSaved(path: String) {
        rescanPaths(arrayListOf(path)) {
            setupPreviewImage(true)
            Intent(BROADCAST_REFRESH_MEDIA).apply {
                putExtra(REFRESH_PATH, path)
                `package` = "org.fossify.gallery"
                sendBroadcast(this)
            }
        }

        if (isImageCaptureIntent()) {
            setResult(RESULT_OK)
            finish()
        }
    }

    private fun scheduleTimer(timerMode: TimerMode) {
        hideViewsOnTimerStart()
        binding.shutter.setImageState(intArrayOf(R.attr.state_timer_cancel), true)
        binding.timerText.beVisible()
        var playSound = true
        countDownTimer = object : CountDownTimer(timerMode.millisInFuture, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = (TimeUnit.MILLISECONDS.toSeconds(millisUntilFinished) + 1).toString()
                binding.timerText.setText(seconds)
                if (playSound && config.isSoundEnabled) {
                    if (millisUntilFinished <= TIMER_2_SECONDS) {
                        mediaSoundHelper.playTimerCountdown2SecondsSound()
                        playSound = false
                    } else {
                        mediaSoundHelper.playTimerCountdownSound()
                    }
                }
            }

            override fun onFinish() {
                cancelTimer()
                mPreview!!.tryTakePicture()
            }
        }.start()
    }

    private fun hideViewsOnTimerStart() = binding.apply {
        arrayOf(topOptions, toggleCamera, lastPhotoVideoPreview, cameraModeHolder).forEach {
            it.fadeOut()
            it.beInvisible()
        }
    }

    private fun resetViewsOnTimerFinish() = binding.apply {
        arrayOf(topOptions, toggleCamera, lastPhotoVideoPreview, cameraModeHolder).forEach {
            it.fadeIn()
            it.beVisible()
        }

        timerText.beGone()
        shutter.setImageState(intArrayOf(-R.attr.state_timer_cancel), true)
    }
}
