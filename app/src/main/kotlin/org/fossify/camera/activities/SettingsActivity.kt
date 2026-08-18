package org.fossify.camera.activities

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.LinearLayout
import org.fossify.camera.BuildConfig
import org.fossify.camera.R
import org.fossify.camera.databinding.ActivitySettingsBinding
import org.fossify.camera.databinding.LayoutSaveLocationBinding
import org.fossify.camera.extensions.checkLocationPermission
import org.fossify.camera.extensions.config
import org.fossify.camera.extensions.getLastTwoFolderNames
import org.fossify.camera.models.CaptureMode
import org.fossify.camera.views.DashedDividerDrawable
import org.fossify.commons.dialogs.*
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.*
import org.fossify.commons.models.FAQItem
import org.fossify.commons.models.RadioItem
import org.fossify.commons.views.MyMaterialSwitch
import java.util.*
import kotlin.system.exitProcess

class SettingsActivity : SimpleActivity() {
    private val binding by viewBinding(ActivitySettingsBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding.apply {
            setContentView(root)
            setupOptionsMenu()
            refreshMenuItems()

            setupEdgeToEdge(padBottomSystem = listOf(settingsNestedScrollview))
            setupMaterialScrollListener(binding.settingsNestedScrollview, binding.settingsAppbar)
        }
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.settingsAppbar, NavigationIcon.Arrow)

        setupCustomizeColors()
        setupUseEnglish()
        setupLanguage()
        setupSound()
        setupVolumeButtonsAsShutter()
        setupMaxBrightness()
        setupFlipPhotos()
        setupSavePhotoMetadata()
        setupSavePhotoVideoLocation()
        setupSaveLocations()
        setupDialogShowTwoLevelPath()
        setupPhotoQuality()
        setupCaptureMode()
        setupAutoRenamePhoto()
        setupUsageInstructions()
        updateTextColors(binding.settingsHolder)

