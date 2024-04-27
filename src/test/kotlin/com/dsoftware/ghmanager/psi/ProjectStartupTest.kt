package com.dsoftware.ghmanager.psi

import com.intellij.codeInsight.navigation.openFileWithPsiElement
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.psi.PsiManager
import com.intellij.testFramework.common.initTestApplication
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.rules.ClassLevelProjectModelExtension
import com.intellij.testFramework.useProject
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension


@RunInEdt(writeIntent = true)
class ProjectStartupTest {


    companion object {
        init {
            initTestApplication()
        }

        @JvmField
        @RegisterExtension
        protected val projectRule: ClassLevelProjectModelExtension = ClassLevelProjectModelExtension()
    }

    @Test
    fun testScanWorkflowFile() {
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


//        projectResource.after()
    }


}