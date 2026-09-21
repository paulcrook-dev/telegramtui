package com.telegramtui.service;

import com.telegramtui.model.ChatModel;
import com.telegramtui.model.MessageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

// Sends desktop notifications for incoming messages using notify-send (libnotify).
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class.getSimpleName());
    private static final int MAX_BODY_LENGTH = 500;

    private final boolean enabled;
    private final ChatService chatService;

    public NotificationService(boolean enabled, ChatService chatService) {
        this.enabled = enabled;
        this.chatService = chatService;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void notifyNewMessage(MessageModel message) {
        if (!enabled) return;
        if (message == null || message.isOutgoing()) return;
        if (isChatMuted(message.chatId())) return;

        fire(chatTitle(message), senderAndText(message));
    }

    private boolean isChatMuted(long chatId) {
        if (chatService == null) return false;
        ChatModel chat = chatService.getChat(chatId);
        return chat != null && chat.isMuted();
    }

    private String chatTitle(MessageModel message) {
        if (chatService != null) {
            ChatModel chat = chatService.getChat(message.chatId());
            if (chat != null && chat.title() != null && !chat.title().isBlank()) {
                return chat.title();
            }
        }
        return sender(message);
    }

    private String sender(MessageModel message) {
        String name = message.senderName();
        return (name == null || name.isBlank()) ? "Unknown" : name;
    }

    private String senderAndText(MessageModel message) {
        String text = message.text();
        if (text == null || text.isBlank()) {
            text = (message.contentType() == null || message.contentType().isBlank())
                    ? "New message"
                    : message.contentType();
        }
        text = text.replaceAll("\\s+", " ").trim();
        if (text.length() > MAX_BODY_LENGTH) {
            text = text.substring(0, MAX_BODY_LENGTH) + "…";
        }
        return sender(message) + ": " + text;
    }

    private void fire(String title, String body) {
        try {
            List<String> cmd = List.of("notify-send", "--app-name=TelegramTUI", title, body);
            new ProcessBuilder(cmd).start();
        } catch (IOException e) {
            log.warn("Could not send notification via notify-send", e);
        }
    }
}