        val properPrimaryColor = getProperPrimaryColor()
        binding.apply {
            arrayListOf(
                settingsColorCustomizationLabel,
                settingsGeneralSettingsLabel,
                settingsCameraLabel,
                settingsSavingLabel,
                settingsSaveLocationsLabel,
            ).forEach {
                it.setTextColor(properPrimaryColor)
            }
        }
    }

    private fun refreshMenuItems() {
        binding.settingsToolbar.menu.apply {
            findItem(R.id.more_apps_from_us).isVisible =
                !resources.getBoolean(org.fossify.commons.R.bool.hide_google_relations)
        }
    }

    private fun setupOptionsMenu() {
        binding.settingsToolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.more_apps_from_us -> launchMoreAppsFromUsIntent()
                R.id.about -> launchAbout()
                else -> return@setOnMenuItemClickListener false
            }
            return@setOnMenuItemClickListener true
        }
    }

    private fun setupCustomizeColors() {
        binding.settingsColorCustomizationHolder.setOnClickListener {
            startCustomizationActivity()
        }
    }

    private fun setupUseEnglish() = binding.apply {
        settingsUseEnglishHolder.beVisibleIf((config.wasUseEnglishToggled || Locale.getDefault().language != "en") && !isTiramisuPlus())
        settingsUseEnglish.isChecked = config.useEnglish
        settingsUseEnglishHolder.setOnClickListener {
            settingsUseEnglish.toggle()
            config.useEnglish = settingsUseEnglish.isChecked
            exitProcess(0)
        }
    }

    private fun setupLanguage() = binding.apply {
        settingsLanguage.text = Locale.getDefault().displayLanguage
        settingsLanguageHolder.beVisibleIf(isTiramisuPlus())

        listOf(settingsGeneralSettingsHolder, settingsGeneralSettingsLabel).forEach {
            it.beGoneIf(settingsUseEnglishHolder.isGone() && settingsPurchaseThankYouHolder.isGone() && settingsLanguageHolder.isGone())
        }

        settingsLanguageHolder.setOnClickListener {
            launchChangeAppLanguageIntent()
        }
    }

    private fun launchAbout() {
        val licenses = LICENSE_GLIDE

        val faqItems = arrayListOf(
            FAQItem(R.string.faq_1_title, R.string.faq_1_text)
        )

        if (!resources.getBoolean(org.fossify.commons.R.bool.hide_google_relations)) {
            faqItems.add(
                FAQItem(
                    org.fossify.commons.R.string.faq_2_title_commons,
                    org.fossify.commons.R.string.faq_2_text_commons
                )
            )
            faqItems.add(
                FAQItem(
                    org.fossify.commons.R.string.faq_6_title_commons,
                    org.fossify.commons.R.string.faq_6_text_commons
                )
            )
        }

        startAboutActivity(
            appNameId = R.string.app_name,
            licenseMask = licenses,
            versionName = BuildConfig.VERSION_NAME,
            faqItems = faqItems,
            showFAQBeforeMail = true
        )
    }

    private fun getLastPart(path: String): String {
        val humanized = humanizePath(path)
        return humanized.substringAfterLast("/", humanized)
    }

    private fun setupSound() = binding.apply {
        settingsSound.isChecked = config.isSoundEnabled
        settingsSoundHolder.setOnClickListener {
            settingsSound.toggle()
            config.isSoundEnabled = settingsSound.isChecked
        }
    }

    private fun setupVolumeButtonsAsShutter() = binding.apply {
        settingsVolumeButtonsAsShutter.isChecked = config.volumeButtonsAsShutter
        settingsVolumeButtonsAsShutterHolder.setOnClickListener {
            settingsVolumeButtonsAsShutter.toggle()
            config.volumeButtonsAsShutter = settingsVolumeButtonsAsShutter.isChecked
        }
    }

    private fun setupMaxBrightness() = binding.apply {
        settingsMaxBrightness.isChecked = config.maxBrightness
        settingsMaxBrightnessHolder.setOnClickListener {
            settingsMaxBrightness.toggle()
            config.maxBrightness = settingsMaxBrightness.isChecked
        }
    }

    private fun setupFlipPhotos() = binding.apply {
        settingsFlipPhotos.isChecked = config.flipPhotos
        settingsFlipPhotosHolder.setOnClickListener {
            settingsFlipPhotos.toggle()
            config.flipPhotos = settingsFlipPhotos.isChecked
        }
    }

    private fun setupSavePhotoMetadata() = binding.apply {
        settingsSavePhotoMetadata.isChecked = config.savePhotoMetadata
        settingsSavePhotoMetadataHolder.setOnClickListener {
            settingsSavePhotoMetadata.toggle()
            config.savePhotoMetadata = settingsSavePhotoMetadata.isChecked
        }
    }

    private fun setupSavePhotoVideoLocation() = binding.apply {
        settingsSavePhotoVideoLocation.isChecked = config.savePhotoVideoLocation
        settingsSavePhotoVideoLocationHolder.setOnClickListener {
            val willEnableSavePhotoVideoLocation = !config.savePhotoVideoLocation

            if (willEnableSavePhotoVideoLocation) {
                if (checkLocationPermission()) {
                    updateSavePhotoVideoLocationConfig(true)
                } else {
                    handlePermission(PERMISSION_ACCESS_FINE_LOCATION) { _ ->
                        if (checkLocationPermission()) {
                            updateSavePhotoVideoLocationConfig(true)
                        } else {
                            OpenDeviceSettingsDialog(
                                activity = this@SettingsActivity,
                                message = getString(org.fossify.commons.R.string.allow_location_permission)
                            )
                        }
                    }
                }
            } else {
                updateSavePhotoVideoLocationConfig(false)
            }
        }
    }

    private fun updateSavePhotoVideoLocationConfig(enabled: Boolean) {
        binding.settingsSavePhotoVideoLocation.isChecked = enabled
        config.savePhotoVideoLocation = enabled
    }

    private fun setupSaveLocations() {
        val container = binding.settingsSaveLocationsContainer
        container.removeAllViews()
        val switches = mutableListOf<MyMaterialSwitch>()

        for (index in 1..5) {
            val item = LayoutSaveLocationBinding.inflate(layoutInflater, container, false)
            val title = getString(R.string.location_index, index)
            item.locationSwitch.text = if (index == 1) buildStagingTitle(title) else title
            item.locationSwitch.isChecked = config.getLocationEnabled(index)
            item.locationPathValue.text = getLocationPathDisplay(index)

            item.locationSwitchHolder.setOnClickListener {
                if (!item.locationSwitch.isEnabled) {
                    return@setOnClickListener
                }
                item.locationSwitch.toggle()
                config.setLocationEnabled(index, item.locationSwitch.isChecked)
                refreshLocationSwitchStates(switches)
            }

            item.locationPathHolder.setOnClickListener {
                pickLocationPath(index) { path ->
                    config.setLocationPath(index, path)
                    item.locationPathValue.text = getLocationPathDisplay(index)
                }
            }

            container.addView(item.root)
            switches.add(item.locationSwitch)

            if (index < 5) {
                container.addView(createDashedDivider())
            }
        }

        refreshLocationSwitchStates(switches)
    }

    private fun buildStagingTitle(title: String): CharSequence {
        val note = getString(R.string.location_staging_note)
        val full = title + note
        return SpannableString(full).apply {
            setSpan(
                ForegroundColorSpan(Color.GRAY),
                title.length,
                full.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private fun getLocationPathDisplay(index: Int): String {
        val path = config.getLocationPath(index)
        return if (path.isEmpty()) "" else path.getLastTwoFolderNames()
    }

    private fun createDashedDivider(): View {
        val density = resources.displayMetrics.density
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (1 * density).toInt()
            )
            background = DashedDividerDrawable(
                color = 0xFF777777.toInt(),
                strokeWidthPx = density,
                dashWidthPx = 4 * density,
                dashGapPx = 3 * density,
            )
        }
    }

    private fun pickLocationPath(index: Int, callback: (String) -> Unit) {
        val current = config.getLocationPath(index)
        val initial = if (current.isEmpty()) config.savePhotosFolder else current
        if (isOrWasThankYouInstalled()) {
            FilePickerDialog(this@SettingsActivity, initial, false, showFAB = true) { path ->
                handleSAFDialog(path) { success ->
                    if (success) {
                        callback(path)
                    }
                }
            }
        } else {
            FeatureLockedDialog(this@SettingsActivity) { }
        }
    }

    private fun refreshLocationSwitchStates(switches: List<MyMaterialSwitch>) {
        val enabledIndexes = (1..5).filter { config.getLocationEnabled(it) }
        if (enabledIndexes.isEmpty()) {
            // 异常兑底：全关时重开位置1
            config.setLocationEnabled(1, true)
            switches.firstOrNull()?.isChecked = true
            refreshLocationSwitchStates(switches)
            return
        }
        val singleEnabled = enabledIndexes.size == 1
        for ((i, switch) in switches.withIndex()) {
            val index = i + 1
            // 只剩一个启用时，把那个启用的开关禁用（灰掉不可关）
            switch.isEnabled = !(singleEnabled && config.getLocationEnabled(index))
        }
    }

    private fun setupDialogShowTwoLevelPath() = binding.apply {
        settingsDialogShowTwoLevelPath.isChecked = config.dialogShowTwoLevelPath
        settingsDialogShowTwoLevelPathHolder.setOnClickListener {
            settingsDialogShowTwoLevelPath.toggle()
            config.dialogShowTwoLevelPath = settingsDialogShowTwoLevelPath.isChecked
        }
    }

    private fun setupPhotoQuality() {
        updatePhotoQuality(config.photoQuality)
        binding.settingsPhotoQualityHolder.setOnClickListener {
            val items = arrayListOf(
                RadioItem(100, "100%"),
                RadioItem(95, "95%"),
                RadioItem(90, "90%"),
                RadioItem(85, "85%"),
                RadioItem(80, "80%"),
                RadioItem(75, "75%"),
                RadioItem(70, "70%"),
                RadioItem(65, "65%"),
                RadioItem(60, "60%"),
                RadioItem(55, "55%"),
                RadioItem(50, "50%")
            )

            RadioGroupDialog(this@SettingsActivity, items, config.photoQuality) {
                config.photoQuality = it as Int
                updatePhotoQuality(it)
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updatePhotoQuality(quality: Int) {
        binding.settingsPhotoQuality.text = "$quality%"
    }

    private fun setupCaptureMode() {
        updateCaptureMode(config.captureMode)
        binding.settingsCaptureModeHolder.setOnClickListener {
            val items = CaptureMode.values().mapIndexed { index, captureMode ->
                RadioItem(index, getString(captureMode.stringResId), captureMode)
            }

            RadioGroupDialog(this@SettingsActivity, ArrayList(items), config.captureMode.ordinal) {
                config.captureMode = it as CaptureMode
                updateCaptureMode(it)
            }
        }
    }

    private fun updateCaptureMode(captureMode: CaptureMode) {
        binding.settingsCaptureMode.text = getString(captureMode.stringResId)
    }

    private fun setupAutoRenamePhoto() = binding.apply {
        settingsAutoRenamePhoto.isChecked = config.autoRenamePhoto
        settingsAutoRenamePhotoHolder.setOnClickListener {
            settingsAutoRenamePhoto.toggle()
            config.autoRenamePhoto = settingsAutoRenamePhoto.isChecked
        }
    }

    private fun setupUsageInstructions() = binding.apply {
        settingsUsageInstructionsHolder.setOnClickListener {
            startActivity(Intent(this@SettingsActivity, HelpActivity::class.java))
        }
    }
}
