package com.qlink.folder.service

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.folder.domain.Folder
import com.qlink.folder.dto.CreateFolderRequest
import com.qlink.folder.dto.FolderPrivacyResponse
import com.qlink.folder.dto.UpdateFolderRequest
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

class GetFolderPrivacyServiceTest :
    BaseServiceTest({
        val service = koinGet<GetFolderPrivacyService>()
        val folders = koinGet<FolderRepository>()
        val members = koinGet<FolderMemberRepository>()
        val users = koinGet<UserRepository>()
        val createFolder = koinGet<CreateFolderService>()
        val updateFolder = koinGet<UpdateFolderService>()

        Given("소유자의 개인 폴더와 협업 폴더") {
            lateinit var private: Folder
            lateinit var shared: Folder
            var memberId = 0L
            var outsiderId = 0L
            lateinit var publicId: String

            beforeTest {
                publicId = FolderFixture.createPublicId()
                val ownerId = users.insert(UserFixture.createRandomValidUser()).id!!
                memberId = users.insert(UserFixture.createRandomValidUser()).id!!
                outsiderId = users.insert(UserFixture.createRandomValidUser()).id!!
                private = folders.insert(FolderFixture.createValidUnsharedFolder(ownerId))
                shared = folders.insert(FolderFixture.createSharedFolderOf(ownerId).setPublic(publicId))
                members.insertIfAbsent(FolderMemberFixture.createMember(folderId = shared.id!!, userId = memberId))
            }

            When("소유자가 공개한 적 없는 폴더의 설정을 조회하면") {
                Then("저장소의 기본값인 비공개와 null을 반환한다") {
                    val response = service.getPrivacy(loginId = private.ownerId, folderId = private.id!!)

                    response.isPublic shouldBe false
                    response.publicId shouldBe null
                    folders.findById(private.id!!)?.publicId shouldBe null
                }
            }

            When("소유자가 공개한 폴더의 설정을 조회하면") {
                Then("공개 상태와 현재 저장된 ID를 반환한다") {
                    val response = service.getPrivacy(loginId = shared.ownerId, folderId = shared.id!!)

                    response.isPublic shouldBe true
                    response.publicId shouldBe publicId
                    response.publicId shouldBe folders.findById(shared.id!!)?.publicId
                }
            }

            When("소유자가 공개 폴더의 생성과 철회 뒤 응답 대신 설정을 재조회하면") {
                val name = RandomFixture.randomSentenceWithMax(100)

                Then("저장된 상태를 그대로 반환한다") {
                    val ownerId = private.ownerId
                    val created = createFolder.createFolder(ownerId, CreateFolderRequest(name = name, isPublic = true))
                    val storedPublicId = folders.findById(created.id)?.publicId
                    val expected = FolderPrivacyResponse(isPublic = true, publicId = storedPublicId)
                    service.getPrivacy(loginId = ownerId, folderId = created.id) shouldBe expected
                    service.getPrivacy(loginId = ownerId, folderId = created.id) shouldBe expected

                    updateFolder.updateFolder(ownerId, created.id, UpdateFolderRequest(name = name, isPublic = false))
                    service.getPrivacy(loginId = ownerId, folderId = created.id) shouldBe
                        FolderPrivacyResponse(isPublic = false, publicId = null)
                    folders.findById(created.id)?.publicId shouldBe null
                }
            }

            When("존재하지 않는 폴더의 설정을 조회하면") {
                val read = suspend { service.getPrivacy(loginId = shared.ownerId, folderId = RandomFixture.randomId()) }

                Then("폴더를 찾을 수 없다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.FOLDER_NOT_FOUND.message) { read() }
                }
            }

            When("협업 멤버가 설정을 조회하면") {
                val read = suspend { service.getPrivacy(loginId = memberId, folderId = shared.id!!) }

                Then("폴더를 찾을 수 없다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.FOLDER_NOT_FOUND.message) { read() }
                }
            }

            When("외부인이 설정을 조회하면") {
                val read = suspend { service.getPrivacy(loginId = outsiderId, folderId = shared.id!!) }

                Then("폴더를 찾을 수 없다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.FOLDER_NOT_FOUND.message) { read() }
                }
            }
        }
    })
