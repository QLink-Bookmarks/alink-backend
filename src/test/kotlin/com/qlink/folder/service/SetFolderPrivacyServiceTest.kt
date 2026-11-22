package com.qlink.folder.service

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.folder.domain.Folder
import com.qlink.folder.dto.CreateFolderRequest
import com.qlink.folder.dto.FolderPrivacyResponse
import com.qlink.folder.repository.FolderRepository
import com.qlink.foldermember.repository.FolderMemberRepository
import com.qlink.support.BaseServiceTest
import com.qlink.support.fixture.FolderFixture
import com.qlink.support.fixture.FolderMemberFixture
import com.qlink.support.fixture.RandomFixture
import com.qlink.support.fixture.UserFixture
import com.qlink.support.koinGet
import com.qlink.user.repository.UserRepository
import io.kotest.assertions.throwables.shouldThrowWithMessage
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class SetFolderPrivacyServiceTest :
    BaseServiceTest({
        val service = koinGet<UpdateFolderService>()
        val folders = koinGet<FolderRepository>()
        val members = koinGet<FolderMemberRepository>()
        val users = koinGet<UserRepository>()
        val createFolder = koinGet<CreateFolderService>()

        Given("소유자의 개인 폴더와 협업 폴더") {
            lateinit var private: Folder
            lateinit var shared: Folder
            var memberId = 0L
            var outsiderId = 0L
            val published = true

            beforeTest {
                val ownerId = users.insert(UserFixture.createRandomValidUser()).id!!
                memberId = users.insert(UserFixture.createRandomValidUser()).id!!
                outsiderId = users.insert(UserFixture.createRandomValidUser()).id!!
                private = folders.insert(FolderFixture.createValidUnsharedFolder(ownerId))
                shared = folders.insert(FolderFixture.createSharedFolderOf(ownerId).setPublic(published))
                members.insertIfAbsent(FolderMemberFixture.createMember(folderId = shared.id!!, userId = memberId))
            }

            When("소유자가 비공개 폴더에 비공개를 다시 지정하면") {
                Then("공개 시각은 null을 유지한다") {
                    val response = service.setPrivacy(isPublic = false, loginId = private.ownerId, folderId = private.id!!)

                    response.turnsPublicAt shouldBe null
                    response.turnsPublicAt shouldBe null
                    folders.findById(private.id!!)?.turnsPublicAt shouldBe null
                }
            }

            When("소유자가 이미 공개한 폴더를 다시 공개하면") {
                Then("기존 공개 시각을 반환한다") {
                    val response = service.setPrivacy(isPublic = true, loginId = shared.ownerId, folderId = shared.id!!)

                    response.turnsPublicAt shouldNotBe null
                    response.turnsPublicAt shouldBe shared.turnsPublicAt
                    response.turnsPublicAt shouldBe folders.findById(shared.id!!)?.turnsPublicAt
                }
            }

            When("소유자가 공개 폴더를 생성한 뒤 공개 유지와 철회를 요청하면") {
                val name = RandomFixture.randomSentenceWithMax(100)

                Then("저장된 상태를 그대로 반환한다") {
                    val ownerId = private.ownerId
                    val created = createFolder.createFolder(ownerId, CreateFolderRequest(name = name, isPublic = true))
                    val storedPublic = folders.findById(created.id)!!.turnsPublicAt
                    val expected = FolderPrivacyResponse(turnsPublicAt = storedPublic)
                    service.setPrivacy(isPublic = true, loginId = ownerId, folderId = created.id) shouldBe expected
                    service.setPrivacy(isPublic = true, loginId = ownerId, folderId = created.id) shouldBe expected

                    service.setPrivacy(isPublic = false, loginId = ownerId, folderId = created.id) shouldBe
                        FolderPrivacyResponse(turnsPublicAt = null)
                    folders.findById(created.id)?.turnsPublicAt shouldBe null
                }
            }

            When("존재하지 않는 폴더의 설정을 변경하면") {
                val change = suspend { service.setPrivacy(isPublic = true, loginId = shared.ownerId, folderId = RandomFixture.randomId()) }

                Then("폴더를 찾을 수 없다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.FOLDER_NOT_FOUND.message) { change() }
                }
            }

            When("협업 멤버가 설정을 변경하면") {
                val change = suspend { service.setPrivacy(isPublic = true, loginId = memberId, folderId = shared.id!!) }

                Then("폴더를 찾을 수 없다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.FOLDER_NOT_FOUND.message) { change() }
                }
            }

            When("외부인이 설정을 변경하면") {
                val change = suspend { service.setPrivacy(isPublic = true, loginId = outsiderId, folderId = shared.id!!) }

                Then("폴더를 찾을 수 없다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.FOLDER_NOT_FOUND.message) { change() }
                }
            }
        }
    })
