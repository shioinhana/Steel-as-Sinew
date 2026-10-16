package org.fsrpg.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.bee.Bee;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.animal.panda.Panda;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.entity.monster.Zoglin;
import net.minecraft.world.entity.monster.creaking.Creaking;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.monster.skeleton.WitherSkeleton;
import net.minecraft.world.entity.monster.spider.CaveSpider;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.entity.monster.zombie.Zombie;
import org.fsrpg.creature.CreatureMeleeServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Defer the complete vanilla attack, including subclass effects, to its contact frame. */
@Mixin({Mob.class, Bee.class, IronGolem.class, Panda.class, Creeper.class, Ravager.class,
        Zoglin.class, Creaking.class, Hoglin.class, WitherSkeleton.class, CaveSpider.class,
        Warden.class, Husk.class, Zombie.class})
public abstract class CreatureMeleeMixin {
    // Every target declares this method. Intercepting only Mob would miss the
    // independent golem/hoglin implementations and run some subclass effects early.
    @Inject(method="doHurtTarget(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;)Z",
            at=@At("HEAD"),cancellable=true,require=1)
    private void fsrpg$deferMelee(ServerLevel level,Entity target,CallbackInfoReturnable<Boolean> cir) {
        if(CreatureMeleeServer.intercept((Mob)(Object)this,level,target))cir.setReturnValue(false);
    }
}
