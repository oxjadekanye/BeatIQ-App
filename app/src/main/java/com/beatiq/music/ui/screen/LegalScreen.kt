package com.beatiq.music.ui.screen

import android.graphics.Color as AndroidColor
import android.net.Uri
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.beatiq.music.BeatIQPublicSite
import com.beatiq.music.R
import com.beatiq.music.ui.components.BeatIQBackButton
import com.beatiq.music.ui.components.PremiumScreenBackground
import com.beatiq.music.ui.theme.BeatIQAccent

@Composable
fun LegalScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    fun open(url: String) {
        val scheme =
            CustomTabColorSchemeParams.Builder()
                .setToolbarColor(AndroidColor.parseColor("#0A0610"))
                .setNavigationBarColor(AndroidColor.parseColor("#0A0610"))
                .build()
        CustomTabsIntent.Builder()
            .setDefaultColorSchemeParams(scheme)
            .setShowTitle(true)
            .build()
            .launchUrl(context, Uri.parse(url))
    }

    PremiumScreenBackground {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            BeatIQBackButton(onBack = onBack)
            Text(
                text = stringResource(R.string.legal_screen_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = stringResource(R.string.legal_company_block),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { open(BeatIQPublicSite.PRIVACY_POLICY_URL) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.legal_open_privacy))
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { open(BeatIQPublicSite.TERMS_URL) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.legal_open_terms))
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { open(BeatIQPublicSite.COOKIE_POLICY_URL) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.legal_open_cookies))
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { open(BeatIQPublicSite.DELETE_ACCOUNT_URL) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.legal_open_delete_account))
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.legal_contact_hint),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = BeatIQAccent,
            )
        }
    }
}
