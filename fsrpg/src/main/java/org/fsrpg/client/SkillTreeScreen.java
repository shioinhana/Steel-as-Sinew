package org.fsrpg.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * One clean cultivation page: a category rail, every node of the selected tree on the same page
 * grouped by branch family, and one detail panel. There is no chart pagination; a category click
 * or a mouse wheel is the only navigation. Creative-mode tools expose fill/free-learn/reset, but
 * every condition is still validated by the server.
 */
public final class SkillTreeScreen extends Screen {
    private static final int TEXT = 0xffd2dee8, MUTED = 0xff93a6b5, GREEN = 0xffa9d49b,
            GOLD = 0xffe8cf98, PURPLE = 0xffb9a7e6, DIM = 0xff6c7f8f, BG = 0xff0e1620,
            PANEL = 0xff15212d, PANEL2 = 0xff101a24, BORDER = 0xff2d4152, ACCENT = 0xff86a978;
    private static final int FOOTER = 30, HEADER = 64, NAV_WIDTH = 122, CARD = 22;
    private static final String[] TREES = {"rapier", "dual_blade", "bow", "footwork"};
    private static final String[] LABELS = {"西洋剑", "双刀", "弓", "步法"};

    private String tree = "rapier", selected = "", lastSelected = "", lastTree = "";
    private String feedback = "";
    private int resetConfirm;
    private boolean learning, creative;
    private long learnRevision;
    private int left, top, panelWidth, panelHeight;
    private int navX, navY, navWidth, navHeight;
    private int bodyX, bodyY, bodyWidth, bodyHeight;
    private int detailX, detailY, detailWidth, detailHeight;
    private int contentScroll, detailScroll;
    private final List<Card> cards = new ArrayList<>();
    private final List<Heading> headings = new ArrayList<>();
    private final Map<String, JsonObject> byId = new LinkedHashMap<>();
    private List<JsonObject> allNodes = List.of();
    private List<JsonObject> nodes = List.of();
    private List<SkillTreeLayout.Group> groups = List.of();
    private List<Line> detailCache;

    private record Card(String id, int x, int y, int width, int height, JsonObject node) {}
    private record Heading(String caption, int x, int y) {}
    private record Line(FormattedCharSequence text, int color) {}

    public SkillTreeScreen() { super(Component.literal("修行 · 技能树")); }

