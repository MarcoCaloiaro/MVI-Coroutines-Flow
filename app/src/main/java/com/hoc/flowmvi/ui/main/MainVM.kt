package com.hoc.flowmvi.ui.main

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hoc.flowmvi.domain.usecase.GetUsersUseCase
import com.hoc.flowmvi.domain.usecase.RefreshGetUsersUseCase
import com.hoc.flowmvi.domain.usecase.RemoveUserUseCase
import com.hoc.flowmvi.flatMapFirst
import com.hoc.flowmvi.ui.main.MainContract.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

@Suppress("USELESS_CAST")
@FlowPreview
@ExperimentalCoroutinesApi
class MainVM(
    private val getUsersUseCase: GetUsersUseCase,
    private val refreshGetUsers: RefreshGetUsersUseCase,
    private val removeUser: RemoveUserUseCase,
) : ViewModel() {
  private val _eventChannel = BroadcastChannel<SingleEvent>(capacity = Channel.BUFFERED)
  private val _intentChannel = BroadcastChannel<ViewIntent>(capacity = Channel.CONFLATED)

  val viewState: StateFlow<ViewState>

  val singleEvent: Flow<SingleEvent>

  suspend fun processIntent(intent: ViewIntent) = _intentChannel.send(intent)

  init {
    val initialVS = ViewState.initial()

    viewState = MutableStateFlow(initialVS)
    singleEvent = _eventChannel.asFlow()

    val intentFlow = _intentChannel.asFlow()
    merge(
        intentFlow.filterIsInstance<ViewIntent.Initial>().take(1),
        intentFlow.filterNot { it is ViewIntent.Initial }
    )
        .toPartialChangeFlow()
        .sendSingleEvent()
        .scan(initialVS) { vs, change -> change.reduce(vs) }
        .onEach { viewState.value = it }
        .catch { }
        .launchIn(viewModelScope)
  }

  private fun Flow<PartialChange>.sendSingleEvent(): Flow<PartialChange> {
    return onEach {
      val event = when (it) {
        is PartialChange.GetUser.Error -> SingleEvent.GetUsersError(it.error)
        is PartialChange.Refresh.Success -> SingleEvent.Refresh.Success
        is PartialChange.Refresh.Failure -> SingleEvent.Refresh.Failure(it.error)
        is PartialChange.RemoveUser.Success -> SingleEvent.RemoveUser.Success(it.user)
        is PartialChange.RemoveUser.Failure -> SingleEvent.RemoveUser.Failure(
            user = it.user,
            error = it.error,
        )
        PartialChange.GetUser.Loading -> return@onEach
        is PartialChange.GetUser.Data -> return@onEach
        PartialChange.Refresh.Loading -> return@onEach
      }
      _eventChannel.send(event)
    }
  }

  private fun Flow<ViewIntent>.toPartialChangeFlow(): Flow<PartialChange> {
    val getUserChanges = getUsersUseCase()
        .onEach { Log.d("###", "[MAIN_VM] Emit users.size=${it.size}") }
        .map {
          val items = it.map(::UserItem)
          PartialChange.GetUser.Data(items) as PartialChange.GetUser
        }
        .onStart { emit(PartialChange.GetUser.Loading) }
        .catch { emit(PartialChange.GetUser.Error(it)) }

    val refreshChanges = flow { emit(refreshGetUsers()) }
        .map { PartialChange.Refresh.Success as PartialChange.Refresh }
        .onStart { emit(PartialChange.Refresh.Loading) }
        .catch { emit(PartialChange.Refresh.Failure(it)) }

    return merge(
        filterIsInstance<ViewIntent.Initial>()
            .logIntent()
            .flatMapConcat { getUserChanges },
        filterIsInstance<ViewIntent.Refresh>()
            .filter { viewState.value.let { !it.isLoading && it.error === null } }
            .logIntent()
            .flatMapFirst { refreshChanges },
        filterIsInstance<ViewIntent.Retry>()
            .filter { viewState.value.error != null }
            .logIntent()
            .flatMapFirst { getUserChanges },
        filterIsInstance<ViewIntent.RemoveUser>()
            .logIntent()
            .map { it.user }
            .flatMapMerge { userItem ->
              flow {
                try {
                  removeUser(userItem.toDomain())
                  emit(PartialChange.RemoveUser.Success(userItem))
                } catch (e: Exception) {
                  emit(PartialChange.RemoveUser.Failure(userItem, e))
                }
              }
            }
    )
  }

  private fun <T : ViewIntent> Flow<T>.logIntent() = onEach { Log.d("MainVM", "## Intent: $it") }
}


