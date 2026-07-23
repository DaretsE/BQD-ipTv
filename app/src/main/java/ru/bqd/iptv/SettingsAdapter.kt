package ru.bqd.iptv

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView

/** Раздел настроек: иконка, название, текущее значение (может быть пустым). */
data class SetItem(val icon: String, val label: String, val kind: String)

/** Список разделов в левой колонке панели настроек. */
class SettingsAdapter(
    private val ctx: Context,
    private var items: List<SetItem>,
    private val valueFor: (SetItem) -> String
) : BaseAdapter() {

    fun refresh() = notifyDataSetChanged()

    override fun getCount() = items.size
    override fun getItem(p: Int) = items[p]
    override fun getItemId(p: Int) = p.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val v = convertView ?: LayoutInflater.from(ctx).inflate(R.layout.item_setting, parent, false)
        val item = items[position]

        IconFont.apply(v.findViewById(R.id.setIcon), item.icon)
        v.findViewById<TextView>(R.id.setLabel).text = item.label

        val value = v.findViewById<TextView>(R.id.setValue)
        val text = valueFor(item)
        if (text.isEmpty()) value.visibility = View.GONE
        else { value.text = text; value.visibility = View.VISIBLE }
        return v
    }
}
