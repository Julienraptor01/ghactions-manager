package com.dsoftware.ghmanager.psi

import com.dsoftware.ghmanager.api.GhApiRequestExecutor
import com.dsoftware.ghmanager.i18n.MessagesBundle.message
import com.dsoftware.ghmanager.toolwindow.executeSomeCoroutineTasksAndDispatchAllInvocationEvents
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInspection.ex.QuickFixWrapper
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.runInEdtAndWait
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith


//@RunInEdt(writeIntent = true)
@ExtendWith(MockKExtension::class)
class TestOutdatedVersionAnnotator {
    @MockK
    lateinit var executorMock: GhApiRequestExecutor

    private val ACTION_TEXT = """           
            jobs:
              build:
                name: Build
                runs-on: ubuntu-latest   
                steps:
                  - name: Fetch Sources
                    uses: actions/checkout@v2
            """.trimIndent()
    private val ACTION_WITH_HIGHLIGHTS = """
            jobs:
              build:
                name: Build
                runs-on: ubuntu-latest   
                steps:
                  - name: Fetch Sources
                    uses: actions/checkout@<warning descr="v2 is outdated. Latest version is v4.0.0">v2</warning>
            """.trimIndent()


    @BeforeEach
    fun setUp() {
        val node = JsonNodeFactory.instance.textNode("v4.0.0")

        executorMock.apply {
            every {
                execute<Any>(any())
            } returns node
        }
    }

    private fun createTestFixture(testName: String): CodeInsightTestFixture {
        val fixtureFactory = IdeaTestFixtureFactory.getFixtureFactory()
        val tempDirFixture = fixtureFactory.createTempDirTestFixture()

        val projectFixture = fixtureFactory.createFixtureBuilder(testName, true)
        val codeInsightFixture = fixtureFactory.createCodeInsightFixture(projectFixture.fixture, tempDirFixture)
        codeInsightFixture.setUp()
        codeInsightFixture.testDataPath = "/testData"
        return codeInsightFixture
    }


    @Test
    fun testAnnotator() {
        var actionLoaded = false
        val fixture = createTestFixture("testAnnotate")
        val psiFile = fixture.configureByText(".github/workflows/workflow1.yaml", ACTION_TEXT)
        val virtualFile = fixture.createFile(".github/workflows/workflow.yaml", ACTION_WITH_HIGHLIGHTS)
        val gitHubActionDataService = fixture.project.service<GitHubActionDataService>()
        gitHubActionDataService.requestExecutor = executorMock
        gitHubActionDataService.actionsToResolve.add("actions/checkout")
        gitHubActionDataService.whenActionsLoaded { actionLoaded = true }
        runInEdtAndWait {
            while (!actionLoaded) {
                executeSomeCoroutineTasksAndDispatchAllInvocationEvents(fixture.project)
            }
            fixture.testHighlighting(true, true, true, virtualFile)
            executeSomeCoroutineTasksAndDispatchAllInvocationEvents(fixture.project)
            val quickFixes = fixture.getAllQuickFixes(psiFile.name)
            Assertions.assertEquals(1, quickFixes.size)
            Assertions.assertEquals(
                //Update actions/checkout action to version v4
                message("ghmanager.update.action.version.fix.family.name", "actions/checkout", "v4"),
                quickFixes.first().text
            )
        }
        fixture.tearDown()
    }

    @Test
    fun testQuickFix() {
        var actionLoaded = false
        val fixture = createTestFixture("testQuickFix")
        val psiFile = fixture.configureByText(".github/workflows/workflow1.yaml", ACTION_TEXT)
        val virtualFile = fixture.tempDirFixture.createFile(".github/workflows/workflow1.yaml", ACTION_TEXT)
        val gitHubActionDataService = fixture.project.service<GitHubActionDataService>()
        gitHubActionDataService.requestExecutor = executorMock
        gitHubActionDataService.actionsToResolve.add("actions/checkout")
        gitHubActionDataService.whenActionsLoaded { actionLoaded = true }
        var action: IntentionAction? = null
        runInEdtAndWait {
            while (!actionLoaded) {
                executeSomeCoroutineTasksAndDispatchAllInvocationEvents(fixture.project)
            }
            fixture.doHighlighting()
            executeSomeCoroutineTasksAndDispatchAllInvocationEvents(fixture.project)
            val quickFixes = fixture.getAllQuickFixes(psiFile.name)
            Assertions.assertEquals(1, quickFixes.size)
            val quickFix = quickFixes.first() as QuickFixWrapper
            WriteCommandAction.runWriteCommandAction(fixture.project) {
                quickFix.invoke(fixture.project, fixture.editor, fixture.file)
            }
            Assertions.assertEquals(
                quickFix.file?.text,
                """
                jobs:
                  build:
                    name: Build
                    runs-on: ubuntu-latest   
                    steps:
                      - name: Fetch Sources
                        uses: actions/checkout@v4
                """.trimIndent()
            )
            executeSomeCoroutineTasksAndDispatchAllInvocationEvents(fixture.project)
        }
        fixture.tearDown()
    }

}