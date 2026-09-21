package com.telegramtui.ui.chat;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.telegramtui.model.MessageModel;
import com.telegramtui.ui.common.CatppuccinMocha;
import com.telegramtui.ui.common.TextRenderer;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MessageRenderer {

    private MessageRenderer() {}

    public static List<List<MessageModel>> groupBySender(List<MessageModel> messages) {
        List<List<MessageModel>> groups = new ArrayList<>();
        for (MessageModel m : messages) {
            if (groups.isEmpty()
                    || groups.get(groups.size() - 1).get(0).senderId() != m.senderId()) {
                groups.add(new ArrayList<>());
            }
            groups.get(groups.size() - 1).add(m);
        }
        return groups;
    }

    public static int groupHeight(List<MessageModel> group, int panelWidth, boolean isGroupChat,
            Map<Long, MessageModel> msgById) {
        boolean isOutgoing = group.get(0).isOutgoing();
        int wrapWidth = textWrapWidth(isOutgoing, panelWidth);
        int rows = 2; // top margin row + name/timestamp row
        for (int i = 0; i < group.size(); i++) {
            if (i > 0) rows++; // blank gap row between messages
            rows += blockHeight(group.get(i), wrapWidth, msgById);
        }
        rows++; // bottom padding row
        return rows;
    }

    // rows occupied by a single message's block (forwarded/reply headers + wrapped text)
    static int blockHeight(MessageModel m, int wrapWidth, Map<Long, MessageModel> msgById) {
        int h = 0;
        if (!m.forwardedFrom().isEmpty()) {
            h++; // forwarded header row
        }
        if (m.replyToMessageId() > 0 && msgById.containsKey(m.replyToMessageId())) {
            h += 2; // gap row + reply header row
        }
        h += TextRenderer.wrap(m.text().isEmpty() ? " " : m.text(), wrapWidth).size();
        return h;
    }

    public static void renderGroup(TextGraphics g, List<MessageModel> group,
            int panelX, int panelY, int panelWidth, boolean isGroupChat, int selectedPos,
            int clipTop, int clipBottom, Map<Long, MessageModel> msgById) {
        MessageModel first = group.get(0);
        boolean isOutgoing = first.isOutgoing();
        int wrapWidth = textWrapWidth(isOutgoing, panelWidth);

        // Highlight the whole group background when a message in it is selected
        TextColor bg = (selectedPos >= 0) ? CatppuccinMocha.SURFACE1
                : isOutgoing ? CatppuccinMocha.SURFACE0 : CatppuccinMocha.BASE;
        TextColor nameFg = isOutgoing ? CatppuccinMocha.OVERLAY1 : CatppuccinMocha.LAVENDER;
        TextColor interior = CatppuccinMocha.SURFACE2;

        // panel-space rows of the pretty box drawn around the selected message
        int boxTop = -1;
        int boxBot = -1;
        if (selectedPos >= 0) {
            int cur = panelY + 2; // first block starts after margin + name/timestamp rows
            for (int i = 0; i < group.size(); i++) {
                if (i > 0) cur++; // blank gap row before this message
                int blockH = blockHeight(group.get(i), wrapWidth, msgById);
                if (i == selectedPos) {
                    boxTop = (i == 0) ? panelY : cur - 1; // margin row for msg 0, gap otherwise
                    boxBot = cur + blockH; // blank row after the block (gap or padding)
                    break;
                }
                cur += blockH;
            }
        }

        int row = panelY;

        // top margin row
        fillRow(g, boxFill(bg, interior, row, boxTop, boxBot), panelX, row, panelWidth,
                clipTop, clipBottom);
        row++;

        String name = isOutgoing
                ? (first.senderName().isEmpty() ? "You" : first.senderName())
                : first.senderName();
        MessageModel last = group.get(group.size() - 1);
        String ts = formatTimestamp(last.timestamp());
        if (last.isEdited()) ts = "[edited] " + ts;

        // name/timestamp row
        if (row >= clipTop && row <= clipBottom) {
            fillRow(g, boxFill(bg, interior, row, boxTop, boxBot), panelX, row, panelWidth);
            g.setForegroundColor(nameFg);
            g.putString(panelX + 1, row, TextRenderer.clip(name, panelWidth - ts.length() - 3));
            g.setForegroundColor(CatppuccinMocha.OVERLAY0);
            g.putString(panelX + panelWidth - ts.length() - 1, row, ts);
        }
        row++;

        int msgIdx = 0;
        for (MessageModel m : group) {
            if (msgIdx > 0) {
                // blank gap row between messages
                fillRow(g, boxFill(bg, interior, row, boxTop, boxBot), panelX, row, panelWidth,
                        clipTop, clipBottom);
                row++;
            }

            TextColor msgBg = (msgIdx == selectedPos) ? CatppuccinMocha.SURFACE2 : bg;

            if (!m.forwardedFrom().isEmpty()) {
                if (row >= clipTop && row <= clipBottom) {
                    fillRow(g, msgBg, panelX, row, panelWidth);
                    g.setForegroundColor(CatppuccinMocha.MAUVE);
                    String fwdLine = "⟫ Forwarded from " + m.forwardedFrom();
                    g.putString(panelX + 1, row, TextRenderer.clip(fwdLine, panelWidth - 2));
                }
                row++;
            }

            if (m.replyToMessageId() > 0) {
                MessageModel orig = msgById.get(m.replyToMessageId());
                if (orig != null) {
                    if (row >= clipTop && row <= clipBottom) {
                        fillRow(g, msgBg, panelX, row, panelWidth);
                    }
                    row++;
                    if (row >= clipTop && row <= clipBottom) {
                        fillRow(g, msgBg, panelX, row, panelWidth);
                        String origSender = orig.senderName().isEmpty() ? "You" : orig.senderName();
                        String replyHeader = "↩ " + origSender + ": "
                                + TextRenderer.clip(orig.text(), panelWidth - origSender.length() - 6);
                        g.setForegroundColor(CatppuccinMocha.TEAL);
                        g.putString(panelX + 1, row, replyHeader);
                    }
                    row++;
                }
            }

            List<String> lines = TextRenderer.wrap(m.text().isEmpty() ? " " : m.text(), wrapWidth);
            boolean isPlainText = "messageText".equals(m.contentType())
                    || "unknown".equals(m.contentType());
            TextColor textFg = isPlainText ? CatppuccinMocha.TEXT : CatppuccinMocha.OVERLAY1;
            for (String line : lines) {
                if (row >= clipTop && row <= clipBottom) {
                    fillRow(g, msgBg, panelX, row, panelWidth);
                    g.setForegroundColor(textFg);
                    g.putString(panelX + 1, row, isOutgoing ? "❯ " + line : line);
                }
                row++;
            }
            msgIdx++;
        }

        // bottom padding row
        fillRow(g, boxFill(bg, interior, row, boxTop, boxBot), panelX, row, panelWidth,
                clipTop, clipBottom);
        row++;

        // pretty box-drawing border around the selected message
        if (selectedPos >= 0 && boxTop >= 0) {
            drawBoxBorder(g, panelX, boxTop, boxBot, panelWidth, clipTop, clipBottom);
        }
    }

    // Outgoing messages leave room for the ❯ prefix
    static int textWrapWidth(boolean isOutgoing, int panelWidth) {
        return isOutgoing ? panelWidth - 4 : panelWidth - 2;
    }

    private static void drawBoxBorder(TextGraphics g, int x, int top, int bottom, int width,
            int clipTop, int clipBottom) {
        int right = x + width - 1;
        String edge = "╭" + "─".repeat(Math.max(0, width - 2)) + "╮";
        String bottomEdge = "╰" + "─".repeat(Math.max(0, width - 2)) + "╯";
        g.setForegroundColor(CatppuccinMocha.LAVENDER);
        if (top >= clipTop && top <= clipBottom) {
            g.putString(x, top, edge);
        }
        if (bottom >= clipTop && bottom <= clipBottom) {
            g.putString(x, bottom, bottomEdge);
        }
        for (int r = top + 1; r < bottom; r++) {
            if (r >= clipTop && r <= clipBottom) {
                g.putString(x, r, "│");
                g.putString(right, r, "│");
            }
        }
    }

    // picks the selected-message box interior color when row is inside the box
    private static TextColor boxFill(TextColor normal, TextColor interior, int row,
            int boxTop, int boxBot) {
        if (boxTop < 0) return normal;
        return (row >= boxTop && row <= boxBot) ? interior : normal;
    }

    private static void fillRow(TextGraphics g, TextColor bg, int x, int row, int width,
            int clipTop, int clipBottom) {
        if (row < clipTop || row > clipBottom) return;
        fillRow(g, bg, x, row, width);
    }

    private static void fillRow(TextGraphics g, TextColor bg, int x, int row, int width) {
        g.setBackgroundColor(bg);
        g.setForegroundColor(bg);
        g.putString(x, row, " ".repeat(width));
    }

    private static String formatTimestamp(long unixSeconds) {
        LocalDateTime dt = LocalDateTime.ofEpochSecond(unixSeconds, 0, ZoneOffset.UTC);
        return String.format("%02d:%02d", dt.getHour(), dt.getMinute());
    }
}
