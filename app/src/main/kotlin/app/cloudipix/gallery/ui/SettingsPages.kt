package app.cloudipix.gallery.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.cloudipix.gallery.R
import app.cloudipix.gallery.ui.component.AppIconImage
import app.cloudipix.gallery.ui.component.GalleryIcons
import app.cloudipix.gallery.ui.theme.GalleryTheme

private data class OpenSourceCredit(
    val name: String,
    val license: String,
    val url: String,
)

private const val APACHE_2_0 = "Apache License 2.0"

private val openSourceCredits = listOf(
    OpenSourceCredit(
        "Simplify my Gallery",
        "Mozilla Public License 2.0",
        "https://github.com/simplifymy-app/gallery",
    ),
    OpenSourceCredit(
        "AndroidX, Jetpack Compose, Room, Media3",
        APACHE_2_0,
        "https://developer.android.com/jetpack/androidx",
    ),
    OpenSourceCredit("Hilt", APACHE_2_0, "https://dagger.dev/hilt/"),
    OpenSourceCredit("Coil", APACHE_2_0, "https://coil-kt.github.io/coil/"),
    OpenSourceCredit("Telephoto", APACHE_2_0, "https://github.com/saket/telephoto"),
    OpenSourceCredit("OkHttp", APACHE_2_0, "https://square.github.io/okhttp/"),
    OpenSourceCredit(
        "Apache Commons Net",
        APACHE_2_0,
        "https://commons.apache.org/proper/commons-net/",
    ),
    OpenSourceCredit(
        "Lucide",
        "ISC License / Feather MIT License",
        "https://lucide.dev/license",
    ),
    OpenSourceCredit(
        "ImageToolboxLibs (GPUImage)",
        APACHE_2_0,
        "https://github.com/T8RIN/ImageToolboxLibs/tree/8.4.1/libs/gpuimage",
    ),
    OpenSourceCredit(
        "Cropper, Gesture (Image Toolbox)",
        APACHE_2_0,
        "https://github.com/T8RIN/ImageToolbox/tree/8fbf8bc335758465107278772b54e33070cf0fa2/lib",
    ),
    OpenSourceCredit(
        "Caveat, IBM Plex, Outfit, Space Grotesk",
        "SIL Open Font License 1.1",
        "https://openfontlicense.org/",
    ),
)

@Composable
internal fun AboutPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val version = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    val links = LocalUriHandler.current
    PageFrame(color = GalleryTheme.colors.settingsBackground) {
        PageHeader(stringResource(R.string.setting_about), onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIconImage(Modifier.size(52.dp))
                Column {
                    Text(stringResource(R.string.cloudipix_name), style = MaterialTheme.typography.titleLarge)
                    Text("${stringResource(R.string.about_version)} $version", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text(stringResource(R.string.about_info), Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
            AppUpdateRow(installedVersion = version)
            HorizontalDivider(Modifier.padding(top = 8.dp))
            Text(stringResource(R.string.about_section_credits), Modifier.padding(18.dp), style = MaterialTheme.typography.titleMedium)
            openSourceCredits.forEach { credit ->
                ListItem(
                    headlineContent = { Text(credit.name) },
                    supportingContent = { Text(credit.license) },
                    trailingContent = { Icon(GalleryIcons.Arrow, null) },
                    modifier = Modifier.clickable { links.openUri(credit.url) },
                )
            }
        }
    }
}
