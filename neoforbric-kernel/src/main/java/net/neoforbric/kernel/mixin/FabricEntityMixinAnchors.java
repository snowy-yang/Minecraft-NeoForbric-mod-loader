/* Copyright 2026 The NeoForbric Project. Licensed under the Apache License, Version 2.0. */
package net.neoforbric.kernel.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import net.neoforbric.kernel.util.NeoForbricLog;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Preserve Fabric entity callbacks at the corresponding stage of the pinned 1.21.1 NeoForge body.
 * Sleeping and occupancy retain their original handlers: the monster check is redirected into the
 * lambda the native startSleepInBed actually invokes, occupancy bridges its audited handled-result
 * contract to the native bed setter (including native beds with no vanilla OCCUPIED property), and the
 * sleeping direction wraps the answer of the NeoForge body that asks the bed block instead of
 * BedBlock.getBedOrientation. The 26.x rows are gone with their premises: this base has no
 * canGlide/GLIDING_FLIGHT attribute rework and no empty-glider guard in updateFallFlying (fabric's own
 * elytra mixin binds as authored), and the pinned 0.116.17 module ships no ServerMobEffectEvents and no
 * effect/ mixin (the clear-all veto table now holds third-party rows only, e.g. balm's MOB_EFFECT_REMOVE). */
public final class FabricEntityMixinAnchors {
 public static final String PROPERTY="neoforbric.fabricEntityAnchors";
 private static final String LIVING="net/minecraft/world/entity/LivingEntity";
 private static final String BASE="net/fabricmc/fabric/mixin/entity/event/";
 private static final String EFFECT="Lnet/minecraft/world/effect/MobEffectInstance;";
 private static final String ENTITY="Lnet/minecraft/world/entity/Entity;";
 private static final String CI="Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
 private static final String CIR="Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";
 private FabricEntityMixinAnchors() { }
 public static int adapt(ClassNode mixin,Function<String,ClassNode> targets) {
  if("off".equalsIgnoreCase(System.getProperty(PROPERTY,"on")))return 0;
  if(!mixin.name.startsWith(BASE)&&CLEAR_ALL_VETOES.stream().anyMatch(v->v.mixin().equals(mixin.name))) {
   // Another Fabric mod's own clear-all veto (balm's): only that row, on LivingEntity.
   ClassNode living=targets.apply(LIVING);MethodNode remove=living==null?null:method(living,"removeAllEffects","()Z");
   int changed=remove==null?0:earlyRemoveVeto(mixin,remove);
   if(changed>0)NeoForbricLog.info("[NeoForbric/Mixin] %s's clear-all effect veto now wraps NeoForge's per-effect EventHooks.onEffectRemoved "
     +"— the merged removeAllEffects has no activeEffects.clear() for its wrap to bind to",mixin.name.replace('/','.'));
   return changed;
  }
  if(!List.of(BASE+"ServerPlayerEntityMixin",BASE+"LivingEntityMixin").contains(mixin.name))return 0;
  ClassNode target=targets.apply(mixin.name.equals(BASE+"ServerPlayerEntityMixin")?"net/minecraft/server/level/ServerPlayer":LIVING);if(target==null)return 0;int changed=0;
  if(mixin.name.equals(BASE+"ServerPlayerEntityMixin"))changed+=sleepLambda(mixin,target);
  if(mixin.name.equals(BASE+"LivingEntityMixin"))changed+=bedOccupation(mixin,target);
  if(mixin.name.equals(BASE+"LivingEntityMixin"))changed+=sleepDirection(mixin,target);
  if(changed>0)NeoForbricLog.info("[NeoForbric/Mixin] restored %d entity callback anchor(s) in %s at the corresponding native decision stage",changed,mixin.name.replace('/','.'));
  return changed;
 }
 private static int sleepLambda(ClassNode mixin,ClassNode target) {
  MethodNode handler=method(mixin,"hasNoMonstersNearby","(Ljava/util/List;Lnet/minecraft/core/BlockPos;)Z");
  MethodNode host=method(target,"startSleepInBed","(Lnet/minecraft/core/BlockPos;)Lcom/mojang/datafixers/util/Either;");
  if(handler==null||host==null||hasGroup(handler.visibleAnnotations)||hasGroup(handler.invisibleAnnotations)
    ||countCalls(host,"java/util/List","isEmpty","()Z")!=0)return 0;
  AnnotationNode inject=MixinFit.injectorOf(handler);
  // The module writes its selector in the names it was authored with: yarn "trySleep", which the refmap
  // resolves onto this base's startSleepInBed — never this base's own spelling.
  if(inject==null||!inject.desc.equals("Lorg/spongepowered/asm/mixin/injection/Redirect;")
    ||!MixinFit.stringList(MixinFit.value(inject,"method")).equals(List.of("trySleep")))return 0;
  List<AnnotationNode> points=MixinFit.atNodes(inject);
  if(points.size()!=1||!"Ljava/util/List;isEmpty()Z".equals(MixinFit.value(points.getFirst(),"target")))return 0;
  List<AbstractInsnNode> body=code(host);if(body.size()<4||!(body.get(0) instanceof VarInsnNode self)||self.var!=0||self.getOpcode()!=Opcodes.ALOAD
    ||!(body.get(1) instanceof VarInsnNode pos)||pos.var!=1||pos.getOpcode()!=Opcodes.ALOAD
    ||!(body.get(2) instanceof InvokeDynamicInsnNode capture)||!capture.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")
    ||!capture.desc.equals("(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/core/BlockPos;)Ljava/util/function/Supplier;")
    ||!call(body.get(3),"java/util/function/Supplier","get","()Ljava/lang/Object;"))return 0;
  List<MethodNode> matches=new ArrayList<>();
  for(Object argument:capture.bsmArgs)if(argument instanceof org.objectweb.asm.Handle h && h.getOwner().equals(target.name)
    &&h.getName().startsWith("lambda$startSleepInBed$")&&h.getDesc().equals(host.desc)
    &&(h.getTag()==Opcodes.H_INVOKEVIRTUAL||h.getTag()==Opcodes.H_INVOKESPECIAL)) {
   MethodNode lambda=method(target,h.getName(),h.getDesc());if(lambda!=null&&countCalls(lambda,"java/util/List","isEmpty","()Z")==1)matches.add(lambda);
  }
  if(matches.size()!=1)return 0;
  set(inject,"method",new ArrayList<>(List.of(matches.getFirst().name+matches.getFirst().desc)));return 1;
 }
 private static final String STATE="net/minecraft/world/level/block/state/BlockState";
 private static final String LEVEL="net/minecraft/world/level/Level";
 private static final String POSITION="Lnet/minecraft/core/BlockPos;";
 private static final String BED_CALL="(L"+LEVEL+";"+POSITION+"L"+LIVING+";Z)V";
 // The exact instruction body of the occupancy redirect in the pinned fabric-entity-events-v1 0.116.17, as the
 // module remap carries it into the base's names — identical to the 0.155.2 body the row was first pinned from:
// the handler never changed between them. Frame/debug/access metadata is ignored.
 private static final String BED_BODY="f783fdec79ad88be77806d13c6c1804729677aa315cf9e8d6f1156351efee6e0";
 private static int bedOccupation(ClassNode mixin,ClassNode target) {
  MethodNode old=method(mixin,"setOccupiedState","(L"+LEVEL+";"+POSITION+"L"+STATE+";I)Z");
  if(old==null||hasGroup(old.visibleAnnotations)||hasGroup(old.invisibleAnnotations)||!bodyHash(old).equals(BED_BODY))return 0;
  AnnotationNode redirect=MixinFit.injectorOf(old);
  if(redirect==null||!redirect.desc.equals("Lorg/spongepowered/asm/mixin/injection/Redirect;"))return 0;
  List<String> selectors=MixinFit.stringList(MixinFit.value(redirect,"method"));
  // 0.116.17 writes its selectors in the names the module was authored with — yarn "sleep" beside the
  // intermediary synthetic "method_18404" — and its refmap resolves them onto vanilla's startSleeping and
  // lambda$stopSleeping$9; this base renumbered the wake lambda ($12) and moved both bodies off
  // Level.setBlockState, so the redirect is dead here twice over. The hosts are therefore found by the
  // audited setter contract below, never by reading a selector or its refmap value as a base name.
  if(!new java.util.HashSet<>(selectors).equals(java.util.Set.of("sleep","method_18404")))return 0;
  List<AnnotationNode> points=MixinFit.atNodes(redirect);
  if(points.size()!=1||!"Lnet/minecraft/world/World;setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;I)Z".equals(MixinFit.value(points.getFirst(),"target")))return 0;
  List<String> hosts=new ArrayList<>();
  for(MethodNode candidate:target.methods) {
   if(!candidate.desc.equals("("+POSITION+")V"))continue;
   if(countCalls(candidate,LEVEL,"setBlock","("+POSITION+"L"+STATE+";I)Z")!=0)continue;
   if(countCalls(candidate,STATE,"setBedOccupied",BED_CALL)!=1)continue;
   hosts.add(candidate.name+candidate.desc);
  }
  if(hosts.size()!=2)return 0; // the sleep path and the wake-up lambda: exactly the two bodies the patch moved
  String desc="(L"+STATE+";L"+LEVEL+";"+POSITION+"L"+LIVING+";Z)V";
  if(method(mixin,"neoforbric$setBedOccupied",desc)!=null)return 0;
  MethodNode bridge=new MethodNode(Opcodes.ACC_PRIVATE,"neoforbric$setBedOccupied",desc,null,null);
  bridge.visibleAnnotations=new ArrayList<>(List.of(redirect));
  if(old.visibleAnnotations!=null)old.visibleAnnotations.remove(redirect);if(old.invisibleAnnotations!=null)old.invisibleAnnotations.remove(redirect);
  set(points.getFirst(),"target","L"+STATE+";setBedOccupied"+BED_CALL);
  // The moved redirect cannot keep the module's own selectors: their refmap values name vanilla's bodies,
  // and the patch renumbered the wake lambda, so at apply time they would bridge the sleep path alone. The
  // selectors are rewritten to the two hosts just proven, exactly as the monster-check row rewrites its own.
  set(redirect,"method",new ArrayList<>(hosts));
  InsnList code=bridge.instructions;LabelNode nativePath=new LabelNode(),perform=new LabelNode(),end=new LabelNode();
  code.add(new VarInsnNode(Opcodes.ALOAD,2));code.add(new VarInsnNode(Opcodes.ALOAD,3));
  code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,LEVEL,"getBlockState","("+POSITION+")L"+STATE+";",false));code.add(new VarInsnNode(Opcodes.ASTORE,6));
  String event="net/fabricmc/fabric/api/entity/event/v1/EntitySleepEvents",callback=event+"$SetBedOccupationState";
  code.add(new FieldInsnNode(Opcodes.GETSTATIC,event,"SET_BED_OCCUPATION_STATE","Lnet/fabricmc/fabric/api/event/Event;"));
  code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,"net/fabricmc/fabric/api/event/Event","invoker","()Ljava/lang/Object;",false));
  code.add(new TypeInsnNode(Opcodes.CHECKCAST,callback));code.add(new VarInsnNode(Opcodes.ALOAD,4));code.add(new VarInsnNode(Opcodes.ALOAD,3));code.add(new VarInsnNode(Opcodes.ALOAD,6));code.add(new VarInsnNode(Opcodes.ILOAD,5));
  code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,callback,"setBedOccupationState","(L"+LIVING+";"+POSITION+"L"+STATE+";Z)Z",true));
  code.add(new JumpInsnNode(Opcodes.IFEQ,nativePath));code.add(new InsnNode(Opcodes.RETURN));
  code.add(nativePath);code.add(new FrameNode(Opcodes.F_APPEND,1,new Object[]{STATE},0,null));
  code.add(new VarInsnNode(Opcodes.ALOAD,6));code.add(new FieldInsnNode(Opcodes.GETSTATIC,"net/minecraft/world/level/block/BedBlock","OCCUPIED","Lnet/minecraft/world/level/block/state/properties/BooleanProperty;"));
  code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,STATE,"hasProperty","(Lnet/minecraft/world/level/block/state/properties/Property;)Z",false));code.add(new JumpInsnNode(Opcodes.IFNE,perform));
  code.add(new VarInsnNode(Opcodes.ALOAD,6));code.add(new VarInsnNode(Opcodes.ALOAD,2));code.add(new VarInsnNode(Opcodes.ALOAD,3));code.add(new VarInsnNode(Opcodes.ALOAD,4));
  code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,STATE,"isBed","(Lnet/minecraft/world/level/BlockGetter;"+POSITION+"L"+LIVING+";)Z",false));code.add(new JumpInsnNode(Opcodes.IFEQ,end));
  code.add(perform);code.add(new FrameNode(Opcodes.F_SAME,0,null,0,null));
  code.add(new VarInsnNode(Opcodes.ALOAD,6));code.add(new VarInsnNode(Opcodes.ALOAD,2));code.add(new VarInsnNode(Opcodes.ALOAD,3));code.add(new VarInsnNode(Opcodes.ALOAD,4));code.add(new VarInsnNode(Opcodes.ILOAD,5));
  code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,STATE,"setBedOccupied",BED_CALL,false));code.add(end);code.add(new FrameNode(Opcodes.F_SAME,0,null,0,null));code.add(new InsnNode(Opcodes.RETURN));
  bridge.maxStack=5;bridge.maxLocals=7;mixin.methods.add(bridge);return 1;
 }
 /** The pinned 0.116.17 module's onGetSleepingDirection, as its remap names it: one Operation.call, then
 * MODIFY_SLEEPING_DIRECTION — the same body the 5.0.4/5.0.5 pin was first derived from. */
 static final String SLEEP_DIRECTION_BODY="6a7ab73c3bbee71f32b7af99490b87be878b9208bda2296f9240638ad59d7a73";
 private static final String DIRECTION="Lnet/minecraft/core/Direction;";
 /** Fabric's MODIFY_SLEEPING_DIRECTION wraps vanilla's BedBlock.getBedOrientation in LivingEntity.getBedOrientation —
  * what the sleeping body is drawn and the sleeping camera faced by. NeoForge's body asks the block instead
  * (isBed, getBedDirection) and never calls it, so the wrap bound nowhere. The same event is asked on the answer:
  * when the entity has a sleeping position, the native direction (FACING for a vanilla bed, the block's own for a
  * modded one, Direction.UP for a spot no bed answers on this base) goes through the listeners, as vanilla's did. */
 private static int sleepDirection(ClassNode mixin,ClassNode target) {
  MethodNode host=method(target,"getBedOrientation","()"+DIRECTION);
  if(host==null||countCalls(host,"net/minecraft/world/level/block/BedBlock","getBedOrientation","(Lnet/minecraft/world/level/BlockGetter;"+POSITION+")"+DIRECTION)!=0
    ||countCalls(host,STATE,"isBed","(Lnet/minecraft/world/level/BlockGetter;"+POSITION+"L"+LIVING+";)Z")!=1
    ||countCalls(host,STATE,"getBedDirection","(Lnet/minecraft/world/level/LevelReader;"+POSITION+")"+DIRECTION)!=1
    ||countCalls(host,LIVING,"getSleepingPos","()Ljava/util/Optional;")!=1)return 0;
  MethodNode old=method(mixin,"onGetSleepingDirection","(Lnet/minecraft/world/level/BlockGetter;"+POSITION+"L"+OPERATION+";)"+DIRECTION);
  if(old==null||hasGroup(old.visibleAnnotations)||hasGroup(old.invisibleAnnotations)||!bodyHash(old).equals(SLEEP_DIRECTION_BODY))return 0;
  AnnotationNode wrap=MixinFit.injectorOf(old);
  // Both strings are the module's own: the selector is yarn "getSleepingDirection" (the refmap resolves it
  // onto this base's getBedOrientation) and the wrapped call is yarn BedBlock.getDirection — the vanilla
  // helper this base no longer calls, which is why the wrap is dead here in the first place.
  if(wrap==null||!wrap.desc.equals("Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;")
    ||!MixinFit.stringList(MixinFit.value(wrap,"method")).equals(List.of("getSleepingDirection")))return 0;
  List<AnnotationNode> points=MixinFit.atNodes(wrap);
  if(points.size()!=1||!"INVOKE".equals(MixinFit.value(points.getFirst(),"value"))
    ||!"Lnet/minecraft/block/BedBlock;getDirection(Lnet/minecraft/world/BlockView;Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/util/math/Direction;".equals(MixinFit.value(points.getFirst(),"target")))return 0;
  String desc="("+DIRECTION+")"+DIRECTION;
  if(method(mixin,"neoforbric$modifySleepingDirection",desc)!=null)return 0;
  if(old.visibleAnnotations!=null)old.visibleAnnotations.remove(wrap);if(old.invisibleAnnotations!=null)old.invisibleAnnotations.remove(wrap);
  MethodNode handler=new MethodNode(Opcodes.ACC_PRIVATE,"neoforbric$modifySleepingDirection",desc,null,null);
  AnnotationNode at=new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");at.values=new ArrayList<>(List.of("value","RETURN"));
  AnnotationNode modify=new AnnotationNode("Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;");
  modify.values=new ArrayList<>(List.of("method",new ArrayList<>(List.of("getBedOrientation()"+DIRECTION)),"at",new ArrayList<>(List.of(at))));
  handler.visibleAnnotations=new ArrayList<>(List.of(modify));
  // Every return in the body as it stands when injections are prepared (an @Overwrite's included). An early return
  // another mixin's cancellable @Inject adds is not seen — it bypassed Fabric's own wrap of the inner call too. Without
  // a sleeping position the answer is passed through untouched, which is when vanilla never reached the wrapped call.
  InsnList code=handler.instructions;LabelNode fire=new LabelNode();
  code.add(new VarInsnNode(Opcodes.ALOAD,0));code.add(new TypeInsnNode(Opcodes.CHECKCAST,LIVING));
  code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,LIVING,"getSleepingPos","()Ljava/util/Optional;",false));
  code.add(new InsnNode(Opcodes.ACONST_NULL));code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,"java/util/Optional","orElse","(Ljava/lang/Object;)Ljava/lang/Object;",false));
  code.add(new TypeInsnNode(Opcodes.CHECKCAST,"net/minecraft/core/BlockPos"));code.add(new VarInsnNode(Opcodes.ASTORE,2));
  code.add(new VarInsnNode(Opcodes.ALOAD,2));code.add(new JumpInsnNode(Opcodes.IFNONNULL,fire));
  code.add(new VarInsnNode(Opcodes.ALOAD,1));code.add(new InsnNode(Opcodes.ARETURN));
  code.add(fire);code.add(new FrameNode(Opcodes.F_APPEND,1,new Object[]{"net/minecraft/core/BlockPos"},0,null));
  String event="net/fabricmc/fabric/api/entity/event/v1/EntitySleepEvents",callback=event+"$ModifySleepingDirection";
  code.add(new FieldInsnNode(Opcodes.GETSTATIC,event,"MODIFY_SLEEPING_DIRECTION","Lnet/fabricmc/fabric/api/event/Event;"));
  code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,"net/fabricmc/fabric/api/event/Event","invoker","()Ljava/lang/Object;",false));
  code.add(new TypeInsnNode(Opcodes.CHECKCAST,callback));
  code.add(new VarInsnNode(Opcodes.ALOAD,0));code.add(new TypeInsnNode(Opcodes.CHECKCAST,LIVING));code.add(new VarInsnNode(Opcodes.ALOAD,2));code.add(new VarInsnNode(Opcodes.ALOAD,1));
  code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,callback,"modifySleepDirection","(L"+LIVING+";"+POSITION+DIRECTION+")"+DIRECTION,true));
  code.add(new InsnNode(Opcodes.ARETURN));
  handler.maxStack=4;handler.maxLocals=3;mixin.methods.add(handler);
  NeoForbricLog.info("[NeoForbric/Mixin] fabric-api's sleeping direction now modifies LivingEntity.getBedOrientation's answer — "
    +"NeoForge's body asks the bed block instead of BedBlock.getBedOrientation, so the wrap bound nowhere");
  return 1;
 }
 private static final String OPERATION="com/llamalad7/mixinextras/injector/wrapoperation/Operation";
 // NeoForge 21.1's removeAllEffects asks EventHooks.onEffectRemoved(LivingEntity, MobEffectInstance, EffectCure) —
 // the per-effect question the clear-all veto wraps. The 26.x pin spelled it without the EffectCure argument,
 // which no body of this base answers.
 private static final String EFFECT_CURE="Lnet/neoforged/neoforge/common/EffectCure;";
 private static final String EFFECT_REMOVED="(L"+LIVING+";"+EFFECT+EFFECT_CURE+")Z";
 /** {@code -Dneoforbric.balmEffectVeto=off} leaves balm's clear-all effect veto on the dead activeEffects.clear(). */
 public static final String BALM_VETO_PROPERTY="neoforbric.balmEffectVeto";
 static final String BALM_MIXIN="net/blay09/mods/balm/fabric/internal/mixin/LivingEntityMixin";
 /** balm-fabric 26.2.0.9's clearAllEffects handler and the MOB_EFFECT_REMOVE question its stream filter asks (lambda$clearAllEffects$0). */
 static final String BALM_HANDLER_BODY="a536e29d5ba35f91d65495dd7ace11ece4cef7e86ab37f4f7088e7a1b14b56d0";
 static final String BALM_QUESTION_BODY="554f63a416f6a64675e733d3a9293d4309e8239bcfaf5631de0c55a279b35973";
 private static final String BALM_EVENT="net/blay09/mods/balm/platform/event/Event";
 private static final String BALM_BEFORE="net/blay09/mods/balm/platform/event/callback/LivingEntityCallback$MobEffectCallback$Remove$Before";
 /**
  * A reviewed "clear every effect" veto: a Fabric mod wraps vanilla's activeEffects.clear() in removeAllEffects and puts
  * back what its listeners veto. NeoForge's body has no clear() — it asks EventHooks.onEffectRemoved once per effect and
  * keeps the effect when that answers true — so each row's veto becomes a wrap of that one call: NeoForge keeping it
  * keeps it, otherwise the mod's own question decides. A vetoed effect is also never handed to onEffectsRemoved, which
  * the clear()-and-put-back originals could not avoid.
  *
  * <p>A row names the mixin and its dead wrap, how that handler is recognised as the one reviewed, the name of the wrap
  * that replaces it, and {@code ask}: the mod's question, entered when NeoForge (and any inner wrap) said "remove", with
  * an empty stack and locals {@code this, entity, instance, operation}, returning true to keep the effect on every path.
  */
 record ClearAllVeto(String mixin,String handler,String handlerDesc,String property,String generated,
   java.util.function.BiPredicate<ClassNode,MethodNode> recognised,java.util.function.BiConsumer<ClassNode,InsnList> ask) { }
 static final List<ClearAllVeto> CLEAR_ALL_VETOES=List.of(
   // fabric-api's ServerMobEffectEvents.ALLOW_EARLY_REMOVE row is gone with the pin: 0.116.17+1.21.1 ships
   // no ServerMobEffectEvents and no effect/ mixin — the API arrived in a later fabric-api generation. A
   // 1.21.1-era balm row follows the same rule when its pin is re-derived.
   // balm's MOB_EFFECT_REMOVE (Balm.events().onEvent(...) listeners, e.g. a mod keeping one effect through milk). Its
   // question sits in a stream-filter lambda, so the handler AND that lambda are pinned by fingerprint and the question
   // is asked here exactly as the lambda asks it: allowRemove(entity, the effect's holder, the instance).
   new ClearAllVeto(BALM_MIXIN,"clearAllEffects","(Ljava/util/Map;L"+OPERATION+";Ljava/util/Map;)V",BALM_VETO_PROPERTY,
     "neoforbric$balmAllowRemove",FabricEntityMixinAnchors::balmVeto,FabricEntityMixinAnchors::askBalm));
 private static int earlyRemoveVeto(ClassNode mixin,MethodNode remove) {
  if(countCalls(remove,"java/util/Map","clear","()V")!=0||countCalls(remove,"net/neoforged/neoforge/event/EventHooks","onEffectRemoved",EFFECT_REMOVED)!=1)return 0;
  int changed=0;
  for(ClearAllVeto veto:CLEAR_ALL_VETOES) {
   if(!veto.mixin().equals(mixin.name)||(veto.property()!=null&&"off".equalsIgnoreCase(System.getProperty(veto.property(),"on"))))continue;
   MethodNode old=method(mixin,veto.handler(),veto.handlerDesc());
   if(old==null||hasGroup(old.visibleAnnotations)||hasGroup(old.invisibleAnnotations))continue;
   AnnotationNode wrap=MixinFit.injectorOf(old);
   if(wrap==null||!wrap.desc.equals("Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;")
     ||!List.of(List.of("removeAllEffects"),List.of("removeAllEffects()Z")).contains(MixinFit.stringList(MixinFit.value(wrap,"method"))))continue;
   List<AnnotationNode> points=MixinFit.atNodes(wrap);
   if(points.size()!=1||!"INVOKE".equals(MixinFit.value(points.getFirst(),"value"))||!"Ljava/util/Map;clear()V".equals(MixinFit.value(points.getFirst(),"target"))
     ||MixinFit.value(points.getFirst(),"ordinal")!=null||MixinFit.value(wrap,"slice")!=null)continue;
   if(!veto.recognised().test(mixin,old))continue;
   String desc="(L"+LIVING+";"+EFFECT+"L"+OPERATION+";)Z";
   if(method(mixin,veto.generated(),desc)!=null)continue;
   MethodNode handler=new MethodNode(Opcodes.ACC_PRIVATE,veto.generated(),desc,null,null);
   handler.visibleAnnotations=new ArrayList<>(List.of(wrap));
   if(old.visibleAnnotations!=null)old.visibleAnnotations.remove(wrap);if(old.invisibleAnnotations!=null)old.invisibleAnnotations.remove(wrap);
   set(points.getFirst(),"target","Lnet/neoforged/neoforge/event/EventHooks;onEffectRemoved"+EFFECT_REMOVED);
   InsnList code=handler.instructions;LabelNode removable=new LabelNode();
   code.add(new VarInsnNode(Opcodes.ALOAD,3));code.add(new InsnNode(Opcodes.ICONST_2));code.add(new TypeInsnNode(Opcodes.ANEWARRAY,"java/lang/Object"));
   code.add(new InsnNode(Opcodes.DUP));code.add(new InsnNode(Opcodes.ICONST_0));code.add(new VarInsnNode(Opcodes.ALOAD,1));code.add(new InsnNode(Opcodes.AASTORE));
   code.add(new InsnNode(Opcodes.DUP));code.add(new InsnNode(Opcodes.ICONST_1));code.add(new VarInsnNode(Opcodes.ALOAD,2));code.add(new InsnNode(Opcodes.AASTORE));
   code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,OPERATION,"call","([Ljava/lang/Object;)Ljava/lang/Object;",true));
   code.add(new TypeInsnNode(Opcodes.CHECKCAST,"java/lang/Boolean"));code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,"java/lang/Boolean","booleanValue","()Z",false));
   code.add(new JumpInsnNode(Opcodes.IFEQ,removable));code.add(new InsnNode(Opcodes.ICONST_1));code.add(new InsnNode(Opcodes.IRETURN));
   code.add(removable);code.add(new FrameNode(Opcodes.F_SAME,0,null,0,null));
   veto.ask().accept(mixin,code);
   handler.maxStack=5;handler.maxLocals=4;mixin.methods.add(handler);changed++;
  }
  return changed;
 }
 /** The handler this reproduces: one clear() through the Operation, then one ALLOW_EARLY_REMOVE question per effect,
  * putting back the ones it vetoes — and nothing else a listener could observe. */
 /** balm's clear(): collect what MOB_EFFECT_REMOVE vetoes, clear through the Operation, put those back, and drop them from
  * the removed copy. Both bodies pinned: a balm that asks anything else, or asks it differently, is not reimplemented. */
 private static boolean balmVeto(ClassNode mixin,MethodNode old) {
  MethodNode question=method(mixin,"lambda$clearAllEffects$0","(L"+LIVING+";Ljava/util/Map$Entry;)Z");
  return question!=null&&BALM_HANDLER_BODY.equals(bodyHash(old))&&BALM_QUESTION_BODY.equals(bodyHash(question));
 }
 private static void askBalm(ClassNode mixin,InsnList code) {
  LabelNode allowed=new LabelNode();
  code.add(new FieldInsnNode(Opcodes.GETSTATIC,"net/blay09/mods/balm/fabric/platform/event/internal/FabricBalmSupplementalEvents","MOB_EFFECT_REMOVE","L"+BALM_EVENT+";"));
  code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,BALM_EVENT,"invoker","()Ljava/lang/Object;",false));
  code.add(new TypeInsnNode(Opcodes.CHECKCAST,BALM_BEFORE));
  code.add(new VarInsnNode(Opcodes.ALOAD,1));
  code.add(new VarInsnNode(Opcodes.ALOAD,2));code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,"net/minecraft/world/effect/MobEffectInstance","getEffect","()Lnet/minecraft/core/Holder;",false));
  code.add(new VarInsnNode(Opcodes.ALOAD,2));
  code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,BALM_BEFORE,"allowRemove","(L"+LIVING+";Lnet/minecraft/core/Holder;"+EFFECT+")Z",true));
  code.add(new JumpInsnNode(Opcodes.IFNE,allowed));code.add(new InsnNode(Opcodes.ICONST_1));code.add(new InsnNode(Opcodes.IRETURN));
  code.add(allowed);code.add(new FrameNode(Opcodes.F_SAME,0,null,0,null));code.add(new InsnNode(Opcodes.ICONST_0));code.add(new InsnNode(Opcodes.IRETURN));
 }
 static String bodyHash(MethodNode original) { return MixinInstructionFingerprint.hash(original); }

 private static int move(ClassNode mixin,String handlerName,String handlerDesc,String selector,String replacementSelector,
   String oldKind,String oldTarget,String newKind,String newTarget) {
  MethodNode handler=method(mixin,handlerName,handlerDesc);if(handler==null)return 0;
  if(hasGroup(handler.visibleAnnotations)||hasGroup(handler.invisibleAnnotations))return 0;
  AnnotationNode injector=MixinFit.injectorOf(handler);
  if(injector==null||!injector.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;"))return 0;
  List<String> selected=MixinFit.stringList(MixinFit.value(injector,"method"));
  if(selected.size()!=1||!selected.getFirst().equals(selector))return 0;
  List<AnnotationNode> points=MixinFit.atNodes(injector);
  if(points.size()!=1)return 0;
  AnnotationNode at=points.getFirst();
  if(!oldKind.equals(MixinFit.value(at,"value"))||!oldTarget.equals(MixinFit.value(at,"target")))return 0;
  if(!oldKind.equals(newKind)&&(MixinFit.value(at,"shift")!=null||MixinFit.value(at,"by")!=null))return 0;
  set(at,"value",newKind);set(at,"target",newTarget);
  if(replacementSelector!=null)set(injector,"method",new ArrayList<>(List.of(replacementSelector)));
  return 1;
 }
 private static int countNew(MethodNode method,String owner){int count=0;for(var i:method.instructions)if(i instanceof TypeInsnNode t&&t.getOpcode()==Opcodes.NEW&&t.desc.equals(owner))count++;return count;}
 private static int countCalls(MethodNode method,String owner,String name,String desc){int count=0;for(var i:method.instructions)if(call(i,owner,name,desc))count++;return count;}
 private static boolean call(AbstractInsnNode instruction,String owner,String name,String desc){return instruction instanceof MethodInsnNode c&&c.owner.equals(owner)&&c.name.equals(name)&&c.desc.equals(desc);}
 private static boolean hasGroup(List<AnnotationNode> annotations){return annotations!=null&&annotations.stream().anyMatch(a->a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Group;"));}
 private static List<AbstractInsnNode> code(MethodNode method){List<AbstractInsnNode> out=new ArrayList<>();for(var i:method.instructions)if(i.getOpcode()>=0)out.add(i);return out;}
 private static MethodNode method(ClassNode node,String name,String desc){return node.methods.stream().filter(m->m.name.equals(name)&&m.desc.equals(desc)).findFirst().orElse(null);}
 private static void set(AnnotationNode annotation,String key,Object value){for(int i=0;i<annotation.values.size();i+=2)if(key.equals(annotation.values.get(i))){annotation.values.set(i+1,value);return;}annotation.values.add(key);annotation.values.add(value);}
}
