package dev.sparkynox.sparkytube.profile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import dev.sparkynox.sparkytube.MainActivity
import dev.sparkynox.sparkytube.R
import dev.sparkynox.sparkytube.databinding.ActivityProfilePickerBinding
import dev.sparkynox.sparkytube.databinding.ProfileCardBinding
import java.io.File
import java.io.FileOutputStream

/**
 * "Who's watching" screen, Netflix/Crunchyroll-style. Shown on cold app
 * launch only when more than one profile exists (see MainActivity's
 * launcher check) -- a single-profile install skips straight to
 * MainActivity so this screen never gets in the way of the common case.
 * Also reachable from Settings at any time to switch/manage profiles.
 */
class ProfilePickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfilePickerBinding

    // Set right before launching the system picker in onPfpCardClicked,
    // so the activity result callback knows which profile the picked
    // image is for.
    private var pfpTargetProfileId: String? = null

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        val profileId = pfpTargetProfileId
        if (uri != null && profileId != null) {
            savePfpFromUri(profileId, uri)
        }
        pfpTargetProfileId = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfilePickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.profileGridScroll.alpha = 0f
        binding.profileGridScroll.translationY = 24f
        binding.profileGridScroll.animate().alpha(1f).translationY(0f).setDuration(220).start()

        renderProfiles()
    }

    private fun renderProfiles() {
        binding.profileGrid.removeAllViews()
        val profiles = ProfileManager.getProfiles(this)
        val activeId = ProfileManager.getActiveProfileId(this)

        profiles.forEach { profile ->
            val cardBinding = ProfileCardBinding.inflate(layoutInflater, binding.profileGrid, false)
            bindProfileCard(cardBinding, profile, isActive = profile.id == activeId)
            cardBinding.root.layoutParams = android.widget.GridLayout.LayoutParams().apply {
                width = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            }
            binding.profileGrid.addView(cardBinding.root)
        }

        // "Add Profile" tile at the end of the grid, same tap-target
        // style as a profile card so it reads as part of the same row
        // rather than a separate button bolted on below it.
        val addBinding = ProfileCardBinding.inflate(layoutInflater, binding.profileGrid, false)
        addBinding.pfpImage.setImageResource(R.drawable.ic_add_profile)
        addBinding.profileNameText.text = "Add Profile"
        addBinding.pfpImage.alpha = 0.6f
        addBinding.profileNameText.alpha = 0.6f
        addBinding.activeIndicator.visibility = View.GONE
        addBinding.root.setOnClickListener { showAddProfileDialog() }
        addBinding.root.layoutParams = android.widget.GridLayout.LayoutParams().apply {
            width = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        }
        binding.profileGrid.addView(addBinding.root)
    }

    private fun bindProfileCard(cardBinding: ProfileCardBinding, profile: Profile, isActive: Boolean) {
        cardBinding.profileNameText.text = profile.name
        cardBinding.activeIndicator.visibility = if (isActive) View.VISIBLE else View.GONE
        loadPfpInto(cardBinding.pfpImage, profile.pfpPath)

        cardBinding.root.setOnClickListener {
            selectProfile(profile.id)
        }
        cardBinding.root.setOnLongClickListener {
            showProfileManageDialog(profile)
            true
        }
    }

    private fun loadPfpInto(imageView: ImageView, pfpPath: String?) {
        if (pfpPath != null && File(pfpPath).exists()) {
            imageView.setImageURI(Uri.fromFile(File(pfpPath)))
        } else {
            imageView.setImageResource(R.drawable.ic_default_pfp)
        }
    }

    private fun selectProfile(profileId: String) {
        // CookieManager is a process-wide singleton -- getInstance() here
        // returns the same cookie store MainActivity's WebView has been
        // writing to, whether or not a WebView is currently visible on
        // screen. So the outgoing profile's session can be captured
        // directly here without needing a WebView reference on this screen.
        val cookieManager = android.webkit.CookieManager.getInstance()
        ProfileManager.switchToProfile(this, profileId, currentWebViewCookieManager = cookieManager)

        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_PROFILE_SWITCHED, true)
        }
        startActivity(intent)
        finish()
    }

    private fun showAddProfileDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "Profile name"
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("Add Profile")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                ProfileManager.createProfile(this, name)
                renderProfiles()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showProfileManageDialog(profile: Profile) {
        val options = arrayOf("Rename", "Change photo", "Delete profile")
        AlertDialog.Builder(this)
            .setTitle(profile.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showRenameDialog(profile)
                    1 -> {
                        pfpTargetProfileId = profile.id
                        pickImageLauncher.launch("image/*")
                    }
                    2 -> showDeleteConfirmation(profile)
                }
            }
            .show()
    }

    private fun showRenameDialog(profile: Profile) {
        val input = android.widget.EditText(this).apply {
            setText(profile.name)
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename Profile")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    ProfileManager.renameProfile(this, profile.id, newName)
                    renderProfiles()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteConfirmation(profile: Profile) {
        if (ProfileManager.getProfiles(this).size <= 1) {
            Toast.makeText(this, "Can't delete the last profile", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Delete \"${profile.name}\"?")
            .setMessage("This removes its settings, login session, and photo. This can't be undone.")
            .setPositiveButton("Delete") { _, _ ->
                ProfileManager.deleteProfile(this, profile.id)
                renderProfiles()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Copies the picked gallery image into app-private storage
     * (filesDir) as this profile's PFP -- never uploaded anywhere, never
     * fetched from the web; purely a local copy of a file the person
     * picked themselves from their own device.
     */
    private fun savePfpFromUri(profileId: String, uri: Uri) {
        try {
            val destFile = File(filesDir, "pfp_$profileId.jpg")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            ProfileManager.setProfilePfp(this, profileId, destFile.absolutePath)
            renderProfiles()
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't set that photo", Toast.LENGTH_SHORT).show()
        }
    }
}
