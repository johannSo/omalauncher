package dev.omalauncher.pp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.lifecycleScope
import dev.omalauncher.pp.linux.AuthorizedKeys
import dev.omalauncher.pp.linux.Ssh
import dev.omalauncher.pp.ui.KeyIndicated
import dev.omalauncher.pp.ui.SshKeyShareDialog
import dev.omalauncher.pp.ui.readSmallText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Add as SSH key" in the share sheet: a public key's text or .pub file shared from another app goes into
 * Linux's ~/.ssh/authorized_keys once confirmed. Only enabled while the SSH server is on ([Ssh]).
 */
class SshKeyShareActivity : ComponentActivity() {
    private val runtime get() = (application as App).runtime

    /** In the language picked in settings. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        AppLanguage.applyTo(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action != Intent.ACTION_SEND) {
            finish()
            return
        }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        @Suppress("DEPRECATION")
        val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        setContent {
            KeyIndicated {
                val shared by produceState<String?>(null) {
                    value = text ?: stream?.let { withContext(Dispatchers.IO) { readSmallText(this@SshKeyShareActivity, it) } }.orEmpty()
                }
                val content = shared ?: return@KeyIndicated
                SshKeyShareDialog(
                    keys = AuthorizedKeys.parse(content),
                    privateKey = AuthorizedKeys.looksPrivate(content),
                    linux = runtime.proot.home.isDirectory,
                    onAdd = {
                        lifecycleScope.launch {
                            val (result, count) = runtime.ssh.addKeys(content)
                            val message =
                                when (result) {
                                    Ssh.Added.Some -> resources.getQuantityString(R.plurals.ssh_keys_added, count, count)
                                    Ssh.Added.AlreadyThere -> getString(R.string.ssh_key_there)
                                    Ssh.Added.PrivateKey -> getString(R.string.ssh_key_private)
                                    Ssh.Added.NoKey -> getString(R.string.ssh_key_none)
                                }
                            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
                            finish()
                        }
                    },
                    onDismiss = ::finish,
                )
            }
        }
    }
}
