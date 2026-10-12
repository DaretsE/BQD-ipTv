package ru.bqd.iptv

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.widget.EditText

class SearchEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : EditText(context, attrs, defStyleAttr) {
    var onBackBeforeIme: (() -> Unit)? = null

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && onBackBeforeIme != null) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) onBackBeforeIme?.invoke()
            return true
        }
        return super.onKeyPreIme(keyCode, event)
    }
}
