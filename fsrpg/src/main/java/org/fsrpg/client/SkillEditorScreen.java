package org.fsrpg.client;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** A live field editor with a server-authoritative save and deterministic engine preview. */
public final class SkillEditorScreen extends Screen {
    private static final String[] KEYS = {"windupTicks", "activeTicks", "recoveryTicks", "cooldownTicks", "damage", "reach", "staminaCost", "spiritCost"};
    private static final String[] LABELS = {"前摇 / tick", "判定 / tick", "后摇 / tick", "冷却 / tick", "伤害", "距离 / 格", "体力消耗", "精神消耗"};
    private final Map<String, EditBox> fields = new LinkedHashMap<>();
    private final FsvoxelPreview preview = new FsvoxelPreview();
    private String selectedId = "";
    private String message = "等待服务端技能配置…";
    private int left, top, panelWidth, panelHeight, fieldWidth, previewX, previewY, previewWidth, previewHeight;
    private int timelineX, timelineY, timelineWidth;
    private boolean filling, dirty, playing = true, pending;
    private int pendingTicks;
    private double previewTick;
    private Button saveButton;
    private Button playButton;

    public SkillEditorScreen() { super(Component.literal("前后摇设计窗口")); }
    @Override protected void init() {
        fields.clear();
        panelWidth = Math.min(680, width - 12); panelHeight = Math.min(370, height - 12);
        left = (width - panelWidth) / 2; top = (height - panelHeight) / 2;
        fieldWidth = Math.min(130, Math.max(72, (panelWidth - 190) / 2));
        int fieldsX = left + 12, fieldsY = top + 48;
        for (int i = 0; i < KEYS.length; i++) {
            int x = fieldsX + (i % 2) * (fieldWidth + 9);
            int y = fieldsY + (i / 2) * 29;
            EditBox edit = new EditBox(font, x, y + 10, fieldWidth, 17, Component.literal(LABELS[i]));
            edit.setMaxLength(12);
            edit.setFilter(value -> value.matches("[0-9.]*"));
            edit.setResponder(value -> { if (!filling) dirty = true; });
            fields.put(KEYS[i], addRenderableWidget(edit));
        }
        previewX = fieldsX + 2 * (fieldWidth + 9) + 4; previewY = fieldsY;
        previewWidth = left + panelWidth - 12 - previewX;
        previewHeight = Math.max(90, panelHeight - 106);
        List<JsonObject> stages = stages();
        if(selectedId.isEmpty()&&!stages.isEmpty())selectedId=ClientState.text(stages.getFirst(),"skillId","");
        if (!stages.isEmpty()) {
            int stageWidth = Math.max(16, (previewWidth - 8) / stages.size());
            for (int i = 0; i < stages.size(); i++) {
                String id = ClientState.text(stages.get(i), "skillId", "");
                int stage = i;
                addRenderableWidget(Button.builder(Component.literal(Integer.toString(i + 1)), button -> {
                    if (pending) return; selectedId = id; previewTick = 0; restoreFields();
                    message = "左键连击第 " + (stage + 1) + " 段 · 前摇 / 判定 / 后摇";
                }).bounds(previewX + 4 + i * stageWidth, previewY + 16, stageWidth - 2, 16).build());
            }
        }
        addRenderableWidget(Button.builder(Component.literal("‹"), button -> select(-1)).bounds(left + 12, top + 24, 22, 18).build());
        addRenderableWidget(Button.builder(Component.literal("›"), button -> select(1)).bounds(left + panelWidth - 34, top + 24, 22, 18).build());
        timelineX = left + 12; timelineY = top + panelHeight - 56; timelineWidth = panelWidth - 24;
        int bottom = top + panelHeight - 25;
        playButton = addRenderableWidget(Button.builder(Component.literal("暂停"), button -> {
            playing = !playing; button.setMessage(Component.literal(playing ? "暂停" : "播放"));
        }).bounds(left + 12, bottom, 46, 18).build());
        addRenderableWidget(Button.builder(Component.literal("归零"), button -> previewTick = 0).bounds(left + 62, bottom, 46, 18).build());
        addRenderableWidget(Button.builder(Component.literal("重载模型"), button -> {
            JsonObject skill = skill(); if (skill != null) preview.load(skill, true); PlayerAnimations.reloadModels();
            message = preview.error().isEmpty() ? "已读取本地 FSVOXEL 文本" : "模型错误：" + preview.error();
        }).bounds(left + 112, bottom, 60, 18).build());
        saveButton = addRenderableWidget(Button.builder(Component.literal("保存配置"), button -> save())
                .bounds(left + panelWidth - 133, bottom, 77, 18).build());
        addRenderableWidget(Button.builder(Component.literal("关闭"), button -> onClose())
                .bounds(left + panelWidth - 52, bottom, 40, 18).build());
        restoreFields();
    }
    private JsonObject skill() {
        List<JsonObject> skills = ClientState.skills();
        for (JsonObject item : skills) if (ClientState.text(item, "id", "").equals(selectedId)) return item;
        if (skills.isEmpty()) return null;
        selectedId = ClientState.text(skills.getFirst(), "id", "");
        return skills.getFirst();
    }
    private void select(int direction) {
        List<JsonObject> skills = ClientState.skills(); if (skills.isEmpty() || pending) return;
        int index = 0;
        for (int i = 0; i < skills.size(); i++) if (ClientState.text(skills.get(i), "id", "").equals(selectedId)) index = i;
        selectedId = ClientState.text(skills.get(Math.floorMod(index + direction, skills.size())), "id", "");
        previewTick = 0; restoreFields();
        message = ClientState.canEdit() ? "修改字段即可预览；保存由服务端校验" : "可预览；保存需要管理员权限";
    }
    private List<JsonObject> stages() {
        String styleId = ClientState.text(ClientState.snapshot(), "weaponStyle", "rapier");
        for (JsonObject style : ClientState.objects(ClientState.snapshot(), "styles"))
            if (ClientState.text(style, "id", "").equals(styleId)) return ClientState.objects(style, "leftCombo");
        return List.of();
    }
    private JsonObject stage() {
        for (JsonObject stage : stages()) if (ClientState.text(stage, "skillId", "").equals(selectedId)) return stage;
        return null;
    }
    private void restoreFields() {
        JsonObject skill = skill(); filling = true;
        try {
            fields.forEach((key, edit) -> {
                double value = skill == null ? (key.equals("activeTicks") ? 1 : key.equals("reach") ? 3 : 0)
                        : ClientState.number(skill, key, 0);
                edit.setValue(value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value));
                edit.setEditable(!pending && skill != null);
            });
        } finally { filling = false; }
        dirty = false;
        if (saveButton != null) saveButton.active = ClientState.canEdit() && !pending && skill != null;
        if (skill != null) preview.load(skill, false);
    }
    public void acceptSnapshot() {
        if (fields.isEmpty()) return;
        saveButton.active = ClientState.canEdit() && !pending && skill() != null;
        var resultValue = ClientState.snapshot().get("editResult");
        JsonObject result = resultValue != null && resultValue.isJsonObject() ? resultValue.getAsJsonObject() : null;
        if (pending && result != null && ClientState.text(result, "id", "").equals(selectedId)) {
            pending = false;
            boolean ok = ClientState.bool(result, "ok", false);
            message = ClientState.text(result, "message", ok ? "已保存至服务端配置" : "保存失败，已恢复服务端数值");
            restoreFields();
        } else if (!dirty && !pending) {
            restoreFields();
            if (message.startsWith("等待")) message = ClientState.canEdit() ? "20 tick = 1 秒 · 保存至服务端 JSON" : "可预览；保存需要管理员权限";
        }
    }
    private void save() {
        if (!ClientState.canEdit() || pending || skill() == null) return;
        JsonObject changes = new JsonObject();
        try {
            for (String key : KEYS) {
                double value = Double.parseDouble(fields.get(key).getValue());
                if (!Double.isFinite(value)) throw new IllegalArgumentException("数值必须有限");
                if (key.endsWith("Ticks")) {
                    if (value != Math.rint(value) || value < (key.equals("activeTicks") ? 1 : 0) || value > 72_000)
                        throw new IllegalArgumentException("时间必须为整数；判定至少 1 tick，上限 72000");
                    changes.addProperty(key, (int) value);
                } else {
                    if (value < (key.equals("reach") ? 0.001 : 0) || value > (key.equals("reach") ? 128 : 1_000_000))
                        throw new IllegalArgumentException("距离须为 0.001～128；消耗与伤害须为 0～1000000");
                    changes.addProperty(key, value);
                }
            }
            JsonObject request = new JsonObject(); request.addProperty("id", selectedId); request.add("changes", changes);
            pending = true; pendingTicks = 0; saveButton.active = false;
            fields.values().forEach(edit -> edit.setEditable(false));
            message = "正在等待服务端保存…";
            ClientHooks.send("editSkill", request.toString());
        } catch (NumberFormatException exception) {
            restoreFields(); message = "无效数字，已恢复服务端数值";
        } catch (IllegalArgumentException exception) {
            restoreFields(); message = exception.getMessage();
        }
    }
    private double value(String key, double fallback) {
        try { double number = Double.parseDouble(fields.get(key).getValue()); return Double.isFinite(number) ? Math.max(0, number) : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }
    private double total() { return Math.max(1, Math.max(value("windupTicks", 0) + value("activeTicks", 1) + value("recoveryTicks", 0), value("cooldownTicks", 0))); }
    @Override public void tick() {
        if (playing) previewTick = (previewTick + 1) % (total() + 10);
        if (pending && ++pendingTicks == 60) ClientHooks.send("status", "");
        if (pending && pendingTicks > 120) {
            pending = false; restoreFields(); message = "未收到保存回执，已显示最新服务端数值";
        }
    }
    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0 && event.x() >= timelineX && event.x() < timelineX + timelineWidth
                && event.y() >= timelineY && event.y() < timelineY + 22) {
            previewTick = total() * (event.x() - timelineX) / timelineWidth;
            playing = false; playButton.setMessage(Component.literal("播放")); return true;
        }
        return super.mouseClicked(event, doubleClick);
    }
    @Override public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        gui.fill(left, top, left + panelWidth, top + panelHeight, 0xf0121b29);
        gui.fill(left, top, left + panelWidth, top + 3, 0xff7cb7ff);
        gui.drawString(font, title, left + 12, top + 10, 0xffe5f0ff);
        String status = font.plainSubstrByWidth(message, Math.max(50, panelWidth - 156));
        gui.drawString(font, status, left + 144, top + 10, 0xffa4b7ce);
        JsonObject skill = skill();
        String label = skill == null ? "尚无技能" : ClientState.text(skill, "label", selectedId) + " · " + selectedId;
        gui.drawCenteredString(font, font.plainSubstrByWidth(label, panelWidth - 82), left + panelWidth / 2, top + 29, 0xfff2cd8b);
        for (int i = 0; i < KEYS.length; i++) {
            EditBox box = fields.get(KEYS[i]);
            gui.drawString(font, LABELS[i], box.getX(), box.getY() - 10, 0xffacbfd5);
        }
        gui.fill(previewX, previewY, previewX + previewWidth, previewY + previewHeight, 0xff0b111c);
        double windup = value("windupTicks", 0), active = value("activeTicks", 1), recovery = value("recoveryTicks", 0);
        int previewInset = stages().isEmpty() ? 13 : 34;
        preview.render(gui, previewX, previewY + previewInset, previewWidth, previewHeight - previewInset - 10, Math.min(previewTick, total()), windup, active, recovery);
        gui.drawString(font, "FSVOXEL · 动作预览", previewX + 4, previewY + 4, 0xff87b9ee);
        String source = preview.error().isEmpty() ? preview.sourceLabel() : "模型错误：" + preview.error();
        gui.drawString(font, font.plainSubstrByWidth(source, Math.max(0, previewWidth - 8)), previewX + 4, previewY + previewHeight - 10, 0xff8b9cb3);
        renderTimeline(gui, windup, active, recovery);
        super.render(gui, mouseX, mouseY, partialTick);
    }
    private void renderTimeline(GuiGraphics gui, double windup, double active, double recovery) {
        double total = total();
        gui.fill(timelineX, timelineY, timelineX + timelineWidth, timelineY + 8, 0xff253447);
        double[] durations = {windup, active, recovery}; int[] colors = {0xffdfa64d, 0xffef6666, 0xff729add};
        double at = 0;
        for (int i = 0; i < durations.length; i++) {
            int from = timelineX + (int) Math.round(timelineWidth * at / total); at += durations[i];
            int to = timelineX + (int) Math.round(timelineWidth * at / total);
            if (to > from) gui.fill(from, timelineY, to, timelineY + 8, colors[i]);
        }
        int cooldownEnd = timelineX + (int) Math.round(timelineWidth * value("cooldownTicks", 0) / total);
        gui.fill(timelineX, timelineY + 10, Math.max(timelineX + 1, cooldownEnd), timelineY + 12, 0xff89d3c0);
        JsonObject stage = stage();
        int chain = timelineX + (int) Math.round(timelineWidth * (windup + active) / total);
        gui.fill(Math.min(chain,timelineX+timelineWidth-1),timelineY-2,Math.min(chain+1,timelineX+timelineWidth),timelineY+15,0xff75ead8);
        int cursor = timelineX + (int) Math.round(timelineWidth * Math.min(previewTick, total) / total);
        gui.fill(Math.min(timelineX + timelineWidth - 1, cursor), timelineY - 2, Math.min(timelineX + timelineWidth, cursor + 1), timelineY + 13, 0xffffffff);
        String legend = "前摇 " + (int) windup + "  判定 " + (int) active + "  后摇 " + (int) recovery + "  冷却 " + (int) value("cooldownTicks", 0)
                + "  · " + (int) Math.min(previewTick, total) + "/" + (int) total + " tick";
        if (stage != null) legend = "前摇可取消 · 判定锁定/可缓存 · 后摇可衔接下一招";
        gui.drawString(font, font.plainSubstrByWidth(legend, timelineWidth), timelineX, timelineY + 16, 0xffc0cddd);
    }
    @Override public boolean isPauseScreen() { return false; }
}
