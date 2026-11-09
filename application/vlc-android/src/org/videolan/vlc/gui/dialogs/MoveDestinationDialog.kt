/*
 * *************************************************************************
 *  MoveDestinationDialog.kt
 * *************************************************************************
 * Copyright © 2026 VLC authors and VideoLAN
 * Author: Botoko
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 * ***************************************************************************
 *
 *
 */

package org.videolan.vlc.gui.dialogs

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.medialibrary.media.MediaLibraryItem
import org.videolan.resources.AndroidDevices
import org.videolan.resources.util.parcelableList
import org.videolan.vlc.R
import org.videolan.vlc.util.FileUtils
import java.io.File

const val MOVE_DESTINATION_DIALOG_RESULT = "MOVE_DESTINATION_DIALOG_RESULT"
const val MOVE_DESTINATION_DIALOG_MEDIALIST = "MOVE_DESTINATION_DIALOG_MEDIALIST"
const val MOVE_DESTINATION_DIALOG_PATH = "MOVE_DESTINATION_DIALOG_PATH"

/**
 * Destination selector for moving files, modeled on Simple Gallery's
 * "Select destination" picker: a breadcrumb path bar on top, a list of
 * the current folder's subfolders to tap into, a "New folder" button,
 * and OK to confirm the folder being shown.
 *
 * Only folders on shared storage are shown; navigation never goes above
 * internal storage. The actual move is done by the caller when it
 * receives [MOVE_DESTINATION_DIALOG_RESULT].
 */
class MoveDestinationDialog : VLCBottomSheetDialogFragment() {

    private lateinit var medias: List<MediaLibraryItem>
    private lateinit var currentPath: String
    private lateinit var folderList: RecyclerView
    private lateinit var breadcrumbs: LinearLayout
    private lateinit var emptyView: TextView

