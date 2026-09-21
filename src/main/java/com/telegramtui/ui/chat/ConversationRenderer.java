package com.telegramtui.ui.chat;

import com.googlecode.lanterna.graphics.TextGraphics;
import com.telegramtui.model.ChatModel;
import com.telegramtui.model.MessageModel;
import com.telegramtui.service.MessageService;
import com.telegramtui.ui.common.CatppuccinMocha;
import com.telegramtui.ui.common.TextRenderer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

class ConversationRenderer {

    record RenderResult(int viewportTop, int selectedMsgIndex, int maxViewport) {}

    private ConversationRenderer() {}

    static RenderResult renderMessages(TextGraphics g, int x, int y, int w, int h,
                                       ChatModel chat, int selectedMsgIndex, int viewportTop,
                                       MessageService messageService, int scrollBy,
                                       boolean autoFollow) {
        g.setBackgroundColor(CatppuccinMocha.BASE);
        g.setForegroundColor(CatppuccinMocha.BASE);
        for (int r = 0; r < h; r++) {
            g.putString(x, y + r, " ".repeat(w));
        }

        List<MessageModel> messages = messageService.getMessages(chat.id());
        if (messages.isEmpty()) {
            g.setForegroundColor(CatppuccinMocha.OVERLAY0);
            String msg = "Loading...";
            g.putString(x + Math.max(0, (w - msg.length()) / 2), y + h / 2, msg);
            return new RenderResult(viewportTop, selectedMsgIndex, 0);
        }

        boolean isGroupChat = chat.chatType() != null
                && chat.chatType().toLowerCase().contains("group");

        // build a lookup map so we can find quoted messages quickly
        Map<Long, MessageModel> msgById = new HashMap<>();
        for (MessageModel m : messages) msgById.put(m.id(), m);

        List<List<MessageModel>> groups = MessageRenderer.groupBySender(messages);
        int[] heights = new int[groups.size()];
        for (int i = 0; i < groups.size(); i++) {
            heights[i] = MessageRenderer.groupHeight(groups.get(i), w, isGroupChat, msgById);
        }

        // cumulative heights help us figure out where each group starts
        int[] cum = new int[groups.size() + 1];
        for (int i = 0; i < groups.size(); i++) cum[i + 1] = cum[i] + heights[i];
        int totalHeight = cum[groups.size()];
        int maxViewport = Math.max(0, totalHeight - h);

        // find which group the selected message belongs to
        int selGroupIdx = -1;
        int selPosInGroup = -1;
        if (selectedMsgIndex >= 0 && selectedMsgIndex < messages.size()) {
            int count = 0;
            for (int i = 0; i < groups.size(); i++) {
                int groupSize = groups.get(i).size();
                if (selectedMsgIndex < count + groupSize) {
                    selGroupIdx = i;
                    selPosInGroup = selectedMsgIndex - count;
                    break;
                }
                count += groupSize;
            }
        }

        if (scrollBy != 0) {
            // cursor-anchored scroll: move the cursor by scrollBy messages and slide
            // the content so the cursor stays at the same screen row
            int effectiveCursorIdx = selectedMsgIndex < 0 ? messages.size() - 1 : selectedMsgIndex;
            int newCursorIdx = Math.max(0, Math.min(messages.size() - 1,
                    effectiveCursorIdx - scrollBy));

            int[] oldRows = findMessageRows(messages, groups, cum, effectiveCursorIdx, w, msgById);
            int[] newRows = findMessageRows(messages, groups, cum, newCursorIdx, w, msgById);

            int anchorRow = oldRows != null ? oldRows[0] - viewportTop : 0;
            if (newRows != null) viewportTop = newRows[0] - anchorRow;

            selectedMsgIndex = newCursorIdx;

            // relocate the selected group for drawing
            selGroupIdx = -1;
            selPosInGroup = -1;
            int count = 0;
            for (int i = 0; i < groups.size(); i++) {
                int groupSize = groups.get(i).size();
                if (selectedMsgIndex < count + groupSize) {
                    selGroupIdx = i;
                    selPosInGroup = selectedMsgIndex - count;
                    break;
                }
                count += groupSize;
            }

            // safety net: keep the selected message fully visible at viewport edges
            if (newRows != null) {
                if (newRows[0] < viewportTop) viewportTop = newRows[0];
                else if (newRows[1] > viewportTop + h - 1) viewportTop = newRows[1] - h + 1;
            }
        } else if (selGroupIdx >= 0) {
            // scroll viewport so the selected message stays visible
            int[] selRows = messageRowRange(groups.get(selGroupIdx), selPosInGroup,
                    cum[selGroupIdx], w, msgById);
            if (selRows != null) {
                if (selRows[0] < viewportTop) viewportTop = selRows[0];
                else if (selRows[1] > viewportTop + h - 1) viewportTop = selRows[1] - h + 1;
            }
        } else if (autoFollow) {
            // nothing selected and auto-following: stick to the newest messages
            viewportTop = maxViewport;
        }
        viewportTop = Math.max(0, Math.min(maxViewport, viewportTop));

        // draw only the groups that are visible in the viewport
        for (int i = 0; i < groups.size(); i++) {
            int groupTop = cum[i];
            int groupBot = cum[i + 1] - 1;
            if (groupBot < viewportTop) continue;
            if (groupTop > viewportTop + h - 1) break;
            int panelRow = y + groupTop - viewportTop;
            MessageRenderer.renderGroup(g, groups.get(i), x, panelRow, w, isGroupChat,
                    i == selGroupIdx ? selPosInGroup : -1,
                    y, y + h - 1, msgById);
        }
        return new RenderResult(viewportTop, selectedMsgIndex, maxViewport);
    }

