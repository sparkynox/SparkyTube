package dev.sparkynox.sparkytube.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.DialogInterface
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties

// Drop-in for androidx.appcompat's AlertDialog.Builder, drawn with Compose.
// Files that used the old one only swap their import, the builder calls stay the same:
//   import dev.sparkynox.sparkytube.ui.ComposeAlertDialog as AlertDialog
// Covers what the app uses: title, message, items, single choice, 3 buttons, custom view, cancel/dismiss.
class ComposeAlertDialog private constructor(
    private val activity: Activity,
    private val b: Builder,
) : DialogInterface {

    private var host: ComposeView? = null

    fun show(): ComposeAlertDialog {
        if (host != null || activity.isFinishing || activity.isDestroyed) return this
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return this
        host = ComposeView(activity).apply {
            setContent { SparkyTheme(fill = false) { DialogBody() } }
        }
        root.addView(host, ViewGroup.LayoutParams(0, 0))
        return this
    }

    override fun dismiss() {
        val h = host ?: return
        host = null
        (h.parent as? ViewGroup)?.removeView(h)
        b.onDismiss?.onDismiss(this)
    }

    override fun cancel() {
        if (host == null) return
        b.onCancel?.onCancel(this)
        dismiss()
    }

    // not named Content: inside ComposeView.apply { } that name resolves to the ComposeView's own Content method,
    // which runs this same lambda again and again until the stack overflows
    @Composable
    private fun DialogBody() {
        var checked by remember { mutableIntStateOf(b.checked) }
        val cancelable = b.cancelable

        AlertDialog(
            onDismissRequest = { if (cancelable) cancel() },
            properties = DialogProperties(dismissOnBackPress = cancelable, dismissOnClickOutside = cancelable),
            title = if (b.title != null) ({ Text(b.title.toString()) }) else null,
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    b.message?.let { Text(it.toString(), style = MaterialTheme.typography.bodyMedium) }
                    b.items?.forEachIndexed { i, label ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                if (b.singleChoice) {
                                    checked = i
                                    b.itemListener?.onClick(this@ComposeAlertDialog, i)
                                } else {
                                    dismiss()
                                    b.itemListener?.onClick(this@ComposeAlertDialog, i)
                                }
                            }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (b.singleChoice) RadioButton(selected = checked == i, onClick = null)
                            Text(label.toString(), Modifier.padding(start = if (b.singleChoice) 12.dp else 0.dp))
                        }
                    }
                    b.view?.let { v ->
                        AndroidView(
                            factory = {
                                (v.parent as? ViewGroup)?.removeView(v)
                                v
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
            confirmButton = {
                b.positiveText?.let { t ->
                    TextButton(onClick = { dismiss(); b.positive?.onClick(this, DialogInterface.BUTTON_POSITIVE) }) { Text(t.toString()) }
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    b.neutralText?.let { t ->
                        TextButton(onClick = { dismiss(); b.neutral?.onClick(this@ComposeAlertDialog, DialogInterface.BUTTON_NEUTRAL) }) { Text(t.toString()) }
                    }
                    b.negativeText?.let { t ->
                        TextButton(onClick = { dismiss(); b.negative?.onClick(this@ComposeAlertDialog, DialogInterface.BUTTON_NEGATIVE) }) { Text(t.toString()) }
                    }
                }
            },
        )
    }

    class Builder(private val context: Context) {
        internal var title: CharSequence? = null
        internal var message: CharSequence? = null
        internal var items: Array<out CharSequence>? = null
        internal var itemListener: DialogInterface.OnClickListener? = null
        internal var singleChoice = false
        internal var checked = -1
        internal var view: View? = null
        internal var cancelable = true
        internal var positiveText: CharSequence? = null
        internal var positive: DialogInterface.OnClickListener? = null
        internal var negativeText: CharSequence? = null
        internal var negative: DialogInterface.OnClickListener? = null
        internal var neutralText: CharSequence? = null
        internal var neutral: DialogInterface.OnClickListener? = null
        internal var onCancel: DialogInterface.OnCancelListener? = null
        internal var onDismiss: DialogInterface.OnDismissListener? = null

        fun setTitle(t: CharSequence?) = apply { title = t }
        fun setTitle(@StringRes id: Int) = apply { title = context.getString(id) }
        fun setMessage(m: CharSequence?) = apply { message = m }
        fun setMessage(@StringRes id: Int) = apply { message = context.getString(id) }
        fun setView(v: View?) = apply { view = v }
        fun setCancelable(c: Boolean) = apply { cancelable = c }

        fun setItems(list: Array<out CharSequence>, l: DialogInterface.OnClickListener?) = apply {
            items = list; itemListener = l; singleChoice = false
        }

        fun setSingleChoiceItems(list: Array<out CharSequence>, checkedItem: Int, l: DialogInterface.OnClickListener?) = apply {
            items = list; itemListener = l; singleChoice = true; checked = checkedItem
        }

        fun setPositiveButton(t: CharSequence?, l: DialogInterface.OnClickListener?) = apply { positiveText = t; positive = l }
        fun setPositiveButton(@StringRes id: Int, l: DialogInterface.OnClickListener?) = apply { positiveText = context.getString(id); positive = l }
        fun setNegativeButton(t: CharSequence?, l: DialogInterface.OnClickListener?) = apply { negativeText = t; negative = l }
        fun setNegativeButton(@StringRes id: Int, l: DialogInterface.OnClickListener?) = apply { negativeText = context.getString(id); negative = l }
        fun setNeutralButton(t: CharSequence?, l: DialogInterface.OnClickListener?) = apply { neutralText = t; neutral = l }
        fun setNeutralButton(@StringRes id: Int, l: DialogInterface.OnClickListener?) = apply { neutralText = context.getString(id); neutral = l }

        fun setOnCancelListener(l: DialogInterface.OnCancelListener?) = apply { onCancel = l }
        fun setOnDismissListener(l: DialogInterface.OnDismissListener?) = apply { onDismiss = l }

        fun create(): ComposeAlertDialog = ComposeAlertDialog(activityOf(context), this)
        fun show(): ComposeAlertDialog = create().show()

        private fun activityOf(c: Context): Activity {
            var cur: Context? = c
            while (cur is ContextWrapper) {
                if (cur is Activity) return cur
                cur = cur.baseContext
            }
            throw IllegalStateException("ComposeAlertDialog needs an Activity context")
        }
    }
}
