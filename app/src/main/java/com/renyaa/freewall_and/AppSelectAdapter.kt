package com.renyaa.freewall_and

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class AppSelectInfo(
    val appName: String,
    val packageName: String,
    val icon: Drawable?,
    var isSelected: Boolean
)

class AppSelectAdapter(
    private val allApps: List<AppSelectInfo>,
    private val onSelectionChanged: (Int) -> Unit
) : RecyclerView.Adapter<AppSelectAdapter.ViewHolder>() {

    private var displayApps: List<AppSelectInfo> = allApps

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivIcon: ImageView = view.findViewById(R.id.iv_app_icon)
        val tvName: TextView = view.findViewById(R.id.tv_app_name)
        val tvPackage: TextView = view.findViewById(R.id.tv_package_name)
        val chkSelected: CheckBox = view.findViewById(R.id.chk_app_selected)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app_select, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = displayApps[position]
        holder.tvName.text = app.appName
        holder.tvPackage.text = app.packageName
        holder.chkSelected.isChecked = app.isSelected

        if (app.icon != null) {
            holder.ivIcon.setImageDrawable(app.icon)
        } else {
            holder.ivIcon.setImageResource(R.drawable.ic_nav_shield)
        }

        holder.itemView.setOnClickListener {
            app.isSelected = !app.isSelected
            holder.chkSelected.isChecked = app.isSelected
            onSelectionChanged(getSelectedCount())
        }
    }

    override fun getItemCount(): Int = displayApps.size

    fun filter(query: String) {
        val trimmed = query.trim().lowercase()
        displayApps = if (trimmed.isEmpty()) {
            allApps
        } else {
            allApps.filter {
                it.appName.lowercase().contains(trimmed) || it.packageName.lowercase().contains(trimmed)
            }
        }
        notifyDataSetChanged()
    }

    fun selectAll() {
        for (app in displayApps) {
            app.isSelected = true
        }
        notifyDataSetChanged()
        onSelectionChanged(getSelectedCount())
    }

    fun deselectAll() {
        for (app in displayApps) {
            app.isSelected = false
        }
        notifyDataSetChanged()
        onSelectionChanged(getSelectedCount())
    }

    fun getSelectedPackages(): Set<String> {
        return allApps.filter { it.isSelected }.map { it.packageName }.toSet()
    }

    fun getSelectedCount(): Int {
        return allApps.count { it.isSelected }
    }
}
