package ru.bqd.iptv

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView

/**
 * Список каналов.
 *
 * У ВЫДЕЛЕННОГО канала справа показывается квадратная кнопка действия:
 *  - канала нет в избранном — звезда с салатовым свечением (добавить);
 *  - канал в избранном — корзина с красным свечением (убрать).
 *
 * Кнопка намеренно НЕ focusable: в ListView на Android TV focusable-элементы
 * внутри строк ломают навигацию по самому списку. Поэтому фокус кнопки
 * эмулируется: PlayerActivity выставляет actionFocused, а адаптер рисует
 * подсветку через state_activated.
 */
class ChannelAdapter(
    private val ctx: Context,
    private var items: List<Channel>,
    private val showNow: Boolean
) : BaseAdapter() {

    /** Позиция выделенной строки (у неё показывается кнопка). -1 — нет. */
    var selectedPos: Int = -1
        set(v) { if (field != v) { field = v; notifyDataSetChanged() } }

    /** Фокус переведён на кнопку действия выделенной строки. */
    var actionFocused: Boolean = false
        set(v) { if (field != v) { field = v; notifyDataSetChanged() } }

    fun update(list: List<Channel>) { items = list; notifyDataSetChanged() }

    override fun getCount() = items.size
    override fun getItem(p: Int) = items[p]
    override fun getItemId(p: Int) = p.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val v = convertView ?: LayoutInflater.from(ctx).inflate(R.layout.item_channel, parent, false)
        val ch = items[position]

        val num = v.findViewById<TextView>(R.id.rowNum)
        val logo = v.findViewById<ImageView>(R.id.rowLogo)
        val name = v.findViewById<TextView>(R.id.rowName)
        val now = v.findViewById<TextView>(R.id.rowNow)
        val star = v.findViewById<TextView>(R.id.rowStar)
        val action = v.findViewById<TextView>(R.id.rowAction)

        num.text = if (ch.chno.isNotEmpty()) ch.chno else (position + 1).toString()
        name.text = ch.name

        val logoUrl = if (ch.logo.isNotEmpty()) ch.logo else EpgManager.iconFor(ch)
        ImageLoader.load(logoUrl, logo)

        if (showNow) {
            val cur = EpgManager.currentFor(ch)
            if (cur != null) { now.text = cur.title; now.visibility = View.VISIBLE }
            else now.visibility = View.GONE
        } else now.visibility = View.GONE

        val fav = Store.isFavorite(ch.url)
        // звёздочку-признак прячем у выделенной строки: там уже есть кнопка
        star.visibility = if (fav && position != selectedPos) View.VISIBLE else View.GONE

        if (position == selectedPos) {
            action.visibility = View.VISIBLE
            if (fav) {
                action.setBackgroundResource(R.drawable.action_del)
                IconFont.apply(action, "delete")
                action.setTextColor(0xFFFF8A8A.toInt())
            } else {
                action.setBackgroundResource(R.drawable.action_fav)
                IconFont.apply(action, "star")
                action.setTextColor(0xFFA8E05F.toInt())
            }
            action.isActivated = actionFocused
        } else {
            action.visibility = View.GONE
            action.isActivated = false
        }
        return v
    }
}
