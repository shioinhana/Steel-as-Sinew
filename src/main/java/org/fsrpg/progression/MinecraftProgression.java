package org.fsrpg.progression;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.Vec3;
import org.fsrpg.combat.*;

/** Only actual server events feed the pure progression service. Forced movement is excluded. */
public final class MinecraftProgression {
    private record Travel(Vec3 position, String dimension, long tick, boolean eligible,
                          Vec3 intended, boolean mindUnlocked, boolean bodyUnlocked, boolean footworkUnlocked) {}
    private record Food(String item,int before) {}
    private final ProgressionService service;
    private final Supplier<SkillTreeCatalog> catalog;
    private final Set<UUID> existing, initialized=new HashSet<>();
    private final Map<UUID,Travel> travel=new HashMap<>();
    private final Map<UUID,Long> forcedUntil=new HashMap<>();
    private final Map<UUID,Food> food=new HashMap<>();
    private static final Identifier HEALTH=Identifier.fromNamespaceAndPath("fsrpg","legacy_mind_health"),
        DAMAGE=Identifier.fromNamespaceAndPath("fsrpg","legacy_body_damage"),
        MOVE=Identifier.fromNamespaceAndPath("fsrpg","legacy_movement");
    public MinecraftProgression(ProgressionService service,Supplier<SkillTreeCatalog> catalog,Collection<UUID> existing) {
        this.service=service;this.catalog=catalog;this.existing=new HashSet<>(existing);
    }
    public static long epochDay(){return LocalDate.now(ZoneOffset.UTC).toEpochDay();}
    public void ensure(ServerPlayer player) {
        UUID id=player.getUUID();if(!initialized.add(id))return;
        var rules=catalog.get().rules().longTerm();int width=rules.initialAptitudeMax()-rules.initialAptitudeMin()+1;
        service.initializeAptitudes(id,rules.initialAptitudeMin()+player.getRandom().nextInt(width),
                rules.initialAptitudeMin()+player.getRandom().nextInt(width),epochDay());
        if(existing.contains(id))service.migrateExistingCombat(id);
        service.maintain(id,epochDay());
    }
    public void sleep(ServerPlayer player){ensure(player);service.onSleep(player.getUUID());}
    public void incoming(ServerPlayer player,Entity attacker) {
        ensure(player);if(hostile(attacker))service.onHostileIncoming(player.getUUID(),attacker.getUUID(),player.level().getGameTime());
    }
    public void damage(ServerPlayer player,Entity attacker,double finalDamage) {
        ensure(player);if(hostile(attacker)&&finalDamage>0)service.onHostileDamage(player.getUUID(),finalDamage);
    }
    public static boolean hostile(Entity entity){return entity instanceof Enemy||entity!=null&&entity.getType().getCategory()==MobCategory.MONSTER;}
    public void foodStart(ServerPlayer player,net.minecraft.world.item.ItemStack stack) {
        ensure(player);
        if(stack.isEmpty()||!stack.has(DataComponents.FOOD)){food.remove(player.getUUID());return;}
        food.put(player.getUUID(),new Food(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),player.getFoodData().getFoodLevel()));
    }
    public void foodEnd(ServerPlayer player,net.minecraft.world.item.ItemStack original) {
        Food start=food.remove(player.getUUID());if(start==null)return;
        if(original.isEmpty()||!original.has(DataComponents.FOOD))return;
        String item=BuiltInRegistries.ITEM.getKey(original.getItem()).toString();
        if(start.item().equals(item))service.onFood(player.getUUID(),item,Math.max(0,player.getFoodData().getFoodLevel()-start.before()));
    }
    public void foodStop(ServerPlayer player){food.remove(player.getUUID());}
    public void forced(ServerPlayer player,int ticks) {
        forcedUntil.put(player.getUUID(),Math.max(forcedUntil.getOrDefault(player.getUUID(),0L),player.level().getGameTime()+ticks));
    }
    public void tick(ServerPlayer player,CombatActor actor) {
        ensure(player);long tick=player.level().getGameTime();UUID id=player.getUUID();
        service.tick(id,tick);if(tick%1200==0)service.maintain(id,epochDay());
        if(player.isAlive()&&!player.isSpectator())service.onActiveTicks(id,1);
        String dimension=player.level().dimension().identifier().toString();
        boolean forced=tick<=forcedUntil.getOrDefault(id,-1L);
        boolean eligible=player.isAlive()&&player.onGround()&&!player.isPassenger()&&!player.isFallFlying()
                &&!player.isSwimming()&&!player.isInWater()&&!player.isInLava()&&!player.onClimbable()
                &&!player.isSleeping()&&!player.getAbilities().flying&&!player.isSpectator()&&!forced;
        var progress=service.view(id);
        // This 1.21.11 API already rotates server-confirmed WASD input into world space.
        Vec3 intended=player.getLastClientMoveIntent().multiply(1,0,1);
        Travel before=travel.put(id,new Travel(player.position(),dimension,tick,eligible,intended,
                progress.mindUnlocked(),progress.bodyUnlocked(),progress.footworkUnlocked()));
        if(before!=null&&before.mindUnlocked()&&progress.mindUnlocked()) {
            Vec3 displacement=player.position().subtract(before.position());
            double metres=GroundTrainingRules.distance(before.eligible(),eligible,dimension.equals(before.dimension()),
                    tick-before.tick(),forced,displacement.x,displacement.z,before.intended().x,before.intended().z);
            if(metres>0)service.onGroundTravel(id,metres,before.bodyUnlocked(),before.footworkUnlocked());
        }
        var effects=service.effects(id);
        modifier(player.getAttribute(Attributes.MAX_HEALTH),HEALTH,effects.healthBonus(),AttributeModifier.Operation.ADD_VALUE);
        modifier(player.getAttribute(Attributes.ATTACK_DAMAGE),DAMAGE,effects.damageBonus(),AttributeModifier.Operation.ADD_VALUE);
        double move=effects.movementSpeedBonus();
        if(actor.drawn()&&actor.style()!=null&&actor.style().controls().dual())
            move+=actor.currentSkill()!=null?effects.dualAttackingMoveBonus():effects.dualHoldingMoveBonus();
        modifier(player.getAttribute(Attributes.MOVEMENT_SPEED),MOVE,move,AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        if(player.getHealth()>player.getMaxHealth())player.setHealth(player.getMaxHealth());
    }
    private static void modifier(AttributeInstance attribute,Identifier id,double amount,AttributeModifier.Operation operation) {
        if(attribute==null)return;var previous=attribute.getModifier(id);
        if(previous!=null&&Math.abs(previous.amount()-amount)<1e-9&&previous.operation()==operation)return;
        if(previous!=null)attribute.removeModifier(id);
        if(Math.abs(amount)>1e-9)attribute.addTransientModifier(new AttributeModifier(id,amount,operation));
    }
    public void remove(UUID player){travel.remove(player);food.remove(player);forcedUntil.remove(player);initialized.remove(player);}
}