    @Override protected void init() {
        panelWidth = Math.min(960, width - 12);
        panelHeight = Math.min(560, height - 12);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        navX = left + 10;
        navY = top + HEADER;
        boolean wide = panelWidth >= 700;
        navWidth = wide ? NAV_WIDTH : 96;
        bodyY = navY;
        detailWidth = wide ? Math.min(304, Math.max(240, panelWidth * 30 / 100))
                : Math.max(150, panelWidth * 36 / 100);
        bodyX = navX + navWidth + 10;
        bodyWidth = left + panelWidth - 10 - detailWidth - 10 - bodyX;
        bodyHeight = panelHeight - HEADER - FOOTER - 6;
        navHeight = bodyHeight;
        detailX = left + panelWidth - 10 - detailWidth;
        detailY = navY;
        detailHeight = bodyHeight;
        creative = ClientState.bool(ClientState.snapshot(), "creative", false);

        allNodes = ClientState.objects(progress(), "nodes");
        List<JsonObject> categoryNodes = allNodes.stream()
                .filter(node -> ClientState.text(node, "tree", "").equals(tree)).toList();
        nodes = categoryNodes;
        byId.clear();
        for (JsonObject node : nodes) byId.put(ClientState.text(node, "id", ""), node);
        if (!byId.containsKey(selected)) selected = nodes.isEmpty() ? "" : ClientState.text(nodes.getFirst(), "id", "");
        groups = SkillTreeLayout.groups(tree, nodes);

        int navButtonHeight = 26;
        for (int i = 0; i < TREES.length; i++) {
            String id = TREES[i];
            String label = LABELS[i] + " " + learnedIn(id) + "/" + countIn(id);
            addRenderableWidget(Button.builder(Component.literal((tree.equals(id) ? "● " : "") + label), button -> {
                tree = id;
                selected = "";
                contentScroll = 0;
                detailScroll = 0;
                feedback = "";
                learning = false;
                rebuild();
            }).bounds(navX, navY + i * (navButtonHeight + 5), navWidth, navButtonHeight).build());
        }
        layoutCards();

        if (!selected.equals(lastSelected) || !tree.equals(lastTree)) {
            detailScroll = 0;
            lastSelected = selected;
            lastTree = tree;
        }
        int footerY = top + panelHeight - FOOTER + 4;
        if (creative) {
            boolean compact = panelWidth < 640;
            int fillWidth = compact ? 72 : 82, resetWidth = compact ? 88 : 96;
            addRenderableWidget(Button.builder(Component.literal("补满进度"), button -> {
                learning = true;
                learnRevision = ClientState.revision();
                feedback = "";
                ClientHooks.send("progressionFill", "");
                rebuild();
            }).bounds(left + 10, footerY, fillWidth, 20).build());
            addRenderableWidget(Button.builder(Component.literal(resetConfirm > 0 ? "再次点击确认" : "清空重选"), button -> {
                if (resetConfirm > 0) {
                    resetConfirm = 0;
                    learning = true;
                    learnRevision = ClientState.revision();
                    feedback = "";
                    ClientHooks.send("progressionReset", "");
                } else {
                    resetConfirm = 80;
                }
                rebuild();
            }).bounds(left + 14 + fillWidth, footerY, resetWidth, 20).build());
        }
        boolean learned = ClientState.bool(chosen(), "learned", false);
        boolean automatic = ClientState.bool(chosen(), "automatic", false);
        boolean available = ClientState.bool(chosen(), "available", false);
        boolean creativeNode = ClientState.bool(chosen(), "creative", false);
        String action = creative ? (!learned ? (automatic ? "自动节点" : "学习（自由）")
                : (automatic ? "自动节点" : "移除技能"))
                : (learned ? "已学会" : automatic ? "条件达成后自动解锁" : "学习所选技能");
        Button actionButton = addRenderableWidget(Button.builder(Component.literal(action), button -> {
            if (learning || !byId.containsKey(selected)) return;
            if (creative && learned) {
                learning = true;
                learnRevision = ClientState.revision();
                feedback = "";
                ClientHooks.send("unlearnSkill", selected);
            } else if (!learned && !automatic && (creative || available)) {
                learning = true;
                learnRevision = ClientState.revision();
                feedback = "";
                ClientHooks.send("learnSkill", selected);
            }
            rebuild();
        }).bounds(detailX, detailY + detailHeight - 24, detailWidth, 20).build());
        actionButton.active = !learning && byId.containsKey(selected)
                && (creative ? !automatic : !learned && available);
        addRenderableWidget(Button.builder(Component.literal("关闭"), button -> onClose())
                .bounds(left + panelWidth - 62, footerY, 52, 20).build());
        detailCache = null;
    }

    private void layoutCards() {
        cards.clear();
        headings.clear();
        int columns = bodyWidth >= 340 ? 2 : 1;
        int gap = 8;
        int cardWidth = Math.max(80, (bodyWidth - 4 - gap * (columns - 1)) / columns);
        int y = bodyY - contentScroll;
        for (SkillTreeLayout.Group group : groups) {
            headings.add(new Heading(group.caption(), bodyX + 2, y));
            y += 16;
            int rows = (group.nodes().size() + columns - 1) / columns;
            for (int i = 0; i < group.nodes().size(); i++) {
                JsonObject node = group.nodes().get(i);
                int x = bodyX + 2 + (i % columns) * (cardWidth + gap);
                int cardY = y + (i / columns) * (CARD + 4);
                cards.add(new Card(ClientState.text(node, "id", ""), x, cardY, cardWidth, CARD, node));
            }
            y += rows * (CARD + 4) + 4;
        }
        int contentHeight = Math.max(0, y - (bodyY - contentScroll));
        contentScroll = Math.clamp(contentScroll, 0, Math.max(0, contentHeight - bodyHeight));
    }

    private JsonObject progress() { return ClientState.object(ClientState.snapshot(), "progression"); }
    private JsonObject footworkView() { return ClientState.object(progress(), "footwork"); }
    private JsonObject chosen() { return byId.getOrDefault(selected, new JsonObject()); }
    private JsonObject find(String id) {
        JsonObject node = byId.get(id);
        if (node != null) return node;
        for (JsonObject candidate : allNodes)
            if (ClientState.text(candidate, "id", "").equals(id)) return candidate;
        return new JsonObject();
    }
    private int countIn(String treeId) {
        int count = 0;
        for (JsonObject node : allNodes) if (ClientState.text(node, "tree", "").equals(treeId)) count++;
        return count;
    }
    private int learnedIn(String treeId) {
        int count = 0;
        for (JsonObject node : allNodes)
            if (ClientState.text(node, "tree", "").equals(treeId) && ClientState.bool(node, "learned", false)) count++;
        return count;
    }
    private void rebuild() { clearWidgets(); init(); }

