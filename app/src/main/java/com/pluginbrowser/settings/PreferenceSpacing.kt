package com.pluginbrowser.settings

import androidx.preference.Preference
import androidx.preference.PreferenceGroup

/**
 * Material 的设置行默认给左侧图标留 56dp 空位，这些页面没有图标，
 * 留着就是一条空白带，把标题挤到中间。递归收掉，让文字铺满整行。
 */
internal fun Preference.dropIconSpace() {
    isIconSpaceReserved = false
    if (this is PreferenceGroup) {
        for (i in 0 until preferenceCount) {
            getPreference(i).dropIconSpace()
        }
    }
}
