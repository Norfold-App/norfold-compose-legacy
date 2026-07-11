package com.norfold.app.branding

import androidx.annotation.DrawableRes
import com.norfold.app.R

data class BuiltInCover(
    val title: String,
    @param:DrawableRes val drawableRes: Int,
    val resourceName: String,
) {
    val uri: String = "android.resource://com.norfold.app/drawable/$resourceName"
}

val BuiltInCovers = listOf(
    BuiltInCover("Cosmic bridge", R.drawable.cover_cosmic_bridge, "cover_cosmic_bridge"),
    BuiltInCover("Crystal cave", R.drawable.cover_crystal_cave, "cover_crystal_cave"),
    BuiltInCover("Mountain path", R.drawable.cover_mountain_path, "cover_mountain_path"),
    BuiltInCover("Desert sunrise", R.drawable.cover_desert_sunrise, "cover_desert_sunrise"),
    BuiltInCover("Coastal light", R.drawable.cover_coastal_light, "cover_coastal_light"),
    BuiltInCover("Ocean sunset", R.drawable.cover_ocean_sunset, "cover_ocean_sunset"),
    BuiltInCover("Dual moons", R.drawable.cover_dual_moons, "cover_dual_moons"),
)
