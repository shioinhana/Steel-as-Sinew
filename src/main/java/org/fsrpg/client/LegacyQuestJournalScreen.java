package org.fsrpg.client;

import net.minecraft.client.gui.screens.Screen;

/** Keeps existing callers compatible while all quest records now use the guild journal. */
public final class LegacyQuestJournalScreen extends GuildQuestScreen {
    public LegacyQuestJournalScreen(Screen parent) { super(parent); }
}
