package org.fsrpg.archery;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.*;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.living.*;
import org.fsrpg.combat.*;
import org.fsrpg.config.RpgConfig;
import org.fsrpg.mixin.ArcheryArrowInvoker;

/** Minecraft adapter. All entry points must run on the server thread after equipment reconciliation. */
public final class ArcheryServer {
    public interface Sink {
        default void changed(ServerPlayer player) {}
        default void skillUsed(UUID player,String skillId) {}
        default void hostileKilled(UUID player) {}
        default void kill(UUID player) { hostileKilled(player); }
        default double damageBonus(UUID player) { return 0; }
    }
    private static final String TAG="fsrpgArchery",OWNER="fsrpgArcheryOwner",PIERCE="fsrpgArcheryPierce",SHIELD="fsrpgArcheryShieldPierce",
            BLOCKS="fsrpgArcheryBlocks",SLOW="fsrpgArcheryBlockSlow",CAPTURED="fsrpgArcheryCaptured";
    private final Map<UUID,ArcheryController> controllers=new HashMap<>();
    private final Set<UUID> manualHeld=new HashSet<>();
    private final Sink sink;
    private ArcheryRules rules;
    public ArcheryServer(ArcheryRules rules,Sink sink) { this.rules=Objects.requireNonNull(rules); this.sink=Objects.requireNonNull(sink); }
    public void setRules(ArcheryRules rules) { this.rules=Objects.requireNonNull(rules); }
    public ArcheryRules rules() { return rules; }
    private ArcheryController controller(UUID player) { return controllers.computeIfAbsent(player,id->new ArcheryController(rules)); }
    public static boolean holdingBow(Player player) { return player.getMainHandItem().getItem() instanceof BowItem||player.getOffhandItem().getItem() instanceof BowItem; }
    private static ItemStack bow(ServerPlayer player) { return player.getMainHandItem().getItem() instanceof BowItem?player.getMainHandItem():player.getOffhandItem(); }
    public static boolean bowStyle(CombatActor actor) { return actor.style()!=null&&actor.style().weaponGroup().equals("bow"); }
    private static ArcheryController.Stance stance(CombatActor actor) {
        return actor.style()!=null&&actor.style().id().equals("bow_snipe")?ArcheryController.Stance.SNIPE:ArcheryController.Stance.RAPID;
    }
    private static String equipmentKey(ServerPlayer p) {
        if(!holdingBow(p)) return "";
        ItemStack stack=bow(p);
        String hand=stack==p.getMainHandItem()?"main:"+p.getInventory().getSelectedSlot():"offhand";
        return hand+":"+BuiltInRegistries.ITEM.getKey(stack.getItem())+":"+System.identityHashCode(stack);
    }
    public boolean reconcile(ServerPlayer p,CombatActor actor,ArcheryProgress progress) {
        var c=controller(p.getUUID());
        boolean changed=c.reconcile(equipmentKey(p),p.isAlive()&&!p.isSpectator()&&bowStyle(actor)&&actor.drawn(),
                stance(actor),p.level().getGameTime(),rules,progress);
        if(changed) manualHeld.remove(p.getUUID());
        if(c.active()&&c.stance()==ArcheryController.Stance.SNIPE) { p.stopRiding(); p.setSprinting(false); }
        return changed;
    }
    /** Query before reconcile when the equipment policy needs to select the initial rapid style. */
    public boolean newlyEquippedBow(ServerPlayer player) { return controller(player.getUUID()).equipmentChanged(equipmentKey(player)); }
    private boolean usable(ServerPlayer p,CombatActor a,ArcheryProgress progress) {
        reconcile(p,a,progress);
        return controller(p.getUUID()).active()&&a.phase()==CombatPhase.READY;
    }
    /** Held is an input intention. Elapsed time and shot power are never accepted from a client. */
    public ActionResult charge(ServerPlayer p,CombatActor a,ArcheryProgress progress,boolean held) {
        if(!usable(p,a,progress)) { controller(p.getUUID()).cancelCharge(); manualHeld.remove(p.getUUID()); return a.drawn()?ActionResult.BUSY:ActionResult.NOT_DRAWN; }
        var c=controller(p.getUUID()); long now=p.level().getGameTime();
        if(held) {
            manualHeld.add(p.getUUID());
            if(c.beginCharge(now)) { sink.skillUsed(p.getUUID(),c.stance()==ArcheryController.Stance.SNIPE?"bow_snipe_charge":"bow_rapid_charge"); sink.changed(p); }
            return ActionResult.ACCEPTED;
        }
        manualHeld.remove(p.getUUID());
        var shot=c.release(now,false,rules,progress);
        ActionResult result=shot==null?ActionResult.ACCEPTED:fire(p,a,progress,shot);
        sink.changed(p); return result;
    }
    /** Legacy 50-score hold-to-fire, additionally available on the left-button input. */
    public ActionResult auto(ServerPlayer p,CombatActor a,ArcheryProgress progress,boolean held) {
        if(!held) {
            reconcile(p,a,progress); var c=controller(p.getUUID());
            c.automatic(false,p.level().getGameTime(),rules,progress);
            if(!manualHeld.contains(p.getUUID())) c.cancelCharge();
            sink.changed(p); return ActionResult.ACCEPTED;
        }
        if(!usable(p,a,progress)) return a.drawn()?ActionResult.BUSY:ActionResult.NOT_DRAWN;
        var c=controller(p.getUUID());
        if(held&&(progress.score()<rules.autoChargeScore()||c.stance()!=ArcheryController.Stance.RAPID)) return ActionResult.TECHNIQUE_NOT_READY;
        c.automatic(held,p.level().getGameTime(),rules,progress);
        sink.changed(p); return ActionResult.ACCEPTED;
    }
    /** Left click interrupts drawing even while the independent bash cooldown is running. */
    public ActionResult bash(ServerPlayer p,CombatActor a,ArcheryProgress progress) {
        if(!usable(p,a,progress)) return a.drawn()?ActionResult.BUSY:ActionResult.NOT_DRAWN;
        var c=controller(p.getUUID()); c.cancelCharge(); manualHeld.remove(p.getUUID());
        if(a.stamina()+1e-9<rules.bashStaminaCost()) { sink.changed(p); return ActionResult.INSUFFICIENT_STAMINA; }
        if(!c.bash(p.level().getGameTime(),rules)) { sink.changed(p); return ActionResult.COOLDOWN; }
        a.consumeStamina(rules.bashStaminaCost());
        Vec3 direction=p.getLookAngle().multiply(1,0,1);
        if(direction.lengthSqr()<1e-8) direction=Vec3.directionFromRotation(0,p.getYRot());
        direction=direction.normalize();
        float damage=(float)Math.min(1000000,rules.bashDamage()+progress.damageBonus());
        for(LivingEntity target:p.level().getEntitiesOfClass(LivingEntity.class,p.getBoundingBox().inflate(rules.bashRange()),e->e!=p&&e.isAlive()&&!e.isSpectator())) {
            Vec3 flat=target.position().subtract(p.position()).multiply(1,0,1);
            if(flat.lengthSqr()<1e-8||flat.lengthSqr()>rules.bashRange()*rules.bashRange()||flat.normalize().dot(direction)<.4) continue;
            if(!p.hasLineOfSight(target)||target instanceof Player other&&!p.canHarmPlayer(other)) continue;
            if(target.hurtServer(p.level(),p.damageSources().playerAttack(p),damage)) {
                Vec3 away=flat.normalize(); target.knockback(rules.bashKnockback(),-away.x,-away.z); target.hurtMarked=true;
            }
        }
        p.level().playSound(null,p.getX(),p.getY(),p.getZ(),SoundEvents.PLAYER_ATTACK_SWEEP,SoundSource.PLAYERS,.9f,.8f);
        p.level().sendParticles(ParticleTypes.SWEEP_ATTACK,p.getX()+direction.x,p.getY()+1.1,p.getZ()+direction.z,4,.4,.3,.4,0);
        sink.skillUsed(p.getUUID(),"bow_bash"); sink.changed(p); return ActionResult.ACCEPTED;
    }
    public void cancelCharge(ServerPlayer p) { controller(p.getUUID()).cancelCharge(); manualHeld.remove(p.getUUID()); }
    /** Keep persistent charge/capture cooldown on logout; clear only the equipped/animation state. */
    public void remove(UUID player) {
        var c=controllers.get(player); if(c!=null) c.detach(); manualHeld.remove(player);
    }
    public void tick(ServerPlayer p,CombatActor a,ArcheryProgress progress) {
        boolean changed=reconcile(p,a,progress);
        var c=controller(p.getUUID()); long now=p.level().getGameTime();
        changed|=c.recover(now,rules,progress);
        if(a.phase()!=CombatPhase.READY&&c.charging()) { c.cancelCharge(); changed=true; }
        if(a.phase()==CombatPhase.READY) {
            changed|=c.maintainAutomatic(now);
            if(manualHeld.contains(p.getUUID())) changed|=c.beginCharge(now);
        }
        if(c.autoReady(now,rules,progress)) {
            var shot=c.release(now,true,rules,progress);
            if(shot!=null) fire(p,a,progress,shot);
            changed=true;
        }
        if(changed) sink.changed(p);
    }
    private ActionResult fire(ServerPlayer p,CombatActor actor,ArcheryProgress progress,ArcheryController.Shot shot) {
        ItemStack weapon=bow(p);
        if(!(weapon.getItem() instanceof BowItem bowItem)) return ActionResult.NOT_DRAWN;
        double stamina=shot.stance()==ArcheryController.Stance.SNIPE?rules.snipeStaminaCost():rules.rapidStaminaCost();
        if(actor.stamina()+1e-9<stamina) return ActionResult.INSUFFICIENT_STAMINA;
        if(actor.spirit()+1e-9<rules.shotSpiritCost()) return ActionResult.INSUFFICIENT_SPIRIT;
        ItemStack ammo=p.getProjectile(weapon);
        if(ammo.isEmpty()&&p.hasInfiniteMaterials()) ammo=bowItem.getDefaultCreativeAmmo(p,weapon);
        if(ammo.isEmpty()||!(ammo.getItem() instanceof ArrowItem arrowItem)) return ActionResult.SKILL_NOT_FOUND;
        int count=p.hasInfiniteMaterials()||arrowItem.isInfinite(ammo,weapon,p)?0:EnchantmentHelper.processAmmoUse(p.level(),weapon,ammo,1);
        if(count<0||count>ammo.getCount()) return ActionResult.SKILL_NOT_FOUND;
        ItemStack projectileStack=ammo.copyWithCount(1);
        if(count==0) projectileStack.set(DataComponents.INTANGIBLE_PROJECTILE,Unit.INSTANCE);
        AbstractArrow arrow=arrowItem.createArrow(p.level(),projectileStack,p,weapon);
        arrow=bowItem.customArrow(arrow,projectileStack,weapon);
        Vec3 look=p.getLookAngle(),eye=p.getEyePosition();
        arrow.setPos(eye.x+look.x*.4,eye.y-.1+look.y*.4,eye.z+look.z*.4);
        arrow.shoot(look.x,look.y,look.z,(float)shot.speed(),0);
        arrow.setCritArrow(shot.power()>=.999);
        arrow.pickup=count==0?AbstractArrow.Pickup.CREATIVE_ONLY:AbstractArrow.Pickup.ALLOWED;
        CompoundTag tag=arrow.getPersistentData();
        tag.putBoolean(TAG,true); tag.putString(OWNER,p.getUUID().toString());
        if(progress.branch250()==ArcheryProgress.Branch250.CHUAN_TANG_FENG) {
            ((ArcheryArrowInvoker)(Object)arrow).fsrpg$setPierceLevel((byte)rules.chuanTangFengEntityPierce());
            tag.putDouble(PIERCE,rules.chuanTangFengArmorPierce()); tag.putInt(BLOCKS,rules.chuanTangFengBlockPierce());
            tag.putBoolean(SHIELD,true);
            tag.putDouble(SLOW,rules.chuanTangFengBlockSlow());
        }
        if(!p.level().addFreshEntity(arrow)) return ActionResult.BUSY;
        if(count>0) { ammo.shrink(count); if(ammo.isEmpty()) p.getInventory().removeItem(ammo); }
        actor.consumeStamina(stamina); actor.consumeSpirit(rules.shotSpiritCost());
        if(shot.stance()==ArcheryController.Stance.SNIPE) {
            int damage=weapon.getDamageValue()+shot.durability();
            if(weapon.isDamageableItem()&&damage>=weapon.getMaxDamage()) { weapon.shrink(1); p.level().playSound(null,p.getX(),p.getY(),p.getZ(),SoundEvents.ITEM_BREAK.value(),SoundSource.PLAYERS,.8f,1); }
            else if(weapon.isDamageableItem()) weapon.setDamageValue(damage);
        } else weapon.hurtAndBreak(shot.durability(),p.level(),p,item->{});
        controller(p.getUUID()).commitShot(shot,p.level().getGameTime(),rules);
        p.level().playSound(null,p.getX(),p.getY(),p.getZ(),SoundEvents.ARROW_SHOOT,SoundSource.PLAYERS,1,
                1/(p.level().getRandom().nextFloat()*.4f+1.2f)+(float)shot.power()*.5f);
        sink.skillUsed(p.getUUID(),shot.stance()==ArcheryController.Stance.SNIPE?"bow_snipe_release":"bow_rapid_release");
        return ActionResult.ACCEPTED;
    }
    public ArcheryController.Snapshot snapshot(ServerPlayer p,ArcheryProgress progress) { return controller(p.getUUID()).snapshot(p.level().getGameTime(),rules,progress); }
    public JsonObject snapshotJson(ServerPlayer p,ArcheryProgress progress) { return RpgConfig.JSON.toJsonTree(snapshot(p,progress)).getAsJsonObject(); }
    public boolean active(ServerPlayer p) { return controller(p.getUUID()).active(); }
    public void onDeath(LivingDeathEvent event) {
        if(event.getEntity() instanceof Player||!(event.getEntity() instanceof net.minecraft.world.entity.monster.Enemy||event.getEntity().getType().getCategory()==MobCategory.MONSTER)) return;
        if(!(event.getSource().getDirectEntity() instanceof AbstractArrow arrow)||!isArcheryArrow(arrow)||!(event.getSource().getEntity() instanceof ServerPlayer killer)) return;
        if(arrow.getPersistentData().getStringOr(OWNER,"").equals(killer.getUUID().toString())) sink.kill(killer.getUUID());
    }
    public void onDamagePre(LivingDamageEvent.Pre event) {
        if(event.getSource().getDirectEntity() instanceof AbstractArrow arrow&&isArcheryArrow(arrow)) {
            double fraction=arrow.getPersistentData().getDoubleOr(PIERCE,0);
            if(Double.isFinite(fraction)&&fraction>0) event.setNewDamage((float)Math.min(1000000,event.getNewDamage()+event.getOriginalDamage()*Math.min(10,fraction)));
        }
    }
    /** Legacy body attack bonus is added before vanilla armor, rather than to projectile velocity. */
    public void onOutgoing(LivingIncomingDamageEvent event) {
        if(event.getSource().getDirectEntity() instanceof AbstractArrow arrow&&isArcheryArrow(arrow)
                &&event.getSource().getEntity() instanceof ServerPlayer player) {
            double bonus=sink.damageBonus(player.getUUID());
            if(Double.isFinite(bonus)&&bonus>0) event.setAmount((float)Math.min(1000000,event.getAmount()+Math.min(1000000,bonus)));
        }
    }
    public void onShieldBlock(LivingShieldBlockEvent event) {
        if(event.getDamageSource().getDirectEntity() instanceof AbstractArrow arrow&&isArcheryArrow(arrow)&&arrow.getPersistentData().getBooleanOr(SHIELD,false)) event.setBlocked(false);
    }
    /** Returns true when the caller should skip its ordinary guard/stun handling. */
    public boolean onIncoming(LivingIncomingDamageEvent event,ArcheryProgress progress) {
        if(!(event.getEntity() instanceof ServerPlayer p)||!progress.unlocked()||progress.branch150()!=ArcheryProgress.Branch150.NIE_SHI||!(event.getSource().getDirectEntity() instanceof Projectile projectile)) return false;
        event.setCanceled(true);
        if(projectile instanceof AbstractArrow arrow&&!arrow.getPersistentData().getBooleanOr(CAPTURED,false)
                &&controller(p.getUUID()).claimCapturedArrow(p.level().getGameTime(),rules)) {
            arrow.getPersistentData().putBoolean(CAPTURED,true);
            ItemStack captured=arrow.getPickupItemStackOrigin().copyWithCount(1);
            if(captured.isEmpty())captured=new ItemStack(Items.ARROW);
            // Intangible/creative ammunition must never become a survival duplication source.
            if(!captured.has(DataComponents.INTANGIBLE_PROJECTILE)&&arrow.pickup!=AbstractArrow.Pickup.CREATIVE_ONLY) {
                if(!p.getInventory().add(captured)&&!captured.isEmpty()) p.drop(captured,false);
            }
            arrow.discard(); sink.changed(p);
        }
        return true;
    }
    /** Block penetration retains the old three impacts and per-block speed reduction. */
    public boolean onProjectileImpact(ProjectileImpactEvent event) {
        if(!(event.getProjectile() instanceof AbstractArrow arrow)||!isArcheryArrow(arrow)||!(event.getRayTraceResult() instanceof BlockHitResult)) return false;
        var tag=arrow.getPersistentData(); int remaining=tag.getIntOr(BLOCKS,0);
        if(remaining<=0) return false;
        Vec3 velocity=arrow.getDeltaMovement();
        double slow=Math.max(0,Math.min(1,tag.getDoubleOr(SLOW,0)));
        tag.putInt(BLOCKS,remaining-1); arrow.setDeltaMovement(velocity.scale(1-slow));
        Vec3 direction=velocity.lengthSqr()>1e-8?velocity.normalize():Vec3.directionFromRotation(arrow.getXRot(),arrow.getYRot());
        arrow.setPos(arrow.position().add(direction.scale(1.05))); event.setCanceled(true); return true;
    }
    public static boolean isArcheryArrow(AbstractArrow arrow) { return arrow.getPersistentData().getBooleanOr(TAG,false); }
    public void save(Path target) throws IOException {
        JsonObject result=new JsonObject(); controllers.forEach((id,c)->result.add(id.toString(),RpgConfig.JSON.toJsonTree(c.persisted())));
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path temp=target.resolveSibling(target.getFileName()+".tmp");
        Files.writeString(temp,RpgConfig.JSON.toJson(result),StandardCharsets.UTF_8);
        try { Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
        catch(AtomicMoveNotSupportedException failure) { Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING); }
    }
    public void restore(Path target) throws IOException {
        if(!Files.exists(target)) return;
        if(Files.size(target)>4*1024*1024) throw new IOException("archery state exceeds 4 MiB");
        try {
            var json=JsonParser.parseString(Files.readString(target,StandardCharsets.UTF_8)).getAsJsonObject();
            Map<UUID,ArcheryController> restored=new HashMap<>();
            for(var entry:json.entrySet()) {
                UUID id=UUID.fromString(entry.getKey()); var c=new ArcheryController(rules);
                c.restore(RpgConfig.JSON.fromJson(entry.getValue(),ArcheryController.Persisted.class),rules); restored.put(id,c);
            }
            controllers.clear(); controllers.putAll(restored);
        } catch(RuntimeException failure) { throw new IOException("Invalid archery state; existing file was preserved",failure); }
    }
}