    private List<Line> detailLines() {
        List<Line> lines = new ArrayList<>();
        JsonObject node = chosen();
        if (node.size() == 0) {
            prose(lines, "等待服务端技能配置…", MUTED);
            return lines;
        }
        boolean learned = ClientState.bool(node, "learned", false);
        boolean automatic = ClientState.bool(node, "automatic", false);
        boolean creativeNode = ClientState.bool(node, "creative", false);
        var requirements = ClientState.object(node, "requirements");
        var cost = ClientState.object(node, "cost");
        String category = switch (tree) {
            case "rapier" -> "西洋剑";
            case "dual_blade" -> "双刀";
            case "bow" -> "弓术";
            default -> "步法";
        };
        prose(lines, category + " · " + (learned ? (creativeNode ? "创造授予" : "已学会")
                : automatic ? (ClientState.bool(node, "available", false) ? "已自动生效" : "条件未达成")
                : creative ? "创造模式可自由学习" : ClientState.bool(node, "available", false) ? "可学习" : "条件不足"),
                learned ? GREEN : creative ? PURPLE : GOLD);
        if (creative) {
            prose(lines, "创造模式：学习/移除不消耗点数、不受前置与条件限制，由服务端校验创造模式。", PURPLE);
        }
        if (!feedback.isBlank()) prose(lines, feedback, GOLD);
        if (!ClientState.text(node, "description", "").isBlank())
            prose(lines, ClientState.text(node, "description", ""), TEXT);
        List<String> requirementsText = new ArrayList<>();
        for (String key : List.of("score", "mind", "body", "footwork")) {
            int value = (int) ClientState.number(requirements, key, 0);
            if (value <= 0) continue;
            requirementsText.add(switch (key) {
                case "score" -> "熟练度 " + value;
                case "mind" -> "心法 " + value;
                case "body" -> "煅体 " + value;
                default -> "步法 " + value;
            });
        }
        if (ClientState.bool(requirements, "trial", false)) requirementsText.add("完整通过试炼刷怪笼");
        if (ClientState.bool(requirements, "dragon", false)) requirementsText.add("击杀末影龙");
        if (!requirementsText.isEmpty()) prose(lines, "条件：" + String.join("；", requirementsText), MUTED);
        List<String> parents = new ArrayList<>();
        var prerequisites = node.get("prerequisites");
        if (prerequisites != null && prerequisites.isJsonArray())
            for (JsonElement parent : prerequisites.getAsJsonArray())
                parents.add(ClientState.text(find(parent.getAsString()), "label", parent.getAsString()));
        if (!parents.isEmpty()) prose(lines, "前置：" + String.join("、", parents), GREEN);
        if (!learned || creative)
            for (String blocker : strings(node, "blockers"))
                if (!creative || automatic) prose(lines, "· " + blocker, GOLD);
        String exclusive = ClientState.text(node, "exclusiveGroup", "");
        if (!exclusive.isBlank()) {
            List<String> peers = new ArrayList<>();
            for (JsonObject peer : nodes)
                if (!ClientState.text(peer, "id", "").equals(selected)
                        && exclusive.equals(ClientState.text(peer, "exclusiveGroup", "")))
                    peers.add(ClientState.text(peer, "label", ""));
            if (!peers.isEmpty()) prose(lines, "与 " + String.join("、", peers) + " 互斥，同组只能选一项。", GOLD);
        }
        if (!automatic) {
            boolean footwork = tree.equals("footwork");
            String effect = ClientState.text(node, "effect", "");
            boolean style = effect.equals("RABBIT") || effect.equals("FLYING_SWAN");
            if (footwork && style) {
                prose(lines, "流派二选一，不占选学额度。", GREEN);
            } else if (footwork) {
                var fw = footworkView();
                prose(lines, "选学额度：剩余 " + (int) ClientState.number(fw, "selectionsAvailable", 0)
                        + " / 已获得 " + (int) ClientState.number(fw, "selectionsEarned", 0)
                        + "（每 150 步法总值 1 次，服务端判定）。", GREEN);
            }
            if (creative) prose(lines, "成本：自由学习忽略。", MUTED);
            else prose(lines, "成本：心 " + (int) ClientState.number(cost, "mind", 0)
                    + " / 体 " + (int) ClientState.number(cost, "body", 0)
                    + " / 步 " + (int) ClientState.number(cost, "footwork", 0), MUTED);
        }
        return lines;
    }

