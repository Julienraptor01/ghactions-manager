package com.dsoftware.ghmanager.psi

import com.dsoftware.ghmanager.toolwindow.executeSomeCoroutineTasksAndDispatchAllInvocationEvents
import com.intellij.codeInsight.navigation.openFileWithPsiElement
import com.intellij.openapi.components.service
import com.intellij.psi.PsiManager
import com.intellij.testFramework.TestApplicationManager
import com.intellij.testFramework.common.initTestApplication
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.rules.ProjectModelExtension
import com.intellij.testFramework.useProject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension


@RunInEdt(writeIntent = true)
//@TestApplication
class ProjectStartupTest {
    init {
        initTestApplication()
    }

    @JvmField
    @RegisterExtension
    protected val projectRule: ProjectModelExtension = ProjectModelExtension()

    @AfterEach
    open fun tearDown() {
        executeSomeCoroutineTasksAndDispatchAllInvocationEvents(projectRule.project)
        TestApplicationManager.tearDownProjectAndApp(projectRule.project)
    }

    @Test
    fun `testScanWorkflowFile workflow-file is scanned`() {
        val content = """
            jobs:
              build:
                name: Build
                runs-on: ubuntu-latest   
                steps:
                  - name: Fetch Sources
                    uses: actions/checkout@v2
                    
            """.trimIndent()
        val workflowFile = projectRule.baseProjectDir
            .newVirtualFile(".github/workflows/workflow1.yaml", content.toByteArray())

        val project = projectRule.project
        project.useProject {
            val psiFile = PsiManager.getInstance(project).findFile(workflowFile)
            openFileWithPsiElement(psiFile!!, true, true)
            val gitHubActionDataService = project.service<GitHubActionDataService>()
            Assertions.assertEquals(1, gitHubActionDataService.actionsToResolve.size)
        }
    }

    @Test
    fun `testScanWorkflowFile action-file is scanned`() {
        val content = """
            runs:
              using: "composite"
              steps:
                - uses: mshick/add-pr-comment@v2              
                  message-id: coverage
            """.trimIndent()
        val workflowFile = projectRule.baseProjectDir
            .newVirtualFile(".github/actions/test-coverage/action.yaml", content.toByteArray())

        val project = projectRule.project
        project.useProject {
            val psiFile = PsiManager.getInstance(project).findFile(workflowFile)
            openFileWithPsiElement(psiFile!!, true, true)
            val gitHubActionDataService = project.service<GitHubActionDataService>()
            Assertions.assertEquals(1, gitHubActionDataService.actionsToResolve.size)
        }
    }


}