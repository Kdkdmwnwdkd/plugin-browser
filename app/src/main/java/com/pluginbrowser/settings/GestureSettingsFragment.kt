package com.pluginbrowser.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.appbar.MaterialToolbar
import com.pluginbrowser.R

/**
 * 手势设置。
 *
 * 直接读写 pb_settings（与 SettingsRepository 同一文件），
 * 因此不需要像设置主页那样手写同步逻辑。
 */
class GestureSettingsFragment : PreferenceFragmentCompat() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val view = super.onCreateView(inflater, container, savedInstanceState)
        view.findViewById<MaterialToolbar>(R.id.toolbar)?.setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        return view
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = "pb_settings"
        setPreferencesFromResource(R.xml.pref_gesture, rootKey)
    }
}
