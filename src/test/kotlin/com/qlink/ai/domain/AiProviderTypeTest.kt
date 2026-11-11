package com.qlink.ai.domain

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class AiProviderTypeTest :
    BehaviorSpec({
        Given("업체별 공식 주소 규칙") {
            When("GEMINI https://generativelanguage.googleapis.com/v1-beta/models_2") {
                val action = { AiProviderType.GEMINI.isAllowedBaseUrl("https://generativelanguage.googleapis.com/v1-beta/models_2") }
                Then("규칙에 맞게 처리한다") {
                    action() shouldBe true
                }
            }
            When("GEMINI https://generativelanguage.googleapis.com:443/") {
                val action = { AiProviderType.GEMINI.isAllowedBaseUrl("https://generativelanguage.googleapis.com:443/") }
                Then("규칙에 맞게 처리한다") {
                    action() shouldBe true
                }
            }
            When("GEMINI http://generativelanguage.googleapis.com") {
                val action = { AiProviderType.GEMINI.isAllowedBaseUrl("http://generativelanguage.googleapis.com") }
                Then("규칙에 맞게 처리한다") {
                    action() shouldBe false
                }
            }
            When("GEMINI https://generativelanguage.googleapis.com.evil.example") {
                val action = { AiProviderType.GEMINI.isAllowedBaseUrl("https://generativelanguage.googleapis.com.evil.example") }
                Then("규칙에 맞게 처리한다") {
                    action() shouldBe false
                }
            }
            When("GEMINI https://api.other.example") {
                val action = { AiProviderType.GEMINI.isAllowedBaseUrl("https://api.other.example") }
                Then("규칙에 맞게 처리한다") {
                    action() shouldBe false
                }
            }
            When("GEMINI https://generativelanguage.googleapis.com:444") {
                val action = { AiProviderType.GEMINI.isAllowedBaseUrl("https://generativelanguage.googleapis.com:444") }
                Then("규칙에 맞게 처리한다") {
                    action() shouldBe false
                }
            }
            When("GEMINI https://user@generativelanguage.googleapis.com") {
                val action = { AiProviderType.GEMINI.isAllowedBaseUrl("https://user@generativelanguage.googleapis.com") }
                Then("규칙에 맞게 처리한다") {
                    action() shouldBe false
                }
            }
            When("GEMINI https://generativelanguage.googleapis.com?key=x") {
                val action = { AiProviderType.GEMINI.isAllowedBaseUrl("https://generativelanguage.googleapis.com?key=x") }
                Then("규칙에 맞게 처리한다") {
                    action() shouldBe false
                }
            }
            When("GEMINI https://generativelanguage.googleapis.com#x") {
                val action = { AiProviderType.GEMINI.isAllowedBaseUrl("https://generativelanguage.googleapis.com#x") }
                Then("규칙에 맞게 처리한다") {
                    action() shouldBe false
                }
            }
            When("GEMINI https://generativelanguage.googleapis.com/../v1") {
                val action = { AiProviderType.GEMINI.isAllowedBaseUrl("https://generativelanguage.googleapis.com/../v1") }
                Then("규칙에 맞게 처리한다") {
                    action() shouldBe false
                }
            }
            When("GEMINI https://generativelanguage.googleapis.com/./v1") {
                val action = { AiProviderType.GEMINI.isAllowedBaseUrl("https://generativelanguage.googleapis.com/./v1") }
                Then("규칙에 맞게 처리한다") {
                    action() shouldBe false
                }
            }
            When("GEMINI https://generativelanguage.googleapis.com/%2fsecret") {
                val action = { AiProviderType.GEMINI.isAllowedBaseUrl("https://generativelanguage.googleapis.com/%2fsecret") }
                Then("규칙에 맞게 처리한다") {
                    action() shouldBe false
                }
            }
            When("OPENAI https://api.openai.com/v1-beta/models_2") {
                val action = { AiProviderType.OPENAI.isAllowedBaseUrl("https://api.openai.com/v1-beta/models_2") }
                Then("규칙에 맞게 처리한다") {
                    action() shouldBe true
                }
            }
            When("OPENAI https://api.openai.com:443/") {
                val action = { AiProviderType.OPENAI.isAllowedBaseUrl("https://api.openai.com:443/") }
                Then("규칙에 맞게 처리한다") {
                    action() shouldBe true
                }
            }
            When("OPENAI http://api.openai.com") {
                val action = { AiProviderType.OPENAI.isAllowedBaseUrl("http://api.openai.com") }
                Then("규칙에 맞게 처리한다") {
                    action() shouldBe false
                }
            }
            When("OPENAI https://api.openai.com.evil.example") {
                val action = { AiProviderType.OPENAI.isAllowedBaseUrl("https://api.openai.com.evil.example") }
                Then("규칙에 맞게 처리한다") {
                    action() shouldBe false
                }
            }
            When("OPENAI https://api.other.example") {
                val action = { AiProviderType.OPENAI.isAllowedBaseUrl("https://api.other.example") }
                Then("규칙에 맞게 처리한다") {
                    action() shouldBe false
                }
            }
            When("OPENAI https://api.openai.com:444") {
                val action = { AiProviderType.OPENAI.isAllowedBaseUrl("https://api.openai.com:444") }
                Then("규칙에 맞게 처리한다") {
                    action() shouldBe false
                }
            }
            When("OPENAI https://user@api.openai.com") {
                val action = { AiProviderType.OPENAI.isAllowedBaseUrl("https://user@api.openai.com") }
                Then("규칙에 맞게 처리한다") {
                    action() shouldBe false
                }
            }
            When("OPENAI https://api.openai.com?key=x") {
                val action = { AiProviderType.OPENAI.isAllowedBaseUrl("https://api.openai.com?key=x") }
                Then("규칙에 맞게 처리한다") {
                    action() shouldBe false
                }
            }
            When("OPENAI https://api.openai.com#x") {
                val action = { AiProviderType.OPENAI.isAllowedBaseUrl("https://api.openai.com#x") }
                Then("규칙에 맞게 처리한다") {
                    action() shouldBe false
                }
            }
            When("OPENAI https://api.openai.com/../v1") {
                val action = { AiProviderType.OPENAI.isAllowedBaseUrl("https://api.openai.com/../v1") }
                Then("규칙에 맞게 처리한다") {
                    action() shouldBe false
                }
            }
            When("OPENAI https://api.openai.com/./v1") {
                val action = { AiProviderType.OPENAI.isAllowedBaseUrl("https://api.openai.com/./v1") }
                Then("규칙에 맞게 처리한다") {
                    action() shouldBe false
                }
            }
            When("OPENAI https://api.openai.com/%2fsecret") {
                val action = { AiProviderType.OPENAI.isAllowedBaseUrl("https://api.openai.com/%2fsecret") }
                Then("규칙에 맞게 처리한다") {
                    action() shouldBe false
                }
            }
            When("CLAUDE https://api.anthropic.com/v1-beta/models_2") {
                val action = { AiProviderType.CLAUDE.isAllowedBaseUrl("https://api.anthropic.com/v1-beta/models_2") }
                Then("규칙에 맞게 처리한다") {
                    action() shouldBe true
                }
            }
            When("CLAUDE https://api.anthropic.com:443/") {
                val action = { AiProviderType.CLAUDE.isAllowedBaseUrl("https://api.anthropic.com:443/") }
                Then("규칙에 맞게 처리한다") {
                    action() shouldBe true
                }
            }
            When("CLAUDE http://api.anthropic.com") {
                val action = { AiProviderType.CLAUDE.isAllowedBaseUrl("http://api.anthropic.com") }
                Then("규칙에 맞게 처리한다") {
                    action() shouldBe false
                }
            }
            When("CLAUDE https://api.anthropic.com.evil.example") {
                val action = { AiProviderType.CLAUDE.isAllowedBaseUrl("https://api.anthropic.com.evil.example") }
                Then("규칙에 맞게 처리한다") {
                    action() shouldBe false
                }
            }
            When("CLAUDE https://api.other.example") {
                val action = { AiProviderType.CLAUDE.isAllowedBaseUrl("https://api.other.example") }
                Then("규칙에 맞게 처리한다") {
                    action() shouldBe false
                }
            }
            When("CLAUDE https://api.anthropic.com:444") {
                val action = { AiProviderType.CLAUDE.isAllowedBaseUrl("https://api.anthropic.com:444") }
                Then("규칙에 맞게 처리한다") {
                    action() shouldBe false
                }
            }
            When("CLAUDE https://user@api.anthropic.com") {
                val action = { AiProviderType.CLAUDE.isAllowedBaseUrl("https://user@api.anthropic.com") }
                Then("규칙에 맞게 처리한다") {
                    action() shouldBe false
                }
            }
            When("CLAUDE https://api.anthropic.com?key=x") {
                val action = { AiProviderType.CLAUDE.isAllowedBaseUrl("https://api.anthropic.com?key=x") }
                Then("규칙에 맞게 처리한다") {
                    action() shouldBe false
                }
            }
            When("CLAUDE https://api.anthropic.com#x") {
                val action = { AiProviderType.CLAUDE.isAllowedBaseUrl("https://api.anthropic.com#x") }
                Then("규칙에 맞게 처리한다") {
                    action() shouldBe false
                }
            }
            When("CLAUDE https://api.anthropic.com/../v1") {
                val action = { AiProviderType.CLAUDE.isAllowedBaseUrl("https://api.anthropic.com/../v1") }
                Then("규칙에 맞게 처리한다") {
                    action() shouldBe false
                }
            }
            When("CLAUDE https://api.anthropic.com/./v1") {
                val action = { AiProviderType.CLAUDE.isAllowedBaseUrl("https://api.anthropic.com/./v1") }
                Then("규칙에 맞게 처리한다") {
                    action() shouldBe false
                }
            }
            When("CLAUDE https://api.anthropic.com/%2fsecret") {
                val action = { AiProviderType.CLAUDE.isAllowedBaseUrl("https://api.anthropic.com/%2fsecret") }
                Then("규칙에 맞게 처리한다") {
                    action() shouldBe false
                }
            }
        }
    })
