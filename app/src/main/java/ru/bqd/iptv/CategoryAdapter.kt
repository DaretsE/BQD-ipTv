package ru.bqd.iptv

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.TextView

/** Пункт левого меню. type: SETTINGS / SEARCH / ALL / GROUP */
data class CatItem(val title: String, val count: Int, val type: String, val group: String? = null)

class CategoryAdapter(
    private val ctx: Context,
    private var items: List<CatItem>,
    /** Компактный режим (рейка): остаются только иконки. */
    private val compact: Boolean = false
) : BaseAdapter() {

    /** Активная категория в рейке (подсвечивается). -1 — нет. */
    var activePos: Int = -1
        set(v) { if (field != v) { field = v; notifyDataSetChanged() } }

    fun update(list: List<CatItem>) { items = list; notifyDataSetChanged() }

    override fun getCount() = items.size
    override fun getItem(p: Int) = items[p]
    override fun getItemId(p: Int) = p.toLong()

    /** Иконка пункта: служебные — фиксированные, группы — по смыслу названия. */
    private fun iconName(item: CatItem): String = when (item.type) {
        "SETTINGS" -> "settings"
        "SEARCH" -> "search"
        "ALL" -> if (item.title.contains("избранн", true)) "star" else "apps"
        else -> GroupIcons.iconFor(item.group ?: item.title)
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val v = convertView ?: LayoutInflater.from(ctx).inflate(R.layout.item_category, parent, false)
        val item = items[position]
        val icon = v.findViewById<TextView>(R.id.catIcon)
        val left = v.findViewById<TextView>(R.id.catName)
        val right = v.findViewById<TextView>(R.id.catCount)

        IconFont.apply(icon, iconName(item))
        left.text = item.title

        if (compact) {
            // рейка: только иконка по центру
            left.visibility = View.GONE
            right.visibility = View.GONE
            icon.layoutParams = (icon.layoutParams as LinearLayout.LayoutParams).apply {
                width = LinearLayout.LayoutParams.MATCH_PARENT
                marginEnd = 0
            }
            val pad = (ctx.resources.displayMetrics.density * 12).toInt()
            v.setPadding(0, pad, 0, pad)
            if (position == activePos) v.setBackgroundResource(R.drawable.rail_active)
            else v.setBackgroundResource(0)
        } else {
            left.visibility = View.VISIBLE
            if (item.count > 0) {
                right.text = item.count.toString()
                right.visibility = View.VISIBLE
            } else {
                right.text = ""
                right.visibility = View.GONE
            }
        }

        // служебные пункты отличаем цветом иконки; фон строки больше не красим —
        // выделение рисует общий фокус списка
        when (item.type) {
            "SETTINGS" -> icon.setTextColor(Color.parseColor("#F0B24A"))
            "SEARCH" -> icon.setTextColor(Color.parseColor("#A8E05F"))
            else -> icon.setTextColor(Color.parseColor("#63D4E2"))
        }
        return v
    }
}