    // content-space {top, bottom} rows of the message at msgIdx, mirroring the row
    // accounting in MessageRenderer.groupHeight
    private static int[] findMessageRows(List<MessageModel> messages,
            List<List<MessageModel>> groups, int[] cum, int msgIdx, int panelWidth,
            Map<Long, MessageModel> msgById) {
        if (msgIdx < 0 || msgIdx >= messages.size()) return null;
        int count = 0;
        for (int i = 0; i < groups.size(); i++) {
            int groupSize = groups.get(i).size();
            if (msgIdx < count + groupSize) {
                return messageRowRange(groups.get(i), msgIdx - count, cum[i], panelWidth, msgById);
            }
            count += groupSize;
        }
        return null;
    }

    // content-space {top, bottom} rows of the message at msgPos within a group,
    // mirroring the row accounting in MessageRenderer.groupHeight
    private static int[] messageRowRange(List<MessageModel> group, int msgPos, int groupTop,
            int panelWidth, Map<Long, MessageModel> msgById) {
        boolean isOutgoing = group.get(0).isOutgoing();
        int wrapWidth = MessageRenderer.textWrapWidth(isOutgoing, panelWidth);
        int row = groupTop + 2; // top margin row + name/timestamp row
        for (int i = 0; i < group.size(); i++) {
            if (i > 0) row++; // blank gap row between messages
            int start = row;
            row += MessageRenderer.blockHeight(group.get(i), wrapWidth, msgById);
            if (i == msgPos) {
                return new int[]{start, row - 1};
            }
        }
        return null;
    }

    static void renderLoading(TextGraphics g, int x, int y, int w, int h) {
        g.setBackgroundColor(CatppuccinMocha.BASE);
        g.setForegroundColor(CatppuccinMocha.BASE);
        for (int r = 0; r < h; r++) {
            g.putString(x, y + r, " ".repeat(w));
        }
        String msg = "Loading...";
        g.setForegroundColor(CatppuccinMocha.OVERLAY0);
        g.putString(x + Math.max(0, (w - msg.length()) / 2), y + h / 2, msg);
    }

    static void renderUrlPicker(TextGraphics g, int x, int y, int w, int h,
                                List<String> urlList, int selectedIndex) {
        int popW = Math.min(w - 4, 64);
        int popH = urlList.size() + 4;
        int popX = x + (w - popW) / 2;
        int popY = y + (h - popH) / 2;

        for (int r = 0; r < popH; r++) {
            g.setBackgroundColor(CatppuccinMocha.SURFACE0);
            g.setForegroundColor(CatppuccinMocha.SURFACE0);
            g.putString(popX, popY + r, " ".repeat(popW));
        }

        g.setBackgroundColor(CatppuccinMocha.SURFACE0);
        g.setForegroundColor(CatppuccinMocha.MAUVE);
        g.putString(popX + 1, popY + 1, "Open link:");

        for (int i = 0; i < urlList.size(); i++) {
            boolean selected = i == selectedIndex;
            g.setBackgroundColor(selected ? CatppuccinMocha.SURFACE2 : CatppuccinMocha.SURFACE0);
            g.setForegroundColor(selected ? CatppuccinMocha.BLUE : CatppuccinMocha.OVERLAY1);
            String url = urlList.get(i);
            int maxLen = popW - 3;
            if (url.length() > maxLen) url = url.substring(0, maxLen - 3) + "...";
            g.putString(popX + 2, popY + 2 + i, url);
        }

        g.setBackgroundColor(CatppuccinMocha.SURFACE0);
        g.setForegroundColor(CatppuccinMocha.OVERLAY0);
        g.putString(popX + 1, popY + popH - 1, "j/k  Enter: open  Esc: cancel");
    }

    static void renderPlaceholder(TextGraphics g, int x, int y, int w, int h) {
        g.setBackgroundColor(CatppuccinMocha.BASE);
        g.setForegroundColor(CatppuccinMocha.BASE);
        for (int r = 0; r < h; r++) {
            g.putString(x, y + r, " ".repeat(w));
        }
        String msg = "Select a chat to start messaging";
        g.setForegroundColor(CatppuccinMocha.OVERLAY0);
        g.putString(x + Math.max(0, (w - msg.length()) / 2), y + h / 2, msg);
    }
}
