package com.sourcegraph.cody.chat

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.intellij.util.ui.UIUtil
import com.intellij.xml.util.XmlStringUtil
import com.jetbrains.rd.util.AtomicReference
import com.sourcegraph.cody.agent.*
import com.sourcegraph.cody.agent.protocol.*
import com.sourcegraph.cody.chat.ui.ChatPanel
import com.sourcegraph.cody.commands.CommandId
import com.sourcegraph.cody.config.RateLimitStateManager
import com.sourcegraph.cody.history.HistoryService
import com.sourcegraph.cody.history.state.ChatState
import com.sourcegraph.cody.history.state.MessageState
import com.sourcegraph.cody.ui.ChatModel
import com.sourcegraph.cody.ui.CodyModelComboboxItem
import com.sourcegraph.cody.vscode.CancellationToken
import com.sourcegraph.common.CodyBundle
import com.sourcegraph.common.UpgradeToCodyProNotification.Companion.isCodyProJetbrains
import com.sourcegraph.telemetry.GraphQlLogger
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import org.slf4j.LoggerFactory

class AgentChatSession
private constructor(
    private val project: Project,
    newSessionId: CompletableFuture<SessionId>,
    private val internalId: String = UUID.randomUUID().toString(),
    private val selectedModel: ChatModel? = null,
) : ChatSession {

  /**
   * There are situations (like startup of the chat) when we want to show UI immediately, but we
   * have not established connection with the agent yet. This is why we use CompletableFuture to
   * store the sessionId.
   */
  private val sessionId: AtomicReference<CompletableFuture<SessionId>> =
      AtomicReference(newSessionId)
  private val chatPanel: ChatPanel = ChatPanel(project, this)
  private val cancellationToken = AtomicReference(CancellationToken())
  private val messages = mutableListOf<ChatMessage>()

  init {
    cancellationToken.get().dispose()
    newSessionId.thenAccept { sessionId ->
      chatPanel.addModelDropdown(project, sessionId, selectedModel)
    }
  }

  fun restoreAgentSession(agent: CodyAgent, model: String? = null) {
    val messagesToReload =
        messages
            .toList()
            .dropWhile { it.speaker == Speaker.ASSISTANT }
            .fold(emptyList<ChatMessage>()) { acc, msg ->
              if (acc.lastOrNull()?.speaker == msg.speaker) acc else acc.plus(msg)
            }

    val modelFromState = model?.let { ChatModel.fromDisplayNameNullable(it) }

    val selectedItem =
        chatPanel.modelDropdown.selectedItem?.let {
          ChatModel.fromDisplayNameNullable(it.toString())
        }
    val restoreParams =
        ChatRestoreParams(
            //        TODO: Change in the agent handling chat restore with null model
            modelFromState?.agentName ?: selectedItem?.agentName ?: DEFAULT_MODEL.agentName,
            messagesToReload,
            UUID.randomUUID().toString())
    val newSessionId = agent.server.chatRestore(restoreParams)
    sessionId.getAndSet(newSessionId)
  }

  fun getPanel(): ChatPanel = chatPanel

  override fun getSessionId(): SessionId? = sessionId.get().getNow(null)

  fun hasSessionId(thatSessionId: SessionId): Boolean = getSessionId() == thatSessionId

  override fun getInternalId(): String = internalId

  override fun getCancellationToken(): CancellationToken = cancellationToken.get()

  private val logger = LoggerFactory.getLogger(ChatSession::class.java)

  @RequiresEdt
  override fun sendMessage(text: String, contextFiles: List<ContextFile>) {
    val displayText = XmlStringUtil.escapeString(text)
    val humanMessage =
        ChatMessage(
            Speaker.HUMAN,
            text,
            displayText,
        )

    if (messages.size == 0) {
      val model = fetchModelFromDropdown()
      setCustomModelForAgentSession(model).thenApply {
        submitMessageToAgent(humanMessage, contextFiles)
      }
      addMessageAtIndex(
          humanMessage, index = messages.count(), shouldAddBlinkingCursor = null, model)
    } else {
      submitMessageToAgent(humanMessage, contextFiles)
      addMessageAtIndex(humanMessage, index = messages.count())
    }
    val responsePlaceholder =
        ChatMessage(
            Speaker.ASSISTANT,
            text = "",
            displayText = "",
        )
    addMessageAtIndex(responsePlaceholder, index = messages.count(), shouldAddBlinkingCursor = true)
  }

  private fun submitMessageToAgent(humanMessage: ChatMessage, contextFiles: List<ContextFile>) {
    CodyAgentService.applyAgentOnBackgroundThread(project) { agent ->
      val message =
          WebviewMessage(
              command = "submit",
              text = humanMessage.actualMessage(),
              submitType = "user",
              addEnhancedContext = chatPanel.isEnhancedContextEnabled(),
              contextFiles = contextFiles)

      val request =
          agent.server.chatSubmitMessage(ChatSubmitMessageParams(sessionId.get().get(), message))

      GraphQlLogger.logCodyEvent(project, "chat-question", "submitted")

      ApplicationManager.getApplication().invokeLater {
        createCancellationToken(
            onCancel = { request.cancel(true) },
            onFinish = { GraphQlLogger.logCodyEvent(project, "chat-question", "executed") })
      }
    }
  }

  @Throws(ExecutionException::class, InterruptedException::class)
  override fun receiveMessage(extensionMessage: ExtensionMessage) {

    try {
      val lastMessage = extensionMessage.messages?.lastOrNull()
      val prevLastMessage = extensionMessage.messages?.dropLast(1)?.lastOrNull()

      if (lastMessage?.error != null && extensionMessage.isMessageInProgress == false) {

        getCancellationToken().dispose()
        val rateLimitError = lastMessage.error.toRateLimitError()
        if (rateLimitError != null) {
          RateLimitStateManager.reportForChat(project, rateLimitError)
          isCodyProJetbrains(project).thenApply { isCodyPro ->
            val text =
                when {
                  rateLimitError.upgradeIsAvailable && isCodyPro ->
                      CodyBundle.getString("chat.rate-limit-error.upgrade")
                  else -> CodyBundle.getString("chat.rate-limit-error.explain")
                }

            addErrorMessageAsAssistantMessage(text, index = extensionMessage.messages.count() - 1)
          }
        } else {
          // Currently we ignore other kind of errors like context window limit reached
        }
      } else {
        RateLimitStateManager.invalidateForChat(project)
        if (extensionMessage.messages?.isNotEmpty() == true &&
            extensionMessage.isMessageInProgress == false) {
          getCancellationToken().dispose()
        } else {

          if (extensionMessage.chatID != null) {
            if (prevLastMessage != null) {
              if (lastMessage?.contextFiles != messages.lastOrNull()?.contextFiles) {
                val index = extensionMessage.messages.count() - 2
                ApplicationManager.getApplication().invokeLater {
                  addMessageAtIndex(prevLastMessage, index)
                }
              }
            }

            if (lastMessage?.text != null) {
              val index = extensionMessage.messages.count() - 1
              ApplicationManager.getApplication().invokeLater {
                addMessageAtIndex(lastMessage, index)
              }
            }
          }
        }
      }
    } catch (error: Exception) {
      getCancellationToken().abort()
      logger.error(CodyBundle.getString("chat-session.error-title"), error)
    }
  }

  private fun addErrorMessageAsAssistantMessage(stringMessage: String, index: Int) {
    UIUtil.invokeLaterIfNeeded {
      addMessageAtIndex(ChatMessage(Speaker.ASSISTANT, stringMessage), index)
    }
  }

  override fun sendWebviewMessage(message: WebviewMessage) {
    CodyAgentService.applyAgentOnBackgroundThread(project) { agent ->
      agent.server.webviewReceiveMessage(
          WebviewReceiveMessageParams(this.sessionId.get().get(), message))
    }
  }

  fun receiveWebviewExtensionMessage(message: ExtensionMessage) {
    when (message.type) {
      ExtensionMessage.Type.USER_CONTEXT_FILES -> {
        if (message.context is List<*>) {
          this.chatPanel.promptPanel.setContextFilesSelector(message.context)
        }
      }
      else -> {
        logger.warn(String.format("CodyToolWindowContent: unknown message type: %s", message.type))
      }
    }
  }

  @RequiresEdt
  private fun fetchModelFromDropdown(): ChatModel {
    return if (chatPanel.modelDropdown.selectedItem != null) {
      val selectedItem = chatPanel.modelDropdown.selectedItem
      val displayModelName = (selectedItem as CodyModelComboboxItem).name
      ChatModel.fromDisplayName(displayModelName)
    } else {
      ChatModel.UNKNOWN_MODEL
    }
  }

  private fun setCustomModelForAgentSession(model: ChatModel): CompletableFuture<Void> {
    return sessionId.get().thenAccept { sessionId ->
      CodyAgentService.applyAgentOnBackgroundThread(project) { agent ->
        agent.server.webviewReceiveMessage(
            WebviewReceiveMessageParams(
                sessionId, WebviewMessage(command = "chatModel", model = model.agentName)))
      }
    }
  }

  @RequiresEdt
  private fun addMessageAtIndex(
      message: ChatMessage,
      index: Int,
      shouldAddBlinkingCursor: Boolean? = null,
      model: ChatModel? = null
  ) {
    val messageToUpdate = messages.getOrNull(index)
    if (messageToUpdate != null) {
      messages[index] = message
    } else {
      messages.add(message)
    }
    chatPanel.addOrUpdateMessage(
        message,
        index,
        shouldAddBlinkingCursor = shouldAddBlinkingCursor ?: message.actualMessage().isBlank())
    HistoryService.getInstance(project).updateChatMessages(internalId, messages, model?.displayName)
  }

  @RequiresEdt
  private fun createCancellationToken(onCancel: () -> Unit, onFinish: () -> Unit) {
    val newCancellationToken = CancellationToken()
    newCancellationToken.onCancellationRequested { onCancel() }
    newCancellationToken.onFinished { onFinish() }
    cancellationToken.getAndSet(newCancellationToken).abort()
    chatPanel.registerCancellationToken(newCancellationToken)
  }

  companion object {
    private val logger = LoggerFactory.getLogger(AgentChatSession::class.java)
    private val DEFAULT_MODEL = ChatModel.ANTHROPIC_CLAUDE_2

    @RequiresEdt
    fun createNew(project: Project): AgentChatSession {
      val sessionId = createNewPanel(project) { it.server.chatNew() }
      val chatSession = AgentChatSession(project, sessionId)
      AgentChatSessionService.getInstance(project).addSession(chatSession)
      return chatSession
    }

    @RequiresEdt
    fun createFromCommand(project: Project, commandId: CommandId): AgentChatSession {
      val sessionId =
          createNewPanel(project) { agent: CodyAgent ->
            when (commandId) {
              CommandId.Explain -> agent.server.commandsExplain()
              CommandId.Smell -> agent.server.commandsSmell()
              CommandId.Test -> agent.server.commandsTest()
            }
          }

      ApplicationManager.getApplication().executeOnPooledThread {
        GraphQlLogger.logCodyEvent(project, "command:${commandId.displayName}", "submitted")
      }

      val chatSession = AgentChatSession(project, sessionId)

      chatSession.createCancellationToken(
          onCancel = { chatSession.sendWebviewMessage(WebviewMessage(command = "abort")) },
          onFinish = {
            GraphQlLogger.logCodyEvent(project, "command:${commandId.displayName}", "executed")
          })

      chatSession.addMessageAtIndex(
          ChatMessage(
              Speaker.HUMAN,
              commandId.displayName,
          ),
          chatSession.messages.count())
      chatSession.addMessageAtIndex(
          ChatMessage(
              Speaker.ASSISTANT,
              text = "",
              displayText = "",
          ),
          chatSession.messages.count())
      AgentChatSessionService.getInstance(project).addSession(chatSession)
      return chatSession
    }

    @RequiresEdt
    fun createFromState(project: Project, state: ChatState): AgentChatSession {
      val sessionId = createNewPanel(project) { it.server.chatNew() }
      val selectedModel = state.model?.let { ChatModel.fromDisplayName(it) }
      val chatSession = AgentChatSession(project, sessionId, state.internalId!!, selectedModel)
      chatSession.chatPanel.modelDropdown.selectedItem =
          selectedModel?.let { CodyModelComboboxItem(it.icon, it.displayName) }
      state.messages.forEachIndexed { index, message ->
        val parsed =
            when (val speaker = message.speaker) {
              MessageState.SpeakerState.HUMAN -> Speaker.HUMAN
              MessageState.SpeakerState.ASSISTANT -> Speaker.ASSISTANT
              else -> error("unrecognized speaker $speaker")
            }
        val chatMessage = ChatMessage(speaker = parsed, message.text)
        chatSession.messages.add(chatMessage)
        chatSession.chatPanel.addOrUpdateMessage(
            chatMessage, index, shouldAddBlinkingCursor = false)
      }
      CodyAgentService.applyAgentOnBackgroundThread(project) { agent ->
        chatSession.restoreAgentSession(agent, state.model)
      }
      AgentChatSessionService.getInstance(project).addSession(chatSession)
      return chatSession
    }

    private fun createNewPanel(
        project: Project,
        newPanelAction: (CodyAgent) -> CompletableFuture<String>
    ): CompletableFuture<SessionId> {
      val sessionId = CompletableFuture<SessionId>()
      CodyAgentService.applyAgentOnBackgroundThread(project) { agent ->
        try {
          sessionId.complete(newPanelAction(agent).get())
        } catch (e: ExecutionException) {
          // Agent cannot gracefully recover when connection is lost, we need to restart it
          // TODO https://github.com/sourcegraph/jetbrains/issues/306
          logger.warn("Failed to load new chat, restarting agent", e)
          CodyAgentService.getInstance(project).restartAgent(project)
          Thread.sleep(5000)
          createNewPanel(project, newPanelAction)
        }
      }
      return sessionId
    }
  }
}