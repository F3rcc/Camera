package org.fossify.camera.helpers

import android.content.Context
import android.os.Environment
import androidx.camera.core.CameraSelector
import org.fossify.camera.models.CaptureMode
import org.fossify.camera.models.TimerMode
import org.fossify.commons.helpers.BaseConfig
import java.io.File

class Config(context: Context) : BaseConfig(context) {
    companion object {
        fun newInstance(context: Context) = Config(context)
    }

    var savePhotosFolder: String
        get(): String {
            var path = prefs.getString(
                SAVE_PHOTOS,
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).toString()
            )
            if (!File(path).exists() || !File(path).isDirectory) {
                path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                    .toString()
                savePhotosFolder = path
            }
            return path!!
        }
        set(path) = prefs.edit().putString(SAVE_PHOTOS, path).apply()

    var isSoundEnabled: Boolean
        get() = prefs.getBoolean(SOUND, true)
        set(enabled) = prefs.edit().putBoolean(SOUND, enabled).apply()

    var volumeButtonsAsShutter: Boolean
        get() = prefs.getBoolean(VOLUME_BUTTONS_AS_SHUTTER, false)
        set(volumeButtonsAsShutter) = prefs.edit()
            .putBoolean(VOLUME_BUTTONS_AS_SHUTTER, volumeButtonsAsShutter).apply()

    var flipPhotos: Boolean
        get() = prefs.getBoolean(FLIP_PHOTOS, true)
        set(flipPhotos) = prefs.edit().putBoolean(FLIP_PHOTOS, flipPhotos).apply()

    var lastUsedCameraLens: Int
        get() = prefs.getInt(LAST_USED_CAMERA_LENS, CameraSelector.LENS_FACING_BACK)
        set(lens) = prefs.edit().putInt(LAST_USED_CAMERA_LENS, lens).apply()

    var initPhotoMode: Boolean
        get() = prefs.getBoolean(INIT_PHOTO_MODE, true)
        set(initPhotoMode) = prefs.edit().putBoolean(INIT_PHOTO_MODE, initPhotoMode).apply()

    var flashlightState: Int
        get() = prefs.getInt(FLASHLIGHT_STATE, FLASH_OFF)
        set(state) = prefs.edit().putInt(FLASHLIGHT_STATE, state).apply()

    var backPhotoResIndex: Int
        get() = prefs.getInt(BACK_PHOTO_RESOLUTION_INDEX, 0)
        set(backPhotoResIndex) = prefs.edit().putInt(BACK_PHOTO_RESOLUTION_INDEX, backPhotoResIndex)
            .apply()

    var backVideoResIndex: Int
        get() = prefs.getInt(BACK_VIDEO_RESOLUTION_INDEX, 0)
        set(backVideoResIndex) = prefs.edit().putInt(BACK_VIDEO_RESOLUTION_INDEX, backVideoResIndex)
            .apply()

    var frontPhotoResIndex: Int
        get() = prefs.getInt(FRONT_PHOTO_RESOLUTION_INDEX, 0)
        set(frontPhotoResIndex) = prefs.edit()
            .putInt(FRONT_PHOTO_RESOLUTION_INDEX, frontPhotoResIndex).apply()

    var frontVideoResIndex: Int
        get() = prefs.getInt(FRONT_VIDEO_RESOLUTION_INDEX, 0)
        set(frontVideoResIndex) = prefs.edit()
            .putInt(FRONT_VIDEO_RESOLUTION_INDEX, frontVideoResIndex).apply()

    var savePhotoMetadata: Boolean
        get() = prefs.getBoolean(SAVE_PHOTO_METADATA, true)
        set(savePhotoMetadata) = prefs.edit().putBoolean(SAVE_PHOTO_METADATA, savePhotoMetadata)
            .apply()

    var savePhotoVideoLocation: Boolean
        get() = prefs.getBoolean(SAVE_PHOTO_VIDEO_LOCATION, false)
        set(savePhotoVideoLocation) = prefs.edit()
            .putBoolean(SAVE_PHOTO_VIDEO_LOCATION, savePhotoVideoLocation).apply()

    var photoQuality: Int
        get() = prefs.getInt(PHOTO_QUALITY, 80)
        set(photoQuality) = prefs.edit().putInt(PHOTO_QUALITY, photoQuality).apply()

    var captureMode: CaptureMode
        get() = CaptureMode.values()[prefs.getInt(
            CAPTURE_MODE,
            CaptureMode.MINIMIZE_LATENCY.ordinal
        )]
        set(captureMode) = prefs.edit().putInt(CAPTURE_MODE, captureMode.ordinal).apply()

    var maxBrightness: Boolean
        get() = prefs.getBoolean(MAX_BRIGHTNESS, false)
        set(maxBrightness) = prefs.edit().putBoolean(MAX_BRIGHTNESS, maxBrightness).apply()

    var timerMode: TimerMode
        get() = TimerMode.values().getOrNull(prefs.getInt(TIMER_MODE, TimerMode.OFF.ordinal))
            ?: TimerMode.OFF
        set(timerMode) = prefs.edit().putInt(TIMER_MODE, timerMode.ordinal).apply()

    var autoRenamePhoto: Boolean
        get() = prefs.getBoolean(AUTO_RENAME_PHOTO, true)
        set(autoRenamePhoto) = prefs.edit().putBoolean(AUTO_RENAME_PHOTO, autoRenamePhoto).apply()

    fun getLocationEnabled(index: Int): Boolean {
        return prefs.getBoolean("$SAVE_LOCATION_ENABLED_PREFIX$index", index == 1)
    }

    fun setLocationEnabled(index: Int, enabled: Boolean) {
        prefs.edit().putBoolean("$SAVE_LOCATION_ENABLED_PREFIX$index", enabled).apply()
    }

    // 位置1 复用 savePhotosFolder（含 DCIM 兜底逻辑），位置2~5 用独立 pref。
    fun getLocationPath(index: Int): String {
        return if (index == 1) {
            savePhotosFolder
        } else {
            prefs.getString("$SAVE_LOCATION_PATH_PREFIX$index", "").orEmpty()
        }
    }

    fun setLocationPath(index: Int, path: String) {
        if (index == 1) {
            savePhotosFolder = path
        } else {
            prefs.edit().putString("$SAVE_LOCATION_PATH_PREFIX$index", path).apply()
        }
    }

    var dialogShowTwoLevelPath: Boolean
        get() = prefs.getBoolean(DIALOG_SHOW_TWO_LEVEL_PATH, true)
        set(enabled) = prefs.edit().putBoolean(DIALOG_SHOW_TWO_LEVEL_PATH, enabled).apply()

    var lastSelectedPhotoLocations: Set<Int>
        get() = prefs.getString(LAST_SELECTED_PHOTO_LOCATIONS, "1").orEmpty()
            .split(',').mapNotNull { it.toIntOrNull() }.toSet()
        set(locations) = prefs.edit()
            .putString(LAST_SELECTED_PHOTO_LOCATIONS, locations.sorted().joinToString(",")).apply()

    var lastSelectedVideoLocations: Set<Int>
        get() = prefs.getString(LAST_SELECTED_VIDEO_LOCATIONS, "1").orEmpty()
            .split(',').mapNotNull { it.toIntOrNull() }.toSet()
        set(locations) = prefs.edit()
            .putString(LAST_SELECTED_VIDEO_LOCATIONS, locations.sorted().joinToString(",")).apply()

}
