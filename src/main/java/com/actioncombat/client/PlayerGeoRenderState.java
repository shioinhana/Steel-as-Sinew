package com.actioncombat.client;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import software.bernie.geckolib.constant.dataticket.DataTicket;
import software.bernie.geckolib.renderer.base.GeoRenderState;

import java.util.Map;

/**
 * EntityRenderState does not implement GeoRenderState at compile time
 * (the GeckoLib mixin only adds it at runtime), so we bridge it ourselves.
 * The mixin also injects a concrete addGeckolibData into the superclass which
 * would win over the interface default and bypass our map, so every access is
 * overridden here to keep reads and writes on the same map.
 */
public class PlayerGeoRenderState extends EntityRenderState implements GeoRenderState {
    public boolean rapier;
    public boolean spear;
    private final Impl impl = new Impl();

    @Override
    public Map<DataTicket<?>, Object> getDataMap() {
        return impl.getDataMap();
    }

    @Override
    public <D> void addGeckolibData(DataTicket<D> ticket, D value) {
        getDataMap().put(ticket, value);
    }

    @Override
    public boolean hasGeckolibData(DataTicket<?> ticket) {
        return getDataMap().containsKey(ticket);
    }
}
