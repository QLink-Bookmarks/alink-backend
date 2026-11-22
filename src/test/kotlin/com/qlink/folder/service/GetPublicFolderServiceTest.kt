package com.qlink.folder.service

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.response.ReadResult
import com.qlink.folder.domain.Folder
import com.qlink.folder.repository.FolderRepository
import com.qlink.support.BaseServiceTest
import com.qlink.support.fixture.FolderFixture
import com.qlink.support.fixture.PublicFolderFixture
import com.qlink.support.fixture.UserFixture
import com.qlink.support.koinGet
import com.qlink.user.repository.UserRepository
import io.kotest.assertions.throwables.shouldThrowWithMessage
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

class GetPublicFolderServiceTest :
    BaseServiceTest({
        val service = koinGet<GetFolderService>()
        val folders = koinGet<FolderRepository>()
        val users = koinGet<UserRepository>()

        Given("공개 상태이고 이모지가 없는 폴더") {
            lateinit var folder: Folder

            beforeTest {
                val ownerId = users.insert(UserFixture.createRandomValidUser()).id!!
                folder =
                    folders.insert(
                        FolderFixture.createFolderWith(ownerId = ownerId, emoji = null, isPublic = true),
                    )
            }

            When("공개 폴더를 읽으면") {
                Then("저장된 ID, 이름과 nullable 이모지만 포함한다") {
                    val response = (service.getFolder(folderId = folder.id!!, viewerId = null) as ReadResult.Guest).data
                    val stored = folders.findById(folder.id!!)!!

                    response.name shouldBe stored.name
                    response.emoji shouldBe stored.emoji
                    Json.encodeToJsonElement(response).jsonObject.keys shouldBe setOf("id", "name", "emoji")
                }
            }
        }

        Given("접근할 수 없거나 존재하지 않는 폴더 주소") {
            lateinit var folderIds: List<Long>

            beforeTest {
                val ownerId = users.insert(UserFixture.createRandomValidUser()).id!!
                folderIds = PublicFolderFixture.createUnavailableIds(folders = folders, ownerId = ownerId)
            }

            When("공개 폴더를 읽으면") {
                val read: suspend (Long) -> Unit = { service.getFolder(folderId = it, viewerId = null) }

                Then("모든 주소에서 같은 공개 폴더 오류를 반환한다") {
                    for (folderId in folderIds) {
                        shouldThrowWithMessage<BusinessException>(ErrorCode.FOLDER_NOT_FOUND.message) {
                            read(folderId)
                        }
                    }
                }
            }
        }
    })
