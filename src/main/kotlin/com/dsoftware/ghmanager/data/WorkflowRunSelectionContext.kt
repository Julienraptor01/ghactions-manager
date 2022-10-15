package com.dsoftware.ghmanager.data

import WorkflowRunJob
import com.dsoftware.ghmanager.api.model.GitHubWorkflowRun
import com.google.common.cache.CacheBuilder
import com.intellij.collaboration.ui.SimpleEventListener
import com.intellij.collaboration.ui.SingleValueModel
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.util.EventDispatcher
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.concurrency.annotations.RequiresEdt
import org.jetbrains.plugins.github.api.GHRepositoryPath
import org.jetbrains.plugins.github.api.GithubApiRequest
import org.jetbrains.plugins.github.api.GithubApiRequestExecutor
import org.jetbrains.plugins.github.api.GithubServerPath
import java.util.EventListener
import java.util.concurrent.TimeUnit
import javax.swing.ListModel
import kotlin.properties.Delegates


class WorkflowDataLoader(
    private val requestExecutor: GithubApiRequestExecutor
) : Disposable {

    private var isDisposed = false
    private val cache = CacheBuilder.newBuilder()
        .removalListener<String, DataProvider<*>> {
            runInEdt { invalidationEventDispatcher.multicaster.providerChanged(it.key!!) }
        }
        .maximumSize(200)
        .build<String, DataProvider<*>>()


    private val invalidationEventDispatcher = EventDispatcher.create(DataInvalidatedListener::class.java)

    fun getLogsDataProvider(workflowRun: GitHubWorkflowRun): WorkflowRunLogsDataProvider {
        if (isDisposed) throw IllegalStateException("Already disposed")

        return cache.get(workflowRun.logs_url) {
            WorkflowRunLogsDataProvider(progressManager, requestExecutor, workflowRun.logs_url)
        } as WorkflowRunLogsDataProvider

    }

    fun getJobsDataProvider(workflowRun: GitHubWorkflowRun): WorkflowRunJobsDataProvider {
        if (isDisposed) throw IllegalStateException("Already disposed")

        return cache.get(workflowRun.jobs_url) {
            WorkflowRunJobsDataProvider(progressManager, requestExecutor, workflowRun.jobs_url)
        } as WorkflowRunJobsDataProvider
    }

    fun <T> createDataProvider(request: GithubApiRequest<T>): DataProvider<T> {
        if (isDisposed) throw IllegalStateException("Already disposed")
        return DefaultDataProvider(progressManager, requestExecutor, request)
    }

    @RequiresEdt
    fun invalidateAllData() {
        LOG.debug("All cache invalidated")
        cache.invalidateAll()
    }

    private interface DataInvalidatedListener : EventListener {
        fun providerChanged(url: String)
    }

    fun addInvalidationListener(disposable: Disposable, listener: (String) -> Unit) =
        invalidationEventDispatcher.addListener(object : DataInvalidatedListener {
            override fun providerChanged(url: String) {
                listener(url)
            }
        }, disposable)

    override fun dispose() {
        LOG.debug("Disposing...")
        invalidateAllData()
        isDisposed = true
    }

    companion object {
        private val LOG = logger<WorkflowDataLoader>()
        private val progressManager = ProgressManager.getInstance()
    }
}

data class RepositoryCoordinates(val serverPath: GithubServerPath, val repositoryPath: GHRepositoryPath) {

    override fun toString(): String {
        return "$serverPath/$repositoryPath"
    }
}

open class ListSelectionHolder<T> {

    @get:RequiresEdt
    @set:RequiresEdt
    var selection: T? by Delegates.observable(null) { _, _, _ ->
        selectionChangeEventDispatcher.multicaster.eventOccurred()
    }

    private val selectionChangeEventDispatcher = EventDispatcher.create(SimpleEventListener::class.java)

    @RequiresEdt
    fun addSelectionChangeListener(disposable: Disposable, listener: () -> Unit) =
        SimpleEventListener.addDisposableListener(selectionChangeEventDispatcher, disposable, listener)
}

class WorkflowRunListSelectionHolder : ListSelectionHolder<GitHubWorkflowRun>()
class JobListSelectionHolder : ListSelectionHolder<WorkflowRunJob>()


class WorkflowRunSelectionContext internal constructor(
    parentDisposable: Disposable,
    val runsListModel: ListModel<GitHubWorkflowRun>,
    val dataLoader: WorkflowDataLoader,
    val runsListLoader: WorkflowRunListLoader,
    val runSelectionHolder: WorkflowRunListSelectionHolder = WorkflowRunListSelectionHolder(),
    val jobSelectionHolder: JobListSelectionHolder = JobListSelectionHolder(),
) : Disposable {
    val jobDataProviderLoadModel: SingleValueModel<WorkflowRunJobsDataProvider?> = SingleValueModel(null)
    val logDataProviderLoadModel: SingleValueModel<WorkflowRunLogsDataProvider?> = SingleValueModel(null)

    init {
        runSelectionHolder.addSelectionChangeListener(parentDisposable) {
            LOG.debug("runSelectionHolder selection change listener")
            setNewJobsProvider()
            setNewLogProvider()
        }
        dataLoader.addInvalidationListener(parentDisposable) {
            LOG.debug("invalidation listener")
            setNewJobsProvider()
            setNewLogProvider()
        }
        val scheduler = AppExecutorUtil.getAppScheduledExecutorService()
        scheduler.scheduleWithFixedDelay({
            if (workflowRun?.status == "in_progress") {
                jobsDataProvider?.reload()
                logsDataProvider?.reload()
            }
        }, 1, frequency, TimeUnit.SECONDS)
    }

    private fun setNewJobsProvider() {
        val oldJobDataProviderModelValue = jobDataProviderLoadModel.value
        if (oldJobDataProviderModelValue != null && jobsDataProvider != null && oldJobDataProviderModelValue.url() != jobsDataProvider?.url()) {
            jobDataProviderLoadModel.value = null
        }
        jobDataProviderLoadModel.value = jobsDataProvider
    }

    private fun setNewLogProvider() {
        val oldValue = logDataProviderLoadModel.value
        if (oldValue != null && logsDataProvider != null && oldValue.url() != logsDataProvider?.url()) {
            logDataProviderLoadModel.value = null
        }
        logDataProviderLoadModel.value = logsDataProvider
    }

    fun resetAllData() {
        LOG.debug("resetAllData")
        runsListLoader.reset()
        runsListLoader.loadMore()
        dataLoader.invalidateAllData()
    }

    private val workflowRun: GitHubWorkflowRun?
        get() = runSelectionHolder.selection

    val logsDataProvider: WorkflowRunLogsDataProvider?
        get() = workflowRun?.let { dataLoader.getLogsDataProvider(it) }
    val jobsDataProvider: WorkflowRunJobsDataProvider?
        get() = workflowRun?.let { dataLoader.getJobsDataProvider(it) }

    companion object {
        private val LOG = logger<WorkflowRunSelectionContext>()
        private const val frequency: Long = 30
    }

    override fun dispose() {
    }
}