package com.pluginbrowser.translate

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.view.ContextThemeWrapper
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.pluginbrowser.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 翻译服务设置：选供应商 → 动态生成该供应商需要的字段 → 保存/测试。
 * 换供应商只改这里，不用动代码。
 */
class TranslateSettingsFragment : Fragment(R.layout.fragment_translate_settings) {

    private val settings by lazy { TranslateSettings(requireContext()) }

    private var provider: TranslateProvider = TranslateProvider.BAIDU
    private var target: TranslateTarget = TranslateTarget.ZH

    private val inputs = linkedMapOf<TranslateProvider.Field, TextInputEditText>()
    private lateinit var fieldsContainer: LinearLayout
    private lateinit var providerHelp: View

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        provider = settings.provider
        target = settings.target

        fieldsContainer = view.findViewById(R.id.fields_container)
        providerHelp = view.findViewById(R.id.provider_help)

        view.findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        val providerInput = view.findViewById<MaterialAutoCompleteTextView>(R.id.provider_input)
        providerInput.setSimpleItems(TranslateProvider.entries.map { it.label }.toTypedArray())
        providerInput.setText(provider.label, false)
        providerInput.setOnItemClickListener { _, _, position, _ ->
            provider = TranslateProvider.entries[position]
            renderFields()
        }

        val targetInput = view.findViewById<MaterialAutoCompleteTextView>(R.id.target_input)
        targetInput.setSimpleItems(TranslateTarget.entries.map { it.label }.toTypedArray())
        targetInput.setText(target.label, false)
        targetInput.setOnItemClickListener { _, _, position, _ ->
            target = TranslateTarget.entries[position]
        }

        view.findViewById<MaterialButton>(R.id.btn_save).setOnClickListener {
            persist()
            toast(getString(R.string.translate_saved))
        }
        view.findViewById<MaterialButton>(R.id.btn_test).setOnClickListener {
            persist()
            testConnection(it as MaterialButton)
        }

        renderFields()
    }

    /** 按当前供应商重建字段区 */
    private fun renderFields() {
        inputs.clear()
        fieldsContainer.removeAllViews()

        val themed = ContextThemeWrapper(
            requireContext(),
            R.style.Widget_PluginBrowser_TextField,
        )

        provider.fields.forEach { field ->
            val layout = TextInputLayout(themed).apply {
                hint = field.label
                helperText = field.hint
                setPadding(0, 0, 0, dp(6))
            }
            val edit = TextInputEditText(layout.context).apply {
                setText(settings.value(provider, field))
                if (field.secret) {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                setSingleLine(true)
            }
            layout.addView(edit)
            fieldsContainer.addView(layout)
            inputs[field] = edit
        }

        (providerHelp as android.widget.TextView).text = provider.help
    }

    private fun persist() {
        settings.provider = provider
        settings.target = target
        inputs.forEach { (field, edit) ->
            settings.putValue(provider, field, edit.text?.toString().orEmpty())
        }
    }

    private fun testConnection(button: MaterialButton) {
        button.isEnabled = false
        val label = getString(R.string.translate_testing)
        toast(label)

        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    Translators.translate(provider, settings, listOf("Hello, world"), target)
                }
            }
            button.isEnabled = true
            result.onSuccess { texts ->
                toast(getString(R.string.translate_test_ok, texts.firstOrNull().orEmpty()))
            }.onFailure { error ->
                toast(getString(R.string.translate_test_fail, error.message ?: "unknown"))
            }
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }
}
