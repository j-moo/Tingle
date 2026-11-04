package com.ssafy.e106.features.proposals

import com.ssafy.e106.core.auth.*
import com.ssafy.e106.features.proposals.data.*
import com.ssafy.e106.features.proposals.ui.ProposalViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** 개발 진입을 열어도 실제 HTTP 가입 단계/소유 권한 검사는 서버에 맡긴다. */
data class ProposalAccess(val development: Boolean = false) {
    fun allowed(session: Session.SignedIn) = session.isReady || development && session.status == OnboardingStatus.KEYRING_REQUIRED
}
fun proposalModule(development: Boolean = false) = module {
    single { ProposalAccess(development) }
    single<ProposalRepository> { HttpProposalRepository(get()) }
    viewModel { ProposalViewModel(get(), get(), get(), get()) }
}
