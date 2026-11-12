package com.horizon.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val FOLDER_COLS = 4

@Composable
fun FolderOverlay(
    folder: HomeItem,
    index: Map<String, AppEntry>,
    vm: LauncherViewModel,
    drag: DragController,
    onLaunch: (AppEntry) -> Unit,
    onDrop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .pointerInput(Unit) { detectTapGestures { vm.closeFolder() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(30.dp))
                .background(Color(0xF51B1E26))
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(horizontal = 12.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicTextField(
                value = folder.name,
                onValueChange = { vm.renameFolder(folder.id, it) },
                singleLine = true,
                textStyle = TextStyle(
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                ),
                cursorBrush = SolidColor(Color.White),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(14.dp))

            Column(
                Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                folder.apps.chunked(FOLDER_COLS).forEachIndexed { r, row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEachIndexed { c, key ->
                            val i = r * FOLDER_COLS + c
                            val app = index[key]
                            if (app == null) {
                                Spacer(Modifier.weight(1f))
                            } else {
                                LauncherIcon(
                                    label = app.label,
                                    drag = drag,
                                    payload = DragPayload.FromFolder(folder.id, i),
                                    onTap = { onLaunch(app) },
                                    menu = listOf(
                                        MenuAction("Move to home screen") { vm.removeFromFolder(folder.id, i) },
                                        MenuAction("App info") { vm.openAppInfo(app) },
                                    ),
                                    onDragStarted = { vm.closeFolder() },
                                    onDrop = onDrop,
                                    modifier = Modifier.weight(1f),
                                ) { AppImage(app) }
                            }
                        }
                        repeat(FOLDER_COLS - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}
