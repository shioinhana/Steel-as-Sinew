package org.fsrpg.smoke;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.fsrpg.client.CombatCamera;
import org.fsrpg.combat.AttackAim;
import org.fsrpg.config.RpgConfig;

/**
 * Dev-only presentation review. Movement is real local-player keyboard input and native
 * collision; the camera alone is an unobstructed following observation point.
 */
final class WeaponMotionReview {
    static final List<String> CLIPS = List.of("dual-idle-breath", "dual-omnidirectional-movement", "dual-cuts-oblique",
            "spear-idle-breath", "spear-omnidirectional-movement", "spear-thrust-sweep-oblique");
    private final WeaponReview owner;
    private boolean reviewing;
    private int tick;
    private String segment = "settle", beat = "settle";
    private final List<String> validationFailures = new ArrayList<>();

    WeaponMotionReview(WeaponReview owner) {
        this.owner = owner;
        NeoForge.EVENT_BUS.addListener(this::cameraAngles);
    }

    private void cameraAngles(ViewportEvent.ComputeCameraAngles event) {
        var game=Minecraft.getInstance();
        if(reviewing&&game.options.getCameraType()==CameraType.THIRD_PERSON_FRONT) {
            event.setYaw(-45);event.setPitch(-6);event.setRoll(0);
        }
    }

