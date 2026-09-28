package com.baystudio.droide

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.baystudio.droide.core.GitHubAccountOAuthAuthority






class GitHubOAuthCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val callback = intent?.data
        if (
            callback != null &&
            GitHubAccountOAuthAuthority().isCallbackRoute(
                callback.toString(),
                BuildConfig.GITHUB_ACCOUNT_REDIRECT_URI,
            )
        ) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .setAction(ACTION_GITHUB_OAUTH_CALLBACK)
                    .setData(callback)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
        finish()
    }

    companion object {
        const val ACTION_GITHUB_OAUTH_CALLBACK = "com.baystudio.droide.action.GITHUB_OAUTH_CALLBACK"
    }
}
