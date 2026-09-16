package com.cvar1984.megaverse.presentation

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.cvar1984.megaverse.sky.SkyCatalog
import com.cvar1984.megaverse.sky.SkyObject
import com.cvar1984.megaverse.sky.SkyType

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.load(this)
        setContent { MaterialTheme { SkyApp() } }
    }
}

/** "all" stands in for the whole catalogue, where every other route carries an object id. */
private const val SHOW_ALL = "all"

@Composable
fun SkyApp() {
    val context = LocalContext.current
    val state = remember { SkyState(context) }
    val navController = rememberSwipeDismissableNavController()

    // The sky screen cannot place anything without knowing where you are standing,
    // so the ask comes up front rather than at the point of use.
    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { state.onPermissionResult() }
    LaunchedEffect(Unit) {
        if (!state.hasLocationPermission()) {
            permissions.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            )
        }
    }

    AppScaffold {
        SwipeDismissableNavHost(navController, startDestination = "root") {
            composable("root") {
                Menu("Locate Sky Object") {
                    item("Show All") { navController.toSky(SHOW_ALL) }
                    item("Sun") { navController.toSky("sun") }
                    item("Moon") { navController.toSky("moon") }
                    item("Planets") { navController.navigate("list/planets") }
                    item("Stars") { navController.navigate("list/stars") }
                    item("Calendar") { navController.navigate("calendar") }
                }
            }
            composable(
                "list/{kind}",
                arguments = listOf(navArgument("kind") { type = NavType.StringType }),
            ) { entry ->
                val planets = entry.arguments?.getString("kind") == "planets"
                val type = if (planets) SkyType.PLANET else SkyType.STAR
                Menu(if (planets) "Planets" else "Stars") {
                    for (obj in SkyCatalog.byType(type)) {
                        item(obj.name) { navController.toSky(obj.id) }
                    }
                }
            }
            composable(
                "sky/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString("id")
                // No object to aim at: a null target puts the pointer screen into
                // its whole-catalogue mode.
                val target: SkyObject? = if (id == SHOW_ALL) null else SkyCatalog.findById(id ?: "")
                SkyScreen(target, state) { navController.navigate("settings") }
            }
            composable("calendar") { CalendarScreen(state) }
            composable("settings") { SettingsScreen() }
        }
    }
}

private fun NavController.toSky(id: String) = navigate("sky/$id")

/**
 * A list of things to pick, in the one shape every menu in the app uses. Items are
 * declared through [MenuScope] so a caller writes the label and what it does and
 * nothing else.
 */
class MenuScope internal constructor() {
    internal val entries = mutableListOf<Pair<String, () -> Unit>>()
    fun item(label: String, onClick: () -> Unit) {
        entries.add(label to onClick)
    }
}

@Composable
private fun Menu(title: String, content: MenuScope.() -> Unit) {
    val scope = MenuScope().apply(content)
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(contentPadding = contentPadding, state = listState) {
            item {
                ListHeader(
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                ) { Text(title) }
            }
            items(scope.entries.size) { index ->
                val (label, onClick) = scope.entries[index]
                Button(
                    onClick = onClick,
                    label = { Text(label) },
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                )
            }
        }
    }
}


/**
 * Each item carries its current value underneath it, and selecting it steps that
 * value on in place rather than opening a list to pick from, so what the menu shows
 * is always what is stored. A short list is quicker to thumb through than a
 * submenu, and the label cannot go stale behind the menu showing it.
 */
@Composable
private fun SettingsScreen() {
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(contentPadding = contentPadding, state = listState) {
            item {
                ListHeader(
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                ) { Text("Settings") }
            }
            items(Settings.specs.size) { index ->
                val spec2 = Settings.specs[index]
                Button(
                    onClick = { Settings.cycle(spec2) },
                    label = { Text(spec2.title) },
                    // Read inside the item, so turning location updates off
                    // relabels Azimuth Motion above it rather than leaving it
                    // claiming to follow something that has stopped arriving.
                    secondaryLabel = { Text(Settings.label(spec2)) },
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                )
            }
            item { Credit() }
        }
    }
}

/** The plate's required attribution, at the foot of the settings list. */
@Composable
private fun Credit() = Text(
    MilkyWay.CREDIT,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
    textAlign = TextAlign.Center,
    fontSize = 10.sp,
    color = Color(0xFF8A8A8A),
)
