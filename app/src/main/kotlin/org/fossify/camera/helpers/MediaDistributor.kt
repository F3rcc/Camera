package org.fossify.camera.helpers

import android.net.Uri
import org.fossify.camera.extensions.config
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.helpers.ensureBackgroundThread

/**
 * Distributes an already-staged media file (saved to 位置1) into the user-selected target
 * locations after the rename dialog is confirmed.
 */
class MediaDistributor(
    private val activity: BaseSimpleActivity,
    private val mediaOutputHelper: MediaOutputHelper,
) {

    private val config = activity.config
    private val photoRenamer = PhotoRenamer(activity)

    data class DistributionResult(
        val savedUris: List<Uri>,
        val failedIndexes: List<Int>,
        val fallbackToStaging: Boolean,
    )

    fun distribute(
        sourceUri: Uri,
        newBaseName: String,
        targetIndexes: List<Int>,
        isPhoto: Boolean,
        onDone: (DistributionResult) -> Unit,
    ) {
        ensureBackgroundThread {
            val result = doDistribute(sourceUri, newBaseName, targetIndexes, isPhoto)
            activity.runOnUiThread { onDone(result) }
        }
    }

    private fun doDistribute(
        sourceUri: Uri,
        newBaseName: String,
        targetIndexes: List<Int>,
        isPhoto: Boolean,
    ): DistributionResult {
        val savedUris = mutableListOf<Uri>()
        val failedIndexes = mutableListOf<Int>()
        var fallbackToStaging = false

        // 1. 先改名暂存文件（在位置1）
        val renamedUri = photoRenamer.renamePhoto(sourceUri, newBaseName) ?: sourceUri
        val fileName = photoRenamer.getDisplayName(renamedUri).orEmpty()

        // 2. 复制到勾选的位置（位置1 是暂存处，跳过自复制）
        val stagingIndex = 1
        val stagingFolder = config.getLocationPath(stagingIndex)
        val copyTargets = targetIndexes.filter { it != stagingIndex }

        for (index in copyTargets) {
            val folder = config.getLocationPath(index)
            if (folder.isEmpty()) {
                failedIndexes.add(index)
                continue
            }
            if (folder == stagingFolder) {
                continue
            }
            if (fileName.isEmpty()) {
                failedIndexes.add(index)
                continue
            }
            val uri = mediaOutputHelper.copyMediaToFolder(renamedUri, folder, fileName, isPhoto)
            if (uri != null) savedUris.add(uri) else failedIndexes.add(index)
        }

        // 3. 位置1 的保留 / 删除（移动）逻辑
        val stagingSelected = stagingIndex in targetIndexes
        if (stagingSelected) {
            savedUris.add(renamedUri)
        } else if (savedUris.isNotEmpty()) {
            val deleted = mediaOutputHelper.deleteMedia(renamedUri)
            if (!deleted) {
                fallbackToStaging = true
                savedUris.add(renamedUri)
            }
        } else {
            // 全部目标失败：保留暂存，回退到位置1
            fallbackToStaging = true
            savedUris.add(renamedUri)
        }

        return DistributionResult(savedUris, failedIndexes, fallbackToStaging)
    }
}
