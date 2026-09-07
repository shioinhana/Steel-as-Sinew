package com.actioncombat;

import com.actioncombat.combat.CombatData;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public final class ModAttachments {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, ActionCombat.MODID);

    /** In-memory combat state (authoritative drawn flag lives on the server side). */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<CombatData>> COMBAT =
            ATTACHMENTS.register("combat", () -> AttachmentType.builder(CombatData::new).build());

    private ModAttachments() {}
}
