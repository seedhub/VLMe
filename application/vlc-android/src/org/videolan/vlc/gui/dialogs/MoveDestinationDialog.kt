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

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.medialibrary.interfaces.Medialibrary
import org.videolan.medialibrary.interfaces.media.Folder
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.medialibrary.media.MediaLibraryItem
import org.videolan.resources.AndroidDevices
import org.videolan.resources.util.parcelableList
import org.videolan.tools.Settings
import org.videolan.tools.removeFileScheme
import org.videolan.vlc.R
import org.videolan.vlc.util.FileUtils
import java.io.File

const val MOVE_DESTINATION_DIALOG_RESULT = "MOVE_DESTINATION_DIALOG_RESULT"
const val MOVE_DESTINATION_DIALOG_MEDIALIST = "MOVE_DESTINATION_DIALOG_MEDIALIST"
const val MOVE_DESTINATION_DIALOG_PATH = "MOVE_DESTINATION_DIALOG_PATH"

/**
 * Destination selector for moving files, modeled on Simple Gallery's
 * "Select destination" picker: a flat list of every folder in the
 * library holding media of the type being moved, each row showing the
 * folder name, its path, and whether it lives on internal storage or
 * the SD card. Tap a folder to select it, OK confirms it. A "New
 * folder" button creates a subfolder inside the selected folder (or
 * the source file's parent when nothing is selected).
 *
 * The actual move is done by the caller when it receives
 * [MOVE_DESTINATION_DIALOG_RESULT].
 */
class MoveDestinationDialog : VLCBottomSheetDialogFragment() {

    private data class DestFolder(val path: String, val name: String, val storage: Int)

    private lateinit var medias: List<MediaLibraryItem>
    private var folders: List<DestFolder> = listOf()
    private var selectedPath: String? = null
    private var sourceParent: String? = null

    /**
     * Called with the chosen destination path when the user confirms.
     * The caller sets this before showing the dialog, so the move does
     * not depend on the fragment-result round trip. When null (e.g. the
     * dialog was restored after a configuration change), confirm()
     * falls back to [MOVE_DESTINATION_DIALOG_RESULT].
     */
    var onDestinationSelected: ((String) -> Unit)? = null
    private lateinit var folderList: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var okButton: Button

