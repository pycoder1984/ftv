package com.vidking.firetv.settings

import android.os.Bundle
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction
import com.vidking.firetv.R
import com.vidking.firetv.data.AppPrefs

class SettingsFragment : GuidedStepSupportFragment() {

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance =
        GuidanceStylist.Guidance(
            getString(R.string.settings_title),
            getString(R.string.settings_description),
            getString(R.string.app_name),
            null
        )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        val baseUrl = AppPrefs.febboxBaseUrl(ctx)
        val token = AppPrefs.febboxToken(ctx)
        val embedFallback = AppPrefs.embedFallbackEnabled(ctx)

        actions.add(
            GuidedAction.Builder(ctx)
                .id(ACTION_BASE_URL)
                .title(getString(R.string.settings_febbox_base_url))
                .description(if (baseUrl.isEmpty()) getString(R.string.settings_not_set) else baseUrl)
                .build()
        )
        actions.add(
            GuidedAction.Builder(ctx)
                .id(ACTION_TOKEN)
                .title(getString(R.string.settings_febbox_token))
                .description(maskToken(token))
                .build()
        )
        actions.add(
            GuidedAction.Builder(ctx)
                .id(ACTION_EMBED_FALLBACK)
                .title(getString(R.string.settings_embed_fallback))
                .description(
                    if (embedFallback) getString(R.string.settings_embed_fallback_on)
                    else getString(R.string.settings_embed_fallback_off)
                )
                .build()
        )
        actions.add(
            GuidedAction.Builder(ctx)
                .id(ACTION_CLEAR)
                .title(getString(R.string.settings_clear_febbox))
                .description(getString(R.string.settings_clear_febbox_desc))
                .build()
        )
        actions.add(
            GuidedAction.Builder(ctx)
                .id(ACTION_DONE)
                .title(getString(R.string.settings_done))
                .build()
        )
    }

    override fun onResume() {
        super.onResume()
        // Refresh descriptions in case a child step changed the underlying value.
        val ctx = requireContext()
        val baseUrl = AppPrefs.febboxBaseUrl(ctx)
        val token = AppPrefs.febboxToken(ctx)
        val embedFallback = AppPrefs.embedFallbackEnabled(ctx)
        findActionById(ACTION_BASE_URL)?.let {
            it.description = if (baseUrl.isEmpty()) getString(R.string.settings_not_set) else baseUrl
            notifyActionChanged(findActionPositionById(ACTION_BASE_URL))
        }
        findActionById(ACTION_TOKEN)?.let {
            it.description = maskToken(token)
            notifyActionChanged(findActionPositionById(ACTION_TOKEN))
        }
        findActionById(ACTION_EMBED_FALLBACK)?.let {
            it.description =
                if (embedFallback) getString(R.string.settings_embed_fallback_on)
                else getString(R.string.settings_embed_fallback_off)
            notifyActionChanged(findActionPositionById(ACTION_EMBED_FALLBACK))
        }
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ACTION_BASE_URL -> add(
                parentFragmentManager,
                TextEntryStepFragment.create(
                    field = TextEntryStepFragment.FIELD_BASE_URL,
                    title = getString(R.string.settings_febbox_base_url),
                    description = getString(R.string.settings_febbox_base_url_hint),
                    initialValue = AppPrefs.febboxBaseUrl(requireContext())
                )
            )
            ACTION_TOKEN -> add(
                parentFragmentManager,
                TextEntryStepFragment.create(
                    field = TextEntryStepFragment.FIELD_TOKEN,
                    title = getString(R.string.settings_febbox_token),
                    description = getString(R.string.settings_febbox_token_hint),
                    initialValue = AppPrefs.febboxToken(requireContext())
                )
            )
            ACTION_EMBED_FALLBACK -> {
                val ctx = requireContext()
                AppPrefs.setEmbedFallbackEnabled(ctx, !AppPrefs.embedFallbackEnabled(ctx))
                onResume()
            }
            ACTION_CLEAR -> {
                val ctx = requireContext()
                AppPrefs.setFebboxBaseUrl(ctx, "")
                AppPrefs.setFebboxToken(ctx, "")
                onResume()
            }
            ACTION_DONE -> requireActivity().finish()
        }
    }

    private fun maskToken(token: String): String {
        if (token.isEmpty()) return getString(R.string.settings_not_set)
        if (token.length <= 6) return "•".repeat(token.length)
        return token.substring(0, 3) + "•".repeat(token.length - 6) + token.substring(token.length - 3)
    }

    companion object {
        private const val ACTION_BASE_URL = 1L
        private const val ACTION_TOKEN = 2L
        private const val ACTION_EMBED_FALLBACK = 3L
        private const val ACTION_CLEAR = 4L
        private const val ACTION_DONE = 5L
    }
}