    boolean tick() throws Exception {
        Minecraft game = Minecraft.getInstance(); int t = tick++;
        if (t == 0) {
            reviewing=true;
            KeyMapping.releaseAll(); game.options.hideGui = true; game.options.fov().set(50);
            game.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
            game.setCameraEntity(game.player);
            owner.server(player -> {
                for (int x=-35;x<=35;x++) for (int z=-35;z<=35;z++)
                    player.level().setBlock(new BlockPos(x,79,z), Blocks.STONE.defaultBlockState(),3);
                player.level().getEntities(player, player.getBoundingBox().inflate(45), e -> e instanceof LivingEntity)
                        .forEach(Entity::discard);
                player.connection.teleport(.5,80,.5,0,0);
                player.getFoodData().setFoodLevel(20); owner.actor(player).setResources(100,100);
                for (var slot:List.of(EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET))
                    player.setItemSlot(slot,ItemStack.EMPTY);
                player.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.DIAMOND_SWORD));
                player.setItemSlot(EquipmentSlot.OFFHAND,new ItemStack(Items.IRON_SWORD));
                if (owner.actor(player).drawn()) owner.action(player,"draw","");
            });
        }
        followCamera();
        if (t == 15) owner.server(player -> { owner.action(player,"weaponStyle","dual_blade"); owner.action(player,"draw",""); });
        if (t == 40) start("dual-idle-breath", "idle-breath");
        if (t == 94 || t == 135 || t == 175) owner.shots.add("dual-breath-"+t);
        if (t == 216) owner.movies.stop();
        if (t == 235) start("dual-omnidirectional-movement", "forward");
        if (t >= 235 && t < 485) movement(t-235,false);
        if (t == 485) { releaseMovement(); owner.movies.stop(); }
        if (t == 486) owner.server(player -> owner.action(player,"draw",""));
        if (t == 501) owner.server(player -> {
            player.connection.teleport(.5,80,.5,0,0); owner.actor(player).setResources(100,100);
            owner.action(player,"draw","");
        });
        if (t == 522) start("dual-cuts-oblique", "neutral");
        if (t >= 522 && t < 672) dualCuts(t-522);
        if (t == 672) owner.movies.stop();
        if (t == 700) owner.server(player -> owner.action(player,"draw",""));
        if (t == 715) owner.server(player -> {
            player.connection.teleport(.5,80,.5,0,0);
            player.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.DIAMOND_SPEAR));
            player.setItemSlot(EquipmentSlot.OFFHAND,ItemStack.EMPTY);
            for(var slot:List.of(EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET))
                player.setItemSlot(slot,ItemStack.EMPTY);
            owner.action(player,"weaponStyle","spear"); owner.actor(player).setResources(100,100);
            owner.action(player,"draw","");
        });
        if (t == 740) start("spear-idle-breath", "idle-breath");
        if (t == 794 || t == 835 || t == 875) owner.shots.add("spear-breath-"+t);
        if (t == 916) owner.movies.stop();
        if (t == 935) start("spear-omnidirectional-movement", "forward");
        if (t >= 935 && t < 1185) movement(t-935,true);
        if (t == 1185) { releaseMovement(); owner.movies.stop(); }
        if (t == 1190) owner.server(player -> { player.connection.teleport(.5,80,.5,0,0); owner.actor(player).setResources(100,100); });
        if (t == 1200) start("spear-thrust-sweep-oblique", "ready");
        if (t >= 1200 && t < 1350) spearAttacks(t-1200);
        if (t == 1350) owner.movies.stop();
        if (t >= 1370) { releaseMovement(); reviewing=false; return true; }
        return false;
    }

    private void followCamera() {
        Minecraft game = Minecraft.getInstance();
        // The local player must remain the controlled camera entity for native input and movement packets.
        // The viewport event adjusts only the observation angles, not player or body rotation.
        if(game.getCameraEntity()!=game.player)game.setCameraEntity(game.player);
        game.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
    }

    private void start(String id,String nextBeat) throws Exception {
        segment = id; beat = nextBeat;
        Minecraft game = Minecraft.getInstance(); Supplier<JsonObject> raw = RenderObservations.sampler(game.player);
        double[] previousPosition={Double.NaN,Double.NaN};
        owner.movies.start(id,"actual RenderFrameEvent.Post; native keyboard locomotion; unobstructed oblique-front observer",()->{
            JsonObject observation = raw.get();
            observation.addProperty("motionReviewTick",tick); observation.addProperty("reviewSegment",segment);
            observation.addProperty("reviewBeat",beat); observation.addProperty("cameraYaw",-45);
            observation.addProperty("actorYaw",game.player.getYRot());
            owner.observeDualInput(observation);
            if(observation.has("torsoUp")) {
                var up=observation.getAsJsonArray("torsoUp");double yaw=Math.toRadians(observation.get("bodyYaw").getAsDouble());
                double wx=up.get(0).getAsDouble()*Math.cos(yaw)-up.get(2).getAsDouble()*Math.sin(yaw);
                double wz=up.get(0).getAsDouble()*Math.sin(yaw)+up.get(2).getAsDouble()*Math.cos(yaw);
                observation.add("worldTorsoTiltXZ",RpgConfig.JSON.toJsonTree(new double[]{wx,wz}));
                double dx=game.player.getX()-previousPosition[0],dz=game.player.getZ()-previousPosition[1];
                double speed=Math.hypot(dx,dz),tilt=Math.hypot(wx,wz);
                if(Double.isFinite(speed)&&speed>.00001&&tilt>.00001)
                    observation.addProperty("leanMovementDirectionDot",(dx*wx+dz*wz)/(speed*tilt));
            }
            previousPosition[0]=game.player.getX();previousPosition[1]=game.player.getZ();return observation;
        });
        owner.shots.add(id+"-start");
    }

    private void movement(int t,boolean spear) {
        Minecraft game=Minecraft.getInstance();
        game.options.keyUp.setDown(t<35 || t>=140&&t<165 || t>=190&&t<215 || t>=232&&t<249);
        game.options.keyDown.setDown(t>=35&&t<70 || t>=165&&t<190);
        game.options.keyLeft.setDown(t>=70&&t<105 || t>=140&&t<165);
        game.options.keyRight.setDown(t>=105&&t<140 || t>=165&&t<190);
        game.options.keySprint.setDown(t>=190&&t<215);
        if(t>=215&&t<232)game.player.setSprinting(false);
        beat=t<35?"forward":t<70?"backward":t<105?"left":t<140?"right":t<165?"forward-left":t<190?"backward-right":t<215?"sprint":t<232?"brake":"moving-attack";
        if (List.of(28,62,97,132,158,182,208,228,242).contains(t)) owner.shots.add((spear?"spear":"dual")+"-motion-"+beat);
        if(t==236) {
            owner.server(player -> {
                owner.actor(player).setResources(100,100);
                if(spear) owner.attack(player,"left",List.of(new AttackAim.Point(0,0)));
            });
            if(!spear)owner.dualClick("left");
        }
    }

    private void dualCuts(int t) {
        beat=t<12?"neutral":t<42?"left-repeat":t<72?"right-repeat":t<102?"left-then-right":"right-then-left";
        if (List.of(12,14,42,44,72,78,102,108).contains(t)) {
            owner.server(player -> owner.actor(player).setResources(100,100));
            owner.dualClick(List.of(12,14,72,108).contains(t)?"left":"right");
        }
        if (List.of(17,24,47,54,77,84,107,114).contains(t)) owner.shots.add("dual-cut-"+t);
        if(t==4)owner.shots.add("dual-grip-neutral");
        if(t==17)owner.shots.add("dual-grip-left-first");
        if(t==24)owner.shots.add("dual-grip-left-repeat");
        if(t==47)owner.shots.add("dual-grip-right-first");
        if(t==54)owner.shots.add("dual-grip-right-repeat");
        if(t==60)owner.server(player->{
            player.setItemSlot(EquipmentSlot.CHEST,new ItemStack(Items.DIAMOND_CHESTPLATE));
            player.setItemSlot(EquipmentSlot.LEGS,new ItemStack(Items.DIAMOND_LEGGINGS));
            player.setItemSlot(EquipmentSlot.FEET,new ItemStack(Items.DIAMOND_BOOTS));
        });
        if(t==65)owner.shots.add("dual-grip-armor-neutral");
        if(t==77)owner.shots.add("dual-grip-armor-left");
        if(t==84)owner.shots.add("dual-grip-armor-follow-right");
        if(t==107)owner.shots.add("dual-grip-armor-right");
        if(t==114)owner.shots.add("dual-grip-armor-follow-left");
    }

    private void spearAttacks(int t) {
        beat=t<12?"ready":t<44?"thrust-1":t<76?"thrust-2":t<112?"sweep":"angled-thrust";
        if(t==12||t==44)owner.server(player->{owner.actor(player).setResources(100,100);owner.attack(player,"left",List.of(new AttackAim.Point(0,0)));});
        if(t==76)owner.server(player->{owner.actor(player).setResources(100,100);owner.attack(player,"sweep",List.of(new AttackAim.Point(-32,0),new AttackAim.Point(-16,-4),new AttackAim.Point(0,-6),new AttackAim.Point(16,-4),new AttackAim.Point(32,0)));});
        if(t==112)owner.server(player->{owner.actor(player).setResources(100,100);owner.attack(player,"left",List.of(new AttackAim.Point(12,-12)));});
        if(List.of(15,18,21,27,47,50,79,86,92,116,121).contains(t))owner.shots.add("spear-motion-attack-"+t);
    }

    private void releaseMovement() {
        var game=Minecraft.getInstance();
        game.options.keyUp.setDown(false);game.options.keyDown.setDown(false);game.options.keyLeft.setDown(false);
        game.options.keyRight.setDown(false);game.options.keySprint.setDown(false);game.player.setSprinting(false);
    }

    void verify(JsonObject capture) throws Exception {
        JsonObject report=new JsonObject(); report.addProperty("captureEvent","RenderFrameEvent.Post");
        report.addProperty("camera","unobstructed oblique front, follows native player position");
        report.addProperty("trajectorySpace","FS centimetres; calibrated stock item tip reference through official carrier, framebuffer is actual ItemStack rendering");
        JsonArray results=new JsonArray(),additionalDualGripClips=new JsonArray(),additionalControlClips=new JsonArray();
        for(var item:capture.getAsJsonArray("clips")) {
            JsonObject clip=item.getAsJsonObject(); String id=clip.get("id").getAsString();
            List<JsonObject> samples=new ArrayList<>();
            for(var frame:clip.getAsJsonArray("frames"))samples.add(frame.getAsJsonObject().getAsJsonObject("observation"));
            validate(samples.stream().allMatch(RenderObservations::stableLowerSupport),id+" final skin hips share a pelvis and grounded support uses complete nonpenetrating boot soles");
            JsonObject metrics=new JsonObject();metrics.addProperty("id",id);metrics.addProperty("sourceFps",clip.get("sourceFps").getAsDouble());
            metrics.addProperty("rootBreathRangeCm",range(samples,"chestY"));
            metrics.addProperty("headHeightRangeCm",coordinateRange(samples,"headCm",1));
            metrics.addProperty("rightPalmTravelCm",path(samples,"rightPalmCm"));metrics.addProperty("leftPalmTravelCm",path(samples,"leftPalmCm"));
            metrics.addProperty("rightTipTravelCm",path(samples,"rightCalibratedItemTipCm"));metrics.addProperty("leftTipTravelCm",path(samples,"leftCalibratedItemTipCm"));
            metrics.addProperty("maximumPalmFrameStepCm",Math.max(maxStep(samples,"rightPalmCm"),maxStep(samples,"leftPalmCm")));
            if(CLIPS.contains(id))results.add(metrics);else if(id.startsWith("dual-"))additionalDualGripClips.add(metrics);else additionalControlClips.add(metrics);
            JsonObject chestRotation=worldRotationSteps(clip.getAsJsonArray("frames"),"bodyWorldQuaternion");
            metrics.add("worldChestRotation",chestRotation);
            if(clip.get("sourceFps").getAsDouble()>=30)
                validate(chestRotation.get("maximumShortStableSameActionPhaseStepDegrees").getAsDouble()<=45,id+" same-action phase has no >45deg chest jump within 40ms while actor bodyYaw is stable");
            for(String side:List.of("right","left")) {
                JsonObject rotation=worldRotationSteps(clip.getAsJsonArray("frames"),side+"HandWorldQuaternion");
                metrics.add(side+"WorldHandRotation",rotation);
                if(clip.get("sourceFps").getAsDouble()>=30) {
                    validate(rotation.get("maximumShortStableWindupStepDegrees").getAsDouble()<=45,id+" "+side+" same-action windup has no >45deg hand jump within 40ms");
                    validate(rotation.get("maximumShortStableReadyReturnStepDegrees").getAsDouble()<=45,id+" "+side+" recovery to ready has no >45deg hand jump within 40ms");
                    if(id.startsWith("spear-"))validate(rotation.get("maximumShortStableSameActionPhaseStepDegrees").getAsDouble()<=45,id+" "+side+" same-action spear phase has no >45deg hand jump within 40ms while actor bodyYaw is stable");
                }
            }
            double backward=maximumClipBacktracking(samples);
            metrics.addProperty("maximumSameAttackVisualClipBacktrackingSeconds",backward);
            validate(backward<=.00001,id+" same attack advances its actual sampled visual clip monotonically");
            JsonObject activeTails=activeTailHandoffs(clip.getAsJsonArray("frames"));
            metrics.add("visualActiveTailHandoffs",activeTails);
            validate(activeTails.getAsJsonArray("failures").isEmpty(),id+" ordinary next windup preserves the previous visual ACTIVE tail within one actual render step");
            if(id.equals("dual-cuts-oblique")||id.equals("dual-direction-cycle")||id.equals("dual-front-armor")) {
                List<String> expected=id.equals("dual-cuts-oblique")
                        ?List.of("dual_ll","dual_ll","dual_rr","dual_rr","dual_ll","dual_rr","dual_rr","dual_ll")
                        :id.equals("dual-direction-cycle")?List.of("dual_ll","dual_ll","dual_ll","dual_rr","dual_rr","dual_rr")
                        :List.of("dual_ll","dual_ll","dual_rr","dual_rr");
                List<String> rendered=new ArrayList<>();
                for(var stroke:activeTails.getAsJsonArray("strokes"))rendered.add(stroke.getAsJsonObject().get("action").getAsString());
                metrics.add("expectedClickActions",RpgConfig.JSON.toJsonTree(expected));
                metrics.add("renderedClickActions",RpgConfig.JSON.toJsonTree(rendered));
                validate(rendered.equals(expected),id+" every left/right client click produces exactly one matching-hand ACTIVE stroke in order");
                validate(activeTails.get("completedOrDiscreteCoveredStrokes").getAsInt()==expected.size(),id+" every clicked stroke reaches its complete authored ACTIVE end within actual render-step tolerance");
                validate(activeTails.get("excludedActiveStrokes").getAsInt()==0,id+" has no unreviewed or truncated ACTIVE strokes");
            }
            if(id.startsWith("dual-")) {
                for(String side:List.of("right","left")) {
                    double flexion=max(samples,side+"WristFlexionDegrees");
                    double rotationStep=maxQuaternionStep(samples,side+"HandLocalQuaternion");
                    double twist=maxAbsolute(samples,side+"HandLocalTwistYDegrees");
                    double hiltError=max(samples,side+"HiltPalmErrorCm");
                    double hiltAlignment=samples.stream().mapToDouble(s->number(s,side+"HiltBladeAlignmentDot")).min().orElse(0);
                    metrics.addProperty(side+"MaximumWristFlexionDegrees",flexion);
                    metrics.addProperty(side+"MaximumLocalHandRotationFrameStepDegrees",rotationStep);
                    metrics.addProperty(side+"MaximumAbsoluteLocalHandTwistYDegrees",twist);
                    metrics.addProperty(side+"MaximumHiltPalmErrorCm",hiltError);
                    metrics.addProperty(side+"MinimumHiltBladeAlignmentDot",hiltAlignment);
                    metrics.addProperty(side+"MaximumForearmOffXHingeDegrees",max(samples,side+"ForearmOffXHingeDegrees"));
                    validate(flexion<=26,id+" "+side+" wrist flexion stays within 26deg in the final rendered rig");
                    validate(rotationStep<=35,id+" "+side+" local hand rotation has no >35deg frame discontinuity");
                    validate(finiteQuaternions(samples,side+"HandLocalQuaternion")&&finiteQuaternions(samples,side+"HandWorldQuaternion"),id+" "+side+" local and world hand quaternion samples are present and finite");
                    validate(twist<=90,id+" "+side+" local hand Y twist stays within 90deg");
                    validate(max(samples,side+"ForearmResidualOffXHingeDegrees")<=1,id+" "+side+" elbow stays on its flexion hinge after pronation");
                    validate(maxAbsolute(samples,side+"ForearmPronationDegrees")<=35.1,id+" "+side+" forearm pronation stays bounded");
                    validate(hiltError<.1,id+" "+side+" official sword hilt stays at the actual closed palm");
                    validate(hiltAlignment>.999,id+" "+side+" official blade axis follows the fixed 50 degree closed grip");
                }
            }
            if(id.startsWith("spear-")) {
                double maximumGripError=0,maximumGripOffset=0;
                for(var sample:samples) {
                    maximumGripError=Math.max(maximumGripError,Math.abs(distance(sample.getAsJsonArray("rightPalmCm"),sample.getAsJsonArray("leftPalmCm"))-58));
                    var right=sample.getAsJsonArray("rightPalmCm");var left=sample.getAsJsonArray("leftPalmCm");var axis=sample.getAsJsonArray("rightBladeAxis");
                    double projection=0,length2=0;for(int i=0;i<3;i++){double d=left.get(i).getAsDouble()-right.get(i).getAsDouble();projection+=d*axis.get(i).getAsDouble();length2+=d*d;}
                    maximumGripOffset=Math.max(maximumGripOffset,Math.sqrt(Math.max(0,length2-projection*projection)));
                }
                metrics.addProperty("maximumTwoHandGripLengthErrorCm",maximumGripError);
                metrics.addProperty("maximumFrontPalmDistanceFromShaftCm",maximumGripOffset);
                validate(maximumGripError<.5,id+" keeps midpoint and rear grip 58cm apart in final rendered rig");
                validate(maximumGripOffset<.5,id+" final rendered front palm remains on the rear-hand shaft axis");
                for(String side:List.of("right","left")) {
                    for(String joint:List.of("UpperArm","Forearm")) {
                        String key=side+joint+"WorldQuaternion";
                        JsonObject jointRotation=worldRotationSteps(clip.getAsJsonArray("frames"),key);
                        metrics.add(side+"World"+joint+"Rotation",jointRotation);
                        validate(finiteQuaternions(samples,key),id+" "+side+" "+joint+" world quaternion samples are present and finite");
                        if(clip.get("sourceFps").getAsDouble()>=30)
                            validate(jointRotation.get("maximumShortStableNonActivePhaseStepDegrees").getAsDouble()<=45,id+" "+side+" "+joint+" has no >45deg same-phase WINDUP/READY/RECOVERY jump within 40ms while actor bodyYaw is stable");
                    }
                    metrics.addProperty(side+"MaximumWristFlexionDegrees",max(samples,side+"WristFlexionDegrees"));
                    metrics.addProperty(side+"MaximumAbsoluteLocalHandTwistYDegrees",maxAbsolute(samples,side+"HandLocalTwistYDegrees"));
                    metrics.addProperty(side+"MaximumForearmOffXHingeDegrees",max(samples,side+"ForearmOffXHingeDegrees"));
                    validate(max(samples,side+"WristFlexionDegrees")<=26,id+" "+side+" spear wrist remains naturally aligned within 26deg");
                    validate(maxAbsolute(samples,side+"HandLocalTwistYDegrees")<=90,id+" "+side+" spear hand twist remains within 90deg");
                    validate(max(samples,side+"ForearmOffXHingeDegrees")<=1,id+" "+side+" spear elbow rotates only around its local X hinge");
                    validate(finiteQuaternions(samples,side+"ForearmLocalQuaternion")&&finiteQuaternions(samples,side+"HandLocalQuaternion")&&finiteQuaternions(samples,side+"HandWorldQuaternion"),id+" "+side+" spear arm quaternions are present and finite");
                    validate(maxQuaternionStep(samples,side+"HandLocalQuaternion")<=35,id+" "+side+" spear hand has no >35deg local rotation frame jump");
                }
                validate(max(samples,"rightHiltPalmErrorCm")<.1,id+" official spear rear grip remains at the actual palm");
                validate(samples.stream().mapToDouble(s->number(s,"rightHiltBladeAlignmentDot")).min().orElse(0)>.999,id+" official spear shaft follows the transverse closed-fist +Z axis");
            }
            if(id.endsWith("idle-breath")) {
                validate(range(samples,"chestY")>=1.2,id+" rendered root breath is visible (>=1.2cm peak-to-peak)");
                validate(coordinateRange(samples,"headCm",1)>=.7,id+" head follows rendered breathing rise");
            }
            if(id.endsWith("omnidirectional-movement")) {
                JsonArray directions=new JsonArray();metrics.add("movementBeats",directions);
                for(String direction:List.of("forward","backward","left","right","forward-left","backward-right","sprint","moving-attack")) {
                    List<JsonObject> selected=samples.stream().filter(s->s.get("reviewBeat").getAsString().equals(direction)).toList();
                    JsonObject d=new JsonObject();d.addProperty("beat",direction);d.addProperty("maximumTorsoTiltDegrees",max(selected,"torsoTiltDegrees"));
                    d.addProperty("maximumHeadTiltDegrees",max(selected,"headTiltDegrees"));d.addProperty("maximumLegRootShiftCm",maxBalance(selected));
                    d.addProperty("maximumLeanMovementDirectionDot",max(selected,"leanMovementDirectionDot"));
                    d.addProperty("actorTravelMeters",path(selected,"actorPositionMeters"));directions.add(d);
                    double required=direction.equals("sprint")?8:direction.equals("moving-attack")?2:6;
                    validate(max(selected,"torsoTiltDegrees")>=required,id+" "+direction+" visibly tilts torso (>= "+required+"deg)");
                    validate(max(selected,"headTiltDegrees")>=required*.65,id+" "+direction+" head follows body inclination");
                    if(!direction.equals("moving-attack"))validate(maxBalance(selected)>2,id+" "+direction+" shifts native lower-body roots with pelvis");
                    validate(path(selected,"actorPositionMeters")>.4,id+" "+direction+" comes from real native player movement");
                    if(!direction.equals("moving-attack"))validate(max(selected,"leanMovementDirectionDot")>.7,id+" "+direction+" body inclination follows actual movement direction");
                }
            }
            if(id.equals("dual-cuts-oblique")) {
                JsonArray cuts=new JsonArray();metrics.add("cuts",cuts);
                for(String skill:List.of("dual_ll","dual_rr")) {
                    List<JsonObject> selected=samples.stream().filter(s->s.get("skill").getAsString().equals(skill)).toList();
                    String side=skill.startsWith("dual_l")?"left":"right";
                    JsonObject cut=new JsonObject();cut.addProperty("skill",skill);cut.addProperty("palmTravelCm",path(selected,side+"PalmCm"));
                    cut.addProperty("tipTravelCm",path(selected,side+"CalibratedItemTipCm"));cuts.add(cut);
                    validate(!selected.isEmpty(),"actual client renders the fixed matching-hand cut "+skill);
                    validate(path(selected,side+"PalmCm")>45,"actual "+skill+" has a substantial cutting hand arc");
                    validate(path(selected,side+"CalibratedItemTipCm")>90,"official blade tip sweeps a cutting arc "+skill);
                }
                validate(samples.stream().noneMatch(s->List.of("dual_rl","dual_lr").contains(text(s,"skill"))),"ordinary dual clicks never select a cross-hand cut");
                validate(Math.max(maxStep(samples,"rightPalmCm"),maxStep(samples,"leftPalmCm"))<70,"dual cut transitions avoid a discontinuous palm teleport");
            }
            if(id.equals("spear-thrust-sweep-oblique")) {
                var first=samples.stream().filter(s->s.get("reviewBeat").getAsString().equals("thrust-1")).toList();
                // The first point aims at world yaw 0. The avatar bodyYaw can differ from that direction,
                // so FS local Z is not the thrust axis; inspect the world-facing palm trajectory.
                double axial=coordinateRange(first,"rightWorldPalmCm",2), sideways=coordinateRange(first,"rightWorldPalmCm",0);
                metrics.addProperty("firstThrustForwardPalmRangeCm",axial);metrics.addProperty("firstThrustSidewaysPalmRangeCm",sideways);
                metrics.addProperty("firstThrustReferenceAxis","world yaw 0; +Z forward and X perpendicular; actor-origin centimetres");
                validate(axial>20,"actual spear thrust drives both-grip carrier forward at least 20cm");
                validate(axial>sideways*1.5,"actual spear thrust follows a longitudinal line instead of a sideways cut");
            }
        }
        report.add("clips",results);report.addProperty("passed",validationFailures.isEmpty());
        report.add("additionalDualGripClips",additionalDualGripClips);
        report.add("additionalControlClips",additionalControlClips);
        report.add("failures",RpgConfig.JSON.toJsonTree(validationFailures));
        RpgConfig.atomicWrite(WeaponReview.ROOT.resolve("motion-review.json"),RpgConfig.JSON.toJson(report));
        if(!validationFailures.isEmpty())throw new AssertionError("Motion presentation checks failed: "+String.join("; ",validationFailures));
    }

    private void validate(boolean value,String message) {
        if(value)owner.check(true,message);
        else {validationFailures.add(message);System.out.println("MOTION FAIL "+message);}
    }

    private static double number(JsonObject sample,String key){return sample.has(key)?sample.get(key).getAsDouble():0;}
    private static double max(List<JsonObject> samples,String key){return samples.stream().mapToDouble(s->number(s,key)).max().orElse(0);}
    private static double maxAbsolute(List<JsonObject> samples,String key){return samples.stream().mapToDouble(s->Math.abs(number(s,key))).max().orElse(0);}
    private static double range(List<JsonObject> samples,String key){return max(samples,key)-samples.stream().mapToDouble(s->number(s,key)).min().orElse(0);}
    private static double coordinateRange(List<JsonObject> samples,String key,int component){return samples.stream().filter(s->s.has(key)).mapToDouble(s->s.getAsJsonArray(key).get(component).getAsDouble()).max().orElse(0)-samples.stream().filter(s->s.has(key)).mapToDouble(s->s.getAsJsonArray(key).get(component).getAsDouble()).min().orElse(0);}
    private static double distance(JsonArray a,JsonArray b){double sum=0;for(int i=0;i<3;i++){double d=a.get(i).getAsDouble()-b.get(i).getAsDouble();sum+=d*d;}return Math.sqrt(sum);}
    private static double path(List<JsonObject> samples,String key){double length=0;JsonArray previous=null;for(var sample:samples)if(sample.has(key)){JsonArray next=sample.getAsJsonArray(key);if(previous!=null)length+=distance(previous,next);previous=next;}return length;}
    private static double maxStep(List<JsonObject> samples,String key){double result=0;JsonArray previous=null;for(var sample:samples)if(sample.has(key)){JsonArray next=sample.getAsJsonArray(key);if(previous!=null)result=Math.max(result,distance(previous,next));previous=next;}return result;}
    private static double maxQuaternionStep(List<JsonObject> samples,String key) {
        double result=0;JsonArray previous=null;
        for(var sample:samples)if(sample.has(key)) {
            JsonArray next=sample.getAsJsonArray(key);
            if(previous!=null) {
                double dot=0;for(int i=0;i<4;i++)dot+=previous.get(i).getAsDouble()*next.get(i).getAsDouble();
                result=Math.max(result,Math.toDegrees(2*Math.acos(Math.clamp(Math.abs(dot),0,1))));
            }
            previous=next;
        }
        return result;
    }
    private static boolean finiteQuaternions(List<JsonObject> samples,String key) {
        if(samples.isEmpty())return false;
        for(var sample:samples) {
            if(!sample.has(key)||!sample.get(key).isJsonArray()||sample.getAsJsonArray(key).size()!=4)return false;
            for(var component:sample.getAsJsonArray(key))if(!Double.isFinite(component.getAsDouble()))return false;
        }
        return true;
    }
    private static String animationText(JsonObject sample,String key) {
        if(sample.has("renderAnimation")&&sample.getAsJsonObject("renderAnimation").has(key))return sample.getAsJsonObject("renderAnimation").get(key).getAsString();
        return key.equals("action")?sample.get("skill").getAsString():sample.get(key).getAsString();
    }
    private static double quaternionAngle(JsonArray a,JsonArray b) {
        double dot=0;for(int i=0;i<4;i++)dot+=a.get(i).getAsDouble()*b.get(i).getAsDouble();
        return Math.toDegrees(2*Math.acos(Math.clamp(Math.abs(dot),0,1)));
    }
    private static JsonArray observedQuaternion(JsonObject sample,String key) {
        if(sample.has(key))return sample.getAsJsonArray(key);
        // Archived captures already contain the exact final bone matrices. Reconstruct
        // their quaternions without rewriting the original render observations.
        String bone=key.equals("bodyWorldQuaternion")?"body"
                :key.endsWith("UpperArmWorldQuaternion")?key.substring(0,key.indexOf("UpperArm"))+"_arm"
                :key.endsWith("ForearmWorldQuaternion")?key.substring(0,key.indexOf("Forearm"))+"_forearm":"";
        if(!bone.isEmpty()&&sample.has("rig")&&sample.getAsJsonObject("rig").has(bone)) {
            JsonArray matrix=sample.getAsJsonObject("rig").getAsJsonArray(bone);
            float[] values=new float[16];for(int i=0;i<16;i++)values[i]=matrix.get(i).getAsFloat();
            var q=new org.joml.Matrix4f().set(values).getUnnormalizedRotation(new org.joml.Quaternionf()).normalize();
            return RpgConfig.JSON.toJsonTree(new float[]{q.x,q.y,q.z,q.w}).getAsJsonArray();
        }
        return null;
    }
    private static JsonObject worldRotationSteps(JsonArray frames,String key) {
        JsonObject result=new JsonObject();
        double maximum=0,maximumWindup=0,maximumSamePhase=0,maximumShortSamePhase=0,maximumNonActive=0,maximumShortWindup=0,maximumReadyReturn=0;
        double maximumSpeed=0;int windupPairs=0;
        JsonObject maximumPair=null,windupPair=null,samePhasePair=null,shortSamePhasePair=null,nonActivePair=null,speedPair=null;
        JsonObject shortStableByPhase=new JsonObject();
        for(int i=1;i<frames.size();i++) {
            var a=frames.get(i-1).getAsJsonObject();var b=frames.get(i).getAsJsonObject();
            var previous=a.getAsJsonObject("observation");var next=b.getAsJsonObject("observation");
            JsonArray oldQuaternion=observedQuaternion(previous,key),newQuaternion=observedQuaternion(next,key);
            if(oldQuaternion==null||newQuaternion==null)continue;
            double angle=quaternionAngle(oldQuaternion,newQuaternion);
            double dt=b.get("capturedAtSeconds").getAsDouble()-a.get("capturedAtSeconds").getAsDouble();
            String phaseA=animationText(previous,"phase"),phaseB=animationText(next,"phase");
            String actionA=animationText(previous,"action"),actionB=animationText(next,"action");
            double yawDelta=Math.abs(((number(next,"bodyYaw")-number(previous,"bodyYaw")+180)%360+360)%360-180);
            boolean sameAction=!actionA.isEmpty()&&actionA.equals(actionB);
            double speed=dt>0?angle/dt:0;
            JsonObject pair=new JsonObject();pair.addProperty("fromIndex",i-1);pair.addProperty("toIndex",i);
            pair.addProperty("fromFile",a.get("file").getAsString());pair.addProperty("toFile",b.get("file").getAsString());
            pair.addProperty("angleDegrees",angle);pair.addProperty("wallDeltaSeconds",dt);pair.addProperty("degreesPerSecond",speed);
            pair.addProperty("sampledTickDelta",number(next,"sampledTick")-number(previous,"sampledTick"));
            pair.addProperty("fromPhase",phaseA);pair.addProperty("toPhase",phaseB);pair.addProperty("fromAction",actionA);pair.addProperty("toAction",actionB);
            pair.addProperty("bodyYawDeltaDegrees",yawDelta);
            if(previous.has("renderAnimation"))pair.add("fromAnimation",previous.get("renderAnimation"));
            if(next.has("renderAnimation"))pair.add("toAnimation",next.get("renderAnimation"));
            if(angle>maximum){maximum=angle;maximumPair=pair;}
            if(speed>maximumSpeed){maximumSpeed=speed;speedPair=pair;}
            if(sameAction&&phaseA.equals(phaseB)&&angle>maximumSamePhase){maximumSamePhase=angle;samePhasePair=pair;}
            if(sameAction&&phaseA.equals(phaseB)&&dt>0&&dt<=.04&&yawDelta<5&&angle>maximumShortSamePhase){maximumShortSamePhase=angle;shortSamePhasePair=pair;}
            if(sameAction&&phaseA.equals(phaseB)&&dt>0&&dt<=.04&&yawDelta<5) {
                double oldPeak=shortStableByPhase.has(phaseA)?shortStableByPhase.get(phaseA).getAsDouble():0;
                if(angle>oldPeak)shortStableByPhase.addProperty(phaseA,angle);
                if(java.util.Set.of("WINDUP","READY","RECOVERY").contains(phaseA)&&angle>maximumNonActive){maximumNonActive=angle;nonActivePair=pair;}
            }
            if(sameAction&&phaseA.equals("WINDUP")&&phaseB.equals("WINDUP")) {
                windupPairs++;if(angle>maximumWindup){maximumWindup=angle;windupPair=pair;}
                if(dt>0&&dt<=.04&&yawDelta<5)maximumShortWindup=Math.max(maximumShortWindup,angle);
            }
            if(phaseA.equals("RECOVERY")&&phaseB.equals("READY")&&dt>0&&dt<=.04&&yawDelta<5)maximumReadyReturn=Math.max(maximumReadyReturn,angle);
        }
        result.addProperty("coordinateFrame","FS rig world; pair includes external bodyYaw delta to distinguish actor turning");
        result.addProperty("maximumStepDegrees",maximum);result.addProperty("maximumDegreesPerSecond",maximumSpeed);
        result.addProperty("sameActionWindupPairs",windupPairs);result.addProperty("maximumSameActionWindupStepDegrees",maximumWindup);
        result.addProperty("maximumSameActionPhaseStepDegrees",maximumSamePhase);
        result.addProperty("maximumShortStableSameActionPhaseStepDegrees",maximumShortSamePhase);
        result.addProperty("maximumShortStableNonActivePhaseStepDegrees",maximumNonActive);
        result.add("maximumShortStableByPhaseDegrees",shortStableByPhase);
        result.addProperty("maximumShortStableWindupStepDegrees",maximumShortWindup);
        result.addProperty("maximumShortStableReadyReturnStepDegrees",maximumReadyReturn);
        if(maximumPair!=null)result.add("maximumPair",maximumPair);
        if(windupPair!=null)result.add("maximumWindupPair",windupPair);
        if(samePhasePair!=null)result.add("maximumSamePhasePair",samePhasePair);
        if(shortSamePhasePair!=null)result.add("maximumShortStableSamePhasePair",shortSamePhasePair);
        if(nonActivePair!=null)result.add("maximumShortStableNonActivePhasePair",nonActivePair);
        if(speedPair!=null)result.add("maximumSpeedPair",speedPair);
        return result;
    }
    private static double maximumClipBacktracking(List<JsonObject> samples) {
        double result=0;
        for(int i=1;i<samples.size();i++) {
            var previous=samples.get(i-1);var next=samples.get(i);
            if(!previous.has("renderAnimation")||!next.has("renderAnimation"))continue;
            var a=previous.getAsJsonObject("renderAnimation");var b=next.getAsJsonObject("renderAnimation");
            if(!a.has("sampledClipSeconds")||!b.has("sampledClipSeconds")||!a.has("started")||!b.has("started"))continue;
            if(!java.util.Set.of("WINDUP","ACTIVE","RECOVERY").contains(animationText(previous,"phase"))||!java.util.Set.of("WINDUP","ACTIVE","RECOVERY").contains(animationText(next,"phase")))continue;
            if(animationText(previous,"action").equals(animationText(next,"action"))&&Math.abs(a.get("started").getAsDouble()-b.get("started").getAsDouble())<.00001)
                result=Math.max(result,a.get("sampledClipSeconds").getAsDouble()-b.get("sampledClipSeconds").getAsDouble());
        }
        return result;
    }
    /** Validate actual visual contact playback, independently of the current server phase. */
    private static JsonObject activeTailHandoffs(JsonArray frames) {
        JsonObject result=new JsonObject();JsonArray strokes=new JsonArray(),failures=new JsonArray();
        int activeStrokes=0,coveredStrokes=0,protectedHandoffs=0,excluded=0;
        for(int first=0;first<frames.size();) {
            JsonObject firstSample=frames.get(first).getAsJsonObject().getAsJsonObject("observation");
            if(!firstSample.has("renderAnimation")){first++;continue;}
            JsonObject initial=firstSample.getAsJsonObject("renderAnimation");
            int last=first;
            while(last+1<frames.size()&&sameVisualBinding(initial,
                    frames.get(last+1).getAsJsonObject().getAsJsonObject("observation")))last++;
            double windup=number(initial,"windupTicks"),active=number(initial,"activeTicks"),end=windup+active;
            double clipEnd=number(initial,"activeEndSeconds"),clipBegin=number(initial,"activeBeginSeconds");
            boolean seenActive=false,presentedEnd=false,pendingWindup=false;
            double maximumElapsed=-1,maximumSeconds=-1;
            for(int i=first;i<=last;i++) {
                JsonObject animation=frames.get(i).getAsJsonObject().getAsJsonObject("observation").getAsJsonObject("renderAnimation");
                double elapsed=animation.has("sampledElapsedTicks")?number(animation,"sampledElapsedTicks"):-1;
                double seconds=animation.has("sampledClipSeconds")?number(animation,"sampledClipSeconds"):-1;
                if(active>0&&elapsed>=windup-.00001&&elapsed<end-.00001&&seconds>=clipBegin-.00001&&seconds<clipEnd-.00001)seenActive=true;
                if(active>0&&elapsed>=end-.00001&&seconds>=clipEnd-.00001)presentedEnd=true;
                maximumElapsed=Math.max(maximumElapsed,elapsed);maximumSeconds=Math.max(maximumSeconds,seconds);
                if(animation.has("pendingAction")) {
                    JsonObject pending=animation.getAsJsonObject("pendingAction");
                    pendingWindup|=text(pending,"phase").equals("WINDUP")
                            &&text(pending,"style").equals(text(initial,"style"))
                            &&text(pending,"model").equals(text(initial,"model"));
                }
            }
            if(seenActive) {
                activeStrokes++;
                JsonObject lastFrame=frames.get(last).getAsJsonObject(),oldSample=lastFrame.getAsJsonObject("observation");
                JsonObject old=oldSample.getAsJsonObject("renderAnimation");
                JsonObject stroke=new JsonObject();stroke.addProperty("action",text(initial,"action"));
                stroke.addProperty("bindingStarted",number(initial,"started"));stroke.addProperty("firstFrame",first);stroke.addProperty("lastFrame",last);
                stroke.addProperty("maximumSampledElapsedTicks",maximumElapsed);stroke.addProperty("maximumSampledClipSeconds",maximumSeconds);
                stroke.addProperty("activeEndElapsedTicks",end);stroke.addProperty("activeEndClipSeconds",clipEnd);stroke.addProperty("actualEndPresented",presentedEnd);
                stroke.addProperty("lastFile",text(lastFrame,"file"));stroke.add("fromAnimation",old);
                boolean ordinary=false;double allowedStep=0;
                if(last+1<frames.size()) {
                    JsonObject nextFrame=frames.get(last+1).getAsJsonObject(),nextSample=nextFrame.getAsJsonObject("observation");
                    if(nextSample.has("renderAnimation")) {
                        JsonObject next=nextSample.getAsJsonObject("renderAnimation");
                        // Forced presentations, asset/style changes, death and missing bindings never
                        // establish an ordinary attack handoff. READY is only completion diagnostics.
                        boolean alive=(!old.has("actorAlive")||old.get("actorAlive").getAsBoolean())
                                &&(!next.has("actorAlive")||next.get("actorAlive").getAsBoolean());
                        boolean drawn=(!next.has("drawn")||next.get("drawn").getAsBoolean());
                        boolean samePresentation=text(oldSample,"style").equals(text(nextSample,"style"))
                                &&text(old,"style").equals(text(next,"style"))&&text(old,"model").equals(text(next,"model"))
                                &&(!old.has("assetIdentity")||!next.has("assetIdentity")||number(old,"assetIdentity")==number(next,"assetIdentity"));
                        boolean normalPhase=java.util.Set.of("WINDUP","ACTIVE","RECOVERY").contains(text(next,"phase"));
                        ordinary=alive&&drawn&&samePresentation&&number(next,"activeTicks")>0
                                &&(text(next,"phase").equals("WINDUP")||pendingWindup&&normalPhase);
                        double dt=number(nextFrame,"capturedAtSeconds")-number(lastFrame,"capturedAtSeconds");
                        double nativeStep=number(nextSample,"sampledTick")-number(oldSample,"sampledTick");
                        // A contact clock is exactly 1x. Only one measured, non-regressing native
                        // render step up to 40ms is a legal discrete endpoint tolerance; no FPS guess.
                        if(dt>0&&dt<=.04&&nativeStep>0&&nativeStep<=.80001)allowedStep=nativeStep;
                        stroke.addProperty("nextFrame",last+1);stroke.addProperty("nextFile",text(nextFrame,"file"));
                        stroke.addProperty("wallDeltaSeconds",dt);stroke.addProperty("sampledTickDelta",nativeStep);stroke.add("toAnimation",next);
                    }
                }
                boolean discrete=ordinary&&maximumElapsed+allowedStep>=end-.00001;
                boolean covered=presentedEnd||discrete;
                stroke.addProperty("ordinaryNextWindup",ordinary);stroke.addProperty("allowedActualRenderStepTicks",ordinary?allowedStep:0);
                stroke.addProperty("coveredWithinActualRenderStep",covered);stroke.addProperty("remainingVisualActiveTicks",Math.max(0,end-maximumElapsed));
                if(ordinary) {
                    protectedHandoffs++;
                    if(!covered)failures.add(stroke);
                } else if(!presentedEnd)excluded++;
                if(covered)coveredStrokes++;
                strokes.add(stroke);
            }
            first=last+1;
        }
        result.addProperty("visualActiveStrokes",activeStrokes);result.addProperty("protectedOrdinaryWindupHandoffs",protectedHandoffs);
        result.addProperty("completedOrDiscreteCoveredStrokes",coveredStrokes);result.addProperty("excludedActiveStrokes",excluded);
        result.addProperty("tolerance","one measured native render step <=40ms at contact clock 1x; no exact floating-point marker requirement");
        result.add("strokes",strokes);result.add("failures",failures);return result;
    }
    private static boolean sameVisualBinding(JsonObject initial,JsonObject sample) {
        if(!sample.has("renderAnimation"))return false;
        JsonObject next=sample.getAsJsonObject("renderAnimation");
        return text(initial,"action").equals(text(next,"action"))&&Math.abs(number(initial,"started")-number(next,"started"))<.00001
                &&text(initial,"style").equals(text(next,"style"))&&text(initial,"model").equals(text(next,"model"))
                &&(!initial.has("assetIdentity")||!next.has("assetIdentity")||number(initial,"assetIdentity")==number(next,"assetIdentity"));
    }
    private static String text(JsonObject value,String key){return value.has(key)&&!value.get(key).isJsonNull()?value.get(key).getAsString():"";}
    private static double maxBalance(List<JsonObject> samples){return samples.stream().mapToDouble(s->Math.hypot(number(s,"balanceXcm"),number(s,"balanceZcm"))).max().orElse(0);}
}
