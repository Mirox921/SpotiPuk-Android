package com.project.puk.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.project.puk.R

val Saira = FontFamily(
    Font(R.font.saira_thin, FontWeight.Thin),
    Font(R.font.saira_extralight, FontWeight.ExtraLight),
    Font(R.font.saira_light, FontWeight.Light),
    Font(R.font.saira_regular, FontWeight.Normal),
    Font(R.font.saira_medium, FontWeight.Medium),
    Font(R.font.saira_semibold, FontWeight.SemiBold),
    Font(R.font.saira_bold, FontWeight.Bold),
    Font(R.font.saira_extrabold, FontWeight.ExtraBold),
    Font(R.font.saira_black, FontWeight.Black),
)

val SairaTypography = Typography(
    displayLarge = TextStyle(fontFamily = Saira, fontWeight = FontWeight.Normal),
    displayMedium = TextStyle(fontFamily = Saira, fontWeight = FontWeight.Normal),
    displaySmall = TextStyle(fontFamily = Saira, fontWeight = FontWeight.Normal),
    headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontFamily = Saira, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontFamily = Saira, fontWeight = FontWeight.Normal),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontFamily = Saira, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 27.sp, fontFamily = Saira, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontFamily = Saira, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontFamily = Saira, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontFamily = Saira, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontFamily = Saira, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontFamily = Saira, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontFamily = Saira, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontFamily = Saira, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, fontFamily = Saira, fontWeight = FontWeight.Medium),
)
