package com.hosoyduc.app

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject

data class RecordItem(val key: String, val json: JSONObject)

class RecordsAdapter(
    private var items: List<RecordItem>,
    private val onDelete: (RecordItem) -> Unit
) : RecyclerView.Adapter<RecordsAdapter.VH>() {

    class VH(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        val tvOriginal: TextView = itemView.findViewById(R.id.tvOriginal)
        val tvMeta: TextView = itemView.findViewById(R.id.tvMeta)
        val tvTimes: TextView = itemView.findViewById(R.id.tvTimes)
        val btnDelete: Button = itemView.findViewById(R.id.btnDelete)
    }

    fun updateItems(newItems: List<RecordItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_record, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val j = item.json
        holder.tvOriginal.text = j.optString("original", "")
        holder.tvMeta.text = "${j.optString("deviceModel", "")} · nguồn: ${j.optString("source", "")}"

        val times = j.optJSONObject("times")
        if (times != null) {
            val sb = StringBuilder()
            val keys = times.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                sb.append("$k: ${times.getString(k)}  ")
            }
            holder.tvTimes.text = sb.toString()
        } else {
            holder.tvTimes.text = ""
        }

        holder.btnDelete.setOnClickListener { onDelete(item) }
    }
}
