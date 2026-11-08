package com.ssafy.e106.features.account

import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.core.storage.*
import com.ssafy.e106.features.account.data.*
import com.ssafy.e106.features.account.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

private const val runId = "11111111-1111-4111-8111-111111111111"
private val run = WithdrawalOperation(runId,"PREPARED","2026-11-08T00:00:00Z","2026-11-08T00:30:00Z",
    externalCleanupStatus="NOT_STARTED",devicePermanentlyBlocked=false)
private val done = run.copy(status="COMPLETED",completedAt="2026-11-08T00:01:00Z",
    rejoinAvailableAt="2026-11-11T00:01:00Z",expiresAt="2026-11-11T00:01:00Z",externalCleanupStatus="PENDING")

/** 합성 서버/보호 저장 포트만 사용한다. 실제 회원 탈퇴나 네트워크 호출은 하지 않는다. */
internal class Records : ProtectedRecordStore {
    var saved: String? = null; var refuseWrite=false
    override suspend fun read(key:String):ProtectedRead = saved?.let { ProtectedRead.Found(it) } ?: ProtectedRead.Missing
    override suspend fun write(key:String,value:String) { check(!refuseWrite); saved=value }
    override suspend fun remove(key:String,expectedValue:String):Boolean = false
}
internal class LoggedIn : SessionStore {
    override val state=MutableStateFlow<Session>(Session.SignedIn(75,OnboardingStatus.READY,true))
    override val events=emptyFlow<SessionEvent>()
    override suspend fun refreshOnboarding()=ApiResult.Ok(Unit)
}
internal class End(private val session:LoggedIn):WithdrawalSessionEnd {
    var calls=0; var fail=false
    override suspend fun capture(userId:Long)=if((session.state.value as? Session.SignedIn)?.userId==userId) WithdrawalSessionHandle(userId,0) else null
    override suspend fun finish(handle:WithdrawalSessionHandle):Boolean {
        check(!fail); calls++; session.state.value=Session.SignedOut; return true
    }
}
internal class Server:WithdrawalRepository {
    val keys=mutableListOf<String>(); var sends=0; var reads=0; var cancels=0
    var cancelFails=false; var prepareFails=false; var response:ApiResult<WithdrawalOperation> = ApiResult.Ok(run)
    var beforePrepare: () -> Unit = {}
    var preparedOperation = run
    var beforeEligibility: () -> Unit = {}
    var eligibilityFails = false
    var eligibilityValue = WithdrawalEligibility(true,0,0,emptyList(),"2026-11-08T00:00:00Z")
    var sent: suspend ()->ApiResult<WithdrawalOperation> = { ApiResult.Ok(done) }
    override suspend fun eligibility(userId:Long):ApiResult<WithdrawalEligibility> {
        beforeEligibility()
        return if(eligibilityFails) ApiResult.Fail(AppError.SessionExpired) else ApiResult.Ok(eligibilityValue)
    }
    override suspend fun prepare(userId:Long,requestKey:String):ApiResult<WithdrawalPrepared> {
        keys+=requestKey
        beforePrepare()
        return if(prepareFails) ApiResult.Fail(AppError.Offline) else ApiResult.Ok(WithdrawalPrepared("synthetic",preparedOperation))
    }
    override suspend fun execute(userId:Long,withdrawalId:String,receipt:String):ApiResult<WithdrawalOperation> { sends++; return sent() }
    override suspend fun result(withdrawalId:String,receipt:String):ApiResult<WithdrawalOperation> { reads++; return response }
    override suspend fun cancel(userId:Long,withdrawalId:String):ApiResult<Unit> {
        cancels++
        return if(cancelFails) ApiResult.Fail(AppError.Offline) else ApiResult.Ok(Unit)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class WithdrawalRecoveryTest {
    @Test fun verifiedCompletionSurvivesOfflineExpiredOrRegressiveServerResponseAfterRestart()=runTest {
        for(response in listOf(ApiResult.Fail(AppError.Offline),
            ApiResult.Fail(AppError.Server(410,"WITHDRAWAL_OPERATION_EXPIRED","synthetic")),ApiResult.Ok(run))) {
            val records=Records();val journal=WithdrawalJournal(records);val session=LoggedIn();val server=Server()
            journal.markSession(75);journal.save(WithdrawalTicket(75,runId,done,"synthetic",attempted=true,autoResume=true))
            session.state.value=Session.SignedOut;server.response=response
            val model=WithdrawalCoordinator(server,journal,session,End(session),backgroundScope,{error("new key forbidden")})
            runCurrent()
            assertEquals(WithdrawalStage.Completed,model.state.value.ui.displayStage)
            assertEquals(done,model.state.value.ui.operation);assertTrue(model.state.value.visible)
            assertEquals(done,journal.all().single().operation)
            assertTrue(server.keys.isEmpty());assertEquals(0,server.sends)
            records.refuseWrite=true;model.reload();runCurrent()
            assertEquals(WithdrawalStage.Completed,model.state.value.ui.displayStage)
            assertEquals("PENDING",model.state.value.ui.operation?.externalCleanupStatus)
        }
    }
    @Test fun cachedCompletionRetriesLocalSessionCleanupWithoutRequiringNetwork()=runTest {
        val journal=WithdrawalJournal(Records());val session=LoggedIn();val server=Server();val end=End(session)
        journal.save(WithdrawalTicket(75,runId,done,"synthetic",attempted=true,autoResume=true))
        server.response=ApiResult.Fail(AppError.Offline);end.fail=true
        val model=WithdrawalCoordinator(server,journal,session,end,backgroundScope,{runId});runCurrent()
        assertEquals(WithdrawalStage.Completed,model.state.value.ui.displayStage)
        assertTrue(model.state.value.ui.localCleanupPending)
        end.fail=false;model.reload();runCurrent()
        assertEquals(Session.SignedOut,session.state.value);assertFalse(model.state.value.ui.localCleanupPending)
        assertEquals(WithdrawalStage.Completed,model.state.value.ui.displayStage);assertEquals(0,server.sends)
    }

    @Test fun rejectedExecuteWithPreparedResultShowsCurrentKeyringBlockWithoutLosingAttempt()=runTest {
        val journal=WithdrawalJournal(Records());val session=LoggedIn();val server=Server()
        session.state.value=Session.SignedIn(75,OnboardingStatus.PROFILE_REQUIRED,false)
        val model=WithdrawalCoordinator(server,journal,session,End(session),backgroundScope,{runId})
        runCurrent();model.open(75);runCurrent();model.prepare();runCurrent()
        server.sent={
            server.eligibilityValue=server.eligibilityValue.copy(eligible=false,registeredKeyringCount=1,
                blockingCodes=listOf("KEYRING_REGISTERED"))
            ApiResult.Fail(AppError.Server(422,"WITHDRAWAL_BLOCKED","synthetic"))
        }
        model.accepted(true);model.execute();runCurrent()
        assertEquals(WithdrawalStage.Blocked,model.state.value.ui.stage)
        assertTrue(journal.all().single().attempted);assertFalse(model.state.value.ui.canCancelPreparation)
        model.openKeyringManagement();runCurrent();assertTrue(model.consumeKeyringNavigation(75))
        server.eligibilityValue=server.eligibilityValue.copy(eligible=true,registeredKeyringCount=0,blockingCodes=emptyList())
        model.open(75);runCurrent()
        assertEquals(WithdrawalStage.Confirm,model.state.value.ui.stage)
        assertFalse(model.state.value.ui.accepted);assertFalse(model.state.value.ui.canCancelPreparation)
        assertEquals(1,server.sends);assertEquals(runId,journal.all().single().operation?.withdrawalId)
    }
    @Test fun lostCancellationResponseRestartsSameCancellationWithoutPreparingReplacement()=runTest {
        val records=Records();val journal=WithdrawalJournal(records);val session=LoggedIn();val server=Server()
        val process=CoroutineScope(SupervisorJob()+StandardTestDispatcher(testScheduler))
        val first=WithdrawalCoordinator(server,journal,session,End(session),process,{runId})
        runCurrent();first.open(75);runCurrent();first.prepare();runCurrent()
        server.cancelFails=true
        first.cancel();runCurrent()
        assertTrue(journal.all().single().cancelRequested)
        assertEquals(WithdrawalStage.Unknown,first.state.value.ui.stage)
        first.close();runCurrent();assertTrue(journal.all().single().autoResume);process.cancel()
        // 서버가 이미 삭제한 준비의 결과는404여도 조회하지 않는다. 같은 ID 취소의 성공만 확인한다.
        server.response=ApiResult.Fail(AppError.Server(404,"RESOURCE_NOT_FOUND","synthetic"))
        server.cancelFails=false
        val next=WithdrawalCoordinator(server,WithdrawalJournal(records),session,End(session),backgroundScope,{error("new key forbidden")})
        runCurrent()
        assertTrue(journal.all().isEmpty());assertEquals(WithdrawalStage.Ready,next.state.value.ui.stage)
        assertEquals(2,server.cancels);assertEquals(1,server.reads)
        assertEquals(listOf(runId),server.keys);assertEquals(0,server.sends)
    }
    @Test fun cancellationPendingAfterSignOutCanRecoverVerifiedCompletionWithoutAuthenticatedCancel()=runTest {
        val records=Records();val journal=WithdrawalJournal(records);val session=LoggedIn();val server=Server()
        val process=CoroutineScope(SupervisorJob()+StandardTestDispatcher(testScheduler))
        val first=WithdrawalCoordinator(server,journal,session,End(session),process,{runId})
        runCurrent();first.open(75);runCurrent();first.prepare();runCurrent()
        server.cancelFails=true;first.cancel();runCurrent();assertTrue(journal.all().single().cancelRequested)
        process.cancel();session.state.value=Session.SignedOut;server.response=ApiResult.Ok(done)
        val next=WithdrawalCoordinator(server,journal,session,End(session),backgroundScope,{error("new key forbidden")})
        runCurrent()
        assertEquals(WithdrawalStage.Completed,next.state.value.ui.displayStage)
        assertFalse(journal.all().single().cancelRequested);assertFalse(journal.all().single().attempted)
        assertEquals(done,journal.all().single().operation)
        assertEquals(1,server.cancels);assertEquals(0,server.sends)
        next.close();runCurrent()
        val restarted=WithdrawalCoordinator(server,journal,session,End(session),backgroundScope,{runId});runCurrent()
        assertFalse(restarted.state.value.visible)
    }

    @Test fun cancellationCannotStartWithoutDurableIntent()=runTest {
        val records=Records();val journal=WithdrawalJournal(records);val session=LoggedIn();val server=Server()
        val model=WithdrawalCoordinator(server,journal,session,End(session),backgroundScope,{runId})
        runCurrent();model.open(75);runCurrent();model.prepare();runCurrent()
        val old=journal.all().single();records.refuseWrite=true
        model.cancel();runCurrent()
        assertEquals(0,server.cancels);assertEquals(old,journal.all().single())
    }

    // 준비/실행 기록을 지우지 않고 차단 해소 → 같은 요청의 새 준비 → 재동의 순서로 이어지는지 검사한다.
    @Test fun expiredPreparationBlockedByKeyringPreservesEvidenceAndRecoversAfterRestrictionRemoved()=runTest {
        for (attempted in listOf(false,true)) for (code in listOf("RESOURCE_NOT_FOUND","WITHDRAWAL_OPERATION_EXPIRED")) {
            val journal=WithdrawalJournal(Records());val session=LoggedIn();val server=Server()
            session.state.value=Session.SignedIn(75,OnboardingStatus.PROFILE_REQUIRED,false)
            val original=WithdrawalTicket(75,runId,run,"synthetic",attempted=attempted,autoResume=attempted)
            journal.save(original)
            server.response=ApiResult.Fail(AppError.Server(if(code=="RESOURCE_NOT_FOUND")404 else 410,code,"synthetic"))
            server.eligibilityValue=server.eligibilityValue.copy(eligible=false,registeredKeyringCount=1,
                blockingCodes=listOf("KEYRING_REGISTERED"))
            val model=WithdrawalCoordinator(server,journal,session,End(session),backgroundScope,{error("new key forbidden")})
            runCurrent();model.open(75);runCurrent()
            assertEquals(WithdrawalStage.Blocked,model.state.value.ui.stage)
            assertEquals(original,journal.all().single())
            assertFalse(model.state.value.ui.canPrepare);assertFalse(model.state.value.ui.canExecute)
            assertFalse(model.state.value.ui.canCancelPreparation)
            assertTrue(server.keys.isEmpty());assertEquals(0,server.sends)
            model.openKeyringManagement();runCurrent()
            assertTrue(model.consumeKeyringNavigation(75));assertFalse(model.state.value.visible)
            assertEquals(original,journal.all().single())
            // 키링 해제 후 재진입해도 새 요청 키나 자동 execute를 만들지 않는다.
            server.eligibilityValue=server.eligibilityValue.copy(eligible=true,registeredKeyringCount=0,blockingCodes=emptyList())
            server.preparedOperation=replacement();model.open(75);runCurrent()
            assertEquals(listOf(runId),server.keys);assertEquals(0,server.sends)
            assertEquals(WithdrawalStage.Confirm,model.state.value.ui.stage)
            assertFalse(model.state.value.ui.accepted)
            assertEquals(replacement().withdrawalId,journal.all().single().operation?.withdrawalId)
        }
    }
    @Test fun restartWithExpiredBlockedRecordStillOffersKeyringManagement()=runTest {
        val records=Records();val journal=WithdrawalJournal(records);val session=LoggedIn();val server=Server()
        session.state.value=Session.SignedIn(75,OnboardingStatus.SURVEY_REQUIRED,false)
        val original=WithdrawalTicket(75,runId,run,"synthetic")
        journal.save(original)
        server.response=ApiResult.Fail(AppError.Server(410,"WITHDRAWAL_OPERATION_EXPIRED","synthetic"))
        server.eligibilityValue=server.eligibilityValue.copy(eligible=false,pendingResetCount=1,
            blockingCodes=listOf("RESET_PENDING"))
        val process=CoroutineScope(SupervisorJob()+StandardTestDispatcher(testScheduler))
        val first=WithdrawalCoordinator(server,journal,session,End(session),process,{error("new key forbidden")})
        runCurrent();first.open(75);runCurrent();first.close();runCurrent();process.cancel()
        val next=WithdrawalCoordinator(server,WithdrawalJournal(records),session,End(session),backgroundScope,{error("new key forbidden")})
        runCurrent();next.open(75);runCurrent()
        assertEquals(WithdrawalStage.Blocked,next.state.value.ui.stage)
        next.openKeyringManagement();runCurrent();assertTrue(next.consumeKeyringNavigation(75))
        assertEquals(original,journal.all().single());assertTrue(server.keys.isEmpty());assertEquals(0,server.cancels)
    }
    @Test fun eligibilityReturningAfterAccountSwitchDoesNotExposeOldBlockedAccount()=runTest {
        val journal=WithdrawalJournal(Records());val session=LoggedIn();val server=Server()
        journal.save(WithdrawalTicket(75,runId,run,"synthetic"))
        server.response=ApiResult.Fail(AppError.Server(410,"WITHDRAWAL_OPERATION_EXPIRED","synthetic"))
        server.eligibilityValue=server.eligibilityValue.copy(eligible=false,registeredKeyringCount=1,
            blockingCodes=listOf("KEYRING_REGISTERED"))
        server.beforeEligibility={session.state.value=Session.SignedIn(7,OnboardingStatus.READY,true)}
        val model=WithdrawalCoordinator(server,journal,session,End(session),backgroundScope,{runId})
        runCurrent();model.open(75);runCurrent()
        assertEquals(WithdrawalStage.Unknown,model.state.value.ui.stage)
        assertNull(model.state.value.ui.eligibility);assertNull(model.state.value.keyringOwner)
        assertTrue(server.keys.isEmpty());assertEquals(0,server.sends)
    }

    @Test fun legacyClosedUnconfirmedRecordStillRecoversWhenSignedOut()=runTest {
        val records=Records();val journal=WithdrawalJournal(records);val session=LoggedIn();val server=Server()
        journal.markSession(75)
        journal.save(WithdrawalTicket(75,runId,run,"synthetic",attempted=true,autoResume=false))
        session.state.value=Session.SignedOut;server.response=ApiResult.Ok(done)
        val model=WithdrawalCoordinator(server,journal,session,End(session),backgroundScope,{runId});runCurrent()
        assertTrue(model.state.value.visible);assertEquals(WithdrawalStage.Completed,model.state.value.ui.stage)
        assertEquals(0,server.sends)
    }
    @Test fun signedOutUnknownClosedThenRestartedStillRecoversWithoutAnotherExecute()=runTest {
        val records=Records();val journal=WithdrawalJournal(records);val session=LoggedIn();val server=Server()
        val process=CoroutineScope(SupervisorJob()+StandardTestDispatcher(testScheduler))
        val first=WithdrawalCoordinator(server,journal,session,End(session),process,{runId})
        runCurrent();first.open(75);runCurrent();first.prepare();runCurrent()
        server.response=ApiResult.Fail(AppError.Offline)
        server.sent={session.state.value=Session.SignedOut;ApiResult.Fail(AppError.Offline)}
        first.accepted(true);first.execute();runCurrent()
        assertEquals(WithdrawalStage.Unknown,first.state.value.ui.stage)
        first.close();runCurrent();assertTrue(journal.all().single().autoResume)
        assertFalse(first.state.value.visible);process.cancel()
        server.response=ApiResult.Ok(done)
        val second=WithdrawalCoordinator(server,WithdrawalJournal(records),session,End(session),backgroundScope,{runId})
        runCurrent()
        assertTrue(second.state.value.visible);assertEquals(WithdrawalStage.Completed,second.state.value.ui.stage)
        assertEquals(1,server.sends)
    }
    @Test fun keyringNavigationIsAccountScopedAndConsumedOnce()=runTest {
        val session=LoggedIn();val model=WithdrawalCoordinator(Server(),WithdrawalJournal(Records()),session,End(session),backgroundScope,{runId})
        runCurrent();model.open(75);runCurrent()
        // 닫힌 상태가 먼저 발행되면 가입 화면이 끼므로 이동 의도와 한 번에 발행되는지도 검사한다.
        val emissions=mutableListOf<WithdrawalPresentation>()
        val observer=backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect { emissions+=it } }
        model.openKeyringManagement();runCurrent()
        assertEquals(75L,emissions.first { !it.visible }.keyringOwner);observer.cancel()
        assertFalse(model.state.value.visible);assertEquals(75L,model.state.value.keyringOwner)
        assertFalse(model.consumeKeyringNavigation(7));assertTrue(model.consumeKeyringNavigation(75))
        assertFalse(model.consumeKeyringNavigation(75))
        model.open(75);runCurrent();model.openKeyringManagement();runCurrent()
        session.state.value=Session.SignedIn(7,OnboardingStatus.READY,true);runCurrent()
        assertNull(model.state.value.keyringOwner)
    }
    // 서버가 이전 준비를 만료 삭제하고 새 ID를 발급하는 실제 계약을 합성 응답으로 재현한다.
    private fun replacement() = run.copy(withdrawalId="22222222-2222-4222-8222-222222222222",
        preparedAt="2026-11-08T00:31:00Z", expiresAt="2026-11-08T01:01:00Z")

    @Test fun expiredPreparationDuringFinalClickRequiresFreshConsentAndPersistsNewId()=runTest {
        val records=Records();val journal=WithdrawalJournal(records);val server=Server();val session=LoggedIn()
        val model=WithdrawalCoordinator(server,journal,session,End(session),backgroundScope,{runId})
        runCurrent();model.open(75);runCurrent();model.prepare();runCurrent()
        server.preparedOperation=replacement();model.accepted(true);model.execute();runCurrent()
        assertEquals(replacement().withdrawalId,journal.all().single().operation?.withdrawalId)
        assertEquals(WithdrawalStage.Confirm,model.state.value.ui.stage)
        assertFalse(model.state.value.ui.canExecute);assertEquals(0,server.sends)
        model.execute();runCurrent();assertEquals(0,server.sends)
    }
    @Test fun restartRecoversDeletedExpiredAttemptOnlyAfterAuthenticatedEligibility()=runTest {
        for(code in listOf("RESOURCE_NOT_FOUND","WITHDRAWAL_OPERATION_EXPIRED")) {
            val records=Records();val journal=WithdrawalJournal(records);val session=LoggedIn();val server=Server()
            journal.save(WithdrawalTicket(75,runId,run,"synthetic",attempted=true,autoResume=true))
            server.response=ApiResult.Fail(AppError.Server(if(code=="RESOURCE_NOT_FOUND")404 else 410,code,"synthetic"))
            server.preparedOperation=replacement()
            val model=WithdrawalCoordinator(server,journal,session,End(session),backgroundScope,{runId})
            runCurrent()
            assertEquals(replacement().withdrawalId,journal.all().single().operation?.withdrawalId)
            assertFalse(journal.all().single().attempted)
            assertFalse(model.state.value.ui.canExecute);assertEquals(0,server.sends)
            // 다음 조회는 서버가 새로 발급한 현재 준비를 반환한다. 재진입 자체가 또 준비를 만들면 안 된다.
            server.response=ApiResult.Ok(replacement())
            model.close();runCurrent();model.open(75);runCurrent()
            assertEquals(listOf(runId),server.keys)
        }
    }
    @Test fun expiredAttemptWithoutActiveAccountNeverCreatesAnotherPreparation()=runTest {
        val journal=WithdrawalJournal(Records());val server=Server();val session=LoggedIn()
        journal.save(WithdrawalTicket(75,runId,run,"synthetic",attempted=true,autoResume=true))
        server.response=ApiResult.Fail(AppError.Server(404,"RESOURCE_NOT_FOUND","synthetic"));server.eligibilityFails=true
        val model=WithdrawalCoordinator(server,journal,session,End(session),backgroundScope,{runId});runCurrent()
        assertEquals(WithdrawalStage.Unknown,model.state.value.ui.stage)
        assertTrue(server.keys.isEmpty());assertTrue(journal.all().single().attempted)
    }
    @Test fun replacementWriteFailureKeepsOldRecoveryEvidence()=runTest {
        val records=Records();val journal=WithdrawalJournal(records);val server=Server();val session=LoggedIn()
        val model=WithdrawalCoordinator(server,journal,session,End(session),backgroundScope,{runId})
        runCurrent();model.open(75);runCurrent();model.prepare();runCurrent()
        val old=journal.all().single();server.preparedOperation=replacement();records.refuseWrite=true
        model.accepted(true);model.execute();runCurrent()
        assertEquals(old,journal.all().single());assertEquals(0,server.sends)
    }
    @Test fun arbitraryNewIdBeforeOldExpiryIsNotAdopted()=runTest {
        val journal=WithdrawalJournal(Records());val server=Server();val session=LoggedIn()
        val model=WithdrawalCoordinator(server,journal,session,End(session),backgroundScope,{runId})
        runCurrent();model.open(75);runCurrent();model.prepare();runCurrent()
        server.preparedOperation=run.copy(withdrawalId=replacement().withdrawalId)
        model.accepted(true);model.execute();runCurrent()
        assertEquals(runId,journal.all().single().operation?.withdrawalId)
        assertEquals(WithdrawalStage.Unknown,model.state.value.ui.stage);assertEquals(0,server.sends)
    }
    @Test fun accountStillInSignupCanCheckEligibilityWithoutPretendingReady()=runTest {
        val server=Server();val session=LoggedIn()
        session.state.value=Session.SignedIn(75,OnboardingStatus.KEYRING_REQUIRED,false)
        val model=WithdrawalCoordinator(server,WithdrawalJournal(Records()),session,End(session),backgroundScope,{runId})
        runCurrent();model.open(75);runCurrent()
        assertTrue(model.state.value.ui.canPrepare)
        assertFalse((session.state.value as Session.SignedIn).isReady)
        assertEquals(0,server.sends)
    }
    @Test fun invalidReceiptCannotCorruptExistingProtectedJournal()=runTest {
        val records=Records();val journal=WithdrawalJournal(records)
        val ticket=WithdrawalTicket(75,runId,run,"valid")
        journal.save(ticket);val original=records.saved
        assertFailsWith<IllegalStateException> { journal.save(ticket.copy(receipt="invalid\r\nreceipt")) }
        assertEquals(original,records.saved);assertEquals(ticket,journal.all().single())
    }
    @Test fun accountSwitchDuringPrepareRebindDoesNotExecuteOldAccountRequest()=runTest {
        val server=Server();val session=LoggedIn()
        val model=WithdrawalCoordinator(server,WithdrawalJournal(Records()),session,End(session),backgroundScope,{runId})
        runCurrent();model.open(75);runCurrent();model.prepare();runCurrent()
        server.beforePrepare={ session.state.value=Session.SignedIn(7,OnboardingStatus.READY,true) }
        model.accepted(true);model.execute();runCurrent()
        assertEquals(0,server.sends);assertEquals(7L,model.state.value.lastAccount)
    }
    @Test fun noHttpPrepareBeforeDiskCommitAndSameKeyAfterLostResponse()=runTest {
        val records=Records(); val server=Server(); val session=LoggedIn()
        val model=WithdrawalCoordinator(server,WithdrawalJournal(records),session,End(session),backgroundScope,{runId})
        runCurrent(); model.open(75); runCurrent()
        records.refuseWrite=true; model.prepare(); runCurrent()
        assertEquals(0,server.keys.size)
        records.refuseWrite=false
        // 저장 실패 뒤에는 새 허용 조회를 거쳐야 하며 임의로 탈퇴 완료를 만들지 않는다.
        model.reload(); runCurrent(); server.prepareFails=true; model.prepare(); runCurrent()
        assertEquals(WithdrawalStage.Unknown,model.state.value.ui.stage)
        assertFalse(model.state.value.ui.executionRequested)
        assertEquals(0,server.sends)
        server.prepareFails=false; model.reload(); runCurrent()
        assertEquals(listOf(runId,runId),server.keys)
        assertEquals(WithdrawalStage.Confirm,model.state.value.ui.stage)
        assertFalse(model.state.value.ui.canExecute)
    }

    @Test fun completionRemainsVisibleAfterSessionEndsAndPhotosStayPending()=runTest {
        val records=Records();val server=Server();val session=LoggedIn();val end=End(session)
        val model=WithdrawalCoordinator(server,WithdrawalJournal(records),session,end,backgroundScope,{runId})
        runCurrent();model.open(75);runCurrent();model.prepare();runCurrent()
        model.execute();runCurrent();assertEquals(0,server.sends)
        model.accepted(true);model.execute();runCurrent()
        assertEquals(1,server.sends);assertEquals(Session.SignedOut,session.state.value)
        assertEquals(WithdrawalStage.Completed,model.state.value.ui.stage)
        assertTrue(model.state.value.visible);assertEquals("PENDING",model.state.value.ui.operation?.externalCleanupStatus)
        assertTrue(WithdrawalJournal(records).all().single().attempted)
    }

    @Test fun restartQueriesExistingAttemptAndNeverAutomaticallyExecutes()=runTest {
        val records=Records();val journal=WithdrawalJournal(records);val session=LoggedIn();val server=Server()
        val process=CoroutineScope(SupervisorJob()+StandardTestDispatcher(testScheduler))
        val first=WithdrawalCoordinator(server,journal,session,End(session),process,{runId})
        runCurrent();first.open(75);runCurrent();first.prepare();runCurrent()
        server.sent={ApiResult.Fail(AppError.Offline)};server.response=ApiResult.Fail(AppError.Offline)
        first.accepted(true);first.execute();runCurrent();assertEquals(1,server.sends)
        process.cancel();session.state.value=Session.SignedOut;server.response=ApiResult.Ok(done)
        val recovered=WithdrawalCoordinator(server,WithdrawalJournal(records),session,End(session),backgroundScope,{error("new key forbidden")})
        runCurrent()
        assertEquals(WithdrawalStage.Completed,recovered.state.value.ui.stage)
        assertEquals(1,server.sends);assertTrue(server.reads>=2)
    }

    @Test fun pendingAfterLostExecuteRequiresFreshConsentAndCannotCancelAmbiguousExecution()=runTest {
        val server=Server();val session=LoggedIn()
        val model=WithdrawalCoordinator(server,WithdrawalJournal(Records()),session,End(session),backgroundScope,{runId})
        runCurrent();model.open(75);runCurrent();model.prepare();runCurrent()
        server.sent={ApiResult.Fail(AppError.Offline)};model.accepted(true);model.execute();runCurrent()
        assertEquals(WithdrawalStage.Confirm,model.state.value.ui.stage)
        assertFalse(model.state.value.ui.canExecute)
        model.cancel();runCurrent();assertEquals(0,server.cancels);assertEquals(1,server.sends)
        assertFalse(model.state.value.ui.canCancelPreparation)
    }

    @Test fun switchingAccountAndRestartingDoesNotAutoExposeOldReceipt()=runTest {
        val records=Records();val server=Server();val session=LoggedIn()
        val process=CoroutineScope(SupervisorJob()+StandardTestDispatcher(testScheduler))
        val model=WithdrawalCoordinator(server,WithdrawalJournal(records),session,End(session),process,{runId})
        runCurrent();model.open(75);runCurrent();model.prepare();runCurrent()
        server.sent={ApiResult.Fail(AppError.Offline)};server.response=ApiResult.Fail(AppError.Offline)
        model.accepted(true);model.execute();runCurrent()
        session.state.value=Session.SignedIn(7,OnboardingStatus.READY,true);runCurrent()
        assertEquals(7L,model.state.value.lastAccount)
        process.cancel();session.state.value=Session.SignedOut
        val recovered=WithdrawalCoordinator(server,WithdrawalJournal(records),session,End(session),backgroundScope,{error("no new request")})
        runCurrent();assertFalse(recovered.state.value.visible)
        assertEquals(1,WithdrawalJournal(records).all().size)
    }

    @Test fun localCleanupFailureRemainsRecoverableWithoutAnotherExecute()=runTest {
        val records=Records();val server=Server();val session=LoggedIn();val end=End(session)
        val model=WithdrawalCoordinator(server,WithdrawalJournal(records),session,end,backgroundScope,{runId})
        runCurrent();model.open(75);runCurrent();model.prepare();runCurrent()
        end.fail=true;model.accepted(true);model.execute();runCurrent()
        assertTrue(model.state.value.ui.localCleanupPending)
        end.fail=false;server.response=ApiResult.Ok(done);model.reload();runCurrent()
        assertFalse(model.state.value.ui.localCleanupPending);assertEquals(1,server.sends)
    }
}
