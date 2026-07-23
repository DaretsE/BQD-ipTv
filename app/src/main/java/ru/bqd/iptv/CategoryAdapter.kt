package ru.bqd.iptv

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Пункт левого меню.
 * type: SETTINGS / SEARCH / FAV / PLSEL / ALL / GROUP — ровно те же типы,
 * что в catItems() прототипа.
 */
data class CatItem(val title: String, val count: Int, val type: String, val group: String? = null)

class CategoryAdapter(
    private val ctx: Context,
    private var items: List<CatItem>,
    /** Компактный режим (рейка): остаются только иконки. */
    private val compact: Boolean = false
) : BaseAdapter() {

    companion object {
        private const val TYPE_ROW = 0
        private const val TYPE_PLSEL = 1
    }

    /** Активная категория в рейке (подсвечивается). -1 — нет. */
    var activePos: Int = -1
        set(v) { if (field != v) { field = v; notifyDataSetChanged() } }

    fun update(list: List<CatItem>) { items = list; notifyDataSetChanged() }

    override fun getCount() = items.size
    override fun getItem(p: Int) = items[p]
    override fun getItemId(p: Int) = p.toLong()

    override fun getViewTypeCount() = 2

    // в рейке селектор плейлиста рисуется обычной иконкой, поэтому спец-тип
    // используется только в развёрнутом меню
    override fun getItemViewType(position: Int): Int =
        if (!compact && items[position].type == "PLSEL") TYPE_PLSEL else TYPE_ROW

    /** Иконка пункта: служебные — фиксированные, группы — по смыслу названия. */
    private fun iconName(item: CatItem): String = when (item.type) {
        "SETTINGS" -> "settings"
        "SEARCH" -> "search"
        "FAV" -> "star"
        "PLSEL" -> "playlist_play"
        "ALL" -> "apps"
        else -> GroupIcons.iconFor(item.group ?: item.title)
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val item = items[position]
        return if (getItemViewType(position) == TYPE_PLSEL) {
            plSelView(item, convertView, parent)
        } else {
            rowView(item, position, convertView, parent)
        }
    }

    /** Строка-«таблетка» выбора плейлиста (#plSel в прототипе). */
    private fun plSelView(item: CatItem, convertView: View?, parent: ViewGroup?): View {
        val v = convertView
            ?: LayoutInflater.from(ctx).inflate(R.layout.item_playlist_sel, parent, false)
        v.findViewById<TextView>(R.id.plSelName).text = item.title
        return v
    }

    /** Обычная строка меню: иконка • название • счётчик-таблетка. */
    private fun rowView(item: CatItem, position: Int, convertView: View?, parent: ViewGroup?): View {
        val v = convertView
            ?: LayoutInflater.from(ctx).inflate(R.layout.item_category, parent, false)
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
        return v
    }
}
