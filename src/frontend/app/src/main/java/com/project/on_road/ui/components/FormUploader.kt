package com.project.on_road.ui.components

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.project.on_road.ui.theme.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File

/** 양식 올리기: 사진 찍기 · 파일(사진·PDF) 고르기. 고른 Uri 를 [onPicked] 로 */
class FormUploader(val takePhoto: () -> Unit, val pickFile: () -> Unit)

@Composable
fun rememberFormUploader(onPicked: (Uri) -> Unit): FormUploader {
    val context = LocalContext.current
    var photoUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = photoUri
        if (ok && uri != null) onPicked(uri)
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(onPicked) }
    return remember {
        FormUploader(
            takePhoto = {
                val uri = photoFile(context)
                photoUri = uri
                camera.launch(uri)
            },
            pickFile = { files.launch(arrayOf("image/*", "application/pdf", "application/x-hwp", "application/haansofthwp", "application/octet-stream")) },
        )
    }
}

/** 카메라가 사진을 쓸 파일 (cacheDir/documents, res/xml/file_paths.xml 의 FileProvider 경로) */
private fun photoFile(context: Context): Uri {
    val dir = File(context.cacheDir, "documents").apply { mkdirs() }
    val file = File(dir, "form-photo.jpg").apply { if (exists()) delete() }
    return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
}

/** 받은 양식 올리기 카드: 사진 찍기 · 파일 고르기 */
@Composable
fun UploadFormCard(
    onTakePhoto: () -> Unit,
    onPickFile: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "받은 양식 올리기",
    subtitle: String = "빈 양식을 찍으면 AI가 칸을 읽어요",
) {
    // 연회색 카드 (홈 바로가기·다음 일정과 같은 모양)
    SubtleCard(modifier.fillMaxWidth(), contentPadding = PaddingValues(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = OnRoadType.Title3, color = OnRoadColors.TextPrimary)
                Text(subtitle, style = OnRoadType.Caption2, color = OnRoadColors.TextSecondary)
            }
        }
        Spacer(Modifier.height(16.dp))
        UploadButtons(onTakePhoto, onPickFile)
    }
}

/** 사진 찍기 · 파일 고르기 버튼 한 줄 */
@Composable
fun UploadButtons(onTakePhoto: () -> Unit, onPickFile: () -> Unit, enabled: Boolean = true) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OnRoadButton(
            "사진 찍기", onTakePhoto, Modifier.weight(1f),
            height = 40.dp, textStyle = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold),
            enabled = enabled,
        )
        OnRoadButton(
            "파일 고르기", onPickFile, Modifier.weight(1f),
            height = 40.dp, textStyle = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold),
            variant = ButtonVariant.Outline, enabled = enabled,
        )
    }
}