    companion object {
        fun newInstance(medias: ArrayList<MediaLibraryItem>): MoveDestinationDialog {
            return MoveDestinationDialog().apply {
                arguments = bundleOf(MOVE_DESTINATION_DIALOG_MEDIALIST to medias)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        medias = arguments?.parcelableList<MediaLibraryItem>(MOVE_DESTINATION_DIALOG_MEDIALIST) ?: listOf()
        val firstPath = (medias.firstOrNull() as? MediaWrapper)?.uri?.path
        currentPath = FileUtils.getParent(firstPath) ?: AndroidDevices.EXTERNAL_PUBLIC_DIRECTORY
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.dialog_move_destination, container)
        val subtitle = view.findViewById<TextView>(R.id.move_subtitle)
        subtitle.text = if (medias.size == 1 && medias[0] is MediaWrapper) {
            (medias[0] as MediaWrapper).fileName
        } else {
            getString(R.string.selection_count, medias.size)
        }
        folderList = view.findViewById(R.id.move_folder_list)
        folderList.layoutManager = LinearLayoutManager(requireContext())
        folderList.adapter = FolderAdapter { folder -> navigateTo(folder.path) }
        breadcrumbs = view.findViewById(R.id.move_breadcrumbs)
        emptyView = view.findViewById(R.id.move_empty)
        view.findViewById<Button>(R.id.move_ok_button).setOnClickListener { confirm() }
        view.findViewById<Button>(R.id.move_cancel_button).setOnClickListener { dismiss() }
        view.findViewById<Button>(R.id.move_new_folder_button).setOnClickListener { showNewFolderDialog() }
        loadFolders()
        return view
    }

    override fun onStart() {
        super.onStart()
        // Back goes up one folder, like the Simple Gallery picker; at the
        // storage root it dismisses as usual.
        dialog?.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && navigateUp()) true else false
        }
    }

    private fun navigateTo(path: String) {
        currentPath = path
        loadFolders()
    }

    private fun navigateUp(): Boolean {
        if (currentPath == AndroidDevices.EXTERNAL_PUBLIC_DIRECTORY) return false
        FileUtils.getParent(currentPath)?.let {
            if (it.startsWith(AndroidDevices.EXTERNAL_PUBLIC_DIRECTORY)) {
                currentPath = it
                loadFolders()
                return true
            }
        }
        return false
    }

    private fun loadFolders() {
        lifecycleScope.launch(Dispatchers.IO) {
            val subfolders = File(currentPath).listFiles()
                    ?.filter { it.isDirectory && !it.name.startsWith(".") }
                    ?.sortedBy { it.name.lowercase() }
                    ?: listOf()
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                (folderList.adapter as FolderAdapter).update(subfolders)
                emptyView.visibility = if (subfolders.isEmpty()) View.VISIBLE else View.GONE
                folderList.visibility = if (subfolders.isEmpty()) View.GONE else View.VISIBLE
                updateBreadcrumbs()
            }
        }
    }

    private fun updateBreadcrumbs() {
        breadcrumbs.removeAllViews()
        val root = AndroidDevices.EXTERNAL_PUBLIC_DIRECTORY
        val segments = ArrayList<Pair<String, String>>()
        segments.add(getString(R.string.internal_memory) to root)
        if (currentPath != root) {
            val path = currentPath.removePrefix(root).trim('/')
            var built = root
            for (part in path.split('/')) {
                if (part.isEmpty()) continue
                built += "/$part"
                segments.add(part to built)
            }
        }
        segments.forEachIndexed { index, (label, path) ->
            if (index > 0) {
                breadcrumbs.addView(TextView(requireContext()).apply { text = " › " })
            }
            breadcrumbs.addView(TextView(requireContext()).apply {
                text = label
                setTextColor(ContextCompat.getColor(requireContext(), R.color.accent_blue))
                setOnClickListener { navigateTo(path) }
            })
        }
        view?.findViewById<View>(R.id.move_breadcrumbs_scroll)?.let {
            (it as? android.widget.HorizontalScrollView)?.post { it.fullScroll(View.FOCUS_RIGHT) }
        }
    }

    private fun confirm() {
        val sourceParents = medias.filterIsInstance<MediaWrapper>()
                .mapNotNull { FileUtils.getParent(it.uri.path) }
                .toSet()
        if (sourceParents.size == 1 && sourceParents.first() == currentPath) {
            Toast.makeText(requireContext(), R.string.move_same_folder, Toast.LENGTH_SHORT).show()
            return
        }
        val clash = medias.filterIsInstance<MediaWrapper>().any { File(currentPath, it.fileName).exists() }
        if (clash) {
            Toast.makeText(requireContext(), R.string.move_file_exists, Toast.LENGTH_LONG).show()
            return
        }
        setFragmentResult(MOVE_DESTINATION_DIALOG_RESULT, bundleOf(
                MOVE_DESTINATION_DIALOG_MEDIALIST to ArrayList(medias),
                MOVE_DESTINATION_DIALOG_PATH to currentPath))
        dismiss()
    }

    private fun showNewFolderDialog() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.new_folder)
            setSingleLine()
        }
        val container = android.widget.FrameLayout(requireContext()).apply {
            val margin = (16 * resources.displayMetrics.density).toInt()
            setPadding(margin, margin / 2, margin, 0)
            addView(input)
        }
        AlertDialog.Builder(requireContext())
                .setTitle(R.string.new_folder)
                .setView(container)
                .setPositiveButton(R.string.create) { _, _ ->
                    val name = input.text.toString().trim()
                    if (name.isEmpty()) return@setPositiveButton
                    val newDir = File(currentPath, name)
                    if (newDir.exists() || !newDir.mkdir()) {
                        Toast.makeText(requireContext(), R.string.move_file_exists, Toast.LENGTH_SHORT).show()
                    } else {
                        navigateTo(newDir.path)
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
    }

    override fun getDefaultState(): Int {
        return STATE_EXPANDED
    }

    override fun initialFocusedView(): View = folderList

    override fun needToManageOrientation(): Boolean {
        return true
    }

    private class FolderAdapter(private val onClick: (File) -> Unit) : RecyclerView.Adapter<FolderAdapter.ViewHolder>() {

        private var folders: List<File> = listOf()

        fun update(newFolders: List<File>) {
            folders = newFolders
            notifyDataSetChanged()
        }

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.move_folder_name)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_move_destination_folder, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val folder = folders[position]
            holder.name.text = folder.name
            holder.itemView.setOnClickListener { onClick(folder) }
        }

        override fun getItemCount(): Int = folders.size
    }
}
