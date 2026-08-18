package org.fossify.camera.activities

import android.os.Bundle
import org.fossify.camera.R
import org.fossify.camera.databinding.ActivityHelpBinding
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.NavigationIcon

class HelpActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityHelpBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(padBottomSystem = listOf(binding.helpNestedScrollview))
        setupMaterialScrollListener(binding.helpNestedScrollview, binding.helpAppbar)
        setupTopAppBar(binding.helpAppbar, NavigationIcon.Arrow)
        binding.helpText.text =
            resources.openRawResource(R.raw.help).bufferedReader().use { it.readText() }
        binding.helpText.setTextColor(getProperTextColor())
    }
}
