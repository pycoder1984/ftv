package com.vidking.firetv.settings

import android.os.Bundle
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction
import com.vidking.firetv.R
import com.vidking.firetv.data.AppPrefs

/**
 * Reusable single-field text entry guided step. The field arg picks which
 * AppPrefs slot is read/written; the Fire TV remote keyboard handles input.
 */
class TextEntryStepFragment : GuidedStepSupportFragment() {

    private val field: String get() = requireArguments().getString(ARG_FIELD).orEmpty()
    private val titleArg: String get() = requireArguments().getString(ARG_TITLE).orEmpty()
    private val descriptionArg: String get() = requireArguments().getString(ARG_DESCRIPTION).orEmpty()
    private val initialValue: String get() = requireArguments().getString(ARG_INITIAL).orEmpty()

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance =
        GuidanceStylist.Guidance(titleArg, descriptionArg, getString(R.string.settings_title), null)

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        actions.add(
            GuidedAction.Builder(ctx)
                .id(ACTION_VALUE)
                .title(titleArg)
                .editTitle(initialValue)
                .editable(true)
                .descriptionEditable(false)
                .build()
        )
        actions.add(
            GuidedAction.Builder(ctx)
                .id(ACTION_SAVE)
                .title(getString(R.string.settings_save))
                .build()
        )
        actions.add(
            GuidedAction.Builder(ctx)
                .id(ACTION_CANCEL)
                .title(getString(R.string.settings_cancel))
                .build()
        )
    }

    override fun onGuidedActionEditedAndProceed(action: GuidedAction): Long {
        if (action.id == ACTION_VALUE) {
            return ACTION_SAVE
        }
        return super.onGuidedActionEditedAndProceed(action)
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ACTION_SAVE -> {
                val value = findActionById(ACTION_VALUE)?.editTitle?.toString().orEmpty()
                val ctx = requireContext()
                when (field) {
                    FIELD_BASE_URL -> AppPrefs.setFebboxBaseUrl(ctx, value)
                    FIELD_TOKEN -> AppPrefs.setFebboxToken(ctx, value)
                    FIELD_LIVETV_URL -> AppPrefs.setLivetvPlaylistUrl(ctx, value)
                }
                parentFragmentManager.popBackStack()
            }
            ACTION_CANCEL -> parentFragmentManager.popBackStack()
        }
    }

    companion object {
        private const val ARG_FIELD = "field"
        private const val ARG_TITLE = "title"
        private const val ARG_DESCRIPTION = "description"
        private const val ARG_INITIAL = "initial"

        private const val ACTION_VALUE = 10L
        private const val ACTION_SAVE = 11L
        private const val ACTION_CANCEL = 12L

        const val FIELD_BASE_URL = "base_url"
        const val FIELD_TOKEN = "token"
        const val FIELD_LIVETV_URL = "livetv_url"

        fun create(field: String, title: String, description: String, initialValue: String) =
            TextEntryStepFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_FIELD, field)
                    putString(ARG_TITLE, title)
                    putString(ARG_DESCRIPTION, description)
                    putString(ARG_INITIAL, initialValue)
                }
            }
    }
}
