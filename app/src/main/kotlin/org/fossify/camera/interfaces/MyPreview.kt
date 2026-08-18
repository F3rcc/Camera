package org.fossify.camera.interfaces

import android.net.Uri
import org.fossify.camera.helpers.MediaDistributor

interface MyPreview {

    fun isInPhotoMode(): Boolean

    fun setFlashlightState(state: Int)

    fun toggleFrontBackCamera()

    fun handleFlashlightClick()

    fun tryTakePicture()

    fun toggleRecording()

    fun initPhotoMode()

    fun initVideoMode()

    fun showChangeResolution()

    fun distributeMedia(
        sourceUri: Uri,
        newBaseName: String,
        targetIndexes: List<Int>,
        isPhoto: Boolean,
        onDone: (MediaDistributor.DistributionResult) -> Unit,
    )
}
