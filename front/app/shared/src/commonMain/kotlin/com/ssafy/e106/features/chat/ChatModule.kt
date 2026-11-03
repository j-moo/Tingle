package com.ssafy.e106.features.chat

import com.ssafy.e106.features.chat.data.*
import com.ssafy.e106.features.chat.ui.*
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** 기능 객체만 등록하며 인증/HttpClient/계정별 저장소는 공통 기반을 주입받는다. */
val chatModule = module {
    single<ChatRepository> { HttpChatRepository(get()) }
    single { ChatLocalStorage(get()) }
    viewModel { ChatListViewModel(get(), get(), get()) }
    viewModel { ChatRoomViewModel(get(), get(), get()) }
    viewModel { ChatPartnerViewModel(get(), get()) }
}