    companion object {
        fun newInstance(medias: ArrayList<MediaLibraryItem>): MoveDestinationDialog {
            return MoveDestinationDialog().apply {
                arguments = bundleOf(MOVE_DESTINATION_DIALOG_MEDIALIST to medias)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        medias = arguments?.parcelableList<MediaLibraryItem>(MOVE_DESTINATION_DIALOG_MEDIALIST) ?: listOf()
        sourceParent = (medias.firstOrNull() as? MediaWrapper)?.uri?.path?.let { FileUtils.getParent(it) }
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
        folderList.adapter = FolderAdapter { folder -> selectFolder(folder.path) }
        emptyView = view.findViewById(R.id.move_empty)
        okButton = view.findViewById(R.id.move_ok_button)
        okButton.setOnClickListener { confirm() }
        okButton.isEnabled = false
        view.findViewById<Button>(R.id.move_cancel_button).setOnClickListener { dismiss() }
        view.findViewById<Button>(R.id.move_new_folder_button).setOnClickListener { showNewFolderDialog() }
        loadFolders()
        return view
    }

    private fun folderTypes(): List<Int> {
        val types = medias.filterIsInstance<MediaWrapper>().map { it.type }.toSet()
        return when {
            types.size == 1 && types.first() == MediaWrapper.TYPE_VIDEO -> listOf(Folder.TYPE_FOLDER_VIDEO)
            types.size == 1 && types.first() == MediaWrapper.TYPE_AUDIO -> listOf(Folder.TYPE_FOLDER_AUDIO)
            else -> listOf(Folder.TYPE_FOLDER_VIDEO, Folder.TYPE_FOLDER_AUDIO)
        }
    }

    // Library folders only live on internal storage or removable storage
    // (SD card), so anything outside the internal root is the SD card.
    private fun storageOf(path: String): Int =
            if (path.startsWith(AndroidDevices.EXTERNAL_PUBLIC_DIRECTORY)) R.string.internal_memory else R.string.sd_card

    private fun loadFolders() {
        lifecycleScope.launch(Dispatchers.IO) {
            val ml = Medialibrary.getInstance()
            val found = LinkedHashMap<String, DestFolder>()
            for (type in folderTypes()) {
                val count = ml.getFoldersCount(type)
                if (count <= 0) continue
                val arr = ml.getFolders(type, Medialibrary.SORT_ALPHA, false, Settings.includeMissing, false, count, 0)
                for (folder in arr) {
                    val path = folder.mMrl.removeFileScheme()
                    if (path.isEmpty() || found.containsKey(path)) continue
                    val name = folder.title.ifEmpty { File(path).name }
                    found[path] = DestFolder(path, name, storageOf(path))
                }
            }
            val list = found.values.sortedWith(compareBy({ it.name.lowercase() }, { it.path.lowercase() }))
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                folders = list
                (folderList.adapter as FolderAdapter).update(list)
                emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                folderList.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
                // Pre-select the folder the file already lives in.
                sourceParent?.let { if (found.containsKey(it)) selectFolder(it) }
            }
        }
    }

    private fun selectFolder(path: String) {
        selectedPath = path
        (folderList.adapter as FolderAdapter).select(path)
        okButton.isEnabled = true
    }

    private fun confirm() {
        val dest = selectedPath ?: return
        val sourceParents = medias.filterIsInstance<MediaWrapper>()
                .mapNotNull { FileUtils.getParent(it.uri.path) }
                .toSet()
        if (sourceParents.size == 1 && sourceParents.first() == dest) {
            Toast.makeText(requireContext(), R.string.move_same_folder, Toast.LENGTH_SHORT).show()
            return
        }
        val clash = medias.filterIsInstance<MediaWrapper>().any { File(dest, it.fileName).exists() }
        if (clash) {
            Toast.makeText(requireContext(), R.string.move_file_exists, Toast.LENGTH_LONG).show()
            return
        }
        val onSelected = onDestinationSelected
        if (onSelected != null) onSelected(dest)
        else setFragmentResult(MOVE_DESTINATION_DIALOG_RESULT, bundleOf(
                MOVE_DESTINATION_DIALOG_MEDIALIST to ArrayList(medias),
                MOVE_DESTINATION_DIALOG_PATH to dest))
        dismiss()
    }

    private fun showNewFolderDialog() {
        val base = selectedPath ?: sourceParent ?: AndroidDevices.EXTERNAL_PUBLIC_DIRECTORY
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
                    val newDir = File(base, name)
                    if (newDir.exists() || !newDir.mkdir()) {
                        Toast.makeText(requireContext(), R.string.move_file_exists, Toast.LENGTH_SHORT).show()
                    } else {
                        val created = DestFolder(newDir.path, name, storageOf(newDir.path))
                        folders = (folders + created).sortedWith(compareBy({ it.name.lowercase() }, { it.path.lowercase() }))
                        (folderList.adapter as FolderAdapter).update(folders)
                        emptyView.visibility = View.GONE
                        folderList.visibility = View.VISIBLE
                        selectFolder(newDir.path)
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

    private class FolderAdapter(private val onClick: (DestFolder) -> Unit) : RecyclerView.Adapter<FolderAdapter.ViewHolder>() {

        private var folders: List<DestFolder> = listOf()
        private var selectedPath: String? = null

        fun update(newFolders: List<DestFolder>) {
            folders = newFolders
            notifyDataSetChanged()
        }

        fun select(path: String?) {
            selectedPath = path
            notifyDataSetChanged()
        }

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.move_folder_icon)
            val name: TextView = view.findViewById(R.id.move_folder_name)
            val path: TextView = view.findViewById(R.id.move_folder_path)
            val storage: TextView = view.findViewById(R.id.move_folder_storage)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_move_destination_folder, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val folder = folders[position]
            holder.name.text = folder.name
            holder.path.text = folder.path
            holder.storage.setText(folder.storage)
            holder.itemView.setOnClickListener { onClick(folder) }
            if (folder.path == selectedPath) {
                val accent = ContextCompat.getColor(holder.itemView.context, R.color.accent_blue)
                holder.itemView.setBackgroundColor(ColorUtils.setAlphaComponent(accent, 30))
            } else {
                holder.itemView.setBackgroundColor(Color.TRANSPARENT)
            }
        }

        override fun getItemCount(): Int = folders.size
    }
}