    private static List<String> strings(JsonObject node, String field) {
        List<String> result = new ArrayList<>();
        JsonElement value = node.get(field);
        if (value != null && value.isJsonArray())
            for (JsonElement entry : value.getAsJsonArray()) if (entry.isJsonPrimitive()) result.add(entry.getAsString());
        return result;
    }

    private void prose(List<Line> lines, String text, int color) {
        for (FormattedCharSequence line : font.split(Component.literal(text), Math.max(20, detailWidth - 16)))
            lines.add(new Line(line, color));
    }

    @Override public void tick() {
        if (resetConfirm > 0) {
            if (--resetConfirm == 0) rebuild();
        }
    }

    public void acceptSnapshot() {
        if (learning && ClientState.revision() > learnRevision) {
            feedback = ClientState.text(ClientState.snapshot(), "message", "");
            learning = false;
        }
        if (panelWidth > 0) rebuild();
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0 && mouseY >= bodyY && mouseY < bodyY + bodyHeight) {
            if (mouseX >= bodyX - 4 && mouseX < bodyX + bodyWidth) {
                contentScroll = Math.clamp(contentScroll - (int) Math.signum(scrollY) * 24, 0, Math.max(0, contentHeight() - bodyHeight));
                rebuild();
                return true;
            }
            if (mouseX >= detailX && mouseX < detailX + detailWidth) {
                detailScroll = Math.clamp(detailScroll - (int) Math.signum(scrollY) * 22, 0, Math.max(0, detailHeight() - 24));
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private int contentHeight() {
        int max = bodyY;
        for (Card card : cards) max = Math.max(max, card.y() + card.height());
        for (Heading heading : headings) max = Math.max(max, heading.y() + 12);
        return Math.max(0, max + contentScroll + 8 - bodyY);
    }

    private int detailHeight() {
        return detail().size() * 11;
    }

    private List<Line> detail() {
        if (detailCache == null) detailCache = detailLines();
        return detailCache;
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        for (Card card : cards) {
            if (event.x() < card.x() || event.x() >= card.x() + card.width()
                    || event.y() < card.y() || event.y() >= card.y() + card.height()) continue;
            if (event.button() == 0) {
                selected = card.id();
                detailScroll = 0;
                feedback = "";
                learning = false;
                rebuild();
            } else if (event.button() == 1 && creative) {
                boolean learned = ClientState.bool(card.node(), "learned", false);
                if (!ClientState.bool(card.node(), "automatic", false)) {
                    learning = true;
                    learnRevision = ClientState.revision();
                    feedback = "";
                    ClientHooks.send(learned ? "unlearnSkill" : "learnSkill", card.id());
                    rebuild();
                }
            }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        gui.fill(0, 0, width, height, 0x8c05090d);
        gui.fill(left - 1, top - 1, left + panelWidth + 1, top + panelHeight + 1, ACCENT);
        gui.fill(left, top, left + panelWidth, top + panelHeight, PANEL);
        gui.fill(left, top, left + panelWidth, top + 3, 0xff9fbe8b);
        var view = ClientState.object(progress(), "view");
        gui.drawString(font, title.getString(), left + 12, top + 10, 0xffeef3df);
        if (creative) gui.drawString(font, "创造模式", left + panelWidth - 12 - font.width("创造模式"), top + 10, PURPLE);
        drawStats(gui, view);
        gui.fill(navX - 1, navY - 1, navX + navWidth + 1, navY + navHeight + 1, BORDER);
        gui.fill(navX, navY, navX + navWidth, navY + navHeight, PANEL2);
        gui.fill(bodyX - 1, bodyY - 1, bodyX + bodyWidth + 1, bodyY + bodyHeight + 1, BORDER);
        gui.fill(bodyX, bodyY, bodyX + bodyWidth, bodyY + bodyHeight, PANEL2);
        gui.fill(detailX - 1, detailY - 1, detailX + detailWidth + 1, detailY + detailHeight + 1, BORDER);
        gui.fill(detailX, detailY, detailX + detailWidth, detailY + detailHeight, PANEL2);
        int selectedIndex = 0;
        for (int i = 0; i < TREES.length; i++) if (TREES[i].equals(tree)) selectedIndex = i;
        gui.fill(navX, navY + selectedIndex * 31, navX + 3, navY + selectedIndex * 31 + 26, ACCENT);
        drawLegend(gui);
        drawCards(gui, mouseX, mouseY);
        drawDetail(gui);
        if (creative) {
            boolean compact = panelWidth < 640;
            int fillWidth = compact ? 72 : 82, resetWidth = compact ? 88 : 96;
            int buttonsEnd = left + 14 + fillWidth + resetWidth;
            int closeX = left + panelWidth - 62;
            if (!compact) {
                String hint = resetConfirm > 0 ? "再次点击「清空重选」确认" : "右键节点可直接学习/移除";
                int width = Math.max(20, closeX - buttonsEnd - 24);
                gui.drawString(font, font.plainSubstrByWidth(hint, width), buttonsEnd + 10,
                        top + panelHeight - FOOTER + 10, MUTED);
            }
        } else {
            gui.drawString(font, font.plainSubstrByWidth("点击左侧分类切换；本页展示该分支全部节点，无需翻页",
                    Math.max(20, panelWidth - 80)), left + 10, top + panelHeight - FOOTER + 10, MUTED);
        }
        super.render(gui, mouseX, mouseY, partialTick);
    }

    private void drawLegend(GuiGraphics gui) {
        if (navHeight < 210) return;
        int y = navY + navHeight - 62;
        gui.hLine(navX + 8, navX + navWidth - 8, y, BORDER);
        gui.drawString(font, "图例", navX + 8, y + 5, MUTED);
        gui.drawString(font, "✓ 已学会 · ◆ 创造授予", navX + 8, y + 18, GREEN);
        gui.drawString(font, "+ 可学习 · ✦ 自动生效", navX + 8, y + 31, DIM);
        gui.drawString(font, "分类共 " + TREES.length + " 个，一页看全", navX + 8, y + 44, DIM);
    }

    private void drawStats(GuiGraphics gui, JsonObject view) {
        var mind = ClientState.object(view, "mind");
        var body = ClientState.object(view, "body");
        var footwork = ClientState.object(view, "footwork");
        String points = "心法 " + points(mind) + " · 煅体 " + points(body) + " · 步法 " + points(footwork);
        String mastery = "成长  剑 " + (int) ClientState.number(view, "rapierKills", 0)
                + " · 刀 " + (int) ClientState.number(view, "dualHits", 0)
                + " · 弓 " + (int) ClientState.number(view, "archeryScore", 0);
        var fw = footworkView();
        int earned = (int) ClientState.number(fw, "selectionsEarned", 0);
        int available = (int) ClientState.number(fw, "selectionsAvailable", 0);
        if (earned > 0) points += " · 选学 " + available + "/" + earned;
        if (ClientState.bool(view, "trialPassed", false)) mastery += " · 试炼 ✓";
        if (ClientState.bool(view, "dragonKilled", false)) mastery += " · 末影龙 ✓";
        gui.drawString(font, font.plainSubstrByWidth(points, panelWidth - 24), left + 12, top + 20, TEXT);
        gui.drawString(font, font.plainSubstrByWidth(mastery, panelWidth - 24), left + 12, top + 35, MUTED);
        gui.drawString(font, font.plainSubstrByWidth("分类：西洋剑 / 双刀 / 弓术 / 步法 · 同类分支归组，一页看全",
                panelWidth - 24), left + 12, top + 49, DIM);
    }

    private void drawCards(GuiGraphics gui, int mouseX, int mouseY) {
        gui.enableScissor(bodyX, bodyY, bodyX + bodyWidth, bodyY + bodyHeight);
        for (Heading heading : headings) {
            if (heading.y() + 12 < bodyY || heading.y() > bodyY + bodyHeight) continue;
            gui.drawString(font, heading.caption(), heading.x(), heading.y() + 1, MUTED);
            gui.hLine(heading.x() + font.width(heading.caption()) + 6, bodyX + bodyWidth - 6, heading.y() + 8, BORDER);
        }
        for (Card card : cards) {
            if (card.y() + card.height() < bodyY || card.y() > bodyY + bodyHeight) continue;
            JsonObject node = card.node();
            boolean learned = ClientState.bool(node, "learned", false);
            boolean available = ClientState.bool(node, "available", false);
            boolean automatic = ClientState.bool(node, "automatic", false);
            boolean creativeNode = ClientState.bool(node, "creative", false);
            boolean chosen = card.id().equals(selected);
            boolean hover = mouseX >= card.x() && mouseX < card.x() + card.width()
                    && mouseY >= card.y() && mouseY < card.y() + card.height();
            int fill = learned ? 0xff1d3327 : creativeNode ? 0xff2a2340 : available ? 0xff1b2b39 : 0xff131c25;
            int border = learned ? 0xff5c8b69 : creativeNode ? 0xff8d7bc4 : available ? 0xff6d8ca5 : 0xff33424f;
            if (chosen) { fill = 0xff23342d; border = GOLD; } else if (hover) fill = 0xff223140;
            gui.fill(card.x(), card.y(), card.x() + card.width(), card.y() + card.height(), fill);
            gui.fill(card.x(), card.y(), card.x() + card.width(), card.y() + 1, border);
            gui.fill(card.x(), card.y() + card.height() - 1, card.x() + card.width(), card.y() + card.height(), border);
            gui.fill(card.x(), card.y(), card.x() + 1, card.y() + card.height(), border);
            gui.fill(card.x() + card.width() - 1, card.y(), card.x() + card.width(), card.y() + card.height(), border);
            String marker = learned ? (creativeNode ? "◆ " : "✓ ") : automatic ? (available ? "✦ " : "· ") : available ? "+ " : "· ";
            int color = learned ? GREEN : creativeNode ? PURPLE : available ? TEXT : DIM;
            String label = marker + ClientState.text(node, "label", card.id());
            gui.drawString(font, font.plainSubstrByWidth(label, card.width() - 8), card.x() + 5, card.y() + 7, color);
        }
        gui.disableScissor();
        if (contentHeight() > bodyHeight) {
            int thumb = Math.max(12, bodyHeight * bodyHeight / contentHeight());
            int offset = (bodyHeight - thumb) * contentScroll / Math.max(1, contentHeight() - bodyHeight);
            gui.fill(bodyX + bodyWidth - 2, bodyY, bodyX + bodyWidth, bodyY + bodyHeight, 0xff233442);
            gui.fill(bodyX + bodyWidth - 2, bodyY + offset, bodyX + bodyWidth, bodyY + offset + thumb, 0xff8fae82);
        }
    }

    private void drawDetail(GuiGraphics gui) {
        JsonObject node = chosen();
        gui.drawString(font, font.plainSubstrByWidth(ClientState.text(node, "label", "技能详情"), detailWidth - 16),
                detailX + 8, detailY + 7, GOLD);
        List<Line> lines = detail();
        int top = detailY + 22;
        int bottom = detailY + detailHeight - 28;
        detailScroll = Math.clamp(detailScroll, 0, Math.max(0, lines.size() * 11 - (bottom - top)));
        gui.enableScissor(detailX + 4, top, detailX + detailWidth - 4, bottom);
        int y = top - detailScroll;
        for (Line line : lines) {
            if (y + 11 >= top && y <= bottom) gui.drawString(font, line.text(), detailX + 8, y, line.color());
            y += 11;
        }
        gui.disableScissor();
        if (lines.size() * 11 > bottom - top) {
            int thumb = Math.max(10, (bottom - top) * (bottom - top) / (lines.size() * 11));
            int offset = ((bottom - top) - thumb) * detailScroll / Math.max(1, lines.size() * 11 - (bottom - top));
            gui.fill(detailX + detailWidth - 4, top, detailX + detailWidth - 2, bottom, 0xff233442);
            gui.fill(detailX + detailWidth - 4, top + offset, detailX + detailWidth - 2, top + offset + thumb, 0xff8fae82);
        }
    }

    private static String points(JsonObject points) {
        return (int) ClientState.number(points, "available", 0) + "/" + (int) ClientState.number(points, "total", 0);
    }

    @Override public boolean isPauseScreen() { return false; }
}
