package com.beatiq.app.presentation.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.beatiq.app.data.model.UserDownload
import com.beatiq.app.data.repository.DownloadsRepository
import com.beatiq.app.features.library.RepositoryProvider
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

class DownloadsViewModel(
    private val downloadsRepository: DownloadsRepository,
) : ViewModel() {

    val downloads =
        downloadsRepository.observeDownloads().stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList<UserDownload>(),
        )

    companion object {
        @Suppress("UNCHECKED_CAST")
        val Factory: ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(DownloadsViewModel::class.java))
                    return DownloadsViewModel(RepositoryProvider.downloadsRepository) as T
                }
            }
    }
}
