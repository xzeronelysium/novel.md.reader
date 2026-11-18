package com.mdreader.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AppChrome(onImport: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(scheme.background)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Box(Modifier.size(6.dp).background(scheme.primary))
            Text(
                "NOVEL.MD.READER",
                fontFamily = Sans,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.1.sp,
                color = scheme.onBackground,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable(onClick = onImport).padding(vertical = 2.dp),
        ) {
            Text("+", fontFamily = Sans, fontSize = 17.sp, color = scheme.primary)
            Text(
                "IMPORT FOLDER",
                fontFamily = Sans,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                color = scheme.onBackground,
                modifier = Modifier.padding(start = 7.dp),
            )
            Text(
                "CTRL O",
                fontFamily = Sans,
                fontSize = 9.sp,
                letterSpacing = 0.6.sp,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
    Hairline()
}
